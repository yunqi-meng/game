package com.chemera.server.controller;

import com.chemera.server.common.ApiResponse;
import com.chemera.server.common.BizException;
import com.chemera.server.security.AuthContext;
import com.chemera.server.security.RateGuard;
import com.chemera.server.security.SessionGuard;
import com.chemera.server.security.TapTapVerifier;
import com.chemera.server.service.AuthService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final RateGuard guard;
    private final TapTapVerifier tap;
    private final SessionGuard sessions;

    public AuthController(AuthService auth, RateGuard guard, TapTapVerifier tap, SessionGuard sessions) {
        this.auth = auth; this.guard = guard; this.tap = tap; this.sessions = sessions;
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

    /**
     * TapTap 登录：客户端把 TapSDK 授权拿到的票据递上来，服务端验签换成本服账号。
     * 首次登录自动建档；带着游客令牌来就把试玩进度并进去（走【转正】同一条并档路径）。
     *
     * <p>这个入口刻意没有进 {@code AuthInterceptor} 的必填名单——"还没登录的人要登录"
     * 本来就不该先要求登录。游客令牌是按可选上下文读的（{@link SessionGuard#optionalUid}），
     * 带坏了令牌也只是当作没登录，不会把一次本来能成功的登录打死；但会话已被撤销的令牌不算登录。
     */
    @PostMapping("/taptap")
    public ApiResponse<Map<String, Object>> taptap(@RequestBody(required = false) JsonNode b,
                                                   HttpServletRequest req) {
        JsonNode ticket = b == null ? null : b.get("ticket");
        String ip = guard.ipOf(req);
        guard.checkTapTap(ip, TapTapVerifier.hint(ticket));
        // 验签放在建号之前：这一步不通过就根本没有身份可谈，绝不返回任何"看起来像令牌"的东西
        TapTapVerifier.Identity id = tap.verify(ticket);
        return ApiResponse.ok(auth.taptap(id, sessions.optionalUid(req)));
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

    /**
     * 注销账号。正式账号仍要口令二次确认；TapTap 登录进来的账号没有口令，改带一张新票据
     * （{@code ticket}）由服务端验签确认本人，见 {@link AuthService#deleteAccount}。
     */
    @PostMapping("/delete")
    public ApiResponse<Void> delete(@RequestBody(required = false) JsonNode b, HttpServletRequest req) {
        String pass = b == null || !b.hasNonNull("pass") ? null : b.get("pass").asText();
        JsonNode ticket = b == null ? null : b.get("ticket");
        TapTapVerifier.Identity reauth = null;
        if (ticket != null && !ticket.isNull()) {
            guard.checkTapTap(guard.ipOf(req), TapTapVerifier.hint(ticket));
            reauth = tap.verify(ticket);            // 验不过就直接 401：注销不可逆，不接受一张可疑票据
        }
        auth.deleteAccount(AuthContext.uid(req), pass, reauth);
        return ApiResponse.ok();
    }
}
