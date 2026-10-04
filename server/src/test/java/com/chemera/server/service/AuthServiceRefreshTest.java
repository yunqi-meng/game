package com.chemera.server.service;

import com.chemera.server.common.BizException;
import com.chemera.server.entity.AppUser;
import com.chemera.server.entity.UserSession;
import com.chemera.server.mapper.AnalyticsMapper;
import com.chemera.server.mapper.ModerationMapper;
import com.chemera.server.mapper.SaveMapper;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.mapper.UserMapper;
import com.chemera.server.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 账号侧会话语义：刷新令牌的轮换/重用判定，以及后台重置玩家口令后的整户下线。 */
class AuthServiceRefreshTest {

    private UserMapper users;
    private SessionMapper sessions;
    private PasswordEncoder enc;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(UserMapper.class);
        sessions = mock(SessionMapper.class);
        enc = mock(PasswordEncoder.class);
        when(enc.encode(anyString())).thenReturn("$2a$10$reset-hash");
        JwtService jwt = new JwtService("unit-test-secret-value-at-least-32-bytes-long", 60);
        auth = new AuthService(users, sessions, mock(ModerationMapper.class), mock(SaveMapper.class),
                mock(AnalyticsMapper.class), enc, jwt, 30);
        when(users.findById(anyLong())).thenReturn(user(7L, "alice"));
    }

    private static AppUser user(long id, String name) {
        AppUser u = new AppUser();
        u.setId(id); u.setUsername(name); u.setNickname(name);
        u.setPassHash("x"); u.setIsGuest(0);
        return u;
    }

    private static UserSession live() {
        return session(null, null, LocalDateTime.now().plusDays(1));
    }

    private static UserSession session(LocalDateTime revokedAt, LocalDateTime rotatedAt, LocalDateTime expiresAt) {
        UserSession s = new UserSession();
        s.setId(1L); s.setUserId(7L); s.setRefreshHash("h");
        s.setExpiresAt(expiresAt); s.setRevokedAt(revokedAt); s.setRotatedAt(rotatedAt);
        return s;
    }

    private void given(UserSession s) {
        when(sessions.findByHash(anyString())).thenReturn(s);
    }

    @Test
    void refreshRotatesTheUsedToken() {
        given(live());

        Map<String, Object> out = auth.refresh("old-token");

        verify(sessions).rotate(anyString());               // 用过的立即作废
        verify(sessions, never()).revoke(anyString());
        verify(sessions, never()).revokeAll(anyLong());
        verify(sessions).insert(eq(7L), anyString(), anyString(), any());
        assertNotEquals("old-token", out.get("refresh"));
        assertNotNull(out.get("token"));
    }

    @Test
    void reusedTokenBeyondGraceKillsEverySession() {
        given(session(null, LocalDateTime.now().minusSeconds(3600), LocalDateTime.now().plusDays(1)));

        BizException e = assertThrows(BizException.class, () -> auth.refresh("stolen-token"));
        assertEquals(401, e.code);
        verify(sessions).revokeAll(7L);                     // 泄露处置：整户下线
        verify(sessions, never()).insert(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void concurrentRefreshWithinGraceIsBenign() {
        given(session(null, LocalDateTime.now().minusSeconds(5), LocalDateTime.now().plusDays(1)));

        assertDoesNotThrow(() -> auth.refresh("second-tab-token"));
        verify(sessions, never()).revokeAll(anyLong());
        verify(sessions).insert(anyLong(), anyString(), anyString(), any());
    }

    /** 退出/改密/注销留下的硬撤销行，绝不能再换出新令牌。 */
    @Test
    void revokedSessionNeverReissues() {
        given(session(LocalDateTime.now().minusSeconds(1), null, LocalDateTime.now().plusDays(1)));

        BizException e = assertThrows(BizException.class, () -> auth.refresh("logged-out-token"));
        assertTrue(e.getMessage().contains("注销"), e.getMessage());
        verify(sessions, never()).insert(anyLong(), anyString(), anyString(), any());
        verify(sessions, never()).revokeAll(anyLong());
    }

    @Test
    void expiredTokenIsRejectedWithoutTouchingOtherSessions() {
        given(session(null, null, LocalDateTime.now().minusDays(1)));

        BizException e = assertThrows(BizException.class, () -> auth.refresh("dead-token"));
        assertTrue(e.getMessage().contains("过期"), e.getMessage());
        verify(sessions, never()).revokeAll(anyLong());
        verify(sessions, never()).insert(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void unknownTokenIsRejected() {
        given(null);
        assertEquals(401, assertThrows(BizException.class, () -> auth.refresh("never-issued")).code);
    }

    @Test
    void missingTokenIsRejected() {
        assertEquals(401, assertThrows(BizException.class, () -> auth.refresh(null)).code);
        verifyNoInteractions(sessions);
    }

    @Test
    void deletedAccountCannotRefresh() {
        given(live());
        when(users.findById(anyLong())).thenReturn(null);
        assertEquals(401, assertThrows(BizException.class, () -> auth.refresh("orphan")).code);
    }

    /**
     * 忘记密码（C5）：后台人工重置。游客档根本没有口令，给它写一个等于凭空造出一个可登录账号，
     * 所以必须先挡掉；重置成功后整户下线，临时口令不会跟着旧会话一起被人沿用。
     */
    @Test
    void adminResetWritesNewHashAndLogsEverySessionOut() {
        auth.adminResetPassword(7L, "Helpdesk77");

        verify(users).updatePass(7L, "$2a$10$reset-hash");   // 落库的是哈希，不是明文
        verify(sessions).revokeAll(7L);
    }

    @Test
    void guestAccountHasNoPasswordToReset() {
        AppUser g = user(7L, "guest_abc");
        g.setIsGuest(1);
        when(users.findById(anyLong())).thenReturn(g);

        BizException e = assertThrows(BizException.class, () -> auth.adminResetPassword(7L, "Helpdesk77"));
        assertTrue(e.getMessage().contains("游客"), e.getMessage());
        verify(users, never()).updatePass(anyLong(), anyString());
    }

    @Test
    void resetRejectsWeakAndSelfDescriptivePasswords() {
        assertThrows(BizException.class, () -> auth.adminResetPassword(7L, "abc"));
        assertThrows(BizException.class, () -> auth.adminResetPassword(7L, "alice"));
        assertThrows(BizException.class, () -> auth.adminResetPassword(7L, null));
        verify(users, never()).updatePass(anyLong(), anyString());
    }

    @Test
    void resetOfUnknownUserIsRefused() {
        when(users.findById(anyLong())).thenReturn(null);
        assertEquals(400, assertThrows(BizException.class, () -> auth.adminResetPassword(99L, "Helpdesk77")).code);
    }

    /** 用户名口令不变时每次登录都是独立会话；旧会话不会被顶掉（多设备是常态）。 */
    @Test
    void loginCreatesItsOwnSessionAndCarriesUsername() {
        PasswordEncoder enc = mock(PasswordEncoder.class);
        AuthService a = new AuthService(users, sessions, mock(ModerationMapper.class), mock(SaveMapper.class),
                mock(AnalyticsMapper.class), enc,
                new JwtService("unit-test-secret-value-at-least-32-bytes-long", 60), 30);
        AppUser u = user(7L, "alice");
        when(users.findByUsername("alice")).thenReturn(u);
        when(enc.matches("pw", "x")).thenReturn(true);

        Map<String, Object> out = a.login("alice", "pw");

        assertEquals("alice", out.get("user"));
        assertEquals(Boolean.FALSE, out.get("guest"));
        verify(sessions).insert(eq(7L), anyString(), eq("web"), any());
        verify(users).touchLogin(7L);
    }
}
