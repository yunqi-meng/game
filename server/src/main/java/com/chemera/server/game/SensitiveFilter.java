package com.chemera.server.game;

import com.chemera.server.common.BizException;
import com.chemera.server.mapper.ModerationMapper;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词分级过滤器（G2）。
 *
 * <p>为什么不是"命中就拦"：{@code sensitive_word.level} 从建表那天起就写着 1=提示 / 2=拦截，
 * 而 {@code AuthService} 过去的做法是**一律拒绝**——字段存在却不生效，比没有这个字段更糟：
 * 运营在后台把某个词调成"提示"，玩家那边照样被拒，看代码的人还得再查一遍才知道真相。
 * 这一层就是把那个分级真正实现出来：
 * <ul>
 *   <li><b>level ≥ 2</b>：{@link #block} 抛 {@link BizException}，写入被拒；</li>
 *   <li><b>level == 1</b>：放行，但把命中的词回给调用方（{@link Verdict#flagged()}），
 *       由它决定怎么留痕——昵称走"提示"，举报走"照单收下"。</li>
 * </ul>
 *
 * <p>词表带一份短 TTL 缓存（默认 60 秒），后台改词时主动失效。以前每次注册都 {@code SELECT} 一遍整表，
 * 那是把一个"几乎不变的小表"当热路径查询打；缓存的代价是"后台刚加的词最多 60 秒后才生效"，
 * 而 {@code invalidate()} 走的是同一个进程内的直接调用，所以后台加词实际是立刻生效的——
 * TTL 只是给"多实例部署时另一台没被叫到"这条未来路径留的收敛上限（当前是单实例，见 G7）。
 *
 * <p>读库失败时按 <b>last-known-good</b> 判（见 {@link #table()}）：拿上一次成功加载的那本词表继续拦，
 * 而不是悄悄换成空表把注册与昵称的防护关一整段。只有这个进程从没成功加载过时才退到"空表放行 + WARN"。
 */
@Component
public class SensitiveFilter {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(SensitiveFilter.class);

    /** 一次扫描的下落：{@code blocked} 是那条"必须拒绝"的词，{@code flagged} 是"提示档"命中的词。 */
    public record Verdict(String blocked, List<String> flagged) {
        public boolean rejected() { return blocked != null; }
        public boolean noted() { return !flagged.isEmpty(); }
    }

    private final ModerationMapper mod;
    private final long ttlMs;
    /** 现在生效的那一份：正常就是刚读上来的，读库失败时是 {@link #lastGood} 那一本。 */
    private volatile Map<String, Integer> words;
    private volatile long loadedAt;
    /**
     * last-known-good：<b>上一次成功加载</b>的词表，只在读库成功时更新。
     *
     * <p>为什么单独存一份而不是复用 {@code words}：{@code words} 会在失败时被换成兜底表（空表），
     * 而兜底表不该成为下一次失败的兜底——那样第一次抖动就会把好词表永久丢掉。
     * 库修好之前的整段时间里，注册/昵称都按这本旧词表判，这是"降级"而不是"关防护"。
     */
    private volatile Map<String, Integer> lastGood;

    public SensitiveFilter(ModerationMapper mod,
                           @Value("${chemera.words.ttl-sec:60}") long ttlSec) {
        this.mod = mod;
        this.ttlMs = Math.max(0L, ttlSec) * 1000L;
    }

    /** 命中判定：先按"拦截档"找，找到就够；同时把"提示档"的命中都收下来。 */
    public Verdict scan(String text) {
        if (text == null || text.isBlank()) return new Verdict(null, List.of());
        String hit = null;
        java.util.ArrayList<String> flagged = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> e : table().entrySet()) {
            String w = e.getKey();
            if (w == null || w.isBlank() || !text.contains(w)) continue;
            if (e.getValue() != null && e.getValue() >= 2) { if (hit == null) hit = w; }
            else flagged.add(w);
        }
        return new Verdict(hit, flagged);
    }

    /** 只关心"能不能写进去"的调用方用它：命中拦截档就抛。 */
    public void block(String text) {
        Verdict v = scan(text);
        if (v.rejected()) throw new BizException("内容包含敏感词，请修改后重试");
    }

    /** 后台改词后立刻失效（同进程调用；TTL 只兜住跨实例那种情况）。last-known-good 那份要留着当兜底。 */
    public void invalidate() {
        words = null;
        loadedAt = 0L;
    }

    /**
     * 取当前生效的词表，必要时重读。
     *
     * <p>读库失败时的三步（迭代 4 复查补的）：WARN、把生效表退到<b>上一次成功加载的那一本</b>、
     * 并把这次的时刻记下来（于是 TTL 之内不再反复打一张坏表）。词库故障的那段时间里注册与昵称
     * 仍然按旧词表判——这才是降级该有的样子：判断可能滞后，但不是没有判断。
     * 只有一次都没成功加载过（新进程 + 库从第一秒就是坏的）才没有旧表可用，那时维持原行为：
     * 空表放行 + WARN，因为拿一份从没存在过的词表去拒玩家昵称，等于凭空造一条拦不住任何事的规则。
     */
    private Map<String, Integer> table() {
        Map<String, Integer> cur = words;
        long now = System.currentTimeMillis();
        if (cur != null && now - loadedAt < ttlMs) return cur;
        Map<String, Integer> fresh = new LinkedHashMap<>();
        boolean loaded = true;
        try {
            for (Map<String, Object> r : mod.words()) {
                Object w = r.get("word");
                if (w == null) continue;
                Object lv = r.get("level");
                fresh.put(String.valueOf(w), lv instanceof Number n ? n.intValue() : 1);
            }
        } catch (Exception e) {
            loaded = false;
            Map<String, Integer> good = lastGood;
            log.warn("敏感词表读取失败，本轮按{}判断: {}",
                    good == null ? "空词表放行（这个进程还没成功加载过词表）" : "上一次成功加载的词表",
                    e.toString());
            // 兜底表同样按 TTL 生效：库在抖的时候，反复重读只会把请求路径拖得更久
            fresh = good == null ? Map.of() : good;
        }
        // 只有成功读上来的那一本才配当兜底：空表/兜底表都不能顶掉它，否则第一次抖动就把好词表永久丢了
        if (loaded) lastGood = fresh;
        words = fresh;
        loadedAt = now;
        return fresh;
    }

    /* 只有测试会读：确认缓存真的在生效（第二次判不该再查库）。 */
    int cachedSize() { return words == null ? -1 : words.size(); }

    /** 只有测试会读：最后一次<b>成功加载</b>的那一本有多大；从没成功过是 -1。 */
    int lastKnownGoodSize() { return lastGood == null ? -1 : lastGood.size(); }
}
