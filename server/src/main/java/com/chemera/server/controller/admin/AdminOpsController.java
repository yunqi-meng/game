package com.chemera.server.controller.admin;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.game.AdService;
import com.chemera.server.game.CurfewGuard;
import com.chemera.server.game.IntentDedupe;
import com.chemera.server.mapper.AdTicketMapper;
import com.chemera.server.mapper.ModerationMapper;
import com.chemera.server.security.RateGuard;
import com.chemera.server.security.TapTapVerifier;
import com.chemera.server.stats.IntentMetrics;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维详情（G8）：原来写在匿名 {@code /api/healthz} 里的那一堆，加上三个便宜指标。
 *
 * <p>为什么单开一个带口令的口子：{@code ad.devMode} 与 {@code taptap.mode} 回答的是"这套环境的验签
 * 是真是假"，探活探针不需要它，而攻击者很需要——那是一份"哪台可以不真授权就拿到登录态"的地图。
 * 拆开的代价只是我们自己排查时多带一个 token，这个代价是零，因为后台本来就要登录。
 *
 * <p>没有引 micrometer：要看的就是"哪条意图慢了多少毫秒、发出去几条 SQL、连接池够不够、
 * 有多少广告工单卡在途中"，一次 HTTP 就能看全。等真要接告警系统时再说。
 */
@RestController
@RequestMapping("/admin/api/ops")
public class AdminOpsController {

    private final AdService ads;
    private final TapTapVerifier tap;
    private final ModerationMapper mod;
    private final AdTicketMapper tickets;
    private final IntentMetrics metrics;
    private final RateGuard guard;
    private final CurfewGuard curfew;
    private final IntentDedupe dedupe;
    private final DataSource ds;
    private final AdminSupport support;

    public AdminOpsController(AdService ads, TapTapVerifier tap, ModerationMapper mod, AdTicketMapper tickets,
                              IntentMetrics metrics, RateGuard guard, CurfewGuard curfew, IntentDedupe dedupe,
                              DataSource ds, AdminSupport support) {
        this.ads = ads; this.tap = tap; this.mod = mod; this.tickets = tickets;
        this.metrics = metrics; this.guard = guard; this.curfew = curfew; this.dedupe = dedupe;
        this.ds = ds; this.support = support;
    }

    /** 内部模式 + 待办量：一眼看出"这套环境配好了没有、有没有东西卡在中间"。 */
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health(HttpServletRequest req) {
        support.requireWriter(req);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> ad = new LinkedHashMap<>();
        ad.put("ready", ads.grantReady());
        ad.put("devMode", ads.devMode());       // true = 回调不验真签名，本机回归与演示环境
        out.put("ad", ad);
        Map<String, Object> tt = new LinkedHashMap<>();
        tt.put("ready", tap.ready());
        tt.put("mode", tap.mode());             // dev / unconfigured / live
        out.put("taptap", tt);
        out.put("pendingReports", mod.openCount());
        out.put("pendingAdTickets", tickets.pendingCount());
        out.put("pool", pool());
        out.put("guards", Map.of(
                "rateKeys", guard.trackedKeys(),          // 限流窗口在内存里占多少格
                "curfewRows", curfew.cachedSize(),        // 青少年模式快照缓存条数
                "dedupePlayers", dedupe.trackedPlayers()  // 意图幂等窗口覆盖多少个玩家
        ));
        return ApiResponse.ok(out);
    }

    /** 每条意图的调用数 / p50 / 最慢一次 / 平均 SQL 条数（按调用数排序）。 */
    @GetMapping("/intents")
    public ApiResponse<Map<String, Object>> intents(HttpServletRequest req) {
        support.requireWriter(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intents", metrics.snapshot());
        out.put("pool", pool());
        return ApiResponse.ok(out);
    }

    /** Hikari 水位：拿不到池 bean（比如测试里换成了别的 DataSource）就回一个说明，而不是抛 500。 */
    private Map<String, Object> pool() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            HikariDataSource h = ds.unwrap(HikariDataSource.class);
            var p = h.getHikariPoolMXBean();
            m.put("name", h.getPoolName());
            m.put("total", p.getTotalConnections());
            m.put("active", p.getActiveConnections());
            m.put("idle", p.getIdleConnections());
            m.put("waiting", p.getThreadsAwaitingConnection());
            m.put("max", h.getMaximumPoolSize());
        } catch (Exception e) {
            m.put("unavailable", e.getClass().getSimpleName());
        }
        return m;
    }
}
