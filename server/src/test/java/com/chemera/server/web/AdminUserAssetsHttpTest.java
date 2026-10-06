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
 * 调整玩家资产的权限层：这扇门能凭空造钱，所以门槛与"重置玩家口令"同档（只有 super），
 * 而且角色以库为准——把某个管理员降级之后，他手上没过期令牌也立刻调不了钱。
 */
class AdminUserAssetsHttpTest {

    private AdminMapper admins;
    private PlayerAssetService assets;
    private AuditService audit;
    private JwtService jwt;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        admins = mock(AdminMapper.class);
        assets = mock(PlayerAssetService.class);
        audit = mock(AuditService.class);
        jwt = new JwtService("player-asset-http-layer-secret32b!", 60);
        AdminSupport support = new AdminSupport(mock(ContentMapper.class), mock(ContentService.class));
        AdminUserController controller = new AdminUserController(mock(UserMapper.class),
                mock(SaveService.class), assets, mock(SessionMapper.class), mock(AuthService.class), mock(AccountPurge.class), support, audit, mock(CurfewGuard.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AdminInterceptor(jwt, admins))
                .build();
    }

    private String asAdmin(long id, String roleInDb) {
        AdminUser a = new AdminUser();
        a.setId(id); a.setUsername("boss"); a.setRole(roleInDb); a.setStatus(0);
        a.setMustChangePassword(0); a.setPassHash("h");
        when(admins.findById(id)).thenReturn(a);
        // 令牌声明故意写成 super：验证裁决看的是库里的角色，不是令牌里的。
        return jwt.issue(id, "admin", "super", "boss");
    }

    private org.springframework.test.web.servlet.ResultActions adjust(String token, String body) throws Exception {
        return mvc.perform(post("/admin/api/users/assets?id=7").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void missingTokenIs401() throws Exception {
        mvc.perform(post("/admin/api/users/assets?id=7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"coins\":100}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void playerTokenCannotAdjustAssets() throws Exception {
        adjust(jwt.issue(7, "user", "user"), "{\"coins\":100}").andExpect(status().isUnauthorized());
        verify(assets, never()).adjust(anyLong(), anyLong(), anyLong());
    }

    @Test
    void editorRoleIsRejectedEvenWithASuperToken() throws Exception {
        adjust(asAdmin(2, "editor"), "{\"coins\":100}").andExpect(status().isForbidden());
        verify(assets, never()).adjust(anyLong(), anyLong(), anyLong());
    }

    @Test
    void superAdjustSucceedsAndIsAuditedWithBeforeAfter() throws Exception {
        when(assets.adjust(7L, 500, -2)).thenReturn(Map.of(
                "coinsBefore", 1000L, "coinsAfter", 1500L,
                "diamondsBefore", 3L, "diamondsAfter", 1L, "revision", 9L));
        adjust(asAdmin(1, "super"), "{\"coins\":500,\"diamonds\":-2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.data.coinsAfter").value(1500));
        verify(assets).adjust(7L, 500, -2);
        verify(audit).log(eq("boss"), eq("user.assets"), eq("uid:7"), any(), any());
    }

    /** 只填一项时另一项按 0 处理，而不是 null 拆箱炸成 500。 */
    @Test
    void absentFieldMeansNoChange() throws Exception {
        when(assets.adjust(anyLong(), anyLong(), anyLong())).thenReturn(Map.of("revision", 1L));
        adjust(asAdmin(1, "super"), "{\"diamonds\":50}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        verify(assets).adjust(7L, 0, 50);
    }

    @Test
    void nonNumericDeltaIsABusinessErrorNotATypeCrash() throws Exception {
        adjust(asAdmin(1, "super"), "{\"coins\":\"lots\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value(400));
        verify(assets, never()).adjust(anyLong(), anyLong(), anyLong());
    }

    @Test
    void serviceRefusalsSurfaceAsBusinessErrors() throws Exception {
        when(assets.adjust(anyLong(), anyLong(), anyLong()))
                .thenThrow(new BizException("金币不足扣减量：当前 100，本次要扣 500"));
        adjust(asAdmin(1, "super"), "{\"coins\":-500}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.msg").value("金币不足扣减量：当前 100，本次要扣 500"));
    }
}
