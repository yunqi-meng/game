package com.chemera.server.security;

import com.chemera.server.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 鉴权端点的防爆破闸门：固定窗口请求配额 + 连续失败锁定，状态全在进程内
 * （单机部署够用；重启即清空，不引入 Redis）。阈值由 {@code chemera.guard.*} 提供，
 * 开发默认宽松、{@code application-prod.yml} 收紧。
 */
@Component
public class RateGuard {
    private static final long MIN = 60_000L;

    private final Map<String, Slot> budget = new ConcurrentHashMap<>();
    private final Map<String, Fail> fails = new ConcurrentHashMap<>();

    private final boolean enabled;
    private final int loginMax, loginWindowMs, loginFailMax, loginLockMs;
    private final int registerMax, registerWindowMs;
    private final int guestMax, guestWindowMs;
    private final int adminMax, adminWindowMs, adminFailMax, adminLockMs;
    private final boolean trustForwardedFor;

    public RateGuard(@Value("${chemera.guard.enabled:true}") boolean enabled,
                     @Value("${chemera.guard.login-max:40}") int loginMax,
                     @Value("${chemera.guard.login-window-minutes:10}") int loginWindowMin,
                     @Value("${chemera.guard.login-fail-max:8}") int loginFailMax,
                     @Value("${chemera.guard.login-lock-minutes:15}") int loginLockMin,
                     @Value("${chemera.guard.register-max:20}") int registerMax,
                     @Value("${chemera.guard.register-window-minutes:60}") int registerWindowMin,
                     @Value("${chemera.guard.guest-max:40}") int guestMax,
                     @Value("${chemera.guard.guest-window-minutes:60}") int guestWindowMin,
                     @Value("${chemera.guard.admin-max:20}") int adminMax,
                     @Value("${chemera.guard.admin-window-minutes:30}") int adminWindowMin,
                     @Value("${chemera.guard.admin-fail-max:5}") int adminFailMax,
                     @Value("${chemera.guard.admin-lock-minutes:30}") int adminLockMin,
                     @Value("${chemera.security.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.enabled = enabled;
        this.loginMax = loginMax; this.loginWindowMs = loginWindowMin * (int) MIN;
        this.loginFailMax = loginFailMax; this.loginLockMs = loginLockMin * (int) MIN;
        this.registerMax = registerMax; this.registerWindowMs = registerWindowMin * (int) MIN;
        this.guestMax = guestMax; this.guestWindowMs = guestWindowMin * (int) MIN;
        this.adminMax = adminMax; this.adminWindowMs = adminWindowMin * (int) MIN;
        this.adminFailMax = adminFailMax; this.adminLockMs = adminLockMin * (int) MIN;
        this.trustForwardedFor = trustForwardedFor;
    }

    /** 只有 X-Forwarded-For 来自可信反代时才取首位；否则任何人都能伪造来源 IP 绕开闸门。 */
    public String ipOf(HttpServletRequest req) {
        if (trustForwardedFor) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                int c = xff.indexOf(',');
                return c > 0 ? xff.substring(0, c).trim() : xff.trim();
            }
        }
        return req.getRemoteAddr();
    }

    /* ---------- 三道闸门：先判锁定期，再扣窗口配额 ---------- */

    public void checkLogin(String ip, String user) {
        enforceLock("login", ip, user);
        acquire("login:" + ip, loginMax, loginWindowMs, "登录尝试过于频繁，请稍后再试");
    }

    public void checkRegister(String ip) {
        acquire("reg:" + ip, registerMax, registerWindowMs, "注册过于频繁，请稍后再试");
    }

    /** 游客档每次都会落一行 app_user，不按 IP 限配额等于把建号接口交给脚本。 */
    public void checkGuest(String ip) {
        acquire("guest:" + ip, guestMax, guestWindowMs, "游客档创建过于频繁，请稍后再试");
    }

    public void checkAdminLogin(String ip, String user) {
        enforceLock("admin", ip, user);
        acquire("admin:" + ip, adminMax, adminWindowMs, "后台登录尝试过于频繁，请稍后再试");
    }

    /* ---------- 失败计数：仅密码/账号错误这类凭据失败才该记 ---------- */

    public void noteFailure(String kind, String ip, String user) {
        if (!enabled) return;
        boolean admin = "admin".equals(kind);
        long now = System.currentTimeMillis();
        String key = key(kind, ip, user);
        fails.compute(key, (k, old) -> old == null || old.expired(now)
                ? new Fail(1, now + (admin ? adminLockMs : loginLockMs), admin ? adminFailMax : loginFailMax)
                : new Fail(old.count + 1, now + (admin ? adminLockMs : loginLockMs), old.limit));
    }

    public void noteSuccess(String kind, String ip, String user) {
        fails.remove(key(kind, ip, user));
    }

    /** 被拒次数（供测试与排查用）：返回该 key 当前的窗口用量或失败数。 */
    public int failuresOf(String kind, String ip, String user) {
        Fail f = fails.get(key(kind, ip, user));
        return f == null ? 0 : f.count;
    }

    private void enforceLock(String kind, String ip, String user) {
        if (!enabled) return;
        String key = key(kind, ip, user);
        Fail f = fails.get(key);
        if (f == null) return;
        long now = System.currentTimeMillis();
        if (f.expired(now)) { fails.remove(key); return; }
        if (f.count < f.limit) return;
        throw BizException.tooMany("失败次数过多，请在 " + Math.max(1, (f.until - now) / MIN) + " 分钟后重试");
    }

    private void acquire(String key, int max, int windowMs, String msg) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        Slot s = budget.compute(key, (k, old) -> {
            if (old == null || old.resetAt <= now) return new Slot(1, now + windowMs);
            old.count++;
            return old;
        });
        synchronized (s) {
            if (s.count > max) {
                s.count = max; // 不在拒绝时继续累加，避免锁定被无限续期
                throw BizException.tooMany(msg);
            }
        }
    }

    private static String key(String kind, String ip, String user) {
        return kind + ":" + ip + ":" + (user == null ? "" : user.trim().toLowerCase());
    }

    /** 窗口与锁定期都会自然过期，这里只把长期空闲的 key 从内存摘掉。 */
    @Scheduled(fixedDelay = 10 * MIN)
    public void purge() {
        long now = System.currentTimeMillis();
        budget.entrySet().removeIf(e -> {
            synchronized (e.getValue()) { return e.getValue().resetAt <= now; }
        });
        fails.entrySet().removeIf(e -> e.getValue().expired(now));
    }

    int trackedKeys() { return budget.size() + fails.size(); }

    private static final class Slot {
        int count;
        long resetAt;
        Slot(int c, long r) { count = c; resetAt = r; }
    }

    private static final class Fail {
        final int count;
        final long until;
        final int limit;
        Fail(int c, long u, int l) { count = c; until = u; limit = l; }
        boolean expired(long now) { return until <= now; }
    }
}
