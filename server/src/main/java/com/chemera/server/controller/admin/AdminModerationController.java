package com.chemera.server.controller.admin;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.service.AuditService;
import com.chemera.server.mapper.ModerationMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/moderation")
public class AdminModerationController {
    private final ModerationMapper mod;
    private final AdminSupport support;
    private final AuditService audit;

    public AdminModerationController(ModerationMapper mod, AdminSupport support, AuditService audit) {
        this.mod = mod; this.support = support; this.audit = audit;
    }

    @GetMapping("/reports")
    public ApiResponse<List<Map<String, Object>>> reports(@RequestParam(defaultValue = "") String status,
                                                          @RequestParam(defaultValue = "50") int size,
                                                          @RequestParam(defaultValue = "0") int off) {
        return ApiResponse.ok(mod.pageReports(status, Math.min(size, 200), off));
    }

    @PostMapping("/reports/handle")
    public ApiResponse<Void> handle(@RequestParam long id, @RequestParam String status, HttpServletRequest req) {
        String by = support.requireWriter(req);
        if (!List.of("open", "handled", "dismissed").contains(status)) return ApiResponse.err(400, "非法状态");
        mod.handleReport(id, status, by);
        audit.log(by, "report.handle", "report:" + id, Map.of("status", status), req);
        return ApiResponse.ok();
    }

    @GetMapping("/words")
    public ApiResponse<List<Map<String, Object>>> words() { return ApiResponse.ok(mod.words()); }

    @PostMapping("/words")
    public ApiResponse<Void> upsertWord(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        String by = support.requireWriter(req);
        String word = String.valueOf(b.get("word"));
        int level = b.get("level") == null ? 1 : ((Number) b.get("level")).intValue();
        mod.upsertWord(word, level);
        audit.log(by, "word.upsert", word, null, req);
        return ApiResponse.ok();
    }

    @DeleteMapping("/words")
    public ApiResponse<Void> deleteWord(@RequestParam long id, HttpServletRequest req) {
        String by = support.requireWriter(req);
        mod.deleteWord(id);
        audit.log(by, "word.delete", "id:" + id, null, req);
        return ApiResponse.ok();
    }

    @GetMapping("/audit")
    public ApiResponse<List<Map<String, Object>>> audit(@RequestParam(defaultValue = "50") int size,
                                                        @RequestParam(defaultValue = "0") int off) {
        return ApiResponse.ok(audit.page(Math.min(size, 200), off));
    }
}
