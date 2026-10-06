package com.chemera.server.security;

import com.chemera.server.common.BizException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtService jwt;
    private final SessionGuard guard;

    /** SessionGuard 是 @Component，构造注入即可；测试里直接 new SessionGuard(mockMapper) 也能拼。 */
    public AuthInterceptor(JwtService jwt, SessionGuard guard) {
        this.jwt = jwt;
        this.guard = guard;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object h) {
        String t = bearer(req);
        if (t == null) throw BizException.unauthorized("未登录");
        Claims c;
        try { c = jwt.verify(t, "user"); }
        catch (Exception e) { throw BizException.unauthorized("登录状态无效或已过期"); }
        long uid = Long.parseLong(c.getSubject());
        Long sid = jwt.sid(c);
        // 签名对不等于还在登录中。封禁、改密、重置口令、注销、刷新轮换都会动 user_session 那一行，
        // 这里按主键问一次，撤销就在此刻生效（A6）；不带 sid 的老令牌同样落进这一句。
        if (!guard.live(sid, uid)) throw BizException.unauthorized("登录状态已失效，请重新登录");
        req.setAttribute(AuthContext.ATTR_USER, uid);
        req.setAttribute(AuthContext.ATTR_USERNAME, c.get("uname", String.class));
        // 同一个 sid 顺带留给幂等层（F2）：意图序号是"会话内单调"的，跨会话必须换个键空间
        req.setAttribute(AuthContext.ATTR_SID, sid);
        return true;
    }

    static String bearer(HttpServletRequest req) {
        String a = req.getHeader("Authorization");
        if (a != null && a.startsWith("Bearer ")) return a.substring(7).trim();
        return null;
    }
}
