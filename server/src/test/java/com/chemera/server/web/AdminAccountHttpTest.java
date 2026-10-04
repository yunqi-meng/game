package com.chemera.server.web;

import com.chemera.server.common.BizException;
import com.chemera.server.common.GlobalExceptionHandler;
import com.chemera.server.controller.AdminAuthController;
import com.chemera.server.controller.admin.AdminAccountController;
import com.chemera.server.controller.admin.AdminSupport;
import com.chemera.server.entity.AdminUser;
import com.chemera.server.mapper.AdminMapper;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.security.AdminInterceptor;
import com.chemera.server.security.JwtService;
import com.chemera.server.security.RateGuard;
import com.chemera.server.service.AdminAccountService;
import com.chemera.server.service.AuditService;
import com.chemera.server.service.ContentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层的后台账号鉴权：拦截器 → 角色裁决 → 控制器 → 异常映射，整条链路只在真服务里被
 * e2e 覆盖过。这里用 standalone MockMvc（不启 Spring 上下文、不连库）把这些分支钉死，
 * 尤其是"角色以库为准而不是以令牌为准"——令牌是 2 小时有效的，降级必须立刻生效。
 */
class AdminAccountHttpTest {

    private AdminMapper admins;
    private PasswordEncoder enc;
    private RateGuard guard;
    private AuditService audit;
    private JwtService jwt;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        admins = mock(AdminMapper.class);
        enc = mock(PasswordEncoder.class);
        guard = mock(RateGuard.class);
        audit = mock(AuditService.class);
        when(enc.encode(anyString())).thenReturn("$2a$10$hash");
        when(enc.matches(anyString(), anyString())).thenReturn(true);
        jwt = new JwtService("http-layer-test-secret-value-32-bytes+", 60);
        AdminAccountService accounts = new AdminAccountService(admins, enc);
        AdminSupport support = new AdminSupport(mock(ContentMapper.class), mock(ContentService.class));
        AdminAccountController controller = new AdminAccountController(accounts, support, audit);
        AdminAuthController auth = new AdminAuthController(admins, enc, jwt, guard, accounts, audit);
        mvc = MockMvcBuilders.standaloneSetup(controller, auth)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AdminInterceptor(jwt, admins))
                .build();
    }

    private static AdminUser row(long id, String user, String role, int status, int mustChange) {
        AdminUser a = new AdminUser();
        a.setId(id); a.setUsername(user); a.setRole(role); a.setStatus(status);
        a.setMustChangePassword(mustChange); a.setPassHash("$2a$10$hash");
        return a;
    }

    /** 令牌里的 role 只代表签发那一刻，所以桩要同时给出令牌角色与库内角色。 */
    private String tokenAs(long id, String claimRole, AdminUser inDb) {
        when(admins.findById(id)).thenReturn(inDb);
        return jwt.issue(id, "admin", claimRole, inDb == null ? "x" : inDb.getUsername());
    }

    @Test
    void missingTokenIs401() throws Exception {
        mvc.perform(get("/admin/api/admins")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void playerTokenCannotEnterAdminApi() throws Exception {
        String player = jwt.issue(7, "user", "user", "player");
        mvc.perform(get("/admin/api/admins").header("Authorization", "Bearer " + player))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerAndEditorCannotListAdminAccounts() throws Exception {
        String t = tokenAs(2, "editor", row(2, "ed", "editor", 0, 0));
        mvc.perform(get("/admin/api/admins").header("Authorization", "Bearer " + t))
                .andExpect(status().isForbidden());
    }

    /** 令牌写着 super，库里已降级为 viewer —— 必须以库为准拒掉。 */
    @Test
    void roleIsJudgedFromDbNotToken() throws Exception {
        String stale = tokenAs(3, "super", row(3, "ed", "viewer", 0, 0));
        mvc.perform(get("/admin/api/admins").header("Authorization", "Bearer " + stale))
                .andExpect(status().isForbidden());
    }

    @Test
    void disabledAdminIsRejectedEvenWithFreshToken() throws Exception {
        String t = tokenAs(4, "super", row(4, "gone", "super", 1, 0));
        mvc.perform(post("/admin/api/admins/create").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user\":\"ops01\",\"pass\":\"Str0ngPass!\",\"role\":\"editor\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void mustChangePasswordLocksEverythingButSelfChange() throws Exception {
        String t = tokenAs(5, "super", row(5, "newbie", "super", 0, 1));
        mvc.perform(get("/admin/api/admins").header("Authorization", "Bearer " + t))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.msg").value("请先修改初始密码后再使用后台"));
        mvc.perform(post("/admin/api/me/password").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"old\":\"Init1234\",\"new\":\"Str0ngPass!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        verify(admins).updatePass(eq(5L), anyString(), eq(0));
    }

    @Test
    void listReturnsRowsWithoutHash() throws Exception {
        String t = tokenAs(1, "super", row(1, "admin", "super", 0, 0));
        when(admins.list()).thenReturn(List.of(row(1, "admin", "super", 0, 0), row(9, "ops", "editor", 0, 1)));
        mvc.perform(get("/admin/api/admins").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].user").value("ops"))
                .andExpect(jsonPath("$.data[1].mustChange").value(true))
                .andExpect(jsonPath("$.data[1].passHash").doesNotExist());
    }

    @Test
    void createIsAuditedWithOperatorName() throws Exception {
        String t = tokenAs(1, "super", row(1, "admin", "super", 0, 0));
        mvc.perform(post("/admin/api/admins/create").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user\":\"ops01\",\"pass\":\"Str0ngPass!\",\"role\":\"editor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user").value("ops01"));
        verify(audit).log(eq("admin"), eq("admin.create"), anyString(), any(), any());
    }

    @Test
    void weakPasswordBecomesBusinessErrorNot500() throws Exception {
        String t = tokenAs(1, "super", row(1, "admin", "super", 0, 0));
        mvc.perform(post("/admin/api/admins/create").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user\":\"ops01\",\"pass\":\"weak\",\"role\":\"editor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value(400));
        verify(admins, never()).insert(any());
    }

    @Test
    void lastSuperCannotBeDisabledThroughHttp() throws Exception {
        String t = tokenAs(1, "super", row(1, "admin", "super", 0, 0));
        when(admins.findById(2L)).thenReturn(row(2, "solo", "super", 0, 0));
        when(admins.countActiveSuper()).thenReturn(1L);
        mvc.perform(post("/admin/api/admins/status?id=2&status=1").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false));
        verify(admins, never()).updateStatus(anyLong(), anyInt());
    }

    @Test
    void loginLockoutSurfacesAs429() throws Exception {
        // MockMvc 里没有真实 socket，ipOf 返回 null，故用 any() 而不是 anyString()
        doThrow(BizException.tooMany("失败次数过多，请在 5 分钟后重试"))
                .when(guard).checkAdminLogin(any(), any());
        // 真实配置里 login 不过 AdminInterceptor（还没有令牌），所以这里单独建一套不带拦截器的 mvc
        MockMvc open = MockMvcBuilders
                .standaloneSetup(new AdminAuthController(admins, enc, jwt, guard,
                        new AdminAccountService(admins, enc), audit))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        open.perform(post("/admin/api/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user\":\"admin\",\"pass\":\"x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    void selfChangeRejectsWrongOldPassword() throws Exception {
        String t = tokenAs(1, "super", row(1, "admin", "super", 0, 0));
        when(enc.matches(eq("nope"), anyString())).thenReturn(false);
        mvc.perform(post("/admin/api/me/password").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"old\":\"nope\",\"new\":\"Str0ngPass!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false));
        verify(admins, never()).updatePass(anyLong(), anyString(), anyInt());
    }
}
