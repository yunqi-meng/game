package com.chemera.server.security;

import com.chemera.server.common.BizException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;

/** 从请求属性读取当前登录主体（由拦截器写入）。 */
public final class AuthContext {
    public static final String ATTR_USER = "chemera.uid";
    public static final String ATTR_USERNAME = "chemera.uname";
    public static final String ATTR_ADMIN = "chemera.admin";
    public static final String ATTR_ROLE = "chemera.role";

    private AuthContext() {}

    public static long uid(HttpServletRequest r) {
        Object v = r.getAttribute(ATTR_USER);
        if (v == null) throw BizException.unauthorized("未登录");
        return (Long) v;
    }

    public static long adminId(HttpServletRequest r) {
        Object v = r.getAttribute(ATTR_ADMIN);
        if (v == null) throw BizException.unauthorized("未登录");
        return (Long) v;
    }

    public static String adminName(HttpServletRequest r) {
        Object v = r.getAttribute(ATTR_USERNAME);
        return v == null ? "admin" : (String) v;
    }

    /** 日志用：谁在打这个接口（玩家 uid / 管理员 id），未登录返回 "anon"，不抛异常。 */
    public static String who(HttpServletRequest r) {
        Object u = r.getAttribute(ATTR_USER);
        if (u != null) return "uid=" + u;
        Object a = r.getAttribute(ATTR_ADMIN);
        if (a != null) return "admin=" + a;
        return "anon";
    }

    public static String role(HttpServletRequest r) {
        Object v = r.getAttribute(ATTR_ROLE);
        return v == null ? null : (String) v;
    }

    public static void apply(Claims c, HttpServletRequest r, String type) {
        long id = Long.parseLong(c.getSubject());
        if ("admin".equals(type)) {
            r.setAttribute(ATTR_ADMIN, id);
            r.setAttribute(ATTR_ROLE, c.get("role", String.class));
        } else {
            r.setAttribute(ATTR_USER, id);
        }
        r.setAttribute(ATTR_USERNAME, c.get("uname", String.class));
    }
}
