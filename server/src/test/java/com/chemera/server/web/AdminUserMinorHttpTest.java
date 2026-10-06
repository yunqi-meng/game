package com.chemera.server.web;

import com.chemera.server.common.GlobalExceptionHandler;
import com.chemera.server.controller.admin.AdminSupport;
import com.chemera.server.controller.admin.AdminUserController;
import com.chemera.server.entity.AdminUser;
import com.chemera.server.entity.AppUser;
import com.chemera.server.game.CurfewGuard;
import com.chemera.server.mapper.AdminMapper;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.mapper.UserMapper;
import com.chemera.server.security.AdminInterceptor;
import com.chemera.server.security.JwtService;
import com.chemera.server.service.AccountPurge;
import com.chemera.server.service.AuditService;
import com.chemera.server.service.AuthService;
import com.chemera.server.service.ContentService;
import com.chemera.server.service.PlayerAssetService;
import com.chemera.server.service.SaveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 标记青少年模式的权限与审计层。
 *
 * <p>这一档比"调整资产"低（{@code requireWriter}，编辑角色就能做），比"重置口令"高不成低不就——
 * 它既不造钱也不顶号，但它直接决定一个未成年玩家能不能在游戏里待着，所以两点必须钉住：
 * 只读角色（viewer）改不动；每一次改动都留下审计，事后能查到是谁在什么时候给谁挂上的限制。
 *
 * <p>另外钉住"标完立刻生效"：控制器必须调用 {@code invalidate}，否则玩家刚被标记，
 * 还得等 60 秒缓存过期才拦得住——那种"运营点了按钮但没生效"的窗口正是投诉的来源。
 */
class AdminUserMinorHttpTest {

    private UserMapper users;
    private CurfewGuard curfew;
    private AuditService audit;
    private JwtService jwt;
    private AdminMapper admins;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(UserMapper.class);
        curfew = mock(CurfewGuard.class);
        audit = mock(AuditService.class);
        admins = mock(AdminMapper.class);
        jwt = new JwtService("minor-mark-http-layer-secret-32b!", 60);
        AdminSupport support = new AdminSupport(mock(ContentMapper.class), mock(ContentService.class));
        when(curfew.view(anyLong())).thenReturn(Map.of("minor", true, "allowed", false));
        AppUser player = new AppUser();
        player.setId(7L); player.setUsername("kid"); player.setMinor(0);
        when(users.findById(7L)).thenReturn(player);

        AdminUserController controller = new AdminUserController(users,
                mock(SaveService.class), mock(PlayerAssetService.class), mock(SessionMapper.class),
                mock(AuthService.class), mock(AccountPurge.class), support, audit, curfew);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AdminInterceptor(jwt, admins))
                .build();
    }

    private String asAdmin(long id, String roleInDb) {
        AdminUser a = new AdminUser();
        a.setId(id); a.setUsername("ops"); a.setRole(roleInDb); a.setStatus(0);
        a.setMustChangePassword(0); a.setPassHash("h");
        when(admins.findById(id)).thenReturn(a);
        // 令牌声明写成 super：验证裁决看的是库里的角色，不是令牌里的
        return jwt.issue(id, "admin", "super", "ops");
    }

    private org.springframework.test.web.servlet.ResultActions mark(String token, boolean on) throws Exception {
        return mvc.perform(post("/admin/api/users/minor?id=7&on=" + on)
                .header("Authorization", "Bearer " + token));
    }

    @Test
    void missingTokenIs401() throws Exception {
        mvc.perform(post("/admin/api/users/minor?id=7&on=true"))
                .andExpect(status().isUnauthorized());
        verify(users, never()).setMinor(anyLong(), anyInt());
    }

    @Test
    void viewerRoleIsRejectedEvenWithASuperToken() throws Exception {
        mark(asAdmin(3, "viewer"), true).andExpect(status().isForbidden());
        verify(users, never()).setMinor(anyLong(), anyInt());
    }

    @Test
    void editorCanMarkAndItIsAudited() throws Exception {
        mark(asAdmin(2, "editor"), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.allowed").value(false));
        verify(users).setMinor(7L, 1);
        verify(curfew).invalidate(7L);
        verify(audit).log(eq("ops"), eq("user.minor"), eq("uid:7"), eq(Map.of("on", true)), any());
    }

    @Test
    void unmarkWritesZeroAndAlsoInvalidates() throws Exception {
        mark(asAdmin(2, "editor"), false).andExpect(status().isOk());
        verify(users).setMinor(7L, 0);
        verify(curfew).invalidate(7L);
    }

    /** 不存在或已注销的 uid：回业务错误，而不是在 setMinor 上静默"成功"。 */
    @Test
    void unknownUserIsRefusedBeforeAnyWrite() throws Exception {
        when(users.findById(99L)).thenReturn(null);
        mvc.perform(post("/admin/api/users/minor?id=99&on=true")
                        .header("Authorization", "Bearer " + asAdmin(2, "editor")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        verify(users, never()).setMinor(anyLong(), anyInt());
    }
}
