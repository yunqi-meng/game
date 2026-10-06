package com.chemera.server.security;

import com.chemera.server.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 鉴权端点的防爆破闸门 + 广告回调的防洪配额：固定窗口请求配额 + 连续失败锁定，状态全在进程内
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
    private final int taptapMax, taptapWindowMs;
    private final int adCallbackMax, adCallbackWindowMs;
    private final int reportMax, reportWindowMs;
    private final int adminMax, adminWindowMs, adminFailMax, adminLockMs;
    private final boolean trustForwardedFor;

    @Autowired
    public RateGuard(@Value("${chemera.guard.enabled:true}") boolean enabled,
                     @Value("${chemera.guard.login-max:40}") int loginMax,
                     @Value("${chemera.guard.login-window-minutes:10}") int loginWindowMin,
                     @Value("${chemera.guard.login-fail-max:8}") int loginFailMax,
                     @Value("${chemera.guard.login-lock-minutes:15}") int loginLockMin,
                     @Value("${chemera.guard.register-max:20}") int registerMax,
                     @Value("${chemera.guard.register-window-minutes:60}") int registerWindowMin,
                     @Value("${chemera.guard.guest-max:40}") int guestMax,
                     @Value("${chemera.guard.guest-window-minutes:60}") int guestWindowMin,
                     @Value("${chemera.guard.taptap-max:30}") int taptapMax,
                     @Value("${chemera.guard.taptap-window-minutes:60}") int taptapWindowMin,
                     @Value("${chemera.guard.ad-callback-max:300}") int adCallbackMax,
                     @Value("${chemera.guard.ad-callback-window-minutes:1}") int adCallbackWindowMin,
                     @Value("${chemera.guard.report-max:20}") int reportMax,
                     @Value("${chemera.guard.report-window-minutes:60}") int reportWindowMin,
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
        this.taptapMax = taptapMax; this.taptapWindowMs = taptapWindowMin * (int) MIN;
        this.adCallbackMax = adCallbackMax; this.adCallbackWindowMs = adCallbackWindowMin * (int) MIN;
        this.reportMax = reportMax; this.reportWindowMs = reportWindowMin * (int) MIN;
        this.adminMax = adminMax; this.adminWindowMs = adminWindowMin * (int) MIN;
        this.adminFailMax = adminFailMax; this.adminLockMs = adminLockMin * (int) MIN;
        this.trustForwardedFor = trustForwardedFor;
    }

    /**
     * 回归用的窄口径构造：广告回调那两道阈值给默认值，免得每加一条闸门就要改一遍散落的测试调用。
     * 生产装配走上面那个带 {@code @Value} 的构造器（所以这里标了 {@code @Autowired} 的那位才是唯一的注入点）。
     */
    public RateGuard(boolean enabled, int loginMax, int loginWindowMin, int loginFailMax, int loginLockMin,
                     int registerMax, int registerWindowMin, int guestMax, int guestWindowMin,
                     int taptapMax, int taptapWindowMin,
                     int adminMax, int adminWindowMin, int adminFailMax, int adminLockMin,
                     boolean trustForwardedFor) {
        this(enabled, loginMax, loginWindowMin, loginFailMax, loginLockMin, registerMax, registerWindowMin,
                guestMax, guestWindowMin, taptapMax, taptapWindowMin, 300, 1, 20, 60,
                adminMax, adminWindowMin, adminFailMax, adminLockMin, trustForwardedFor);
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

    /* ---------- 五道闸门：先判锁定期，再扣窗口配额 ---------- */

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

    /**
     * TapTap 登录：首次登录同样会落一行 app_user，所以按 IP 限配额；
     * 再按票据摘要限一次，免得有人拿同一张坏票据把上游验签接口打穿（那是一次出网调用，
     * 成本比本地查询高）。摘要只做计数用的 key，不入库、不进日志。
     */
    public void checkTapTap(String ip, String ticketHint) {
        acquire("taptap:" + ip, taptapMax, taptapWindowMs, "TapTap 登录过于频繁，请稍后再试");
        if (ticketHint != null && !ticketHint.isBlank())
            acquire("taptap:" + ticketHint, taptapMax, taptapWindowMs, "该登录票据已反复使用，请回 TapTap 重新授权");
    }

    /**
     * 广告网络的 SSV 回调（{@code /api/ad/callback}）不带玩家 JWT，签名就是唯一凭证，
     * 所以它只需要<b>防洪</b>而不是防爆破：一次尝试的代价是一次哈希 + 一次工单查询，
     * 放开了刷就是拿别人的广告流量打自己的库。阈值按"每小时几千次观看"这个量级给，
     * 真被限住时宁可报错也别默认放行——429 会让平台重试，配额恢复后奖励照发（工单仍是 issued）。
     */
    public void checkAdCallback(String ip) {
        acquire("adcb:" + ip, adCallbackMax, adCallbackWindowMs, "广告回调过于频繁");
    }

    public void checkAdminLogin(String ip, String user) {
        enforceLock("admin", ip, user);
        acquire("admin:" + ip, adminMax, adminWindowMs, "后台登录尝试过于频繁，请稍后再试");
    }

    /**
     * 玩家举报（G2）：两道一起走。IP 那道防的是"一个脚本换成一堆游客档刷举报表"，
     * uid 那道防的是"一个人把举报当成刷屏工具对着同一个人连点"。
     * 举报是要人处理的工单，不是事件埋点——放行无上限就等于把审核页变成垃圾场，
     * 而 {@code report} 表一 dirty，真正该看的那几条就沉到分页第二页去了。
     */
    public void checkReport(String ip, long uid) {
        acquire("report:" + ip, reportMax, reportWindowMs, "举报提交过于频繁，请稍后再试");
        acquire("report:u" + uid, reportMax, reportWindowMs, "今天提交的举报已经够多了，谢谢你的配合");
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

    /** 运维面板（G8）与测试都读这个：窗口与失败表在内存里占了多少格。 */
    public int trackedKeys() { return budget.size() + fails.size(); }

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
