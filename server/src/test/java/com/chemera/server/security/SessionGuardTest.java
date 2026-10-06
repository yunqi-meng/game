package com.chemera.server.security;

import com.chemera.server.common.BizException;
import com.chemera.server.mapper.SessionMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A6 的判定核心：一次访问令牌能不能用，取决于它签的那次登录还在不在。
 *
 * <p>这里刻意把"拦截器怎么拒"和"可选登录态怎么降级"放在一起测：同一个判断在两个入口必须给同一个答案。
 * 前者要打死（回 401 让玩家重登），后者要当没登录（不能把一次本来会成功的第三方登录打死），
 * 处理方式不同、依据相同——把两份逻辑写岔，就等于给"已撤销的会话还能并档"留了条缝。
 */
class SessionGuardTest {

    private static final String SECRET = "session-guard-test-secret-value-32bytes+";

    private final SessionMapper sessions = mock(SessionMapper.class);
    private final JwtService jwt = new JwtService(SECRET, 60);
    private final SessionGuard guard = new SessionGuard(sessions, jwt);

    @Test
    void missingOrNonPositiveSidIsNeverLive() {
        assertFalse(guard.live(null, 7L), "没有 sid 的令牌（本轮上线前的老形状）一律当失效");
        assertFalse(guard.live(0L, 7L));
        assertEquals(0L, sessions.liveCount(1L, 1L), "未 stub 的 mock 返回 0：默认拒绝，不会误放");
    }

    @Test
    void liveIsAnExistenceCheckOnTheRow() {
        when(sessions.liveCount(42L, 7L)).thenReturn(1);
        assertTrue(guard.live(42L, 7L));
    }

    /** 会话行被撤销（退出/封禁/改密/重置口令都会标掉）后，签名依然正确的令牌也不再是登录态。 */
    @Test
    void interceptorRejectsRevokedSessionEvenThoughSignatureIsFine() {
        String token = jwt.issue(7L, "user", "user", "小化", 42L);
        when(sessions.liveCount(anyLong(), anyLong())).thenReturn(0);
        AuthInterceptor it = new AuthInterceptor(jwt, guard);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/game/state");
        req.addHeader("Authorization", "Bearer " + token);

        BizException e = assertThrows(BizException.class, () -> it.preHandle(req, null, null));
        assertEquals(401, e.code);
        assertTrue(e.getMessage().contains("重新登录"), "话术要能自救：告诉玩家该重新登录，而不是一句\"无效\"");
    }

    @Test
    void interceptorAcceptsLiveSessionAndWritesUid() {
        String token = jwt.issue(7L, "user", "user", "小化", 42L);
        when(sessions.liveCount(42L, 7L)).thenReturn(1);
        AuthInterceptor it = new AuthInterceptor(jwt, guard);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/game/state");
        req.addHeader("Authorization", "Bearer " + token);

        assertTrue(it.preHandle(req, null, null));
        assertEquals(7L, req.getAttribute(AuthContext.ATTR_USER));
        assertEquals("小化", req.getAttribute(AuthContext.ATTR_USERNAME));
    }

    /** 同一个判断在"可选登录态"那条路上的答案是降级而不是打死：坏令牌当没登录，登录照做。 */
    @Test
    void optionalContextDegradesInsteadOfFailingTheLogin() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/taptap");
        req.addHeader("Authorization", "Bearer " + jwt.issue(7L, "user", "user", "游客x", 42L));
        assertNull(guard.optionalUid(req), "会话已撤销 → 当作没登录，不带并档权限");

        when(sessions.liveCount(42L, 7L)).thenReturn(1);
        assertEquals(7L, guard.optionalUid(req), "会话还在 → 就是这一次登录的人");
    }

    /** 后台令牌不该被当成玩家登录态（typ 不符），可选路径同样要拒。 */
    @Test
    void adminTokenIsNotAPlayerSession() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/taptap");
        req.addHeader("Authorization", "Bearer " + jwt.issue(3L, "admin", "super", "boss"));
        assertNull(guard.optionalUid(req));
    }
}
