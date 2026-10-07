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
import org.mockito.ArgumentCaptor;
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

        verify(sessions).rotate(anyString(), any(LocalDateTime.class));   // 用过的立即作废
        verify(sessions, never()).revoke(anyString(), any(LocalDateTime.class));
        verify(sessions, never()).revokeAll(anyLong(), any(LocalDateTime.class));
        verify(sessions).insert(sessionRow(7L, "web"));
        assertNotEquals("old-token", out.get("refresh"));
        assertNotNull(out.get("token"));
    }

    @Test
    void reusedTokenBeyondGraceKillsEverySession() {
        given(session(null, LocalDateTime.now().minusSeconds(3600), LocalDateTime.now().plusDays(1)));

        BizException e = assertThrows(BizException.class, () -> auth.refresh("stolen-token"));
        assertEquals(401, e.code);
        verify(sessions).revokeAll(eq(7L), any(LocalDateTime.class));      // 泄露处置：整户下线
        verify(sessions, never()).insert(any(UserSession.class));
    }

    @Test
    void concurrentRefreshWithinGraceIsBenign() {
        given(session(null, LocalDateTime.now().minusSeconds(5), LocalDateTime.now().plusDays(1)));

        assertDoesNotThrow(() -> auth.refresh("second-tab-token"));
        verify(sessions, never()).revokeAll(anyLong(), any(LocalDateTime.class));
        verify(sessions).insert(any(UserSession.class));
    }

    /**
     * CI 首跑那次红测的就是这条：盖 {@code rotated_at} 的钟和判"是否超出宽限期"的钟必须是同一支。
     *
     * <p>当时 {@code rotated_at}/{@code revoked_at} 由 SQL 的 {@code NOW()} 写（库在 UTC），
     * 而 {@code AuthService.refresh} 拿 {@code LocalDateTime.now()}（应用在 +08:00）去判，
     * 于是刚轮换完的那一行立刻被算成"超出 15 秒宽限期"→ 整户 {@code revokeAll} → 玩家被踢下线、
     * 后面几十条断言级联成红。
     *
     * <p>这里能钉住的形式是"传进去的那支针"：mapper 收到的时间戳必须落在本用例自己量出的
     * JVM 时间窗里（而不是库钟可能给出的那个数），并且**同一次调用**里判断用的和写下的相差不超过
     * 一个毫秒级抖动。宽限期那侧用一支人为拨快 8 小时的"库钟"来反证——它必须把会话判死，
     * 否则这条断言就只是空转。
     */
    @Test
    void rotationGraceUsesTheSameClockItWrites() {
        LocalDateTime before = LocalDateTime.now();
        given(live());
        auth.refresh("first-tab-token");
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> stamp = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessions).rotate(anyString(), stamp.capture());
        LocalDateTime written = stamp.getValue();
        assertFalse(written.isBefore(before.minusSeconds(1)),
                "写进 rotated_at 的钟比本用例开始还早：" + written + "（看起来像库钟，不是 JVM 钟）");
        assertFalse(written.isAfter(after.plusSeconds(1)),
                "写进 rotated_at 的钟比本用例结束还晚：" + written);

        // 反证：同一行如果由一支快了 8 小时的钟来判（迭代 4 CI 里库与进程的差），刚轮换也算"超期"
        UserSession justRotated = session(null, written, LocalDateTime.now().plusDays(1));
        given(justRotated);
        assertDoesNotThrow(() -> auth.refresh("second-tab-token"), "自家钟下 15 秒内的并发刷新不该打死");
        LocalDateTime shifted = written.minusHours(8);
        given(session(null, shifted, LocalDateTime.now().plusDays(1)));
        BizException e = assertThrows(BizException.class, () -> auth.refresh("third-tab-token"));
        assertTrue(e.getMessage().contains("异常"), e.getMessage());
    }

    /** 退出是硬撤销：写下的仍然是进程那支钟（与 liveCount 的判断同一支）。 */
    @Test
    void logoutStampsWithTheAppClock() {
        LocalDateTime before = LocalDateTime.now();
        auth.logout("some-token");
        ArgumentCaptor<LocalDateTime> stamp = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessions).revoke(anyString(), stamp.capture());
        assertFalse(stamp.getValue().isBefore(before.minusSeconds(1))
                        || stamp.getValue().isAfter(LocalDateTime.now().plusSeconds(1)),
                "revoked_at 不该由库钟盖：" + stamp.getValue());
    }

    /**
     * {@code live(sid,uid)} 的 {@code now} 也是调用方给的：会话活体检查判的是 {@code expires_at}，
     * 那一列由 {@code token(u)} 用 JVM 钟写入——两支钟一旦不一致，玩家每次请求都在赌库的时区。
     */
    @Test
    void sessionLivenessJudgedWithAppClock() {
        when(sessions.liveCount(anyLong(), anyLong(), any(LocalDateTime.class))).thenReturn(1);
        com.chemera.server.security.SessionGuard guard =
                new com.chemera.server.security.SessionGuard(sessions,
                        new JwtService("unit-test-secret-value-at-least-32-bytes-long", 60));
        LocalDateTime before = LocalDateTime.now();
        assertTrue(guard.live(1L, 7L));
        ArgumentCaptor<LocalDateTime> stamp = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessions).liveCount(eq(1L), eq(7L), stamp.capture());
        assertFalse(stamp.getValue().isBefore(before.minusSeconds(1))
                        || stamp.getValue().isAfter(LocalDateTime.now().plusSeconds(1)),
                "活体检查用的不是 JVM 现在：" + stamp.getValue());
    }

    /**
     * 会话那一行的 {@code expires_at} 也必须是 JVM 钟写的——它是 {@code liveCount} 与
     * {@code refresh} 两条判断共同比对的对象，两支钟错开就是"刚登录就过期"。
     */
    @Test
    void sessionRowExpiryComesFromTheAppClock() {
        given(live());
        auth.refresh("first-tab-token");

        ArgumentCaptor<UserSession> row = ArgumentCaptor.forClass(UserSession.class);
        verify(sessions).insert(row.capture());
        LocalDateTime exp = row.getValue().getExpiresAt();
        assertTrue(exp.isAfter(LocalDateTime.now().plusDays(29)) && exp.isBefore(LocalDateTime.now().plusDays(31)),
                "刷新令牌的到期时间不在 30 天窗口内：" + exp);
        assertEquals(7L, row.getValue().getUserId());
    }

    /**
     * 退出/改密/注销留下的硬撤销行，绝不能再换出新令牌。 */
    @Test
    void revokedSessionNeverReissues() {
        given(session(LocalDateTime.now().minusSeconds(1), null, LocalDateTime.now().plusDays(1)));

        BizException e = assertThrows(BizException.class, () -> auth.refresh("logged-out-token"));
        assertTrue(e.getMessage().contains("注销"), e.getMessage());
        verify(sessions, never()).insert(any(UserSession.class));
        verify(sessions, never()).revokeAll(anyLong(), any(LocalDateTime.class));
    }

    @Test
    void expiredTokenIsRejectedWithoutTouchingOtherSessions() {
        given(session(null, null, LocalDateTime.now().minusDays(1)));

        BizException e = assertThrows(BizException.class, () -> auth.refresh("dead-token"));
        assertTrue(e.getMessage().contains("过期"), e.getMessage());
        verify(sessions, never()).revokeAll(anyLong(), any(LocalDateTime.class));
        verify(sessions, never()).insert(any(UserSession.class));
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
        verify(sessions).revokeAll(eq(7L), any(LocalDateTime.class));
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
        verify(sessions).insert(sessionRow(7L, "web"));
        verify(users).touchLogin(7L);
    }

    /**
     * 会话行断言：A6 之后 {@code insert} 收的是实体（自增主键要回填成令牌里的 sid），
     * 所以这里按字段匹配，而不是继续传四个散参数。uid + device 是这两组用例真正在意的两件事。
     */
    private static UserSession sessionRow(long uid, String device) {
        return org.mockito.ArgumentMatchers.argThat(s -> s != null && s.getUserId() != null
                && s.getUserId() == uid && device.equals(s.getDevice()));
    }
}
