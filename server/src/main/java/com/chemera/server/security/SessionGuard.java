package com.chemera.server.security;

import com.chemera.server.mapper.SessionMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 会话活体检查（A6）：令牌里那个 sid 对应的登录态还成立吗。
 *
 * <p>为什么单独一层：这件事有两个入口要做——每个需要登录的接口（{@link AuthInterceptor}），
 * 以及"可以裸奔也可以带游客令牌来"的 TapTap 登录（{@link #optionalUid}）。
 * 两处必须问同一句话，否则并档那条路就成了绕过会话撤销的后门：
 * 一次退出登录之后，旧访问令牌在其余地方都失效了，却还能在换绑时把游客进度搬走。
 *
 * <p>成本：一次按主键的 COUNT。与后台侧"每请求回查 admin_user"同量级，
 * 换来的是封禁/改密/重置口令/注销在同一个请求里生效，而不是等访问令牌自然过期的 ≤2h。
 */
@Component
public class SessionGuard {

    private final SessionMapper sessions;
    private final JwtService jwt;

    public SessionGuard(SessionMapper sessions, JwtService jwt) {
        this.sessions = sessions;
        this.jwt = jwt;
    }

    /** sid 缺失一律按失效处理：本轮上线前签发的老令牌就是这个形状，代价是玩家重登一次。 */
    public boolean live(Long sid, long uid) {
        if (sid == null || sid <= 0) return false;
        return sessions.liveCount(sid, uid) > 0;
    }

    /**
     * 可选登录态：为 TapTap 登录这类"带游客令牌来、也可以裸奔来"的入口问一句
     * "如果这个人其实已经登录了，他是谁"。
     *
     * <p>解不出有效 uid 时一律当作没登录（没带头、令牌过期、拿的是后台令牌、会话已撤销），
     * 绝不因为一段坏 Authorization 头就把一次本来会成功的第三方登录打死——那种失败玩家没法自救。
     * 但与纯粹的签名校验不同，这里**要求会话还活着**：并档会把一个账号的全部存档搬到新账号上，
     * 一次已经被撤销的登录不该再有这个能力。
     */
    public Long optionalUid(HttpServletRequest req) {
        Object v = req.getAttribute(AuthContext.ATTR_USER);
        if (v != null) return (Long) v;
        String t = AuthInterceptor.bearer(req);
        if (t == null || t.isBlank()) return null;
        try {
            Claims c = jwt.verify(t, "user");
            long uid = Long.parseLong(c.getSubject());
            return live(jwt.sid(c), uid) ? uid : null;
        } catch (Exception e) {
            return null;
        }
    }
}
