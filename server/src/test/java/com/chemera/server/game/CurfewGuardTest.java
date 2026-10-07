package com.chemera.server.game;

import com.chemera.server.common.BizException;
import com.chemera.server.mapper.UserMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 青少年模式时段闸门的回归。
 *
 * <p>这块代码的失败方式很特别：配置填错，服务器不会崩、不会报错、不会留日志，
 * 只是"所有未成年人在所有时刻都进不去"（或者反过来，"限制静默失效，孩子凌晨三点还在玩"）。
 * 两种后果都只在真人身上才看得见。所以断言的重点是**窗口端点的开闭**与**配置读不出来时的回落方向**：
 * 合规项的兜底必须往严的方向倒，而窗口终点必须是"到点即锁"而不是"多给一分钟"。
 *
 * <p>时钟与内容快照都从包私有的注入点进来（{@code nowMs} / 快照 Supplier），
 * 这样"周五 20:30"是一条被钉死的事实，而不是等测试恰好跑到那个时刻才成立的条件。
 */
class CurfewGuardTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    /** 2026-10-09 是周五：整份测试围绕这一周（五六日放行，周一到周四不放行）。 */
    private static final LocalDate FRI = LocalDate.of(2026, 10, 9);
    private static final LocalDate SAT = FRI.plusDays(1);
    private static final LocalDate MON = FRI.plusDays(3);

    /** 上线口径：周五六日 20:00-21:00。 */
    private static final String WEEKEND = "{\"enabled\":true,\"zone\":\"Asia/Shanghai\",\"days\":[5,6,7],"
            + "\"from\":\"20:00\",\"to\":\"21:00\",\"hint\":\"周五六日 20-21 点可玩\"}";

    /* ---------------- 脚手架 ---------------- */

    /** 用给定 curfew 配置造最小内容快照（其余内容表留空：闸门只读 config.curfew）。 */
    private static ContentRegistry.Snapshot snapWith(String curfewJson) throws Exception {
        Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        if (curfewJson != null) {
            cfg.put("curfew", OM.readValue(curfewJson, new TypeReference<Map<String, Object>>() { }));
        }
        return new ContentRegistry(null, OM).build(1L, Map.of(), cfg);
    }

    private static UserMapper users(boolean minor) {
        UserMapper m = mock(UserMapper.class);
        when(m.minorOf(anyLong())).thenReturn(minor ? 1 : 0);
        return m;
    }

    /** 把闸门钉在某个时刻：minor 决定这个账号是不是未成年人。 */
    private static CurfewGuard guard(ContentRegistry.Snapshot s, long epochMillis, UserMapper m) {
        CurfewGuard g = new CurfewGuard(() -> s, m);
        g.nowMs = () -> epochMillis;
        return g;
    }

    private static CurfewGuard guard(String curfewJson, long epochMillis, boolean minor) throws Exception {
        return guard(snapWith(curfewJson), epochMillis, users(minor));
    }

    /**
     * 专给缓存那几条用例：闸门时钟照旧钉在周一 03:00（未成年人一律拦），
     * 而缓存自己的有效期读的是<b>真实墙钟</b>（与生产一致），所以摆缓存用的是
     * {@link CurfewGuard#addCached} 那个注入口，不是把 {@code nowMs} 调过去。
     */
    private static CurfewGuard cacheGuard(UserMapper m) throws Exception {
        return cacheGuard(m, 5000);
    }

    private static CurfewGuard cacheGuard(UserMapper m, int cacheMaxEntries) throws Exception {
        ContentRegistry.Snapshot s = snapWith(WEEKEND);
        CurfewGuard g = new CurfewGuard(() -> s, m, cacheMaxEntries);
        g.nowMs = () -> ms(MON, 3, 0);
        return g;
    }

    private static long ms(LocalDate d, int h, int mi) {
        return d.atTime(h, mi).atZone(SH).toInstant().toEpochMilli();
    }

    private static ZonedDateTime zt(LocalDate d, int h, int mi) {
        return d.atTime(h, mi).atZone(SH);
    }

    private static Content.Curfew cfg(String json) throws Exception {
        return OM.readValue(json, Content.Curfew.class);
    }

    /* ---------------- 固定日期本身要成立，否则下面全是空断言 ---------------- */

    @Test
    void fixturesAreTheWeekdaysTheyClaim() {
        assertEquals(DayOfWeek.FRIDAY, FRI.getDayOfWeek());
        assertEquals(DayOfWeek.SATURDAY, SAT.getDayOfWeek());
        assertEquals(DayOfWeek.MONDAY, MON.getDayOfWeek());
    }

    /* ---------------- 窗口端点：开闭必须钉死 ---------------- */

    @Test
    void windowIsLeftClosedRightOpen() throws Exception {
        Content.Curfew c = cfg(WEEKEND);
        assertTrue(CurfewGuard.allowedAt(c, zt(FRI, 20, 30)), "周五 20:30 在窗口内");
        assertTrue(CurfewGuard.allowedAt(c, zt(FRI, 20, 0)), "起点含在内：20:00 整就放行");
        assertFalse(CurfewGuard.allowedAt(c, zt(FRI, 19, 59)), "20:00 之前不放行");
        assertFalse(CurfewGuard.allowedAt(c, zt(FRI, 21, 0)), "终点不含在内：到点即锁，不多给一分钟");
        assertFalse(CurfewGuard.allowedAt(c, zt(MON, 20, 30)), "周一不是放行日");
    }

    @Test
    void crossMidnightWindowBelongsToTheDayItOpenedOn() throws Exception {
        // 22:00–01:00：周六 00:30 属于"周五开的那个窗口"的延续，所以判的是周五是不是放行日
        Content.Curfew c = cfg("{\"days\":[5,6,7],\"from\":\"22:00\",\"to\":\"01:00\"}");
        assertTrue(CurfewGuard.allowedAt(c, zt(FRI, 23, 0)), "周五深夜在窗口内");
        assertTrue(CurfewGuard.allowedAt(c, zt(SAT, 0, 30)), "周六凌晨 00:30 仍算周五开的窗口");
        assertFalse(CurfewGuard.allowedAt(c, zt(SAT, 1, 30)), "01:00 一过就锁");
    }

    @Test
    void holidayDatesExtendTheWeeklyPattern() throws Exception {
        LocalDate nationalDay = LocalDate.of(2026, 10, 1);
        assertEquals(DayOfWeek.THURSDAY, nationalDay.getDayOfWeek());
        Content.Curfew c = cfg("{\"days\":[5,6,7],\"from\":\"20:00\",\"to\":\"21:00\","
                + "\"extraDates\":[\"2026-10-01\"]}");
        assertTrue(CurfewGuard.allowedAt(c, zt(nationalDay, 20, 30)), "补进来的节假日当天放行");
        // 10-02 是周五，本来就放行，用它当"没补的日子"会假过；10-05 是周一且没补
        assertFalse(CurfewGuard.allowedAt(c, zt(LocalDate.of(2026, 10, 5), 20, 30)), "没补的平日照旧拦");
    }

    @Test
    void badTimesAndZoneFallBackInsteadOfThrowing() throws Exception {
        // 闸门在每次玩法请求的路径上：配置里的 from/to/zone 写坏时必须回落默认窗口，而不是把请求带崩
        Content.Curfew c = cfg("{\"from\":\"20点\",\"to\":\"半个晚上\",\"zone\":\"Asia/Shangai\"}");
        assertEquals(SH, c.zoneOr(), "非法时区回落上海");
        assertTrue(CurfewGuard.allowedAt(c, zt(FRI, 20, 30)), "非法时刻回落 20:00-21:00");
        assertFalse(CurfewGuard.allowedAt(c, zt(FRI, 22, 0)));
    }

    @Test
    void emptyDaysMeansNeverOpenNotEverythingOpen() throws Exception {
        // days=[] 是运营主动收紧，绝不能被兜底当成"没配"而放开全部日子（只有 null 才回落默认五六日）
        assertFalse(CurfewGuard.allowedAt(cfg("{\"days\":[]}"), zt(FRI, 20, 30)));
        assertTrue(CurfewGuard.allowedAt(cfg("{\"days\":null}"), zt(FRI, 20, 30)),
                "缺字段才回落默认窗口，显式空数组是有意收紧");
    }

    @Test
    void nextOpenSkipsToTheFollowingPlayDay() throws Exception {
        Content.Curfew c = cfg(WEEKEND);
        assertEquals(zt(FRI.plusDays(7), 20, 0), CurfewGuard.nextOpenAt(c, zt(MON, 20, 30)),
                "周一 10-12 要等到下下个周五 10-16 的 20:00");
        assertEquals(zt(SAT, 20, 0), CurfewGuard.nextOpenAt(c, zt(FRI, 21, 30)), "周五窗口过后是周六");
        assertNull(CurfewGuard.nextOpenAt(cfg("{\"days\":[]}"), zt(FRI, 12, 0)),
                "没有放行日时返回 null：让闸门说\"请联系客服\"，而不是给一个假的倒计时");
    }

    @Test
    void humanDurationReadsLikeChineseNotLikeSeconds() {
        assertEquals("1 天 2 小时", CurfewGuard.human(zt(FRI, 21, 0), zt(SAT, 23, 0)));
        assertEquals("30 分钟", CurfewGuard.human(zt(FRI, 20, 0), zt(FRI, 20, 30)));
        assertEquals("不到 1 分钟", CurfewGuard.human(zt(FRI, 20, 0), zt(FRI, 20, 0)));
    }

    /* ---------------- 闸门本身：谁被拦、拦成什么样 ---------------- */

    @Test
    void adultPlaysAnytime() throws Exception {
        assertDoesNotThrow(() -> guard(WEEKEND, ms(MON, 3, 0), false).assertAllowed(7L));
    }

    @Test
    void minorInsideWindowPlays() throws Exception {
        assertDoesNotThrow(() -> guard(WEEKEND, ms(SAT, 20, 30), true).assertAllowed(7L));
    }

    @Test
    void minorOutsideWindowIsRefusedWithMachineReadableTag() throws Exception {
        BizException e = assertThrows(BizException.class,
                () -> guard(WEEKEND, ms(MON, 3, 0), true).assertAllowed(7L));
        assertEquals(403, e.code, "必须是 403：客户端据此弹校门页，而不是当普通业务错误吞掉");
        assertEquals(CurfewGuard.TAG, e.tag, "客户端认 tag 不认文案，文案会改词");
        assertTrue(e.getMessage().contains("周五六日 20-21 点可玩"), "把人话带上：" + e.getMessage());
        assertTrue(e.getMessage().contains("距下次可玩还有"), "要告诉玩家还要等多久：" + e.getMessage());
    }

    @Test
    void noPlayDayConfiguredSaysSoInsteadOfAFakeCountdown() throws Exception {
        // days 全空：闸门必须承认"现在谁都不放行、也没有下次"，不能报一个不存在的时刻
        BizException e = assertThrows(BizException.class,
                () -> guard("{\"days\":[],\"hint\":\"维护中\"}", ms(FRI, 12, 0), true).assertAllowed(7L));
        assertTrue(e.getMessage().contains("没有任何放行时段"), e.getMessage());
    }

    @Test
    void switchingTheMasterSwitchOffUnblocksEveryone() throws Exception {
        // enabled=false 是运营在面板上的显式决定（并被审计记录在案），此时闸门整体放行
        assertDoesNotThrow(() -> guard("{\"enabled\":false,\"days\":[],\"hint\":\"x\"}",
                ms(MON, 3, 0), true).assertAllowed(7L));
    }

    @Test
    void missingCurfewRowDefaultsToEnforced() throws Exception {
        // 库里这行被误删 ≠ 对所有孩子放行：兜底方向必须是更严
        assertThrows(BizException.class, () -> guard(null, ms(MON, 3, 0), true).assertAllowed(7L));
    }

    /* ---------------- 缓存：闸门在每次玩法请求的路径上，不能次次查库 ---------------- */

    @Test
    void minorLookupIsCachedAndInvalidatedOnMark() throws Exception {
        UserMapper m = users(true);
        CurfewGuard g = guard(snapWith(WEEKEND), ms(MON, 3, 0), m);
        for (int i = 0; i < 5; i++) assertThrows(BizException.class, () -> g.assertAllowed(9L));
        verify(m, times(1)).minorOf(9L);          // 五次玩法请求只问一次库

        g.invalidate(9L);
        assertThrows(BizException.class, () -> g.assertAllowed(9L));
        verify(m, times(2)).minorOf(9L);          // 运营刚标记完，玩家不该再等 60 秒缓存过期
    }

    /**
     * 缓存有条上界（G7）：以前一个号被闸门碰过一次就在表里永远留一格，长跑只会长不会消。
     * 上界按最久未碰逐出，被逐出的代价只是"下一次玩法请求多查一遍 {@code app_user.minor}"。
     */
    @Test
    void minorCacheHasABound() throws Exception {
        CurfewGuard g = cacheGuard(users(true), 3);
        for (long uid = 1; uid <= 50; uid++) g.view(uid);       // view 也走 isMinor，且不抛，正好用来灌表
        assertEquals(3, g.cachedSize(), "灌五十位只留三格：这张表不许没有上界");
    }

    /**
     * 定期清扫只收<b>已经过了有效期</b>的条目，一个"还在有效期内"的都不碰。
     *
     * <p>这两半合起来才是这条防线的全部：只断言"陈旧的被清掉"，可能清的是刚判过的那些——
     * 那等于把缓存拆了，每次玩法请求都回库查 minor；只断言"新的留着"，又清不出任何东西。
     * 而且过期条目本来在 {@code isMinor} 里就要重查一遍库，删掉它不改变任何人的判定结果。
     */
    @Test
    void purgeSweepsStaleJudgementsAndKeepsTheLiveOne() throws Exception {
        UserMapper m = users(true);
        CurfewGuard g = cacheGuard(m);                          // 时钟钉在周一 03:00：未成年人一律拦
        long wall = System.currentTimeMillis();
        g.addCached(1L, true, wall);                            // 刚判过：还在 60 秒有效期内
        g.addCached(2L, true, wall - 61_000L);                  // 一分钟以前判的：早就该重查了

        g.purge();
        assertEquals(1, g.cachedSize(), "清掉的只有过期那一条");
        verify(m, never()).minorOf(anyLong());                  // 清扫自己一次库都不该打

        assertThrows(BizException.class, () -> g.assertAllowed(1L));
        verify(m, never()).minorOf(1L);                         // 有效期内的那条还是按缓存判，没被清扫打成一次回库
        assertThrows(BizException.class, () -> g.assertAllowed(2L));
        verify(m, times(1)).minorOf(2L);                        // 被清掉的那条回来时重查一遍库，判定结果不变
    }

    /** 清空之后不留残渣：全表都是过期条目时一趟扫完就该归零。 */
    @Test
    void purgeEmptiesAWholeTableOfStaleEntries() throws Exception {
        CurfewGuard g = cacheGuard(users(true));
        long wall = System.currentTimeMillis();
        for (long uid = 1; uid <= 20; uid++) g.addCached(uid, true, wall - 61_000L);
        assertEquals(20, g.cachedSize());
        g.purge();
        assertEquals(0, g.cachedSize(), "长跑之后这张表不该靠着上界才收敛，时间本身就够把它清干净");
    }

    /* ---------------- 展示视图：不抛异常，且必须把服务器时钟一起给出去 ---------------- */

    @Test
    void statusViewCarriesCountdownSources() throws Exception {
        CurfewGuard g = guard(WEEKEND, ms(MON, 3, 0), true);
        Map<String, Object> v = g.view(7L);
        assertEquals(true, v.get("enforced"));
        assertEquals(true, v.get("minor"));
        assertEquals(false, v.get("allowed"));
        assertEquals(ms(FRI.plusDays(7), 20, 0), v.get("nextOpenAt"), "周一 03:00 的下次可玩是 10-16 周五 20:00");
        assertEquals(ms(MON, 3, 0), v.get("serverNow"), "倒计时以服务器时钟为准，改本机时间没用");
        @SuppressWarnings("unchecked")
        Map<String, Object> w = (Map<String, Object>) v.get("window");
        assertEquals(List.of(5, 6, 7), w.get("days"));
        assertEquals("20:00", w.get("from"));
        assertEquals("Asia/Shanghai", w.get("zone"));
    }

    @Test
    void statusViewForAdultIsPlainlyAllowed() throws Exception {
        CurfewGuard g = guard(WEEKEND, ms(MON, 3, 0), false);
        Map<String, Object> v = g.view(7L);
        assertEquals(true, v.get("allowed"));
        assertEquals(false, v.get("minor"));
        assertNull(v.get("nextOpenAt"), "放行状态下不给假倒计时");
    }
}
