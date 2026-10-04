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
    public AuthInterceptor(JwtService jwt) { this.jwt = jwt; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object h) {
        String t = bearer(req);
        if (t == null) throw BizException.unauthorized("未登录");
        Claims c;
        try { c = jwt.verify(t, "user"); }
        catch (Exception e) { throw BizException.unauthorized("登录状态无效或已过期"); }
        req.setAttribute(AuthContext.ATTR_USER, Long.parseLong(c.getSubject()));
        req.setAttribute(AuthContext.ATTR_USERNAME, c.get("uname", String.class));
        return true;
    }

    static String bearer(HttpServletRequest req) {
        String a = req.getHeader("Authorization");
        if (a != null && a.startsWith("Bearer ")) return a.substring(7).trim();
        return null;
    }
}
