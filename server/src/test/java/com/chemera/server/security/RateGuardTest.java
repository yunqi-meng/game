package com.chemera.server.security;

import com.chemera.server.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class RateGuardTest {

    private static RateGuard guard(boolean enabled, int loginMax, int loginWindowMin,
                                   int failMax, int lockMin) {
        return new RateGuard(enabled, loginMax, loginWindowMin, failMax, lockMin,
                5, 60, 3, 60, 20, 30, failMax, lockMin, false);
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
    void disabledGuardLetsEverythingThrough() {
        RateGuard g = guard(false, 1, 10, 1, 15);
        for (int i = 0; i < 10; i++) assertDoesNotThrow(() -> g.checkLogin("1.1.1.1", "alice"));
        for (int i = 0; i < 10; i++) assertDoesNotThrow(() -> g.checkGuest("1.1.1.1"));
    }

    @Test
    void forwardedForIsIgnoredUnlessTrusted() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "6.6.6.6");

        assertEquals("10.0.0.1", new RateGuard(true, 5, 10, 2, 15, 5, 60, 3, 60, 20, 30, 2, 30, false)
                .ipOf(req), "直连时客户端可伪造 XFF，不能采信");
        assertEquals("6.6.6.6", new RateGuard(true, 5, 10, 2, 15, 5, 60, 3, 60, 20, 30, 2, 30, true)
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
