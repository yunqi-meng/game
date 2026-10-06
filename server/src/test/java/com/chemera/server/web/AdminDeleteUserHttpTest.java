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
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台删号这条通道（G6）在 HTTP 层的三件事：谁能按、按下去走的是哪一份清单、事后留下什么凭据。
 *
 * <p>原来这条通道只删了 {@code app_user} 一行——存档、历史、埋点、广告工单全留在库里，
 * 于是"后台删过的玩家"用同名重新注册还能撞上残留档。清单本身由 {@code AccountPurgeTest} 逐表钉住，
 * 这里钉的是<b>控制器确实把删除交给了那份清单</b>，而不是顺手又写了一遍 {@code users.delete(id)}：
 * 两个入口有两种语义本身就是事故（商店审号时挑的就是这个）。
 *
 * <p>另一半是审计：删完必须把"删了几行"落进 {@code audit_log}。事后有人问"你到底清没清"，
 * 那份数字是凭据，一句"我清了"不是。
 */
class AdminDeleteUserHttpTest {

    private UserMapper users;
    private AccountPurge purge;
    private AuditService audit;
    private CurfewGuard curfew;
    private AdminMapper admins;
    private JwtService jwt;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(UserMapper.class);
        purge = mock(AccountPurge.class);
        audit = mock(AuditService.class);
        curfew = mock(CurfewGuard.class);
        admins = mock(AdminMapper.class);
        jwt = new JwtService("delete-user-http-layer-secret-32bytes!", 60);
        AdminUserController controller = new AdminUserController(users,
                mock(SaveService.class), mock(PlayerAssetService.class), mock(SessionMapper.class),
                mock(AuthService.class), purge,
                new AdminSupport(mock(ContentMapper.class), mock(ContentService.class)), audit, curfew);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AdminInterceptor(jwt, admins))
                .build();
    }

    /**
     * 库里那一行才是裁决依据：令牌里的 role 只是签发那一刻的身份。
     * 所以这里故意让签发出去的角色和库里的角色一致，另有一条用例（只读角色被拒）专门验证降级即时生效。
     */
    private String tokenAs(String roleInDb) {
        AdminUser a = new AdminUser();
        a.setId(1L); a.setUsername("boss"); a.setRole(roleInDb); a.setStatus(0);
        a.setMustChangePassword(0); a.setPassHash("h");
        when(admins.findById(1L)).thenReturn(a);
        return jwt.issue(1L, "admin", roleInDb, "boss");
    }

    private AppUser player(long id) {
        AppUser u = new AppUser();
        u.setId(id); u.setUsername("玩家" + id); u.setNickname("玩家" + id);
        u.setPassHash("h"); u.setIsGuest(0);
        return u;
    }

    /* ---------------- 1. 谁都能按不行：只读角色被挡在清单之外 ---------------- */

    @Test
    void aViewerCannotDeleteAnAccount() throws Exception {
        when(users.findById(7L)).thenReturn(player(7L));

        mvc.perform(delete("/admin/api/users?id=7").header("Authorization", "Bearer " + tokenAs("viewer")))
                .andExpect(status().isForbidden());

        verify(purge, never()).purge(anyLong());
        verify(users, never()).delete(anyLong());
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }

    /** 没令牌连库都不回查，更别说删人。 */
    @Test
    void noTokenNoDeletion() throws Exception {
        mvc.perform(delete("/admin/api/users?id=7")).andExpect(status().isUnauthorized());
        verify(purge, never()).purge(anyLong());
    }

    /* ---------------- 2. 按下去走的是那份共享清单 ---------------- */

    @Test
    void theWriterPathRunsTheSharedPurgeList() throws Exception {
        when(users.findById(7L)).thenReturn(player(7L));
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("sessions", 3); done.put("save", 1); done.put("saveRevisions", 25);
        done.put("analytics", 9); done.put("adTickets", 2);
        done.put("reportsFiled", 1); done.put("reportsAbout", 4); done.put("user", 1);
        when(purge.purge(7L)).thenReturn(done);

        mvc.perform(delete("/admin/api/users?id=7").header("Authorization", "Bearer " + tokenAs("operator")))
                .andExpect(status().isOk())
                // 回执直接透给后台界面：运营按完当场看见"清了什么"，而不是只看见一个勾
                .andExpect(jsonPath("$.data.saveRevisions").value(25))
                .andExpect(jsonPath("$.data.reportsAbout").value(4));

        verify(purge).purge(7L);
        // 控制器自己不许再去删 app_user：那份清单里已经有这一项，重复一次就是两套口径
        verify(users, never()).delete(anyLong());
        verify(curfew).invalidate(7L);      // 防沉迷缓存里不该留着这个人的行
    }

    /**
     * 审计里落的必须是<b>那份"删了几行"的数字</b>，而且落的就是清理真实返回的那一个对象。
     *
     * <p>这是整条通道唯一可追责的痕迹。如果这里落的是 {@code Map.of("id", 7)} 之类的占位，
     * 事后追责时"我确实清了他的存档"就重新变成一句口头承诺。
     */
    @Test
    void theRowCountsAreWhatLandsInTheAuditLog() throws Exception {
        when(users.findById(7L)).thenReturn(player(7L));
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("user", 1); done.put("save", 1);
        when(purge.purge(7L)).thenReturn(done);

        mvc.perform(delete("/admin/api/users?id=7").header("Authorization", "Bearer " + tokenAs("super")))
                .andExpect(status().isOk());

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("boss"), eq("user.delete"), eq("uid:7"), cap.capture(), any());
        assertSame(done, cap.getValue(), "审计里那份数字必须是清理真实报回来的那一个，不是控制器现编的");
    }

    /* ---------------- 3. 不存在的人：404，且一张表都不碰 ---------------- */

    /**
     * 先查再删。
     *
     * <p>{@code AccountPurge} 的清单对不存在的 uid 也能跑完（全是 0 行），所以"少这一句"不会写坏数据，
     * 但会让运营对着一个成功回执分不清"这个 uid 真的清过了"还是"这个 uid 从来没有过"——
     * 删号是不可逆动作，这两种情况必须给出不同的答复。
     */
    @Test
    void anUnknownIdIsRefusedBeforeAnythingIsSwept() throws Exception {
        when(users.findById(7L)).thenReturn(null);

        mvc.perform(delete("/admin/api/users?id=7").header("Authorization", "Bearer " + tokenAs("super")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.msg").value("用户不存在"));

        verify(purge, never()).purge(anyLong());
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }
}
