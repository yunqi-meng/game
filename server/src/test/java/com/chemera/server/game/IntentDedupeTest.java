package com.chemera.server.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 意图幂等窗口（F2）自身的回归。这里不看玩法结算，只管四件事：
 * 同键只跑一次、跑出来的那一帧能原样回来、{@code stale} 不进缓存、以及<b>两份配额都是硬上界</b>。
 *
 * <p>最后一条单独立测的原因：这一层是进程内内存，没有表、没有 TTL、重启清零，
 * 一旦没上界就是"玩得越久越肥"的泄漏（升级方案 G7 给 {@code EngineCtx} 记的就是同一笔账）。
 * 内存撑爆的代价是整台服务，而幂等窗口的代价只是"某个人下一次重发可能多买一件"——
 * 这个不对称就是"宁可漏去重"的依据，所以必须有用例站在"不许没有上界"这一边。
 */
class IntentDedupeTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** 一份假的意图回执：{@code tag} 用来认出"这是哪一次结算的产物"。 */
    private static Map<String, Object> frame(String tag, long coins) {
        return Map.of("revision", 3L, "tag", tag, "sandbox", false,
                "result", Map.of("ok", true, "cost", coins));
    }

    @Test
    void theSecondDeliveryGetsTheFirstResultAndNeverRunsAgain() throws Exception {
        IntentDedupe d = new IntentDedupe(OM);
        AtomicInteger runs = new AtomicInteger();
        Map<String, Object> a = d.execute(1L, 9L, 41, () -> {
            runs.incrementAndGet();
            return frame("first", 100L);
        });
        Map<String, Object> b = d.execute(1L, 9L, 41, () -> {
            runs.incrementAndGet();
            return frame("second", 999L);      // 重发的那一次如果真的走到这里，玩家就被扣了第二笔
        });
        assertEquals(1, runs.get(), "同键只许执行一次");
        assertEquals("first", b.get("tag"), "重复交付退回的是第一次那一帧");
        assertEquals(OM.writeValueAsString(a), OM.writeValueAsString(b), "两次返回逐字节相同");
    }

    @Test
    void anotherKeyIsAnotherIntent() {
        IntentDedupe d = new IntentDedupe(OM);
        AtomicInteger runs = new AtomicInteger();
        d.execute(1L, 9L, 41, () -> { runs.incrementAndGet(); return frame("a", 1L); });
        d.execute(1L, 9L, 42, () -> { runs.incrementAndGet(); return frame("b", 2L); });   // 换号 = 另一笔
        d.execute(2L, 9L, 41, () -> { runs.incrementAndGet(); return frame("c", 3L); });   // 换人 = 另一笔
        d.execute(1L, 10L, 41, () -> { runs.incrementAndGet(); return frame("d", 4L); });  // 换登录态 = 另一笔
        assertEquals(4, runs.get(), "幂等键的三个维度都得参与，少一个就会把另一笔生意误判成重发");
    }

    /** {@code seq<=0}（旧包不发序号）与 {@code sid<=0}（非玩家入口）都退回"不去重"，而且一个槽位都不占。 */
    @Test
    void noKeyNoDedupe() {
        IntentDedupe d = new IntentDedupe(OM);
        AtomicInteger runs = new AtomicInteger();
        Map<String, Object> f = frame("x", 1L);
        assertEquals(f, d.execute(1L, 9L, 0, () -> { runs.incrementAndGet(); return f; }), "seq=0：直接跑");
        assertEquals(f, d.execute(1L, 0L, 5, () -> { runs.incrementAndGet(); return f; }), "sid=0：直接跑");
        assertEquals(f, d.execute(1L, 9L, -2, () -> { runs.incrementAndGet(); return f; }), "负数序号：直接跑");
        assertEquals(3, runs.get(), "没有完整幂等键就不许缓存，行为与 F2 之前一致");
        assertEquals(0, d.cachedSlots(1L), "也不占窗口：旧包不该把新包的空间吃掉");
    }

    /** {@code stale} 那一帧库里一个字没改：缓存它等于把"请重试"焊死，重发永远做不成。 */
    @Test
    void aStaleFrameIsNeverRemembered() {
        IntentDedupe d = new IntentDedupe(OM);
        AtomicInteger runs = new AtomicInteger();
        Map<String, Object> stale = Map.of("stale", true, "result", Map.of("ok", false));
        d.execute(1L, 9L, 7, () -> { runs.incrementAndGet(); return stale; });
        d.execute(1L, 9L, 7, () -> { runs.incrementAndGet(); return stale; });
        assertEquals(2, runs.get(), "抢不过的那些帧不进缓存，第二次交付要有真的重试机会");
        // 槽位本身还在（done=false），但里面没有一份会被当成结果退回的回执——这就是"没缓存"的确切形状。
    }

    /** 执行到一半抛异常（库里断了）：这一笔什么都没落成，缓存必须保持"没见过"，重发才可能救回来。 */
    @Test
    void aFailedExecutionLeavesNoTraceInTheWindow() {
        IntentDedupe d = new IntentDedupe(OM);
        AtomicInteger runs = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> d.execute(1L, 9L, 8, () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("库里断了");
        }));
        Map<String, Object> retry = d.execute(1L, 9L, 8, () -> {
            runs.incrementAndGet();
            return frame("retry", 1L);
        });
        assertEquals(2, runs.get(), "上一笔没落成，这一笔要有真的执行机会");
        assertEquals("retry", retry.get("tag"));
    }

    /**
     * 同键真的并发到来时（客户端超时重发、原请求还在飞），只许结算一次，
     * 而后到那一路拿到的必须是先到那一路算出来的帧。
     */
    @Test
    void concurrentSameSeqSettlesOnce() throws Exception {
        IntentDedupe d = new IntentDedupe(OM);
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        java.util.List<Map<String, Object>> outs = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread[] t = new Thread[2];
        for (int i = 0; i < 2; i++) {
            t[i] = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                outs.add(d.execute(1L, 9L, 64, () -> {
                    runs.incrementAndGet();
                    try {
                        Thread.sleep(80);          // 拖住第一次，让另一路一定撞在"还在执行"上
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return frame("once", 42L);
                }));
            });
            t[i].start();
        }
        ready.await();
        go.countDown();
        for (Thread th : t) th.join(10_000);
        assertEquals(2, outs.size(), "两路都得有回执，不许谁被静默吞掉");
        assertEquals(1, runs.get(), "同键并发只结算一次");
        assertEquals("once", outs.get(0).get("tag"));
        assertEquals("once", outs.get(1).get("tag"), "慢的那一路拿到的就是快的那一路那一帧");
    }

    /**
     * 每个会话的窗口是硬上界：只留最近 {@code seqWindow} 条写意图，超了就淘汰最久没被碰的那一条。
     * 淘汰的后果只是"那一句再重发就会真执行"，这是设计内的退化（见类注释），但<b>不许无限增长</b>。
     */
    @Test
    void thePerSessionWindowIsBounded() {
        IntentDedupe d = IntentDedupe.withQuota(OM, 4, 100);
        for (long seq = 1; seq <= 50; seq++) {
            long s = seq;
            d.execute(1L, 9L, seq, () -> frame("s" + s, s));
        }
        assertEquals(4, d.cachedSlots(1L), "窗口只留配额那几条：50 笔写意图不该存 50 份全量帧");
    }

    /** 玩家数同样是硬上界：热玩家留下，冷玩家整窗（连同那几份帧）一起放掉。 */
    @Test
    void theSessionCountIsBounded() {
        IntentDedupe d = IntentDedupe.withQuota(OM, 4, 3);
        for (long uid = 1; uid <= 30; uid++) {
            long u = uid;
            d.execute(u, 9L, 1, () -> frame("u" + u, u));
        }
        assertEquals(3, d.cachedSessions(), "同时留档的玩家数不许超过配额");
    }

    /** 换一次登录（sid 变了）就把旧会话那一窗清掉：旧回执配新序号是"静默不执行"的源头。 */
    @Test
    void aNewSidClearsTheOldWindow() {
        IntentDedupe d = new IntentDedupe(OM);
        AtomicInteger runs = new AtomicInteger();
        d.execute(1L, 100L, 1, () -> { runs.incrementAndGet(); return frame("old", 1L); });
        assertEquals(1, d.cachedSlots(1L));
        d.execute(1L, 200L, 1, () -> { runs.incrementAndGet(); return frame("new", 2L); });
        assertEquals(2, runs.get(), "重登后的第 1 号是另一笔生意，不许拿上一次会话的回执糊弄");
        assertEquals(1, d.cachedSlots(1L), "旧会话那一窗整条清掉，不留两份");
    }

    /* ---------- seq 这个传输层字段的取与摘 ---------- */

    @Test
    void seqParsingToleratesWhatClientsActuallySend() {
        assertEquals(41L, IntentDedupe.seqOf(Map.of("seq", 41)));
        assertEquals(41L, IntentDedupe.seqOf(Map.of("seq", 41.0)), "JSON 数字解成 Double 也得认");
        assertEquals(41L, IntentDedupe.seqOf(Map.of("seq", "41")), "curl／旧包发成字符串");
        assertEquals(0L, IntentDedupe.seqOf(Map.of("seq", 0)), "非正数不是序号");
        assertEquals(0L, IntentDedupe.seqOf(Map.of("seq", -3)));
        assertEquals(0L, IntentDedupe.seqOf(Map.of("seq", "abc")), "不是数字就按没带处理，绝不当 0 号去撞键");
        assertEquals(0L, IntentDedupe.seqOf(Map.of("seq", 9_999_999_999_999L)), "大得不像序号的按没带处理");
        assertEquals(0L, IntentDedupe.seqOf(Map.of("id", "Na")), "压根没带");
        assertEquals(0L, IntentDedupe.seqOf(null));
    }

    /** {@code seq} 是传输层的，不许漏进玩法参数：意图分发看到的必须是摘干净的那一份。 */
    @Test
    void seqIsStrippedBeforeTheIntentSeesIt() {
        Map<String, Object> p = Map.of("id", "filterpaper", "n", 1, "seq", 41);
        Map<String, Object> clean = IntentDedupe.withoutSeq(p);
        assertFalse(clean.containsKey("seq"), "摘掉传输层字段");
        assertEquals(Map.of("id", "filterpaper", "n", 1), clean, "玩法参数一个不能少、一个不能多");
        assertEquals(41L, IntentDedupe.seqOf(p), "摘之前要先取号：顺序反了幂等键就没了");
        Map<String, Object> plain = Map.of("id", "Na");
        assertSame(plain, IntentDedupe.withoutSeq(plain), "没带 seq 就原样返回，不做无谓拷贝");
        assertEquals(Map.of(), IntentDedupe.withoutSeq(null), "空参数给空 map，分发处不必判 null");
    }
}
