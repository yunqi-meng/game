package com.chemera.server.controller.admin;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.security.AuthContext;
import com.chemera.server.service.AdminAccountService;
import com.chemera.server.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 管理员账号 CRUD（仅 super）。每个写操作都进 audit_log——后台账号是这台服务最高的权限，
 * 谁建了号、谁改了谁的口令必须可追。响应体永不含口令哈希。
 */
@RestController
@RequestMapping("/admin/api/admins")
public class AdminAccountController {
    private final AdminAccountService accounts;
    private final AdminSupport support;
    private final AuditService audit;

    public AdminAccountController(AdminAccountService accounts, AdminSupport support, AuditService audit) {
        this.accounts = accounts; this.support = support; this.audit = audit;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest req) {
        support.requireSuper(req);
        return ApiResponse.ok(accounts.list());
    }

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, String> b, HttpServletRequest req) {
        String by = support.requireSuper(req);
        Map<String, Object> row = accounts.create(b.get("user"), b.get("pass"), b.get("role"));
        audit.log(by, "admin.create", "id:" + row.get("id"), Map.of("role", row.get("role")), req);
        return ApiResponse.ok(row);
    }

    /** 超管重置他人口令：被重置者下次登录即失去后台使用权，直到自己改密。 */
    @PostMapping("/password")
    public ApiResponse<Void> reset(@RequestParam long id, @RequestBody Map<String, String> b,
                                   HttpServletRequest req) {
        String by = support.requireSuper(req);
        accounts.resetPassword(id, b.get("pass"));
        audit.log(by, "admin.password", "id:" + id, null, req);
        return ApiResponse.ok();
    }

    @PostMapping("/role")
    public ApiResponse<Void> role(@RequestParam long id, @RequestParam String role, HttpServletRequest req) {
        String by = support.requireSuper(req);
        accounts.setRole(id, role, AuthContext.adminId(req));
        audit.log(by, "admin.role", "id:" + id, Map.of("role", role), req);
        return ApiResponse.ok();
    }

    @PostMapping("/status")
    public ApiResponse<Void> status(@RequestParam long id, @RequestParam int status, HttpServletRequest req) {
        String by = support.requireSuper(req);
        accounts.setStatus(id, status, AuthContext.adminId(req));
        audit.log(by, "admin.status", "id:" + id, Map.of("status", status), req);
        return ApiResponse.ok();
    }

    @PostMapping("/remove")
    public ApiResponse<Void> remove(@RequestParam long id, HttpServletRequest req) {
        String by = support.requireSuper(req);
        accounts.delete(id, AuthContext.adminId(req));
        audit.log(by, "admin.remove", "id:" + id, null, req);
        return ApiResponse.ok();
    }
}
