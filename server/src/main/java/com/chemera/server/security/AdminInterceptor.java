package com.chemera.server.security;

import com.chemera.server.common.BizException;
import com.chemera.server.entity.AdminUser;
import com.chemera.server.mapper.AdminMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理侧鉴权。令牌里的 role 只是"签发那一刻"的身份，账号可能随后被停用或降级，
 * 因此每个后台请求都回查一次 admin_user——表只有几行，成本可忽略，换掉的是
 * "被停用的管理员仍能用旧令牌调到过期"这类真实事故。
 * 初始/被重置口令（must_change_password）在改密之前只放行自助改密端点。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {
    /** 必须先改密才能访问的路径白名单（改密本身与"我是谁"探测）。 */
    private static final String SELF_PASS = "/admin/api/me/password";

    private final JwtService jwt;
    private final AdminMapper admins;

    public AdminInterceptor(JwtService jwt, AdminMapper admins) {
        this.jwt = jwt; this.admins = admins;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object h) {
        String t = AuthInterceptor.bearer(req);
        if (t == null) throw BizException.unauthorized("未登录");
        Claims c;
        try { c = jwt.verify(t, "admin"); }
        catch (Exception e) { throw BizException.unauthorized("管理员登录状态无效或已过期"); }
        long id = Long.parseLong(c.getSubject());
        AdminUser a = admins.findById(id);
        if (a == null) throw BizException.unauthorized("管理员账号已不存在，请重新登录");
        if (a.getStatus() != null && a.getStatus() == 1) throw BizException.forbidden("管理员已停用");
        // 角色以库为准：JWT 签发后可能已被 super 调整过权限
        req.setAttribute(AuthContext.ATTR_ADMIN, id);
        req.setAttribute(AuthContext.ATTR_ROLE, a.getRole());
        req.setAttribute(AuthContext.ATTR_USERNAME, a.getUsername());
        boolean mustPass = a.getMustChangePassword() != null && a.getMustChangePassword() == 1;
        if (mustPass && !SELF_PASS.equals(req.getRequestURI())) {
            throw BizException.forbidden("请先修改初始密码后再使用后台");
        }
        return true;
    }
}
