package com.chemera.server.web;

import com.chemera.server.common.BizException;
import com.chemera.server.common.GlobalExceptionHandler;
import com.chemera.server.controller.admin.AdminSupport;
import com.chemera.server.controller.admin.AdminUserController;
import com.chemera.server.game.CurfewGuard;
import com.chemera.server.entity.AdminUser;
import com.chemera.server.mapper.AdminMapper;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.mapper.UserMapper;
import com.chemera.server.security.AdminInterceptor;
import com.chemera.server.security.JwtService;
import com.chemera.server.service.AuditService;
import com.chemera.server.service.AuthService;
import com.chemera.server.service.ContentService;
import com.chemera.server.service.SaveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 重置玩家口令（忘记密码）的权限层：这道口子等于顶号登录，所以比一般后台写操作高一档，
 * 只有 super 能调；编辑角色即便拿着有效令牌也一律拒。
 */
class AdminUserResetPasswordHttpTest {

    private AdminMapper admins;
    private AuthService auth;
    private AuditService audit;
    private JwtService jwt;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        UserMapper users = mock(UserMapper.class);
        SessionMapper sessions = mock(SessionMapper.class);
        admins = mock(AdminMapper.class);
        auth = mock(AuthService.class);
        audit = mock(AuditService.class);
        jwt = new JwtService("reset-password-layer-secret-32-bytes!", 60);
        AdminSupport support = new AdminSupport(mock(ContentMapper.class), mock(ContentService.class));
        AdminUserController controller = new AdminUserController(users, mock(SaveService.class),
                mock(com.chemera.server.service.PlayerAssetService.class),
                sessions, auth, mock(com.chemera.server.service.AccountPurge.class),
                support, audit, mock(com.chemera.server.game.CurfewGuard.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AdminInterceptor(jwt, admins))
                .build();
    }

    private String asAdmin(long id, String role) {
        AdminUser a = new AdminUser();
        a.setId(id); a.setUsername("admin"); a.setRole(role); a.setStatus(0);
        a.setMustChangePassword(0); a.setPassHash("h");
        when(admins.findById(id)).thenReturn(a);
        return jwt.issue(id, "admin", role, "admin");
    }

    @Test
    void missingTokenIs401() throws Exception {
        mvc.perform(post("/admin/api/users/reset-password?id=7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":\"NewPass123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void editorCannotResetPlayerPassword() throws Exception {
        String t = asAdmin(2, "editor");
        mvc.perform(post("/admin/api/users/reset-password?id=7").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pass\":\"NewPass123\"}"))
                .andExpect(status().isForbidden());
        verify(auth, never()).adminResetPassword(anyLong(), any());
    }

    @Test
    void superResetIsAudited() throws Exception {
        String t = asAdmin(1, "super");
        mvc.perform(post("/admin/api/users/reset-password?id=7").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pass\":\"NewPass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        verify(auth).adminResetPassword(7L, "NewPass123");
        verify(audit).log(eq("admin"), eq("user.resetPassword"), eq("uid:7"), any(), any());
    }

    /** 游客档没有口令：服务端的业务拒绝要原样变成 ok:false，不能冒成 500。 */
    @Test
    void guestRefusalSurfacesAsBusinessError() throws Exception {
        String t = asAdmin(1, "super");
        doThrow(new BizException("游客档没有口令，无法重置")).when(auth).adminResetPassword(eq(8L), any());
        mvc.perform(post("/admin/api/users/reset-password?id=8").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pass\":\"NewPass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void tooShortPasswordIsRejected() throws Exception {
        String t = asAdmin(1, "super");
        doThrow(new BizException("新密码至少 6 位")).when(auth).adminResetPassword(anyLong(), eq("123"));
        mvc.perform(post("/admin/api/users/reset-password?id=7").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pass\":\"123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false));
    }
}
