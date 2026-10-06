package com.chemera.server.game;

import com.chemera.server.common.BizException;
import com.chemera.server.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 青少年模式时段闸门：落在放行时段外的账号，任何玩法意图（含取帧）一律回绝。
 *
 * <p>刻意放在 {@code GameService.act} 的最前面而不是前端按钮上——在线游戏的约束面只要有一条
 * "客户端不说就算"的路，它就等于没有。命中与否取决于两件事：账号是否 {@code app_user.minor}，
 * 以及"现在是几点"（按 {@code curfew.zone} 换算，服务器在哪个机房与此无关）。
 *
 * <p>没有实名认证接口可用（那是独立资质），所以"谁是未成年人"只有两个可信来源：玩家自己开
 * 青少年模式（{@code POST /api/me/minor}）、或运营在后台【用户管理】标记。两者都留审计。
 *
 * <p>minor 查询带 60 秒进程内缓存：闸门在每次玩法请求的路径上，而标记一天也改不了几次。
 * 标记变更处显式 {@link #invalidate}，最坏情况也只是 60 秒后自愈。
 */
@Component
public class CurfewGuard {

    /** 错误标签：客户端据此弹倒计时页，而不是去匹配会改词的文案。 */
    public static final String TAG = "CURFEW";

    private static final LocalTime FALLBACK_FROM = LocalTime.of(20, 0);
    private static final LocalTime FALLBACK_TO = LocalTime.of(21, 0);
    /** 向前找下一个放行窗口最多找这么多天：放行日全被清空时不至于死循环。 */
    private static final int LOOKAHEAD_DAYS = 21;
    private static final long CACHE_TTL_MS = 60_000L;

    private final java.util.function.Supplier<ContentRegistry.Snapshot> snap;
    private final UserMapper users;
    private final Map<Long, Slot> minorCache = new ConcurrentHashMap<>();

    /** 时钟注入点（毫秒）：单测要固定到某个周几的某个时刻，生产用系统时间。 */
    LongSupplier nowMs = System::currentTimeMillis;

    /**
     * Spring 装配入口。另一个包私有的构造只给单测注入固定时钟与假快照，
     * 所以必须显式标注首选构造——两个候选摆在那里而没有一个被指名，容器会退回找无参构造并报错。
     */
    @Autowired
    public CurfewGuard(ContentRegistry reg, UserMapper users) {
        this(reg::current, users);
    }

    /** 测试入口：内容快照由 {@code new ContentRegistry(null, om).build(…)} 直接造，不必假装连了库。 */
    CurfewGuard(java.util.function.Supplier<ContentRegistry.Snapshot> snap, UserMapper users) {
        this.snap = snap; this.users = users;
    }

    private record Slot(boolean minor, long at) {}

    /** 玩法意图的统一闸门：不放行就抛 403 + tag=CURFEW，带一句人话和下次可玩的时刻。 */
    public void assertAllowed(long uid) {
        Content.Curfew c = snap.get().config.curfewOr();
        if (!c.on() || !isMinor(uid)) return;
        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs.getAsLong()), c.zoneOr());
        if (allowedAt(c, now)) return;
        ZonedDateTime next = nextOpenAt(c, now);
        String msg = c.hintOr() + (next == null ? "（当前配置没有任何放行时段，请联系客服）"
                : "（距下次可玩还有 " + human(now, next) + "）");
        throw BizException.forbidden(msg).tagged(TAG);
    }

    /**
     * 给客户端看的状态视图（不抛异常）：登录页与游戏内的倒计时都读它。
     * 权威判定仍然只在 {@link #assertAllowed}——这个视图被改了也只是界面难看，拿不到奖励。
     */
    public Map<String, Object> view(long uid) {
        Content.Curfew c = snap.get().config.curfewOr();
        ZonedDateTime now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs.getAsLong()), c.zoneOr());
        boolean minor = isMinor(uid);
        boolean allowed = !c.on() || !minor || allowedAt(c, now);
        ZonedDateTime next = allowed ? null : nextOpenAt(c, now);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enforced", c.on());
        out.put("minor", minor);
        out.put("allowed", allowed);
        out.put("serverNow", now.toInstant().toEpochMilli());
        out.put("nextOpenAt", next == null ? null : next.toInstant().toEpochMilli());
        out.put("hint", c.hintOr());
        out.put("window", Map.of("days", c.daysOr(), "from", text(c.from(), FALLBACK_FROM),
                "to", text(c.to(), FALLBACK_TO), "zone", c.zoneOr().getId()));
        return out;
    }

    /** 标记变更后调用，免得玩家刚开了青少年模式却还要等缓存过期。 */
    public void invalidate(long uid) { minorCache.remove(uid); }

    /** 运维面板（G8）读的第三个内存量：这一层是进程内缓存，多实例时各自的水位不同，看的是"有没有失控"。 */
    public int cachedSize() { return minorCache.size(); }

    private boolean isMinor(long uid) {
        long wall = System.currentTimeMillis();
        Slot s = minorCache.get(uid);
        if (s != null && s.at() + CACHE_TTL_MS > wall) return s.minor();
        Integer v = users.minorOf(uid);
        boolean minor = v != null && v == 1;
        minorCache.put(uid, new Slot(minor, wall));
        return minor;
    }

    /* ================= 纯函数判定面（单测直接打这里，不必伪造整条请求链） ================= */

    /** 此刻是否在放行窗口内。 */
    static boolean allowedAt(Content.Curfew c, ZonedDateTime at) {
        LocalTime from = time(c.from(), FALLBACK_FROM), to = time(c.to(), FALLBACK_TO);
        LocalTime t = at.toLocalTime();
        if (from.compareTo(to) <= 0) return isPlayDay(c, at.toLocalDate()) && !t.isBefore(from) && t.isBefore(to);
        // 跨零点窗口（如 22:00–01:00）：晚上那段属于今天，凌晨那段属于昨天开的窗口
        if (!t.isBefore(from)) return isPlayDay(c, at.toLocalDate());
        if (t.isBefore(to)) return isPlayDay(c, at.toLocalDate().minusDays(1));
        return false;
    }

    /** 放行日 = 命中 days 里的周几，或命中运营补的节假日日期。 */
    static boolean isPlayDay(Content.Curfew c, LocalDate d) {
        if (c.daysOr().contains(d.getDayOfWeek().getValue())) return true;
        return c.extraDatesOr().contains(d.toString());
    }

    /** 下一个放行窗口的起点；找不到（放行日被清空）返回 null。 */
    static ZonedDateTime nextOpenAt(Content.Curfew c, ZonedDateTime now) {
        LocalTime from = time(c.from(), FALLBACK_FROM);
        ZoneId z = now.getZone();
        LocalDate d0 = now.toLocalDate();
        for (int i = 0; i <= LOOKAHEAD_DAYS; i++) {
            LocalDate d = d0.plusDays(i);
            if (!isPlayDay(c, d)) continue;
            ZonedDateTime open = d.atTime(from).atZone(z);
            if (open.isAfter(now)) return open;
            // 今天的窗口要么正在进行（allowedAt 已判过），要么已过，继续往后找
        }
        return null;
    }

    /** 把"还要等多久"说成人话：天 / 小时 / 分钟三档够用，别给玩家看 123456 秒。 */
    static String human(ZonedDateTime from, ZonedDateTime to) {
        long sec = Math.max(0, to.toEpochSecond() - from.toEpochSecond());
        long d = sec / 86400, h = sec % 86400 / 3600, m = sec % 3600 / 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append(" 天 ");
        if (h > 0) sb.append(h).append(" 小时 ");
        if (m <= 0 && sb.length() == 0) sb.append("不到 1 分钟");
        else if (m > 0) sb.append(m).append(" 分钟");
        return sb.toString().trim();
    }

    static LocalTime time(String raw, LocalTime fallback) {
        if (raw != null && !raw.isBlank()) {
            try { return LocalTime.parse(raw.trim()); } catch (Exception ignore) { }
        }
        return fallback;
    }

    private static String text(String raw, LocalTime fallback) {
        LocalTime t = time(raw, fallback);
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }
}
