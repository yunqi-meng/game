package com.chemera.server.service;

import com.chemera.server.common.BizException;
import com.chemera.server.entity.AppUser;
import com.chemera.server.entity.UserSession;
import com.chemera.server.mapper.AnalyticsMapper;
import com.chemera.server.mapper.ModerationMapper;
import com.chemera.server.mapper.SaveMapper;
import com.chemera.server.mapper.SessionMapper;
import com.chemera.server.mapper.UserMapper;
import com.chemera.server.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern USER = Pattern.compile("^[\\w\\u4e00-\\u9fa5]{2,24}$");
    /** 并发刷新的容忍窗口：这一刻内被轮换掉的令牌再出现不视为盗用（多标签页会同时刷）。 */
    private static final long ROTATE_GRACE_SEC = 15;
    private final UserMapper users;
    private final SessionMapper sessions;
    private final ModerationMapper mod;
    private final SaveMapper saves;
    private final AnalyticsMapper analytics;
    private final PasswordEncoder enc;
    private final JwtService jwt;
    private final long refreshTtlMs;

    public AuthService(UserMapper users, SessionMapper sessions, ModerationMapper mod, SaveMapper saves,
                       AnalyticsMapper analytics, PasswordEncoder enc, JwtService jwt,
                       @org.springframework.beans.factory.annotation.Value("${chemera.jwt.refresh-ttl-days:30}") long days) {
        this.users = users; this.sessions = sessions; this.mod = mod;
        this.saves = saves; this.analytics = analytics;
        this.enc = enc; this.jwt = jwt; this.refreshTtlMs = days * 86400_000L;
    }

    private Map<String, Object> token(AppUser u) {
        String access = jwt.issue(u.getId(), "user", "user", u.getUsername());
        String refresh = newToken();
        sessions.insert(u.getId(), sha(refresh), "web", LocalDateTime.now().plusSeconds(refreshTtlMs / 1000));
        boolean guest = u.getIsGuest() != null && u.getIsGuest() == 1;
        return Map.of("token", access, "refresh", refresh, "user", u.getUsername(),
                "guest", guest, "expiresIn", jwt.accessTtlMs() / 1000);
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    /** 游客试玩：服务端建一次性账号（随机不可见口令）并发常规令牌；转正时由 {@link #upgrade} 并入正式账号。 */
    public Map<String, Object> guest() {
        AppUser u = new AppUser();
        String name = "游客" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        u.setUsername(name);
        u.setNickname(name);
        u.setPassHash(enc.encode(UUID.randomUUID().toString()));
        u.setIsGuest(1);
        try {
            users.insertGuest(u);
        } catch (DuplicateKeyException e) {
            throw new BizException("游客创建失败，请重试");
        }
        return token(u);
    }

    public Map<String, Object> register(String username, String pass, String nickname) {
        return token(createUser(username, pass, nickname));
    }

    private AppUser createUser(String username, String pass, String nickname) {
        if (username == null || !USER.matcher(username).matches()) throw new BizException("用户名需 2-24 位（中文/字母/数字/下划线）");
        if (pass == null || pass.length() < 6) throw new BizException("密码至少 6 位");
        checkSensitive(username);
        if (nickname != null) checkSensitive(nickname);
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPassHash(enc.encode(pass));
        u.setNickname(nickname == null || nickname.isBlank() ? username : nickname);
        try {
            users.insert(u);
        } catch (DuplicateKeyException e) {
            throw new BizException("用户名已被占用");
        }
        return u;
    }

    /**
     * 游客转正：用当前游客令牌注册正式账号，并把游客名下的存档/历史/埋点整体并入新账号。
     * 游客行随后删除，旧刷新令牌失效。
     */
    @Transactional
    public Map<String, Object> upgrade(long guestUid, String username, String pass, String nickname) {
        AppUser g = users.findById(guestUid);
        if (g == null || g.getIsGuest() == null || g.getIsGuest() != 1)
            throw new BizException("当前登录的不是游客账号，无需转正");
        AppUser u = createUser(username, pass, nickname);
        saves.reassignUser(guestUid, u.getId());
        saves.reassignRevisions(guestUid, u.getId());
        analytics.reassignUser(guestUid, u.getId());
        sessions.revokeAll(guestUid);
        users.delete(guestUid);
        Map<String, Object> out = new LinkedHashMap<>(token(u));
        out.put("guest", false);
        out.put("merged", true);
        return out;
    }

    public Map<String, Object> login(String username, String pass) {
        AppUser u = users.findByUsername(username == null ? "" : username);
        if (u == null || !enc.matches(pass == null ? "" : pass, u.getPassHash())) {
            throw BizException.unauthorized("用户名或密码错误");
        }
        if (u.getStatus() != null && u.getStatus() == 1) {
            if (u.getBannedUntil() == null || u.getBannedUntil().isAfter(LocalDateTime.now()))
                throw BizException.forbidden("账号已被封禁");
        }
        users.touchLogin(u.getId());
        return token(u);
    }

    /**
     * 刷新即轮换：本次使用的刷新令牌当场作废（rotated_at），只下发新的一对。
     * 三类令牌要分开对待——硬撤销（退出/改密/注销）绝不补发；被轮换掉的令牌在宽限期内
     * 视为多标签页并发刷新，超期再出现即按泄露处理，整户下线。
     */
    public Map<String, Object> refresh(String refresh) {
        if (refresh == null) throw BizException.unauthorized("缺少刷新令牌");
        String h = sha(refresh);
        UserSession s = sessions.findByHash(h);
        if (s == null) throw BizException.unauthorized("刷新令牌无效");
        LocalDateTime now = LocalDateTime.now();
        if (s.getRevokedAt() != null) throw BizException.unauthorized("该登录态已注销，请重新登录");
        if (s.getRotatedAt() != null) {
            if (s.getRotatedAt().isBefore(now.minusSeconds(ROTATE_GRACE_SEC))) {
                sessions.revokeAll(s.getUserId());
                log.warn("刷新令牌重用（超出轮换宽限期），已吊销 uid={} 的全部登录态", s.getUserId());
                throw BizException.unauthorized("登录状态异常，请重新登录");
            }
        } else if (s.getExpiresAt() == null || s.getExpiresAt().isBefore(now)) {
            throw BizException.unauthorized("刷新令牌已过期");
        } else {
            sessions.rotate(h);
        }
        AppUser u = users.findById(s.getUserId());
        if (u == null) throw BizException.unauthorized("账号不存在");
        return token(u);
    }

    public void logout(String refresh) {
        if (refresh != null) sessions.revoke(sha(refresh));
    }

    public void changePassword(long uid, String oldPass, String newPass) {
        AppUser u = users.findById(uid);
        if (u == null || !enc.matches(oldPass == null ? "" : oldPass, u.getPassHash()))
            throw new BizException("原密码不正确");
        if (newPass == null || newPass.length() < 6) throw new BizException("新密码至少 6 位");
        users.updatePass(uid, enc.encode(newPass));
        sessions.revokeAll(uid); // 改密后其余设备登录态失效
    }

    /**
     * 忘记密码的唯一出路（没有邮件服务可验证身份）：由超管在后台核实身份后重置。
     * 刻意排除游客档——游客从未设置过口令，重置等于凭空造一个可登录账号，
     * 且游客名是随机后缀，客服无从核对。
     */
    public void adminResetPassword(long uid, String newPass) {
        AppUser u = users.findById(uid);
        if (u == null) throw new BizException("账号不存在");
        if (u.getIsGuest() != null && u.getIsGuest() == 1)
            throw new BizException("游客档没有口令，无法重置；请让玩家在游戏内【设置-账号】转正");
        if (newPass == null || newPass.length() < 6) throw new BizException("新密码至少 6 位");
        if (newPass.equalsIgnoreCase(u.getUsername())) throw new BizException("新密码不能与用户名相同");
        users.updatePass(uid, enc.encode(newPass));
        // 整户刷新令牌作废：被盗设备最多还能用完手上的访问令牌（≤2h），续不了命
        sessions.revokeAll(uid);
        log.info("管理员重置玩家口令 uid={} user={}", uid, u.getUsername());
    }

    /**
     * 注销账号：用户名下登录态、存档、存档历史与埋点全部物理删除（表间无外键级联，必须逐个清）。
     */
    @Transactional
    public void deleteAccount(long uid, String pass) {
        AppUser u = users.findById(uid);
        if (u == null || !enc.matches(pass == null ? "" : pass, u.getPassHash()))
            throw new BizException("密码不正确");
        sessions.purge(uid);
        saves.purgeUser(uid);
        saves.purgeRevisions(uid);
        analytics.purgeUser(uid);
        users.delete(uid);
    }

    private void checkSensitive(String text) {
        List<String> words = mod.allWords();
        for (String w : words) if (!w.isBlank() && text.contains(w))
            throw new BizException("内容包含敏感词，请修改后重试");
    }

    private static String sha(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
