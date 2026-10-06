package com.chemera.server.security;

import com.chemera.server.common.BizException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;

/** 从请求属性读取当前登录主体（由拦截器写入）。 */
public final class AuthContext {
    public static final String ATTR_USER = "chemera.uid";
    public static final String ATTR_USERNAME = "chemera.uname";
    public static final String ATTR_SID = "chemera.sid";
    public static final String ATTR_ADMIN = "chemera.admin";
    public static final String ATTR_ROLE = "chemera.role";

    private AuthContext() {}

    public static long uid(HttpServletRequest r) {
        Object v = r.getAttribute(ATTR_USER);
        if (v == null) throw BizException.unauthorized("未登录");
        return (Long) v;
    }

    /**
     * 当前登录态主键（{@code user_session.id}），拿不到就回 0 表示"没有可用会话标识"。
     *
     * <p>意图幂等（F2）按 {@code (uid, sid, seq)} 认重，而 {@code seq} 是<b>客户端会话内</b>自增的：
     * 退出重登后会从 1 重新数。少了 sid 这一维，重登后的第一句意图会撞上一次会话留下的缓存，
     * 结果是"静默不执行"这种最难查的下落。不带 sid 的老令牌在 {@link SessionGuard} 那一步就已经被拒了，
     * 所以这里读到 0 只会发生在非玩家入口（后台、测试直接调服务），那时按"不做幂等"处理。
     */
    public static long sid(HttpServletRequest r) {
        Object v = r.getAttribute(ATTR_SID);
        return v instanceof Number n ? n.longValue() : 0L;
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

    /**
     * 可选登录态已经挪到 {@link SessionGuard#optionalUid}：那一步必须顺带查会话活体（A6），
     * 而这个类刻意只做"从请求属性里读数"，不碰数据库。留一个只验签不看会话的静态副本，
     * 迟早会有人挑错那一个用。
     */
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
