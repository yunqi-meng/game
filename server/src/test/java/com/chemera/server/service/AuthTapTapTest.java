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
import com.chemera.server.security.TapTapVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TapTap 身份变成本服账号的那几步：建档、复用、并档、拒绝并档、封禁、以及随之而来的注销路径。
 *
 * <p>这一层最值得钉死的是"什么时候会动到别人的存档"：mergeFrom 会删掉游客行，
 * 所以它只允许发生在"这个 openId 之前没有账号"的前提下——写错一次的代价是不可逆的。
 */
class AuthTapTapTest {

    private UserMapper users;
    private SessionMapper sessions;
    private SaveMapper saves;
    private AnalyticsMapper analytics;
    private ModerationMapper mod;
    private PasswordEncoder enc;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(UserMapper.class);
        sessions = mock(SessionMapper.class);
        saves = mock(SaveMapper.class);
        analytics = mock(AnalyticsMapper.class);
        mod = mock(ModerationMapper.class);
        enc = mock(PasswordEncoder.class);
        when(enc.encode(anyString())).thenReturn("$2a$10$placeholder");
        auth = new AuthService(users, sessions, mod, saves, analytics, enc,
                new JwtService("unit-test-secret-value-at-least-32-bytes-long", 60), 30);
        // 建档得像 MyBatis 那样把自增 id 回填到实体上，否则后面发令牌用的是 null
        when(users.insertExternal(any())).thenAnswer(inv -> {
            ((AppUser) inv.getArgument(0)).setId(900L);
            return 1;
        });
    }

    private static AppUser row(long id, String name, int guest) {
        AppUser u = new AppUser();
        u.setId(id); u.setUsername(name); u.setNickname(name);
        u.setPassHash("$2a$10$placeholder"); u.setIsGuest(guest);
        return u;
    }

    private static TapTapVerifier.Identity alice() {
        return new TapTapVerifier.Identity("dev-alice", "小艾");
    }

    @Test
    void firstTapTapLoginBuildsTheAccountAndIssuesTokens() {
        Map<String, Object> out = auth.taptap(alice(), null);

        ArgumentCaptor<AppUser> cap = ArgumentCaptor.forClass(AppUser.class);
        verify(users).insertExternal(cap.capture());
        AppUser created = cap.getValue();
        assertEquals("dev-alice", created.getTaptapOpenId());
        assertEquals("小艾", created.getNickname());
        assertTrue(created.getUsername().startsWith("tap"), created.getUsername());
        assertEquals(19, created.getUsername().length(), "用户名由 openId 摘要派生，玩家不拿它登录");
        assertEquals(Boolean.FALSE, out.get("guest"));
        assertEquals(Boolean.TRUE, out.get("bound"));
        assertNull(out.get("merged"), "没带游客档就不该宣称并过档");
        assertNotNull(out.get("token"));
        verify(users).touchLogin(900L);
        verify(sessions).insert(sessionRow(900L, "web"));
    }

    @Test
    void sameOpenIdSecondTimeOnlyLogsIn() {
        when(users.findByTapTap("dev-alice")).thenReturn(row(42L, "tap8f3a2b1c0d9e8f7a", 0));

        Map<String, Object> out = auth.taptap(alice(), null);

        verify(users, never()).insertExternal(any());
        assertEquals(Boolean.TRUE, out.get("bound"));
        verify(sessions).insert(sessionRow(42L, "web"));
    }

    @Test
    void guestProgressIsMovedIntoTheNewTapTapAccount() {
        when(users.findById(7L)).thenReturn(row(7L, "游客ab12cd34", 1));

        Map<String, Object> out = auth.taptap(alice(), 7L);

        verify(saves).reassignUser(7L, 900L);
        verify(saves).reassignRevisions(7L, 900L);
        verify(analytics).reassignUser(7L, 900L);
        verify(sessions).revokeAll(eq(7L), any(LocalDateTime.class));          // 游客的登录态必须撤干净，它已经没有账号了
        verify(users).delete(7L);
        assertEquals(Boolean.TRUE, out.get("merged"));
        assertEquals(Boolean.FALSE, out.get("guest"));
    }

    /** 坏的游客令牌不该在库里留下一行没人认领的正式账号，所以验档在建档之前。 */
    @Test
    void guestTokenIsValidatedBeforeTheNewAccountIsCreated() {
        when(users.findById(7L)).thenReturn(row(7L, "alice", 0));

        BizException e = assertThrows(BizException.class, () -> auth.taptap(alice(), 7L));
        assertTrue(e.getMessage().contains("不是游客账号"), e.getMessage());
        verify(users, never()).insertExternal(any());
        verify(users, never()).delete(anyLong());
    }

    /** 两份存档硬并必然覆盖其中一份，而覆盖不可逆——这里必须回绝，而不是"挑一个"。 */
    @Test
    void alreadyBoundAccountRefusesToSwallowTheGuestSave() {
        when(users.findByTapTap("dev-alice")).thenReturn(row(42L, "tap8f3a2b1c0d9e8f7a", 0));
        when(users.findById(7L)).thenReturn(row(7L, "游客ab12cd34", 1));

        BizException e = assertThrows(BizException.class, () -> auth.taptap(alice(), 7L));
        assertTrue(e.getMessage().contains("已经有存档"), e.getMessage());
        verify(saves, never()).reassignUser(anyLong(), anyLong());
        verify(users, never()).delete(anyLong());
        verify(sessions, never()).insert(any(UserSession.class));
    }

    @Test
    void bannedTapTapAccountCannotLogIn() {
        AppUser u = row(42L, "tap8f3a2b1c0d9e8f7a", 0);
        u.setStatus(1);
        u.setBannedUntil(LocalDateTime.now().plusDays(1));
        when(users.findByTapTap("dev-alice")).thenReturn(u);

        BizException e = assertThrows(BizException.class, () -> auth.taptap(alice(), null));
        assertEquals(403, e.code);
        verify(sessions, never()).insert(any(UserSession.class));
    }

    /** 昵称过了敏感词才落库：后台审核词表管的不只是玩家自己改的那次（level=2 才拦，G2）。 */
    @Test
    void sensitiveNicknameFromTapTapIsRejectedBeforeTheRowExists() {
        when(mod.words()).thenReturn(List.of(java.util.Map.of("word","违规词","level",2)));

        BizException e = assertThrows(BizException.class,
                () -> auth.taptap(new TapTapVerifier.Identity("dev-z", "含违规词的名字"), null));
        assertTrue(e.getMessage().contains("敏感词"), e.getMessage());
        verify(users, never()).insertExternal(any());
    }

    /** 缺昵称就用占位名，别把 null 写进昵称列。 */
    @Test
    void emptyNicknameFallsBackToPlaceholder() {
        auth.taptap(new TapTapVerifier.Identity("dev-noname", "   "), null);

        ArgumentCaptor<AppUser> cap = ArgumentCaptor.forClass(AppUser.class);
        verify(users).insertExternal(cap.capture());
        assertEquals("TapTap玩家", cap.getValue().getNickname());
    }

    /** 同一个人两个请求同时首次登录：撞唯一键就回重试，不能发两份存档。 */
    @Test
    void concurrentFirstLoginIsReportedAsRetry() {
        // doThrow 形式：when(...) 会真的调用一次桩，把 setUp 里回填 id 的 answer 拿 null 参数跑一遍
        doThrow(new DuplicateKeyException("uk_taptap_open")).when(users).insertExternal(any());

        BizException e = assertThrows(BizException.class, () -> auth.taptap(alice(), null));
        assertTrue(e.getMessage().contains("请重试"), e.getMessage());
    }

    @Test
    void incompleteIdentityIsRejectedWithoutAnyLookup() {
        assertEquals(401, assertThrows(BizException.class,
                () -> auth.taptap(new TapTapVerifier.Identity(" ", "x"), null)).code);
        assertEquals(401, assertThrows(BizException.class, () -> auth.taptap(null, null)).code);
        verifyNoInteractions(saves);
    }

    /* ---------- 注销：TapTap 档没有口令，二次确认换成"重新授权一次" ---------- */

    @Test
    void tapAccountIsDeletedWithAFreshTapTapAuthInsteadOfAPassword() {
        AppUser u = row(42L, "tap8f3a2b1c0d9e8f7a", 0);
        u.setTaptapOpenId("dev-alice");
        when(users.findById(42L)).thenReturn(u);

        assertDoesNotThrow(() -> auth.deleteAccount(42L, null, alice()));

        verify(users).delete(42L);
        verify(sessions).purge(42L);
        verify(saves).purgeUser(42L);
        verify(saves).purgeRevisions(42L);
        verify(analytics).purgeUser(42L);
    }

    /** 没重新授权就不删：令牌被盗时，这一条是注销这个不可逆动作上仅剩的门。 */
    @Test
    void tapAccountWithoutReauthCannotBeDeleted() {
        AppUser u = row(42L, "tap8f3a2b1c0d9e8f7a", 0);
        u.setTaptapOpenId("dev-alice");
        when(users.findById(42L)).thenReturn(u);

        BizException e = assertThrows(BizException.class, () -> auth.deleteAccount(42L, "", null));
        assertTrue(e.getMessage().contains("TapTap"), e.getMessage());
        verify(users, never()).delete(anyLong());
    }

    /** 递来的票据是别人的 openId 也不能删：必须是这个账号绑的那一个。 */
    @Test
    void reauthTicketMustMatchTheBoundOpenId() {
        AppUser u = row(42L, "tap8f3a2b1c0d9e8f7a", 0);
        u.setTaptapOpenId("dev-bob");
        when(users.findById(42L)).thenReturn(u);

        BizException e = assertThrows(BizException.class, () -> auth.deleteAccount(42L, null, alice()));
        assertTrue(e.getMessage().contains("TapTap"), e.getMessage());
        verify(users, never()).delete(anyLong());
    }

    @Test
    void passwordAccountStillNeedsItsPassword() {
        when(users.findById(42L)).thenReturn(row(42L, "alice", 0));
        when(enc.matches("pw", "$2a$10$placeholder")).thenReturn(true);

        auth.deleteAccount(42L, "pw", null);
        verify(users).delete(42L);

        BizException e = assertThrows(BizException.class, () -> auth.deleteAccount(42L, "wrong", null));
        assertEquals("密码不正确", e.getMessage());
    }

    /** 会话行断言（A6 后 insert 收实体，主键要回填给令牌当 sid）。 */
    private static UserSession sessionRow(long uid, String device) {
        return org.mockito.ArgumentMatchers.argThat(s -> s != null && s.getUserId() != null
                && s.getUserId() == uid && device.equals(s.getDevice()));
    }
}
