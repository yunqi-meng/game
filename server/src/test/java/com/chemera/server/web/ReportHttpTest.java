package com.chemera.server.web;

import com.chemera.server.common.BizException;
import com.chemera.server.common.GlobalExceptionHandler;
import com.chemera.server.controller.ReportController;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.security.AuthInterceptor;
import com.chemera.server.security.JwtService;
import com.chemera.server.security.RateGuard;
import com.chemera.server.security.SessionGuard;
import com.chemera.server.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 举报入口的 HTTP 层（G2）：这条通道以前<b>一个人都进不来</b>——表、mapper、后台审核页都在，
 * 唯独没人能往 {@code report} 写一行。所以这里钉的是"进得来、带得上身份、防洪真的接上了"三件事，
 * 业务判定（类型白名单、对象存在、不能举报自己、当日限额）在 {@code ReportServiceTest} 里已经钉过。
 *
 * <p>两道防洪只有在真发请求时才看得出差别：{@code RateGuard.checkReport} 里 IP 一格、uid 一格，
 * 单测里递两个字符串是察觉不到"换 IP 或换号能不能绕过去"的。这里用 {@code remoteAddr} 换着来源地址
 * 打同一个账号，正好把那两个桶各自的用处逼出来。
 *
 * <p>不启 Spring 上下文、不连库：standalone MockMvc + 真的 {@link AuthInterceptor}。
 */
class ReportHttpTest {

