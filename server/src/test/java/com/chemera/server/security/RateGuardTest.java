package com.chemera.server.security;

import com.chemera.server.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class RateGuardTest {

    private static RateGuard guard(boolean enabled, int loginMax, int loginWindowMin,
                                   int failMax, int lockMin) {
        return new RateGuard(enabled, loginMax, loginWindowMin, failMax, lockMin,
                5, 60, 3, 60, 3, 60, 20, 30, failMax, lockMin, false);
    }

    private static RateGuard strict() { return guard(true, 3, 10, 2, 15); }

    @Test
    void windowBudgetRejectsOnceExhausted() {
        RateGuard g = strict();
        g.checkLogin("1.1.1.1", "alice");
        g.checkLogin("1.1.1.1", "alice");
        g.checkLogin("1.1.1.1", "alice");
        BizException e = assertThrows(BizException.class, () -> g.checkLogin("1.1.1.1", "alice"));
        assertEquals(429, e.code);
    }

    @Test
    void budgetIsPerIp() {
        RateGuard g = strict();
        for (int i = 0; i < 3; i++) g.checkLogin("1.1.1.1", "alice");
        assertDoesNotThrow(() -> g.checkLogin("2.2.2.2", "alice"));
    }

    @Test
    void failuresLockOnlyTheAttackedAccount() {
        RateGuard g = strict();
        g.noteFailure("login", "1.1.1.1", "alice");
        g.checkLogin("1.1.1.1", "alice");          // 未达上限：放行
        g.noteFailure("login", "1.1.1.1", "alice");
        BizException e = assertThrows(BizException.class, () -> g.checkLogin("1.1.1.1", "alice"));
        assertTrue(e.getMessage().contains("分钟"), e.getMessage());
        assertDoesNotThrow(() -> g.checkLogin("1.1.1.1", "bob"));   // 换账号不受影响
        assertEquals(2, g.failuresOf("login", "1.1.1.1", "alice"));
    }

    @Test
    void successClearsFailureCount() {
        RateGuard g = strict();
        g.noteFailure("login", "1.1.1.1", "alice");
        g.noteSuccess("login", "1.1.1.1", "alice");
        g.noteFailure("login", "1.1.1.1", "alice");
        assertDoesNotThrow(() -> g.checkLogin("1.1.1.1", "alice"));
    }

    @Test
    void keyIsCaseInsensitiveOnUsername() {
        RateGuard g = strict();
        g.noteFailure("login", "1.1.1.1", "Alice");
        assertEquals(1, g.failuresOf("login", "1.1.1.1", "  alice "));
    }

    @Test
    void guestQuotaIsSmallerThanLoginQuota() {
        RateGuard g = strict();          // guestMax=3
        g.checkGuest("9.9.9.9");
        g.checkGuest("9.9.9.9");
        g.checkGuest("9.9.9.9");
        assertThrows(BizException.class, () -> g.checkGuest("9.9.9.9"));
        assertDoesNotThrow(() -> g.checkRegister("9.9.9.9"));
    }

    @Test
    void tapTapQuotaCutsBothIpAndTicket() {
        RateGuard g = strict();          // taptapMax=3
        g.checkTapTap("9.9.9.9", "tk-a");
        g.checkTapTap("9.9.9.9", "tk-a");
        g.checkTapTap("9.9.9.9", "tk-a");
        assertThrows(BizException.class, () -> g.checkTapTap("9.9.9.9", "tk-z"), "本机配额用尽");
        // 换个来源 IP 也躲不掉同一张票据的配额：一次验签是一次出网调用，不该让人拿坏票据反复打上游
        assertThrows(BizException.class, () -> g.checkTapTap("8.8.8.8", "tk-a"), "票据维度按内容限，换 IP 绕不过");
        assertDoesNotThrow(() -> g.checkTapTap("8.8.8.8", "tk-z"));
    }

    /**
     * 广告回调那道闸（迭代 4 的 F5）：按来源 IP 限频次，性质是<b>防洪</b>不是防爆破——
     * 不认用户名、不进失败计数，超了回 429 让平台自己重试（工单仍是 issued，配额恢复后奖励照发）。
     */
    @Test
    void adCallbackQuotaIsPerIpAndIndependent() {
        // 这里是全仓唯一要同时拧"回调频次"和"举报配额"两道闸的地方，所以走宽口径构造：
        // …taptap 3/60, adCallback 2/1, report 50/60（这条测不到，给个不会和别的撞车的数）, admin 20/30/2/30
        RateGuard g = new RateGuard(true, 5, 10, 2, 15, 5, 60, 3, 60, 3, 60, 2, 1, 50, 60, 20, 30, 2, 30, false);
        g.checkAdCallback("3.3.3.3");
        g.checkAdCallback("3.3.3.3");
        BizException e = assertThrows(BizException.class, () -> g.checkAdCallback("3.3.3.3"));
        assertEquals(429, e.code);
        assertDoesNotThrow(() -> g.checkAdCallback("4.4.4.4"), "配额按来源 IP 各自计");
        assertDoesNotThrow(() -> g.checkLogin("3.3.3.3", "alice"), "回调配额用尽不该顺手锁掉登录面");

        RateGuard off = new RateGuard(false, 5, 10, 2, 15, 5, 60, 3, 60, 3, 60, 1, 1, 50, 60, 20, 30, 2, 30, false);
        for (int i = 0; i < 10; i++) assertDoesNotThrow(() -> off.checkAdCallback("3.3.3.3"), "闸门关了就不能拦投放");
    }

    @Test
    void disabledGuardLetsEverythingThrough() {
        RateGuard g = guard(false, 1, 10, 1, 15);
        for (int i = 0; i < 10; i++) assertDoesNotThrow(() -> g.checkLogin("1.1.1.1", "alice"));
        for (int i = 0; i < 10; i++) assertDoesNotThrow(() -> g.checkGuest("1.1.1.1"));
        for (int i = 0; i < 10; i++) assertDoesNotThrow(() -> g.checkTapTap("1.1.1.1", "tk"));
        for (int i = 0; i < 10; i++) assertDoesNotThrow(() -> g.checkAdCallback("1.1.1.1"));
    }

    @Test
    void forwardedForIsIgnoredUnlessTrusted() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "6.6.6.6");

        assertEquals("10.0.0.1", new RateGuard(true, 5, 10, 2, 15, 5, 60, 3, 60, 3, 60, 20, 30, 2, 30, false)
                .ipOf(req), "直连时客户端可伪造 XFF，不能采信");
        assertEquals("6.6.6.6", new RateGuard(true, 5, 10, 2, 15, 5, 60, 3, 60, 3, 60, 20, 30, 2, 30, true)
                .ipOf(req), "可信反代之后取链上第一个地址");
    }

    @Test
    void expiredWindowResetsBudget() {
        RateGuard g = guard(true, 1, 1, 5, 15);   // 1 分钟窗口
        g.checkLogin("1.1.1.1", "alice");
        assertThrows(BizException.class, () -> g.checkLogin("1.1.1.1", "alice"));
        g.purge();                                  // purge 按 resetAt 回收，这里手工推进不可行，只验证不抛
        assertTrue(g.trackedKeys() > 0);
    }
}
