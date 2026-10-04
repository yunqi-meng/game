package com.chemera.server.controller.admin;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.common.BizException;
import com.chemera.server.entity.AppConfigRow;
import com.chemera.server.game.ConfigSpec;
import com.chemera.server.mapper.ConfigMapper;
import com.chemera.server.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/config")
public class AdminConfigController {
    private final ConfigMapper config;
    private final AdminSupport support;
    private final AuditService audit;
    private final ObjectMapper om;

    public AdminConfigController(ConfigMapper config, AdminSupport support, AuditService audit, ObjectMapper om) {
        this.config = config; this.support = support; this.audit = audit; this.om = om;
    }

    @GetMapping
    public ApiResponse<List<AppConfigRow>> list() { return ApiResponse.ok(config.list()); }

    /**
     * 配置说明书（含"这项到底影不影响结算"）。给后台面板当文案用，
     * 未列在这里的键意味着引擎的 Config 记录里没有对应字段，改了不会被服务端读到。
     */
    @GetMapping("/spec")
    public ApiResponse<List<ConfigSpec>> spec() { return ApiResponse.ok(ConfigSpec.ALL); }

    @GetMapping("/get")
    public ApiResponse<Object> get(@RequestParam String key) {
        AppConfigRow r = config.get(key);
        if (r == null) throw BizException.notFound("配置项");
        try { return ApiResponse.ok(om.readValue(r.getCfgValue(), Object.class)); }
        catch (Exception e) { return ApiResponse.ok(r.getCfgValue()); }
    }

    @PutMapping
    public ApiResponse<Void> upsert(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String by = support.requireWriter(req);
        String key = String.valueOf(body.get("key"));
        if (key == null || "null".equals(key)) throw new BizException("key 必填");
        AppConfigRow r = new AppConfigRow();
        r.setCfgKey(key);
        try { r.setCfgValue(om.writeValueAsString(body.get("value"))); }
        catch (Exception e) { throw new BizException("value 序列化失败"); }
        r.setCategory(String.valueOf(body.getOrDefault("category", "general")));
        r.setRemark(body.get("remark") == null ? null : String.valueOf(body.get("remark")));
        r.setUpdatedBy(by);
        config.upsert(r);
        support.publishContent(); // 配置随 bundle 下发，需失效缓存
        audit.log(by, "config.upsert", key, null, req);
        return ApiResponse.ok();
    }

    @DeleteMapping
    public ApiResponse<Void> delete(@RequestParam String key, HttpServletRequest req) {
        String by = support.requireWriter(req);
        config.delete(key);
        support.publishContent();
        audit.log(by, "config.delete", key, null, req);
        return ApiResponse.ok();
    }
}
