package com.chemera.server.stats;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每条意图的耗时与 SQL 条数（G8：三个便宜指标里的第一个）。
 *
 * <p>不引 micrometer：这一层要回答的只有"哪条意图变慢了""一次点击发几条 SQL"两个问题，
 * 一个有界 map 就够，多引一套指标体系反而没人看。
 *
 * <p><b>两个有界设计是重点</b>（G7 说的"map 不上界"这个坑这里先自己填上）：
 * <ul>
 *   <li>键数上限 {@value #MAX_KEYS}：意图名来自 URL，不能让人拿任意字符串把内存撑大——
 *       超了就不再收新键，只丢统计，不丢功能；</li>
 *   <li>p50 用<b>最近 32 次</b>的样本中位数，而不是全历史：全历史要么存所有样本（无上界），
 *       要么存直方图（复杂度不值）。最近 32 次的中位数足够回答"刚才那一下是不是变慢了"。</li>
 * </ul>
 *
 * <p>计数是"每台进程各算各的"：单实例部署下就是全局值（见 G7），扩容后要先做进程聚合才有意义。
 */
@Component
public class IntentMetrics {

    static final int MAX_KEYS = 256;
    static final int RESERVOIR = 32;

    private static final class Slot {
        long count;                 // 总条数：判断"这条意图最近有没有人走"
        long maxMs;                 // 最慢一次：p50 平稳但尾巴炸了的时候，这个数会先变
        long sqlTotal;              // 累计 SQL 条数
        long sqlCount;              // 其中<b>真在计数里</b>的那几次：平均值只除以它
        long warmCount;             // 其中"没赶上内容重建"的那几次：预算只读这些
        long sqlMin = Long.MAX_VALUE;   // 最少的那次：结算链自己的条数，见 {@link #snapshot()}
        final int[] lat = new int[RESERVOIR];   // 最近 N 次的耗时（毫秒），取中位数当 p50
        int idx;                    // 环形写指针
        int filled;                 // 已写入的样本数（<N 时中位数按已有样本算）
    }

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    /** 记一次。{@code sql < 0} 表示这次没在 SQL 计数里（不该发生，按未知处理，不污染平均值）。 */
    public void record(String intent, long ms, int sql) {
        record(intent, ms, sql, false);
    }

    /**
     * 记一次，并说明这一次有没有<b>顺带重建内容快照</b>（{@code rebuilt}）。
     *
     * <p>为什么这件事要单独标出来：预算读的是 {@code minSql}，而"最小值天然滤掉缓存重建"这个前提
     * 只在<b>混合样本</b>时成立。回归里有一批意图（claim.ach 就是）只在"后台刚改过内容"那一段被调用，
     * 于是它的每一个样本都带着重建的那三条 SELECT，最小值也就是 7——那条预算于是变成在量
     * "这一轮谁先被调用"，而不是"这条链自己发几条 SQL"。它红过一次，查了半天其实链没变胖。
     * 标记一打，重建窗口的那些次只进平均值，不再参与最小值。
     *
     * <p>这条预算并没有因此变松：往结算里加的 N+1 是每次都发，温缓存的样本照样把它顶上来；
     * 而"一个干净样本都没有"会输出 {@code null}，读的人必须明说"没判"，不能拿脏值当结论。
     */
    public void record(String intent, long ms, int sql, boolean rebuilt) {
        if (intent == null || intent.isBlank()) return;
        Slot s = slots.get(intent);
        if (s == null) {
            if (slots.size() >= MAX_KEYS) return;          // 键满了就丢统计：这一层永远不该影响玩法
            s = slots.computeIfAbsent(intent, k -> new Slot());
        }
        synchronized (s) {
            s.count++;
            s.maxMs = Math.max(s.maxMs, ms);
            // sql < 0 是"这一次没在 SQL 计数里"（拦截器没起表、异步线程里跑的），
            // 它必须整个不参与平均值：当成 0 条会把优化空间读没，等于自己给自己放假。
            if (sql >= 0) {
                s.sqlTotal += sql; s.sqlCount++;
                if (!rebuilt) { s.sqlMin = Math.min(s.sqlMin, sql); s.warmCount++; }
            }
            s.lat[s.idx] = (int) Math.min(ms, Integer.MAX_VALUE);
            s.idx = (s.idx + 1) % RESERVOIR;
            if (s.filled < RESERVOIR) s.filled++;
        }
    }

    /** 给运维接口用的一份快照：按调用次数从多到少排，中位数与平均 SQL 在这里算。 */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Slot> e : slots.entrySet()) {
            Slot s = e.getValue();
            int[] copy;
            long count, maxMs, sqlTotal, sqlCount, sqlMin, warmCount;
            synchronized (s) {
                count = s.count; maxMs = s.maxMs; sqlTotal = s.sqlTotal; sqlCount = s.sqlCount;
                sqlMin = s.sqlMin; warmCount = s.warmCount;
                copy = new int[s.filled];
                System.arraycopy(s.lat, 0, copy, 0, s.filled);
            }
            if (count == 0) continue;
            java.util.Arrays.sort(copy);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intent", e.getKey());
            m.put("count", count);
            m.put("p50Ms", median(copy));
            m.put("maxMs", maxMs);
            m.put("avgSql", sqlCount == 0 ? 0 : Math.round((double) sqlTotal / sqlCount * 10) / 10.0);
            // minSql = 结算链自己的条数：只取"没赶上内容重建"的那些次的下界。
            //   avgSql 里混着两种不该相减的东西：① 后台改一次内容就 publishContent() 失效缓存，
            //   紧接着的那条意图要多跑 content_item + app_config 几条 SELECT 把包重攒出来（运营动作
            //   的代价，不是结算链的）；② 这一层是进程内存，同一台服务端连着跑两轮回归，上一轮的
            //   样本还会摊进平均值。以前靠"取最小值天然滤掉重建"，可它对"整条意图只在重建窗口被调用"
            //   没辙——见 record(...) 的说明；现在改成显式标记，minSql 只吃干净样本。
            m.put("minSql", warmCount == 0 ? null : sqlMin);
            m.put("warmSqlSamples", warmCount);
            m.put("rebuildSqlSamples", sqlCount - warmCount);
            out.add(m);
        }
        out.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));
        return out;
    }

    /** 只有测试会读：这条意图一共记了多少次。 */
    public long countOf(String intent) {
        Slot s = slots.get(intent);
        return s == null ? 0 : s.count;
    }

    /** 测试用：把累计清空，好让断言不受用例顺序影响。 */
    public void reset() { slots.clear(); }

    private static long median(int[] sorted) {
        if (sorted.length == 0) return 0;
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }
}
