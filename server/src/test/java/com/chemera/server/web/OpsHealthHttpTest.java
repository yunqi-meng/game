package com.chemera.server.web;

import com.chemera.server.common.GlobalExceptionHandler;
import com.chemera.server.controller.HealthController;
import com.chemera.server.controller.admin.AdminOpsController;
import com.chemera.server.controller.admin.AdminSupport;
import com.chemera.server.entity.AdminUser;
import com.chemera.server.game.AdService;
import com.chemera.server.game.CurfewGuard;
import com.chemera.server.game.IntentDedupe;
import com.chemera.server.mapper.AdminMapper;
import com.chemera.server.mapper.AdTicketMapper;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.mapper.ModerationMapper;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.mapper.SystemMapper;
import com.chemera.server.security.AdminInterceptor;
import com.chemera.server.security.JwtService;
import com.chemera.server.security.RateGuard;
import com.chemera.server.security.TapTapVerifier;
import com.chemera.server.service.ContentService;
import com.chemera.server.stats.IntentMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运维面的两个入口（G8）：匿名探活与带口令详情，各说各的话。
 *
 * <p>以前 {@code /api/healthz} 一个端点全包，里面赫然写着 {@code ad.devMode} 与 {@code taptap.mode}——
 * 那两个字段回答的是"这套环境的验签是真是假"，也就是"哪台可以不真授权就拿到登录态、
 * 哪台的广告回调可以乱猜"。探活探针要的是布尔值，攻击者要的正是这张地图，
 * 所以这条断言必须同时钉住两头：<b>匿名那侧看不到</b>，而<b>运维那侧一个字都没少</b>。
 * 藏起来只会让我们自己排障时瞎掉，收进带口令的端点才是解法。
 *
 * <p>顺带把 {@code /admin/api/ops/intents} 的形状验一遍：e2e 里那条"单次意图 ≤6 条 SQL"读的就是它，
 * 键名一旦拼错，e2e 会读成 0——那是最客气的一种假数据。
 */
class OpsHealthHttpTest {

    private JwtService jwt;
    private AdminMapper admins;
    private AdService ads;
    private TapTapVerifier tap;
    private MockMvc anonymous;      // 只有 HealthController，不挂任何拦截器（它本来就该匿名可达）
    private MockMvc admin;          // 只有 AdminOpsController，挂 AdminInterceptor

