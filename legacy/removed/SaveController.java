package com.chemera.server.controller;

import com.chemera.server.security.AuthContext;
import com.chemera.server.service.SaveService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 云存档：GET 拉取；PUT 上传（带 base 冲突检测 / force 覆盖）。直接返回业务结构体，便于客户端解析。 */
@RestController
@RequestMapping("/api/save")
public class SaveController {
    private final SaveService saves;
    public SaveController(SaveService saves) { this.saves = saves; }

    @GetMapping
    public Map<String, Object> get(HttpServletRequest req) {
        return saves.get(AuthContext.uid(req));
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Object p = body.get("save") != null ? body.get("save") : body.get("payload");
        JsonNode payload = toNode(p);
        Long base = body.get("base") == null ? null : ((Number) body.get("base")).longValue();
        boolean force = Boolean.TRUE.equals(body.get("force"));
        return saves.put(AuthContext.uid(req), payload, base, force);
    }

    private static JsonNode toNode(Object o) {
        if (o == null) return null;
        try {
            var om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.valueToTree(o);
        } catch (Exception e) { return null; }
    }
}
