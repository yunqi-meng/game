package com.chemera.server.controller;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.common.BizException;
import com.chemera.server.entity.AdminUser;
import com.chemera.server.mapper.AdminMapper;
import com.chemera.server.security.AuthContext;
import com.chemera.server.security.JwtService;
import com.chemera.server.security.RateGuard;
import com.chemera.server.service.AdminAccountService;
import com.chemera.server.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/api")
public class AdminAuthController {
    private final AdminMapper admins;
    private final PasswordEncoder enc;
    private final JwtService jwt;
    private final RateGuard guard;
    private final AdminAccountService accounts;
    private final AuditService audit;

    public AdminAuthController(AdminMapper admins, PasswordEncoder enc, JwtService jwt, RateGuard guard,
                               AdminAccountService accounts, AuditService audit) {
        this.admins = admins; this.enc = enc; this.jwt = jwt; this.guard = guard;
        this.accounts = accounts; this.audit = audit;
    }

    /** 后台拿到的是全量内容与玩家数据写权限，口令又常是种子默认值，必须限爆破。 */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> b, HttpServletRequest req) {
        String ip = guard.ipOf(req), user = b.get("user");
        guard.checkAdminLogin(ip, user);
        try {
            Map<String, Object> out = doLogin(user, b.get("pass"));
            guard.noteSuccess("admin", ip, user);
            return ApiResponse.ok(out);
        } catch (BizException e) {
            if (e.code == 401) guard.noteFailure("admin", ip, user);
            throw e;
        }
    }

    private Map<String, Object> doLogin(String username, String pass) {
        AdminUser a = admins.findByUsername(username == null ? "" : username);
        if (a == null || !enc.matches(pass == null ? "" : pass, a.getPassHash()))
            throw BizException.unauthorized("账号或密码错误");
        if (a.getStatus() != null && a.getStatus() == 1) throw BizException.forbidden("管理员已停用");
        admins.touchLogin(a.getId());
        String token = jwt.issue(a.getId(), "admin", a.getRole(), a.getUsername());
        // mustChange 让 SPA 登录后立刻弹改密框；服务端拦截器同时锁死其余端点，前端不可绕
        return Map.of("token", token, "user", a.getUsername(), "role", a.getRole(),
                "mustChange", a.getMustChangePassword() != null && a.getMustChangePassword() == 1,
                "expiresIn", jwt.accessTtlMs() / 1000);
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(HttpServletRequest req) {
        AdminUser a = admins.findById(AuthContext.adminId(req));
        return ApiResponse.ok(Map.of("user", a.getUsername(), "role", a.getRole(),
                "mustChange", a.getMustChangePassword() != null && a.getMustChangePassword() == 1));
    }

    /** 自助改密：任何角色都能换自己的口令，也是"必须先改初始口令"时唯一放行的端点。 */
    @PostMapping("/me/password")
    public ApiResponse<Void> changeOwnPass(@RequestBody Map<String, String> b, HttpServletRequest req) {
        accounts.changeOwnPassword(AuthContext.adminId(req), b.get("old"), b.get("new"));
        audit.log(AuthContext.adminName(req), "admin.selfPassword", "id:" + AuthContext.adminId(req), null, req);
        return ApiResponse.ok();
    }

    @GetMapping("/ping")
    public ApiResponse<String> ping() { return ApiResponse.ok("pong"); }
}
