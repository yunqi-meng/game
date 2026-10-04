package com.chemera.server.controller;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.mapper.AnalyticsMapper;
import com.chemera.server.security.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private static final Set<String> ALLOWED = Set.of(
            "react_success", "react_boom", "discover", "sell", "buy", "trade",
            "quiz_ok", "sign", "challenge_win", "level_up", "login");
    private final AnalyticsMapper analytics;
    private final ObjectMapper om;
    public AnalyticsController(AnalyticsMapper a, ObjectMapper om) { this.analytics = a; this.om = om; }

    @PostMapping("/event")
    public ApiResponse<Void> event(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        String ev = String.valueOf(b.get("event"));
        if (!ALLOWED.contains(ev)) return ApiResponse.ok(); // 忽略未知事件
        Long uid = AuthContext.uid(req);
        String props = null;
        try { if (b.get("props") != null) props = om.writeValueAsString(b.get("props")); } catch (Exception ignored) {}
        analytics.insert(uid, ev, props);
        return ApiResponse.ok();
    }
}
