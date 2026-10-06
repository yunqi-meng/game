package com.chemera.server.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 意图幂等（F2）：同一句意图被链路送来说"交付了两次"时，服务端只执行一次，第二次原样退回第一次那一帧。
 *
 * <p>为什么必须有这一层：C4 只管住了"客户端自己别重放写意图"，F3 只管住了"同一个人连敲两下同一个按钮"，
 * 而 4G／移动网络最常见的下落是<b>服务端已经算完、响应没回来</b>——客户端按超时重发，
 * 服务端看到的就是两句一模一样的 {@code market.buy}，于是扣两次钱、到两件事。
 * 玩家在 UI 上分辨不出"我要连买两次"和"网络给我重发了一次"，这个判断只能由一个带序号的协议来做。
 *
 * <p>键是 {@code (uid, sid, seq)}，不是 {@code (uid, seq)}：{@code seq} 是<b>会话内</b>单调的，
 * 玩家退出重登后从 1 重新数。若只按 uid 认，重登后的第一句意图会撞上上一次会话缓存里的第 1 版，
 * 结果不是"报错"而是<b>静默不执行</b>——玩家点了购买、金币没少、货也没到，比重复扣款更难查。
 * {@code sid} 是 {@code user_session} 行主键（A6 已经把访问令牌绑上它），换一次登录必然换一个，
 * 于是"会话内单调"这个前提在服务端也成立了。
 *
 * <p>缓存里存的是<b>序列化之后的那一帧</b>，不是 {@code Map} 引用：意图回执里带着
 * {@code EngineCtx.tempBench} 这类跨意图复用的对象，直接引用会在后续意图改动它之后把"缓存"一起改掉，
 * 那时两次交付返回的就不是同一帧了。存字符串顺带两个好处——缓存不再钉住活的 {@code GameState}
 * （内存账见下面的配额注释），以及重放时与第一次<b>逐字节相同</b>（e2e 就是按字符串相等判的）。
 * 序列化用的是 MVC 那一份 mapper（{@code default-property-inclusion: non_null}）：换成临时 new 出来的
 * 默认 mapper，缓存那一帧就会比第一次多一串 null 字段，"逐字相同"这条判据当场不成立。
 *
 * <p>三处刻意不缓存：① 只读意图（{@code state}／{@code ad.status}／{@code leaderboard}／{@code quiz.pickOne}）
 * 本来就幂等，而且客户端每 8～30 秒就来一次，缓存它们只会把热帧塞满窗口、把真正需要幂等的写意图挤出去；
 * ② {@code stale} 回执——那一帧什么都没写进库，重发就该真有一次机会，而不是永远拿到"请重试"；
 * ③ 不带 {@code seq} 的请求（旧安装包，或者后台直接调 API）——行为与 F2 之前完全一致，不做任何猜测式去重。
 *
 * <p>配额是硬上界，两份都有：每个会话留最近 {@code seqWindow} 条写意图（重发只会紧挨着原请求，
 * 窗口给到 8 已经远够，多留纯粹浪费），全局最多 {@code maxSessions} 个玩家。超界按最近最少使用淘汰，
 * 被淘汰的后果只是"这个人下一次重发可能真执行两次"，也就是退回 F2 之前的水平——
 * <b>幂等窗口是尽力而为的优化，不是记账依据</b>，所以宁可漏去重，也绝不允许这一层撑爆进程。
 * 淘汰恰好发生在一笔意图飞行中时，重复请求会拿到一个新的 {@link Slot} 而真的执行一次；
 * 那种情况下 F1 的带号写回仍然保证两笔不会互相抹掉存档，玩家最多重复一次购买——仍然优于把整台服务撑爆。
 */
@Component
public class IntentDedupe {

    private static final Logger log = LoggerFactory.getLogger(IntentDedupe.class);

    /** 意图参数里保留的这个键：客户端每次发意图都换一个号，服务端拿它当幂等键。 */
    public static final String SEQ_KEY = "seq";

    private static final TypeReference<LinkedHashMap<String, Object>> FRAME = new TypeReference<>() { };

    private final ObjectMapper om;
    private final int seqWindow;
    private final int maxSessions;
    private final Map<Long, Session> byUid;

    /** 一笔 seq 的下落：要么"还在执行"（{@code done==false}），要么带着第一次算出来的那一帧。 */
    private static final class Slot {
        boolean done;
        String json;
    }

    /** 一个登录会话（同一个 sid）里最近的写意图回执，按访问顺序淘汰。 */
    private final class Session {
        long sid;
        final LinkedHashMap<Long, Slot> slots = new LinkedHashMap<>(8, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Slot> eldest) {
                return size() > seqWindow;
            }
        };

