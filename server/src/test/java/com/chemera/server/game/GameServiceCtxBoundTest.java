package com.chemera.server.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 每 uid 内存现场的上界与清扫（升级方案 G7 原文那条："灌 2×N 个 uid 的 ctx 之后大小仍有界"）。
 *
 * <p>{@code GameService.sessions} 以前是一个只进不出的 {@code ConcurrentHashMap}：每一个来过的人
 * 都在里面永远留一格 {@code EngineCtx}。这台服务不重启就会一直涨上去，而涨爆的代价是<b>整台进程</b>，
 * 与"某一位的临时台重开"完全不对称，所以必须有用例站在"不许没有上界"这一边。
 *
 * <p>口径照 {@code IntentDedupeTest}（同一笔账的另一半）：灌到配额的<b>两倍</b>，断言表里剩下的恰好
 * 是配额那么多，而且留下的是最近碰过的那些。另外几条钉的是这条独立的退出路径：
 * 闲置清扫收走冷现场、<b>在途的那一格绝对不碰</b>、以及 {@code evict} 真的把整格摘掉。
 *
 * <p>配额从构造注入（{@code ctxMaxEntries} / {@code ctxIdleMinutes}）：闲置时长给 0 就是
 * "每一趟清扫把所有不在途的现场都收走"，好把时间相关的判定写成断言，而不是等三十分钟。
 */
class GameServiceCtxBoundTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 一台只为了数内存格子的装配：存档用 {@link GameServiceTest.MemStore}，闸门关掉。 */
    private static GameService svc(int cap, int idleMinutes, GameServiceTest.MemStore store) throws Exception {
        ContentRegistry.Snapshot s = GameServiceTest.snap();
        GameEngine e = new GameEngine();
        AdService ads = new AdService(new MemAdTickets(), "ctx-bound-test-key",
                "{transId}{key}", "lower", true, "space-test");
        GameService svc = new GameService(() -> s, e, new EconomyService(e), store, ads,
                uid -> { }, new IntentDedupe(OM), cap, idleMinutes);
        svc.rng = () -> 0.999;
        return svc;
    }

    /* ---------------- 1. 上界：灌两倍 ---------------- */

    /** 原文那条：灌 2×N 个 uid 的现场之后，表的大小仍然有界（这里 N=20，灌 40）。 */
    @Test
    void twiceTheCapStillStaysBounded() throws Exception {
        GameService svc = svc(20, 30, new GameServiceTest.MemStore());
        for (long uid = 1; uid <= 40; uid++) svc.ctx(uid);
        assertEquals(20, svc.ctxCount(), "灌到两倍配额，表里只许留配额那么多格");

        // 再灌一整轮新号：上界不会"慢慢松一口气"，涨到过就停在那里
        for (long uid = 100; uid <= 400; uid++) svc.ctx(uid);
        assertEquals(20, svc.ctxCount(), "另外三百位玩家进来之后仍然是同一个上界");
    }

    /** 走真实意图链再验一遍：{@code act()} 建的现场同样受上界管，不是只有直接调 {@code ctx()} 才收敛。 */
    @Test
    void intentsBuildCtxUnderTheSameBound() throws Exception {
        GameService svc = svc(10, 30, new GameServiceTest.MemStore());
        for (long uid = 1; uid <= 25; uid++) svc.act(uid, "state", Map.of());
        assertEquals(10, svc.ctxCount(), "25 位玩家各取过一帧之后，留在内存里的现场只有 10 格");
    }

    /** 逐的是"最久没被碰的"，不是"最早建出来的"：被重新碰过的人就该带着原现场活下来。 */
    @Test
    void evictionIsLeastRecentlyUsedNotFirstCome() throws Exception {
        GameService svc = svc(3, 30, new GameServiceTest.MemStore());
        svc.ctx(1).quizId = "1号来了";          // 给每一格留一个能认出身份的指纹
        svc.ctx(2).quizId = "2号来了";
        svc.ctx(1);                             // 1 号又被碰了一次 ⇒ 现在最久未用的是 2 号
        svc.ctx(3).quizId = "3号来了";
        svc.ctx(4);                             // 超界：该逐最久未用的 2 号

        assertEquals("1号来了", svc.ctx(1).quizId, "被重新碰过的 1 号要活着，一格现场都不许丢");
        assertEquals("3号来了", svc.ctx(3).quizId, "后进来的 3 号也活着");
        assertNull(svc.ctx(2).quizId, "逐出的必须是最久未用的那一格：2 号回来时是全新的现场");
        assertEquals(3, svc.ctxCount());
    }

    /** 默认配额也要有界：七参构造（生产装配的同形形状）默认上界 2000，绝不是"没有上界"。 */
    @Test
    void defaultWiringIsBoundedToo() throws Exception {
        ContentRegistry.Snapshot s = GameServiceTest.snap();
        GameEngine e = new GameEngine();
        GameService dflt = new GameService(() -> s, e, new EconomyService(e),
                new GameServiceTest.MemStore(),
                new AdService(new MemAdTickets(), "ctx-bound-test-key", "{transId}{key}", "lower", true, "space-test"),
                uid -> { }, new IntentDedupe(OM));
        for (long uid = 1; uid <= 3000; uid++) dflt.ctx(uid);
        assertEquals(2000, dflt.ctxCount(), "没显式给配额时走 chemera.game.ctx-max-entries 的默认值 2000");
    }

    /* ---------------- 2. 闲置清扫 ---------------- */

    /** 清扫收走冷到闲置时长的现场：这是"没超界但一直在长"那种慢泄漏唯一的出口。 */
    @Test
    void sweepDropsIdleEntries() throws Exception {
        GameService svc = svc(100, 0, new GameServiceTest.MemStore());   // 闲置门槛 0 ⇒ 不在途的都算冷
        for (long uid = 1; uid <= 5; uid++) svc.ctx(uid);
        assertEquals(5, svc.ctxCount(), "上界还远，超界淘汰不会发生：下面这一趟靠的是时间，不是容量");
        svc.sweepSessions();
        assertEquals(0, svc.ctxCount(), "冷到闲置时长的现场要被清扫收走，不能等到撑爆上界才动");
    }

    /* ---------------- 3. 在途那一格绝对不碰 ---------------- */

    /**
     * 清扫不许动"本次 {@code act()} 正在用"的那一格。
     *
     * <p>这里用 {@code MemStore.afterLoad} 那个钩子在请求<b>进行中</b>插一趟清扫：那是唯一能确定
     * "此刻这一位确实还在途"的时刻，比开线程去猜时序可靠。断言的是两件事一起成立——
     * 在途的活着、冷的走了，而不是"谁都扫不动"。
     */
    @Test
    void sweepNeverDropsTheCtxBeingSettled() throws Exception {
        GameServiceTest.MemStore store = new GameServiceTest.MemStore();
        GameService svc = svc(100, 0, store);
        svc.ctx(2L);                                        // 一位冷玩家
        List<Integer> sizeMidRequest = new ArrayList<>();
        store.afterLoad = () -> {
            svc.sweepSessions();                            // 在 1 号的结算中途扫一次
            sizeMidRequest.add(svc.ctxCount());
        };
        svc.act(1L, "state", Map.of());
        assertEquals(List.of(1), sizeMidRequest,
                "在途的那一格要活过这一趟清扫，而冷掉的那位要被收走（否则玩家的临时台会被自己这一次请求抽掉）");

        store.afterLoad = () -> { };
        svc.act(1L, "state", Map.of());                     // 结算结束之后他也不再在途
        svc.sweepSessions();
        assertEquals(0, svc.ctxCount(), "解除在途之后，下一趟清扫正常收走");
    }

    /** 超界时宁可让表临时多出在途的那几格，也不抽走正在被用／刚进来的那一格（软上界的取舍，见 ctx 注释）。 */
    @Test
    void overCapacityKeepsTheInFlightSlot() throws Exception {
        GameServiceTest.MemStore store = new GameServiceTest.MemStore();
        GameService svc = svc(1, 30, store);
        List<Integer> mid = new ArrayList<>();
        store.afterLoad = () -> {
            svc.ctx(2L);                                    // 1 号在途，此时插一格必然超界
            mid.add(svc.ctxCount());
        };
        svc.act(1L, "state", Map.of());
        assertEquals(List.of(2), mid, "上界 1 格、在途 1 位：临时顶到 2 格，但在途那格与新来那格都不许被抽走");

        svc.ctx(3L);                                        // 请求已结束，没有人在途了
        assertEquals(1, svc.ctxCount(), "在途解除之后，超界要立刻收敛回上界");
    }

    /* ---------------- 4. evict ---------------- */

    /** {@code evict}：删号那条路（G6）要把整格现场摘掉，而不是留在内存里等 TTL。 */
    @Test
    void evictRemovesThatPlayerOnly() throws Exception {
        GameService svc = svc(100, 30, new GameServiceTest.MemStore());
        svc.ctx(1).quizId = "1号来了";
        svc.ctx(2).quizId = "2号来了";

        svc.evict(1L);
        assertEquals(1, svc.ctxCount(), "只摘掉点名的那一位");
        assertNull(svc.ctx(1).quizId, "被 evict 的人再进来是一格全新现场，旧台的题号不跟着回来");
        assertEquals(2, svc.ctxCount(), "读回来的是新建的一格，不是把旧那格复活");
        assertEquals("2号来了", svc.ctx(2).quizId, "别人那一格一个字没动");

        svc.evict(1L);                                      // 重复 evict、以及压根没这一位：都只是无操作
        svc.evict(999L);
        assertDoesNotThrow(() -> svc.evict(999L));
        assertEquals(1, svc.ctxCount(), "第二次 evict 与不存在的号都不许顺手带走别人");
    }
}
