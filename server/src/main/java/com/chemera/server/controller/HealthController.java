package com.chemera.server.controller;

import com.chemera.server.game.AdService;
import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.mapper.SystemMapper;
import com.chemera.server.security.TapTapVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维探活：不鉴权、不含任何玩家数据，只回答"进程活着且数据库连得上吗"。
 * 库不通时返回 503，便于反代/容器编排把实例摘掉。
 *
 * <p><b>这里刻意不放内部模式（G8）</b>：这个端点是匿名可达的（探活探针不该带口令），
 * 而 {@code ad.devMode} 与 {@code taptap.mode} 说的正是"验签是假的还是真的"。
 * 把它亮出来等于给外面的人一张地图：哪台环境可以不真授权就拿到登录态、哪台环境的广告回调可以乱猜。
 * 我们自己排查这些问题走带口令的 {@code /admin/api/ops/health}（{@code AdminOpsController}），
 * 那里一个字都不用藏。
 */
@RestController
public class HealthController {
    private final SystemMapper system;
    private final SessionMapper sessions;
    private final ContentMapper content;
    private final AdService ads;
    private final TapTapVerifier tap;

    public HealthController(SystemMapper system, SessionMapper sessions, ContentMapper content, AdService ads,
                            TapTapVerifier tap) {
        this.system = system; this.sessions = sessions; this.content = content; this.ads = ads; this.tap = tap;
    }

    @GetMapping("/api/healthz")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        boolean db = true;
        try {
            system.ping();
            out.put("contentVersion", content.version());
            out.put("activeSessions", sessions.countActive());
        } catch (Exception e) {
            db = false;
            out.put("dbError", e.getClass().getSimpleName());
        }
        out.put("db", db);
        // 只回"成不成"，不回"为什么不成"：探针要的是布尔值，细节留给运维端点
        Map<String, Object> ad = new LinkedHashMap<>();
        ad.put("ready", ads.grantReady());
        out.put("ad", ad);
        Map<String, Object> tt = new LinkedHashMap<>();
        tt.put("ready", tap.ready());
        out.put("taptap", tt);
        if (!db) {
            out.put("ok", false);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(out);
        }
        return ResponseEntity.ok(out);
    }
}
