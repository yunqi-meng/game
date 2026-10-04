package com.chemera.server.controller;

import com.chemera.server.mapper.ContentMapper;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.mapper.SystemMapper;
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
 */
@RestController
public class HealthController {
    private final SystemMapper system;
    private final SessionMapper sessions;
    private final ContentMapper content;

    public HealthController(SystemMapper system, SessionMapper sessions, ContentMapper content) {
        this.system = system; this.sessions = sessions; this.content = content;
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
        if (!db) {
            out.put("ok", false);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(out);
        }
        return ResponseEntity.ok(out);
    }
}
