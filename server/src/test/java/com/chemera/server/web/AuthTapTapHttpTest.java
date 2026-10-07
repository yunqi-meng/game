package com.chemera.server.web;

import com.chemera.server.common.GlobalExceptionHandler;
import com.chemera.server.controller.AuthController;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.security.AuthContext;
import com.chemera.server.security.JwtService;
import com.chemera.server.security.RateGuard;
import com.chemera.server.security.SessionGuard;
import com.chemera.server.security.TapTapVerifier;
import com.chemera.server.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层的 TapTap 登录入口：路由是否匿名可达、游客令牌是否被当作"可选上下文"读出来、
 * 新加的限流配额是否真的接在这个接口上。
 *
 * <p>这几件事在单测里看不见（它们只在"请求进来"那一刻成立），在 e2e 里又只覆盖成功路径，
 * 所以用 standalone MockMvc 钉住：不启 Spring 上下文、不连库、验签走演示票据通道。
 */
class AuthTapTapHttpTest {

    private AuthService auth;
    private MockMvc mvc;
    private JwtService jwt;
    private SessionMapper sessions;

    @BeforeEach
    void setUp() {
        auth = mock(AuthService.class);
        // 其余阈值给足，只让 taptap 配额是 2 次：这样"第 3 次被拒"只能归因于新加的那道闸
        RateGuard guard = new RateGuard(true, 200, 10, 50, 15, 200, 60, 200, 60, 2, 60, 20, 30, 5, 30, false);
        jwt = new JwtService("http-layer-test-secret-value-32-bytes+", 60);
        TapTapVerifier tap = new TapTapVerifier(true, "open.tapapis.cn", "", new ObjectMapper());
        when(auth.taptap(any(), any())).thenReturn(Map.of("token", "tk", "user", "tapx",
                "guest", false, "bound", true));
        // 默认"这一次登录还活着"；单独的用例里再把它改成撤销态
        sessions = mock(SessionMapper.class);
        when(sessions.liveCount(anyLong(), anyLong(), any(LocalDateTime.class))).thenReturn(1);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth, guard, tap, new SessionGuard(sessions, jwt)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void anonymousRequestIsFineBecauseThisIsExactlyWhereLoginHappens() throws Exception {
        mvc.perform(post("/api/auth/taptap").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":{\"dev\":\"dev:alice:小艾\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bound").value(true));

        ArgumentCaptor<TapTapVerifier.Identity> cap = ArgumentCaptor.forClass(TapTapVerifier.Identity.class);
        verify(auth).taptap(cap.capture(), isNull());
        assertEquals("dev-alice", cap.getValue().openId());
        assertEquals("小艾", cap.getValue().name());
    }

    @Test
    void guestTokenIsReadAsOptionalContextSoTheTrialSaveComesAlong() throws Exception {
        // 带 sid：A6 之后"能验签"不再足够，游客档要能把进度并过去，这一次登录会话必须还活着
        String guestToken = jwt.issue(7L, "user", "user", "游客ab12cd34", 4242L);

        mvc.perform(post("/api/auth/taptap").header("Authorization", "Bearer " + guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":{\"dev\":\"dev:alice\"}}"))
                .andExpect(status().isOk());

        verify(auth).taptap(any(), eq(7L));
    }

    /**
     * 已撤销的会话（退出登录 / 封禁 / 改密 / 重置口令都会把这一行标掉）不能再用来并档。
     *
     * <p>这一条是 A6 真正的意义所在：TapTap 入口是匿名接口，不走 {@code AuthInterceptor}，
     * 如果"可选登录态"只验签不查会话，被撤销的旧访问令牌就还能在这里把一个游客档的整体存档搬走。
     */
    @Test
    void revokedSessionLosesTheRightToMergeTheTrialSave() throws Exception {
        String stale = jwt.issue(7L, "user", "user", "游客ab12cd34", 4242L);
        when(sessions.liveCount(eq(4242L), eq(7L), any(LocalDateTime.class))).thenReturn(0);

        mvc.perform(post("/api/auth/taptap").header("Authorization", "Bearer " + stale)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":{\"dev\":\"dev:alice\"}}"))
                .andExpect(status().isOk());

        verify(auth).taptap(any(), isNull());
    }

    /** 老形状（没有 sid）的令牌：一律当作没登录，宁可让玩家重登一次，也不给无主令牌开后门。 */
    @Test
    void tokenWithoutSessionClaimIsTreatedAsNotLoggedIn() throws Exception {
        String legacy = jwt.issue(7L, "user", "user", "游客ab12cd34");

        mvc.perform(post("/api/auth/taptap").header("Authorization", "Bearer " + legacy)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":{\"dev\":\"dev:alice\"}}"))
                .andExpect(status().isOk());

        verify(auth).taptap(any(), isNull());
    }

    /** 坏令牌当作没登录：一段过期的 Authorization 头不该把一次本来能成的第三方登录打死。 */
    @Test
    void brokenTokenDoesNotPoisonTheLogin() throws Exception {
        mvc.perform(post("/api/auth/taptap").header("Authorization", "Bearer not-a-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":{\"dev\":\"dev:alice\"}}"))
                .andExpect(status().isOk());

        verify(auth).taptap(any(), isNull());
    }

    /** 后台令牌不是玩家令牌：类型不符也只能当作没登录，绝不能拿它换出玩家身份。 */
    @Test
    void adminTokenIsNotAPlayerContext() throws Exception {
        String adminToken = jwt.issue(3L, "admin", "super");

        mvc.perform(post("/api/auth/taptap").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":{\"dev\":\"dev:alice\"}}"))
                .andExpect(status().isOk());

        verify(auth).taptap(any(), isNull());
    }

    @Test
    void emptyTicketIsUnauthorizedAndReachesNoService() throws Exception {
        mvc.perform(post("/api/auth/taptap").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(auth, never()).taptap(any(), any());
    }

    @Test
    void quotaBitesOnTheThirdLoginAttempt() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/auth/taptap").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"ticket\":{\"dev\":\"dev:u" + i + "\"}}")).andExpect(status().isOk());
        }
        mvc.perform(post("/api/auth/taptap").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":{\"dev\":\"dev:u2\"}}"))
                .andExpect(status().isTooManyRequests());

        verify(auth, times(2)).taptap(any(), any());
    }

    /** 注销带票据时，验签在控制器就完成，服务层只认解析出来的身份。 */
    @Test
    void deleteAcceptsTapTapReauth() throws Exception {
        mvc.perform(post("/api/auth/delete").requestAttr(AuthContext.ATTR_USER, 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticket\":{\"dev\":\"dev:alice\"}}"))
                .andExpect(status().isOk());

        ArgumentCaptor<TapTapVerifier.Identity> cap = ArgumentCaptor.forClass(TapTapVerifier.Identity.class);
        verify(auth).deleteAccount(eq(42L), isNull(), cap.capture());
        assertEquals("dev-alice", cap.getValue().openId());
    }

    @Test
    void deleteWithoutAnyTicketKeepsThePasswordPath() throws Exception {
        mvc.perform(post("/api/auth/delete").requestAttr(AuthContext.ATTR_USER, 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":\"pw\"}"))
                .andExpect(status().isOk());

        verify(auth).deleteAccount(eq(42L), eq("pw"), isNull());
    }
}