    @BeforeEach
    void setUp() {
        jwt = new JwtService("ops-http-layer-secret-value-at-least-32b", 60);
        ads = mock(AdService.class);
        tap = mock(TapTapVerifier.class);
        when(ads.grantReady()).thenReturn(true);
        when(ads.devMode()).thenReturn(true);
        when(tap.ready()).thenReturn(true);
        when(tap.mode()).thenReturn("dev");

        SystemMapper system = mock(SystemMapper.class);
        SessionMapper sessions = mock(SessionMapper.class);
        ContentMapper content = mock(ContentMapper.class);
        when(content.version()).thenReturn(41L);
        when(sessions.countActive()).thenReturn(3L);
        anonymous = MockMvcBuilders.standaloneSetup(new HealthController(system, sessions, content, ads, tap))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        admins = mock(AdminMapper.class);
        ModerationMapper mod = mock(ModerationMapper.class);
        AdTicketMapper tickets = mock(AdTicketMapper.class);
        when(mod.openCount()).thenReturn(2L);
        when(tickets.pendingCount()).thenReturn(7L);
        RateGuard guard = new RateGuard(true, 40, 10, 8, 15, 20, 60, 40, 60, 30, 60, 20, 30, 5, 30, false);
        IntentMetrics metrics = new IntentMetrics();
        metrics.record("react", 12, 3);
        metrics.record("react", 30, 3);
        metrics.record("sell", 5, 2);
        DataSource ds = mock(DataSource.class);   // 拿不到 Hikari 池：pool 那格该回一句说明，而不是把端点打成 500
        AdminSupport support = new AdminSupport(content, mock(ContentService.class));
        admin = MockMvcBuilders.standaloneSetup(new AdminOpsController(ads, tap, mod, tickets, metrics,
                        guard, mock(CurfewGuard.class), mock(IntentDedupe.class), ds, support))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AdminInterceptor(jwt, admins))
                .build();
    }

    private String asRole(String roleInDb) {
        AdminUser a = new AdminUser();
        a.setId(1L); a.setUsername("boss"); a.setRole(roleInDb); a.setStatus(0);
        a.setMustChangePassword(0); a.setPassHash("h");
        when(admins.findById(1L)).thenReturn(a);
        // 令牌声明故意写成 super：验证裁决看的是库里的角色，不是令牌里的那一个
        return jwt.issue(1L, "admin", "super", "boss");
    }

    /* ---------------- 1. 匿名探活：够用，且不多说一句 ---------------- */

    @Test
    void probeAnswersTheBooleansItNeeds() throws Exception {
        anonymous.perform(get("/api/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.db").value(true))
                .andExpect(jsonPath("$.contentVersion").value(41))
                .andExpect(jsonPath("$.activeSessions").value(3))
                .andExpect(jsonPath("$.ad.ready").value(true))
                .andExpect(jsonPath("$.taptap.ready").value(true));
    }

    /**
     * 匿名响应里没有"验签是真是假"，也没有内部水位。
     *
     * <p>用 {@code doesNotExist} 而不是"值等于什么"：这一条钉的是<b>字段根本不在</b>。
     * 将来谁图省事把 {@code devMode} 塞回探活接口，这里立刻红。
     */
    @Test
    void probeDoesNotDrawTheAttackersMap() throws Exception {
        anonymous.perform(get("/api/healthz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ad.devMode").doesNotExist())
                .andExpect(jsonPath("$.taptap.mode").doesNotExist())
                .andExpect(jsonPath("$.pool").doesNotExist())
                .andExpect(jsonPath("$.pendingReports").doesNotExist())
                .andExpect(jsonPath("$.guards").doesNotExist());
    }

    /* ---------------- 2. 带口令详情：一个字都不藏 ---------------- */

    @Test
    void opsHealthWithoutTokenIs401() throws Exception {
        admin.perform(get("/admin/api/ops/health")).andExpect(status().isUnauthorized());
    }

    /** 只读角色也进不来：这里给的是"哪台环境验签是假的"，不是一份内容列表。 */
    @Test
    void opsHealthForAViewerIs403() throws Exception {
        admin.perform(get("/admin/api/ops/health").header("Authorization", "Bearer " + asRole("viewer")))
                .andExpect(status().isForbidden());
    }

    @Test
    void opsHealthSaysEverythingTheProbeMustNot() throws Exception {
        admin.perform(get("/admin/api/ops/health").header("Authorization", "Bearer " + asRole("operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ad.devMode").value(true))
                .andExpect(jsonPath("$.data.taptap.mode").value("dev"))
                .andExpect(jsonPath("$.data.pendingReports").value(2))
                .andExpect(jsonPath("$.data.pendingAdTickets").value(7))
                .andExpect(jsonPath("$.data.pool.unavailable").exists())
                .andExpect(jsonPath("$.data.guards.rateKeys").exists())
                .andExpect(jsonPath("$.data.guards.curfewRows").exists())
                .andExpect(jsonPath("$.data.guards.dedupePlayers").exists());
    }

    /** 指标也要口令：两个 ops 端点不是一个带一个不带，否则前面那道闸等于没装。 */
    @Test
    void intentsAreNotAnonymousEither() throws Exception {
        admin.perform(get("/admin/api/ops/intents")).andExpect(status().isUnauthorized());
    }

    /**
     * 意图指标从内存走到 HTTP 的那几列：调用数、p50、最慢一次、平均/最少 SQL 条数，按调用数排序。
     *
     * <p>e2e 的"单次意图 ≤6 条 SQL"读的是这里的 {@code minSql}（链路在温缓存下的真实条数），
     * {@code avgSql} 只在面板上看趋势，所以两个键名与顺序都在这里验一遍。
     * {@code p50Ms} 是 12 与 30 的中位数 21（偶数个样本取中间两者平均），这条也算给"中位数怎么算"上了锁。
     */
    @Test
    void intentMetricsComeOutOrderedAndNamedAsTheE2eReaderExpects() throws Exception {
        admin.perform(get("/admin/api/ops/intents").header("Authorization", "Bearer " + asRole("operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intents[0].intent").value("react"))
                .andExpect(jsonPath("$.data.intents[0].count").value(2))
                .andExpect(jsonPath("$.data.intents[0].p50Ms").value(21))
                .andExpect(jsonPath("$.data.intents[0].maxMs").value(30))
                .andExpect(jsonPath("$.data.intents[0].avgSql").value(3.0))
                // 这份桩数据里 react 的两次都是 3 条（没有缓存重建那次），所以 min 与 avg 相等；
                // "重建把 avg 抬高而 min 不动"那个分叉由 IntentTraceTest 单独钉。
                .andExpect(jsonPath("$.data.intents[0].minSql").value(3))
                .andExpect(jsonPath("$.data.intents[1].intent").value("sell"))
                .andExpect(jsonPath("$.data.intents[1].avgSql").value(2.0))
                .andExpect(jsonPath("$.data.intents[1].minSql").value(2));
    }
}
