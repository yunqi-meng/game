package com.chemera.server.service;

import com.chemera.server.entity.AuditLog;
import com.chemera.server.mapper.AuditMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AuditService {
    private final AuditMapper audit;
    private final ObjectMapper om;

    public AuditService(AuditMapper audit, ObjectMapper om) { this.audit = audit; this.om = om; }

    public void log(String admin, String action, String target, Object detail, HttpServletRequest req) {
        AuditLog a = new AuditLog();
        a.setAdmin(admin); a.setAction(action); a.setTarget(target);
        try { a.setDetail(detail == null ? null : om.writeValueAsString(Map.of("d", detail))); } catch (Exception ignored) {}
        a.setIp(clientIp(req));
        try { audit.insert(a); } catch (Exception ignored) {}
    }

    public java.util.List<Map<String, Object>> page(int size, int off) { return audit.page(size, off); }

    private static String clientIp(HttpServletRequest r) {
        String x = r.getHeader("X-Forwarded-For");
        return x != null && !x.isBlank() ? x.split(",")[0].trim() : r.getRemoteAddr();
    }
}
