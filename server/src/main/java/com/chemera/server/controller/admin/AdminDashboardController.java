package com.chemera.server.controller.admin;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.mapper.AnalyticsMapper;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.service.DailyStatsJob;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/dashboard")
public class AdminDashboardController {
    private final AnalyticsMapper analytics;
    private final ContentMapper content;
    private final DailyStatsJob stats;
    private final AdminSupport support;
    private final com.chemera.server.service.AuditService audit;

    public AdminDashboardController(AnalyticsMapper a, ContentMapper c, DailyStatsJob stats,
                                    AdminSupport support, com.chemera.server.service.AuditService audit) {
        this.analytics = a; this.content = c; this.stats = stats; this.support = support; this.audit = audit;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalUsers", analytics.totalUsers());
        m.put("dauToday", analytics.dauToday());
        m.put("newUsersToday", analytics.newUsersToday());
        m.put("contentVersion", content.version());
        m.put("events7d", analytics.eventCounts(7));
        return ApiResponse.ok(m);
    }

    @GetMapping("/trend")
    public ApiResponse<Map<String, Object>> trend(@RequestParam(defaultValue = "14") int days) {
        int d = Math.min(Math.max(days, 1), 90);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logins", analytics.loginTrend(d));
        m.put("events", analytics.eventCounts(d));
        m.put("recentDaily", analytics.recentDaily(d));
        return ApiResponse.ok(m);
    }

    /**
     * 手工补一天日报（G1 的运营入口）。
     *
     * <p>{@code DailyStatsJob} 每天自己跑，但运营会遇到两种"表里就是没有那天"的情况：服务那天正好停着，
     * 或者想立刻看到刚发生的一天而不想等到凌晨。两种都不值得发版解决，所以给一个按钮口径的接口。
     * 写权限 + 落审计：这是唯一能改看板历史数字的动作（虽然改的是聚合值，不是玩家数据）。
     *
     * <p>参数缺省＝今天。{@code upsertDaily} 是覆盖式的，重复点只会把同一天的数算成同一个值，多点几次不会翻倍。
     */
    @PostMapping("/roll")
    public ApiResponse<Map<String, Object>> roll(@RequestParam(required = false) String day, HttpServletRequest req) {
        String by = support.requireWriter(req);
        java.time.LocalDate d;
        try {
            d = day == null || day.isBlank() ? java.time.LocalDate.now() : java.time.LocalDate.parse(day.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return ApiResponse.err(400, "日期格式应为 YYYY-MM-DD");
        }
        if (d.isAfter(java.time.LocalDate.now())) return ApiResponse.err(400, "还不能预算未来的某一天");
        Map<String, Object> row = stats.roll(d);
        audit.log(by, "dashboard.roll", "day:" + d, row, req);
        return ApiResponse.ok(row);
    }
}