        Session(long sid) { this.sid = sid; }
    }

    /** Spring 装配入口：配额走配置，序列化必须用 MVC 那一份 mapper（两份输出要逐字节一致）。 */
    @Autowired
    public IntentDedupe(ObjectMapper om,
                        @Value("${chemera.intent.dedupe-seqs:8}") int seqWindow,
                        @Value("${chemera.intent.dedupe-sessions:400}") int maxSessions) {
        this.om = om;
        this.seqWindow = Math.max(1, seqWindow);
        this.maxSessions = Math.max(1, maxSessions);
        this.byUid = lru();
    }

    /** 测试用：默认配额，mapper 由调用方给（要和被测代码的序列化口径一致）。 */
    public IntentDedupe(ObjectMapper om) { this(om, 8, 400, true); }

    public IntentDedupe() { this(new ObjectMapper()); }

    /**
     * 测试用：自定义配额，好把"淘汰真的发生"写成断言。
     * 做成工厂而不是重载构造，是因为它和上面那个 Spring 入口的形参一模一样（{@code mapper,int,int}），
     * 编译器分不清哪个该注入配额、哪个该信测试。
     */
    public static IntentDedupe withQuota(ObjectMapper om, int seqWindow, int maxSessions) {
        return new IntentDedupe(om, seqWindow, maxSessions, true);
    }

    /** 只给 {@link #withQuota} 用：靠一个哑元参数和 Spring 的构造分开。 */
    private IntentDedupe(ObjectMapper om, int seqWindow, int maxSessions, boolean testOnly) {
        this.om = om;
        this.seqWindow = Math.max(1, seqWindow);
        this.maxSessions = Math.max(1, maxSessions);
        this.byUid = lru();
    }

    /**
     * 进程内 LRU，上界 {@code maxSessions} 个玩家。这一层没有 DB 支撑，重启即清空是设计内的行为
     * （幂等窗口的长度本来就是"几秒"，不值得为它建表）。
     */
    private Map<Long, Session> lru() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Session> eldest) {
                return size() > maxSessions;
            }
        });
    }

    /**
     * 幂等执行：{@code seq<=0} 或 {@code sid<=0} 直接跑；否则同键只跑一次，重复交付返回第一次那一帧。
     *
     * @param exec 真正结算这一句意图（{@code GameService} 的"载入 → 结算 → 带号写回"）
     */
    public Map<String, Object> execute(long uid, long sid, long seq, Supplier<Map<String, Object>> exec) {
        if (seq <= 0 || sid <= 0) return exec.get();
        Slot slot = slotFor(uid, sid, seq);
        // 锁住这一笔 seq：重复交付排在后面，等第一次算完直接拿那一帧，而不是并发把同一句意图跑两遍。
        // 最坏等待 = 一次意图结算的耗时（mybatis 那侧 30s 语句超时兜着），而且只有同键请求会等。
        synchronized (slot) {
            if (slot.done) {
                Map<String, Object> hit = parse(slot.json);
                if (hit != null) {
                    log.debug("意图重复交付，回缓存帧 uid={} sid={} seq={}", uid, sid, seq);
                    return hit;
                }
                // 缓存读不回来（理论上只在 JSON 被截断时发生）：宁可重新执行，也不给玩家一个空回执
                slot.done = false;
            }
            Map<String, Object> out = exec.get();
            remember(slot, out);
            return out;
        }
    }

    /** 什么值得留：只有"真的动过库"的那一帧才进缓存，{@code stale} 与 {@code null} 都不留。 */
    private void remember(Slot slot, Map<String, Object> out) {
        if (out == null || Boolean.TRUE.equals(out.get("stale"))) return;
        try {
            slot.json = om.writeValueAsString(out);
            slot.done = true;
        } catch (Exception e) {
            // 序列化失败只是丢掉这一笔幂等记录，绝不能把玩家已经算完的意图变成 500
            log.warn("意图回执进不了幂等缓存，这一笔不再去重: {}", e.toString());
        }
    }

    private Slot slotFor(long uid, long sid, long seq) {
        Session s = byUid.compute(uid, (k, old) -> {
            if (old == null) return new Session(sid);
            if (old.sid != sid) {            // 换了一次登录：旧会话的回执与新会话的序号无关，整窗清掉
                synchronized (old.slots) { old.slots.clear(); }
                old.sid = sid;
            }
            return old;
        });
        // synchronizedMap 只保证单次 map 操作原子，"取窗口 + 取/建槽位"这一段要自己上锁
        synchronized (s.slots) {
            return s.slots.computeIfAbsent(seq, k -> new Slot());
        }
    }

    private Map<String, Object> parse(String json) {
        if (json == null) return null;
        try { return om.readValue(json, FRAME); } catch (Exception e) { return null; }
    }

    /** 取意图里的 {@code seq}；没有、非数字、或大得不像是序号的，一律按"不带幂等标识"处理。 */
    public static long seqOf(Map<String, Object> params) {
        if (params == null) return 0L;
        Object v = params.get(SEQ_KEY);
        if (v instanceof Number n) return sane(n.longValue());
        if (v instanceof String str) {
            try { return sane(Long.parseLong(str.trim())); } catch (Exception ignored) { return 0L; }
        }
        return 0L;
    }

    /** 上界只是防御：客户端自增的号不该过万亿，真出现就是有人在乱打接口，按"不幂等"处理更安全。 */
    private static long sane(long s) { return s > 0 && s < 1_000_000_000_000L ? s : 0L; }

    /** 把 {@code seq} 摘掉再交给意图分发：它是传输层的字段，不该混进玩法参数里。 */
    public static Map<String, Object> withoutSeq(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return params == null ? Map.of() : params;
        if (!params.containsKey(SEQ_KEY)) return params;
        Map<String, Object> copy = new LinkedHashMap<>(params);
        copy.remove(SEQ_KEY);
        return copy;
    }

    /* 只有测试与运维面板会读这几个数：配额是否真的生效，靠"塞满了还剩多少"来判。 */
    public int trackedPlayers() { return cachedSessions(); }

    int cachedSessions() { return byUid.size(); }

    int cachedSlots(long uid) {
        Session s = byUid.get(uid);
        return s == null ? 0 : s.slots.size();
    }
}
