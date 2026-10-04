package com.chemera.server.service;

import com.chemera.server.common.BizException;
import com.chemera.server.entity.AdminUser;
import com.chemera.server.mapper.AdminMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 管理员账号 CRUD 的边界。这里守住的是"后台把自己锁死"和"公开默认口令长存"两类事故，
 * 所以断言的重点不在增删改查本身，而在每一次改动前的那道守卫条件。
 */
class AdminAccountServiceTest {

    private AdminMapper admins;
    private PasswordEncoder enc;
    private AdminAccountService svc;

    @BeforeEach
    void setUp() {
        admins = mock(AdminMapper.class);
        enc = mock(PasswordEncoder.class);
        when(enc.encode(anyString())).thenReturn("$2a$10$hashed");
        when(enc.matches(anyString(), anyString())).thenReturn(true);
        svc = new AdminAccountService(admins, enc);
    }

    private static AdminUser admin(long id, String user, String role, int status) {
        AdminUser a = new AdminUser();
        a.setId(id); a.setUsername(user); a.setRole(role); a.setStatus(status);
        a.setPassHash("$2a$10$hashed"); a.setMustChangePassword(0);
        return a;
    }

    @Test
    void createRejectsMalformedName() {
        for (String bad : new String[]{"ab", "1abc", "a b", "中文admin", ""}) {
            assertThrows(BizException.class, () -> svc.create(bad, "Str0ngPass!", "editor"), bad);
        }
        verify(admins, never()).insert(any());
    }

    @Test
    void createRejectsWeakOrUnknownPassword() {
        assertThrows(BizException.class, () -> svc.create("ops01", "short", "editor"));
        assertThrows(BizException.class, () -> svc.create("ops01", "admin123", "editor"));
        assertThrows(BizException.class, () -> svc.create("ops01", "PASSWORD", "editor"));
        assertThrows(BizException.class, () -> svc.create("ops01", "ops01", "editor"));   // 等于账号名
        assertThrows(BizException.class, () -> svc.create("ops01", "xadmin123y", "editor"));
        verify(admins, never()).insert(any());
    }

    @Test
    void createRejectsUnknownRoleAndDuplicateName() {
        assertThrows(BizException.class, () -> svc.create("ops01", "Str0ngPass!", "root"));
        when(admins.findByUsername("ops01")).thenReturn(admin(9, "ops01", "editor", 0));
        assertThrows(BizException.class, () -> svc.create("ops01", "Str0ngPass!", "editor"));
    }

    @Test
    void createMarksAccountAsNeedingFirstLogin() {
        Map<String, Object> row = svc.create("ops01", "Str0ngPass!", "editor");
        var cap = org.mockito.ArgumentCaptor.forClass(AdminUser.class);
        verify(admins).insert(cap.capture());
        AdminUser saved = cap.getValue();
        assertEquals(1, saved.getMustChangePassword(), "初始口令必须打\"下次登录改密\"位");
        assertEquals("$2a$10$hashed", saved.getPassHash(), "落库的是哈希不是明文");
        assertEquals("editor", saved.getRole());
        assertEquals(Boolean.TRUE, row.get("mustChange"), "列表要能看出该号还没改密");
    }

    @Test
    void resetAlsoForcesPasswordChange() {
        when(admins.findById(3L)).thenReturn(admin(3, "ops01", "editor", 0));
        svc.resetPassword(3, "An0therPass!");
        verify(admins).updatePass(eq(3L), eq("$2a$10$hashed"), eq(1));
    }

    @Test
    void selfChangeRequiresCorrectOldPassword() {
        when(admins.findById(3L)).thenReturn(admin(3, "ops01", "editor", 0));
        when(enc.matches(eq("wrong"), anyString())).thenReturn(false);
        assertThrows(BizException.class, () -> svc.changeOwnPassword(3, "wrong", "An0therPass!"));
        verify(admins, never()).updatePass(anyLong(), anyString(), anyInt());
    }

    @Test
    void selfChangeClearsFlagAndRejectsSamePassword() {
        when(admins.findById(3L)).thenReturn(admin(3, "ops01", "editor", 0));
        assertThrows(BizException.class, () -> svc.changeOwnPassword(3, "SamePass1", "SamePass1"));
        svc.changeOwnPassword(3, "OldPass99", "An0therPass!");
        verify(admins).updatePass(eq(3L), anyString(), eq(0));
    }

    @Test
    void cannotDisableOrDeleteOrDemoteSelf() {
        AdminUser me = admin(1, "admin", "super", 0);
        when(admins.findById(1L)).thenReturn(me);
        when(admins.countActiveSuper()).thenReturn(3L);
        assertThrows(BizException.class, () -> svc.setStatus(1, 1, 1L));
        assertThrows(BizException.class, () -> svc.delete(1, 1L));
        assertThrows(BizException.class, () -> svc.setRole(1, "viewer", 1L));
        verify(admins, never()).updateStatus(anyLong(), anyInt());
        verify(admins, never()).delete(anyLong());
    }

    @Test
    void lastActiveSuperCannotBeDisabledDemotedOrDeleted() {
        AdminUser other = admin(2, "ops", "super", 0);
        when(admins.findById(2L)).thenReturn(other);
        when(admins.countActiveSuper()).thenReturn(1L);
        assertThrows(BizException.class, () -> svc.setStatus(2, 1, 1L));
        assertThrows(BizException.class, () -> svc.setRole(2, "editor", 1L));
        assertThrows(BizException.class, () -> svc.delete(2, 1L));
    }

    @Test
    void secondSuperMayBeDisabled() {
        AdminUser other = admin(2, "ops", "super", 0);
        when(admins.findById(2L)).thenReturn(other);
        when(admins.countActiveSuper()).thenReturn(2L);
        svc.setStatus(2, 1, 1L);
        verify(admins).updateStatus(2L, 1);
    }

    @Test
    void disabledSuperDoesNotCountAsLastResort() {
        AdminUser gone = admin(2, "ops", "super", 1);
        when(admins.findById(2L)).thenReturn(gone);
        when(admins.countActiveSuper()).thenReturn(1L);   // 别人还在岗
        svc.delete(2, 1L);                                 // 清理已停用的超管不该被拦
        verify(admins).delete(2L);
    }

    @Test
    void invalidStatusAndMissingAccountAreRejected() {
        when(admins.findById(1L)).thenReturn(admin(1, "admin", "super", 0));
        BizException bad = assertThrows(BizException.class, () -> svc.setStatus(1, 5, 9L));
        assertTrue(bad.getMessage().contains("状态"));
        when(admins.findById(77L)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> svc.setRole(77, "editor", 1L));
        assertTrue(e.getMessage().contains("不存在"));
    }

    @Test
    void listNeverLeaksPasswordHash() {
        AdminUser a = admin(1, "admin", "super", 0);
        a.setLastLoginAt(java.time.LocalDateTime.of(2026, 9, 25, 10, 0));
        when(admins.list()).thenReturn(List.of(a));
        List<Map<String, Object>> rows = svc.list();
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).containsKey("mustChange"));
        assertEquals("admin", rows.get(0).get("user"));
        assertNull(rows.get(0).get("passHash"), "列表响应不含口令哈希");
    }
}
