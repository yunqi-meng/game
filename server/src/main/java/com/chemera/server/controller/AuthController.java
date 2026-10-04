package com.chemera.server.controller;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.common.BizException;
import com.chemera.server.security.AuthContext;
import com.chemera.server.security.RateGuard;
import com.chemera.server.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final RateGuard guard;

    public AuthController(AuthService auth, RateGuard guard) {
        this.auth = auth; this.guard = guard;
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, String> b, HttpServletRequest req) {
        guard.checkRegister(guard.ipOf(req));
        return ApiResponse.ok(auth.register(b.get("user"), b.get("pass"), b.get("nickname")));
    }

    /** 登录是唯一的在线爆破面：按 IP 限总速、按 IP+用户名数失败次数并临时锁定。 */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> b, HttpServletRequest req) {
        String ip = guard.ipOf(req), user = b.get("user");
        guard.checkLogin(ip, user);
        try {
            Map<String, Object> out = auth.login(user, b.get("pass"));
            guard.noteSuccess("login", ip, user);
            return ApiResponse.ok(out);
        } catch (BizException e) {
            if (e.code == 401) guard.noteFailure("login", ip, user);
            throw e;
        }
    }

    /** 每次调用都会落一行游客账号，必须限频，否则建号接口就是灌库接口。 */
    @PostMapping("/guest")
    public ApiResponse<Map<String, Object>> guest(HttpServletRequest req) {
        guard.checkGuest(guard.ipOf(req));
        return ApiResponse.ok(auth.guest());
    }

    /** 游客转正：携带当前游客令牌，注册成功即把游客进度并入新账号。 */
    @PostMapping("/upgrade")
    public ApiResponse<Map<String, Object>> upgrade(@RequestBody Map<String, String> b, HttpServletRequest req) {
        guard.checkRegister(guard.ipOf(req));
        return ApiResponse.ok(auth.upgrade(AuthContext.uid(req), b.get("user"), b.get("pass"), b.get("nickname")));
    }

    @PostMapping("/refresh")
    public ApiResponse<Map<String, Object>> refresh(@RequestBody Map<String, String> b) {
        return ApiResponse.ok(auth.refresh(b.get("refresh")));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody(required = false) Map<String, String> b) {
        auth.logout(b == null ? null : b.get("refresh"));
        return ApiResponse.ok();
    }

    @PostMapping("/pass")
    public ApiResponse<Void> pass(@RequestBody Map<String, String> b, HttpServletRequest req) {
        auth.changePassword(AuthContext.uid(req), b.get("old"), b.get("new"));
        return ApiResponse.ok();
    }

    @PostMapping("/delete")
    public ApiResponse<Void> delete(@RequestBody Map<String, String> b, HttpServletRequest req) {
        auth.deleteAccount(AuthContext.uid(req), b.get("pass"));
        return ApiResponse.ok();
    }
}
