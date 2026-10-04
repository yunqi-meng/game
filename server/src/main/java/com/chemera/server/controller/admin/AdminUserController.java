package com.chemera.server.controller.admin;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.common.BizException;
import com.chemera.server.entity.AppUser;
import com.chemera.server.entity.UserSaveRevision;
import com.chemera.server.mapper.SaveMapper;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.mapper.UserMapper;
import com.chemera.server.service.AuditService;
import com.chemera.server.service.AuthService;
import com.chemera.server.service.PlayerAssetService;
import com.chemera.server.service.SaveService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/users")
public class AdminUserController {
    private final UserMapper users;
    private final SaveMapper saves;
    private final SaveService saveService;
    private final PlayerAssetService assets;
    private final SessionMapper sessions;
    private final AuthService auth;
    private final AdminSupport support;
    private final AuditService audit;

    public AdminUserController(UserMapper users, SaveMapper saves, SaveService saveService,
                               PlayerAssetService assets,
                               SessionMapper sessions, AuthService auth,
                               AdminSupport support, AuditService audit) {
        this.users = users; this.saves = saves; this.saveService = saveService;
        this.assets = assets;
        this.sessions = sessions; this.auth = auth;
        this.support = support; this.audit = audit;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "") String q,
                                                 @RequestParam(defaultValue = "30") int size,
                                                 @RequestParam(defaultValue = "0") int off) {
        List<Map<String, Object>> rows = users.page(q, Math.min(size, 200), off);
        return ApiResponse.ok(Map.of("rows", rows, "total", users.count(q)));
    }

    @PostMapping("/ban")
    public ApiResponse<Void> ban(@RequestParam long id, @RequestParam(defaultValue = "7") int days,
                                 HttpServletRequest req) {
        String by = support.requireWriter(req);
        users.setBan(id, 1, LocalDateTime.now().plusDays(days));
        sessions.revokeAll(id);
        audit.log(by, "user.ban", "uid:" + id, Map.of("days", days), req);
        return ApiResponse.ok();
    }

    @PostMapping("/unban")
    public ApiResponse<Void> unban(@RequestParam long id, HttpServletRequest req) {
        String by = support.requireWriter(req);
        users.setBan(id, 0, null);
        audit.log(by, "user.unban", "uid:" + id, null, req);
        return ApiResponse.ok();
    }

    /**
     * 忘记密码的落地路径（C5）：没有邮件服务可验证身份，只能由超管人工核实用户名后重置。
     * 比 requireWriter 高一档——重置口令等于顶号登录，编辑角色不该有这个能力。
     */
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@RequestParam long id, @RequestBody Map<String, String> b,
                                           HttpServletRequest req) {
        String by = support.requireSuper(req);
        auth.adminResetPassword(id, b.get("pass"));
        audit.log(by, "user.resetPassword", "uid:" + id, null, req);
        return ApiResponse.ok();
    }

    /**
     * 调整单个玩家的金币/钻石。与重置口令同档（{@code requireSuper}）：它能凭空造钱，
     * 编辑角色不该有这条路径。增减值而非绝对值，服务端记前后值进审计与存档历史。
     */
    @PostMapping("/assets")
    public ApiResponse<Map<String, Object>> assets(@RequestParam long id, @RequestBody Map<String, Object> b,
                                                   HttpServletRequest req) {
        String by = support.requireSuper(req);
        Map<String, Object> r = assets.adjust(id, delta(b, "coins"), delta(b, "diamonds"));
        audit.log(by, "user.assets", "uid:" + id, r, req);
        return ApiResponse.ok(r);
    }

    private static long delta(Map<String, Object> b, String key) {
        Object v = b == null ? null : b.get(key);
        if (v == null) return 0;
        if (v instanceof Number n) return n.longValue();
        throw new BizException(key + " 必须是整数");
    }

    @GetMapping("/save")
    public ApiResponse<Map<String, Object>> save(@RequestParam long id) {
        AppUser u = users.findById(id);
        if (u == null) throw BizException.notFound("用户");
        return ApiResponse.ok(saveService.get(id));
    }

    @GetMapping("/save/revisions")
    public ApiResponse<List<Map<String, Object>>> revisions(@RequestParam long id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserSaveRevision r : saves.listRevisions(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("revision", r.getRevision());
            m.put("source", r.getSource());
            m.put("createdAt", r.getCreatedAt());
            m.put("bytes", r.getPayload() == null ? 0 : r.getPayload().length());
            out.add(m);
        }
        return ApiResponse.ok(out);
    }

    @PostMapping("/save/rollback")
    public ApiResponse<Void> rollback(@RequestParam long id, @RequestParam long revision, HttpServletRequest req) {
        String by = support.requireWriter(req);
        saveService.rollback(id, revision, by);
        audit.log(by, "user.save.rollback", "uid:" + id, Map.of("revision", revision), req);
        return ApiResponse.ok();
    }

    @DeleteMapping
    public ApiResponse<Void> delete(@RequestParam long id, HttpServletRequest req) {
        String by = support.requireWriter(req);
        sessions.revokeAll(id);
        users.delete(id);
        audit.log(by, "user.delete", "uid:" + id, null, req);
        return ApiResponse.ok();
    }
}