    private ReportService reports;
    private JwtService jwt;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reports = mock(ReportService.class);
        when(reports.file(anyLong(), any(), any(), any(), any()))
                .thenReturn(Map.of("ok", true, "kind", "cheater", "openToday", 1, "dailyMax", 5));
        // 只把举报配额压到 1 次，其余闸门给足：这样"被拒"只可能来自新加的那道闸
        RateGuard guard = new RateGuard(true, 200, 10, 50, 15, 200, 60, 200, 60, 200, 60,
                300, 1, 1, 60, 200, 30, 5, 30, false);
        jwt = new JwtService("report-http-layer-secret-value-32b!", 60);
        SessionMapper sessions = mock(SessionMapper.class);
        when(sessions.liveCount(anyLong(), anyLong())).thenReturn(1);
        mvc = MockMvcBuilders.standaloneSetup(new ReportController(reports, guard))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AuthInterceptor(jwt, new SessionGuard(sessions, jwt)))
                .build();
    }

    private String asPlayer(long uid) {
        return jwt.issue(uid, "user", "player", "玩家" + uid, 100L + uid);
    }

    private org.springframework.test.web.servlet.ResultActions file(String token, String ip, String body)
            throws Exception {
        return mvc.perform(post("/api/report").remoteAddress(ip)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /* ---------------- 1. 门：匿名递不进来 ---------------- */

    /**
     * 没带令牌就是 401，而且<b>一次业务都不会被调用</b>。
     *
     * <p>举报是要追责的行为：匿名可写等于把审核面变成垃圾桶，谁都能往里塞同行。
     * 这条断言的是 {@code WebConfig} 把 {@code /api/report} 放进 {@code AuthInterceptor} 名单之后，
     * 拦截器确实拦在了服务之前——顺序错了的话，脏数据已经落库才返回 401。
     */
    @Test
    void anonymousPostIsRefusedBeforeTheServiceIsTouched() throws Exception {
        mvc.perform(post("/api/report").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"cheater\",\"target\":42}"))
                .andExpect(status().isUnauthorized());
        verify(reports, never()).file(anyLong(), any(), any(), any(), any());
    }

    /** 签名对但会话已撤销（被封禁/改密/注销）也进不来：这一条走的是 {@code SessionGuard.live}。 */
    @Test
    void aTokenWhoseSessionWentAwayIsRefused() throws Exception {
        SessionMapper dead = mock(SessionMapper.class);        // liveCount 默认回 0 = 这次登录已经不在
        JwtService j = new JwtService("report-http-layer-secret-value-32b!", 60);
        MockMvc offline = MockMvcBuilders.standaloneSetup(new ReportController(reports,
                        new RateGuard(true, 200, 10, 50, 15, 200, 60, 200, 60, 200, 60, 300, 1, 5, 60, 200, 30, 5, 30, false)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AuthInterceptor(j, new SessionGuard(dead, j)))
                .build();

        offline.perform(post("/api/report").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + j.issue(7L, "user", "player", "玩家7", 107L))
                        .content("{\"kind\":\"cheater\",\"target\":42}"))
                .andExpect(status().isUnauthorized());
        verify(reports, never()).file(anyLong(), any(), any(), any(), any());
    }

    /* ---------------- 2. 参数：body 怎么摊到服务的那五个位置 ---------------- */

    @Test
    void theLoggedIdentityIsTakenFromTheTokenNotTheBody() throws Exception {
        String token = asPlayer(7L);
        // body 里塞一个别人的 uid：递进服务的 reporter 必须是令牌里那一个
        file(token, "1.1.1.1", "{\"kind\":\"cheater\",\"target\":42,\"ref\":\"listing:9\","
                + "\"reason\":\"开挂\",\"uid\":999}").andExpect(status().isOk());

        verify(reports).file(eq(7L), eq("cheater"), eq(42L), eq("listing:9"), eq("开挂"));
    }

    /** 一个字段都不递：控制器不替玩家编默认值，空值原样交给服务去判（那里回"没有这一类举报"）。 */
    @Test
    void anEmptyBodyReachesTheServiceAsNullsRatherThanBeingFilledInHere() throws Exception {
        when(reports.file(anyLong(), any(), any(), any(), any())).thenThrow(new BizException("没有这一类举报"));

        // 业务错误在这个项目里是 200 + ok:false（见 GlobalExceptionHandler：只有 401/403/404/429 才动状态码），
        // 所以这里验的是"文案原样透回客户端"，而不是某个 4xx。
        file(asPlayer(7L), "1.1.1.1", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.msg").value("没有这一类举报"));

        verify(reports).file(eq(7L), isNull(), isNull(), isNull(), isNull());
    }

    /** 连 body 都没有（{@code required=false}）：仍然走到服务那一步，由服务判定参数不足。 */
    @Test
    void aBodylessPostIsNotRejectedByTheFramework() throws Exception {
        when(reports.file(anyLong(), any(), any(), any(), any())).thenThrow(new BizException("没有这一类举报"));

        mvc.perform(post("/api/report").remoteAddress("1.1.1.1")
                        .header("Authorization", "Bearer " + asPlayer(8L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false));
        verify(reports).file(eq(8L), isNull(), isNull(), isNull(), isNull());
    }

    /** 回执里的东西要能透回客户端：客户端【举报成功】的提示就靠这句。 */
    @Test
    void theReceiptComesBackToTheClient() throws Exception {
        file(asPlayer(9L), "1.1.1.1", "{\"kind\":\"spam\",\"target\":42}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.dailyMax").value(5));
    }

    /* ---------------- 3. 两道闸门：换 IP 与换号各挡一头 ---------------- */

    /**
     * 同一个账号换个来源地址再报，仍然被拒——挡的是"多出口刷举报"。
     *
     * <p>这一条只有把 {@code remoteAddr} 真的换掉才测得出来：只看 {@code checkReport(ip, uid)} 的签名的话，
     * 少传一个桶（比如只按 IP 计）也能过单测。配额是 1，所以第一次过、第二次拒。
     */
    @Test
    void aSecondReportFromAnotherIpIsStillBlockedByTheUidBucket() throws Exception {
        String token = asPlayer(7L);
        file(token, "10.0.0.1", "{\"kind\":\"cheater\",\"target\":42}").andExpect(status().isOk());

        file(token, "10.0.0.2", "{\"kind\":\"cheater\",\"target\":43}")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("今天提交的举报")));
        verify(reports, times(1)).file(anyLong(), any(), any(), any(), any());
    }

    /** 同一个来源地址换个账号再报，也被拒——挡的是"注册小号来刷举报"。 */
    @Test
    void anotherAccountFromTheExhaustedIpIsBlockedByTheIpBucket() throws Exception {
        file(asPlayer(7L), "10.0.0.1", "{\"kind\":\"cheater\",\"target\":42}").andExpect(status().isOk());

        file(asPlayer(8L), "10.0.0.1", "{\"kind\":\"cheater\",\"target\":43}")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("过于频繁")));
        verify(reports, times(1)).file(anyLong(), any(), any(), any(), any());
    }

    /**
     * 被闸门挡下时不写库。
     *
     * <p>上面两条各自验过一遍，但真正要紧的是这个顺序：先过闸、后落库。反过来做的话，
     * 限流只是给客户端一个错误码，库里的垃圾行一条没少——那这道闸就纯属装饰了。
     */
    @Test
    void theGateRunsBeforeAnythingIsWritten() throws Exception {
        String token = asPlayer(7L);
        for (int i = 0; i < 5; i++) file(token, "10.0.0.9", "{\"kind\":\"cheater\",\"target\":42}");
        verify(reports, times(1)).file(anyLong(), any(), any(), any(), any());
    }
}
