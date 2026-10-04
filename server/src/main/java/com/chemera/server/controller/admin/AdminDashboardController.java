package com.chemera.server.controller.admin;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.mapper.AnalyticsMapper;
import com.chemera.server.mapper.ContentMapper;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/dashboard")
public class AdminDashboardController {
    private final AnalyticsMapper analytics;
    private final ContentMapper content;
    public AdminDashboardController(AnalyticsMapper a, ContentMapper c) { this.analytics = a; this.content = c; }

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
}
