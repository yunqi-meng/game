package com.chemera.server.controller;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.game.ContentRegistry;
import com.chemera.server.mapper.AnalyticsMapper;
import com.chemera.server.security.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * 行为埋点入库。看板（{@code AdminDashboardController}）的所有数字都从这里长出来，
 * 所以这个类是唯一能决定"要不要留下这条记录"的地方。
 *
 * <p>{@code app_config.analytics_enabled} 是合规面：隐私政策一旦声明可关，就必须有一个真能关的开关，
 * 而不是前端把上报按钮藏起来。关的时候仍然回 200——客户端不该因为"采集停了"看到任何错误，
 * 更不该为此发版（H5 与已装的安卓包共用这一条语义）。
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private static final Set<String> ALLOWED = Set.of(
            "react_success", "react_boom", "discover", "sell", "buy", "trade",
            "quiz_ok", "sign", "challenge_win", "level_up", "login");
    private final AnalyticsMapper analytics;
    private final ContentRegistry reg;
    private final ObjectMapper om;
    public AnalyticsController(AnalyticsMapper a, ContentRegistry reg, ObjectMapper om) {
        this.analytics = a; this.reg = reg; this.om = om;
    }

    @PostMapping("/event")
    public ApiResponse<Void> event(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        String ev = String.valueOf(b.get("event"));
        if (!ALLOWED.contains(ev)) return ApiResponse.ok(); // 忽略未知事件
        if (!reg.current().config.analyticsOn()) return ApiResponse.ok();  // 采集已关停：静默丢弃，不写库
        Long uid = AuthContext.uid(req);
        String props = null;
        try { if (b.get("props") != null) props = om.writeValueAsString(b.get("props")); } catch (Exception ignored) {}
        analytics.insert(uid, ev, props);
        return ApiResponse.ok();
    }
}
