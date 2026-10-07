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
        long warmCount;             // "链的正常形状"的那几次（没替缓存干活、也没被拒）：预算只读这些
        long sqlMin = Long.MAX_VALUE;   // 最省的那次：多半是提前返回的分支，见 {@link #snapshot()}
        long sqlMax;                // 最费的那次：一次性的建档分支会把它顶高，给人看不给预算用
        final int[] sql = new int[RESERVOIR];   // 最近 N 次<b>干净样本</b>的条数，取中位数当预算
        int sqlIdx;
        int sqlFilled;
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
     * 记一次，并说明这一次<b>不在链的正常形状上</b>（{@code offChain}）：要么顺带替内容缓存干了活
     * （整包重建，或者越过 1 秒 TTL 真去读了一次 {@code content_version}），要么根本没进结算
     * （401 令牌失效、403 宵禁、429 限流、5xx——走到会话校验就返回了）。
     *
     * <p>为什么这件事要单独标出来：预算读的是 {@code minSql}，而"最小值天然滤掉缓存开销"这个前提
     * 只在<b>混合样本</b>时成立。三种样本都会把它带歪：
     * <ul>
     *   <li>重建：回归里有一批意图（claim.ach 就是）只在"后台刚改过内容"那一段被调用，于是它的
     *       每一个样本都带着重建的那三条 SELECT，最小值也就是 7——那条预算于是变成在量
     *       "这一轮谁先被调用"，而不是"这条链自己发几条 SQL"；</li>
     *   <li>版本号轮询：{@code ContentService} 的版本号读数有 1 秒 TTL，跨过边界那一次多一条
     *       {@code SELECT version FROM content_version}。它跟"谁先被调用"没关系，纯粹是谁刚好
     *       踩在那一秒上——{@code sandbox.exit} 就这么在 6 条与 7 条之间来回跳过，而它的样本数只有 1，
     *       最小值等于那一次的运气；</li>
     *   <li>被拒掉的那一次：401/403/429 只走到会话校验就返回。这一种最阴——它不会把预算读高，
     *       只会把最小值读成 1，于是那条预算<b>永远量不到东西</b>。尺子空转比尺子严更害人。</li>
     * </ul>
     * 标记一打，这些次只进平均值，不再参与 {@code p50Sql} / {@code minSql} / {@code maxCleanSql}。
     *
     * <p>这条预算并没有因此变松：往结算里加的 N+1 是每次都发，干净样本的中位数照样跟着涨；
     * 而"一个干净样本都没有"会输出 {@code null}，读的人必须明说"没判"，不能拿脏值当结论。
     */
    public void record(String intent, long ms, int sql, boolean offChain) {
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
                if (!offChain) {
                    s.sqlMin = Math.min(s.sqlMin, sql);
                    s.sqlMax = Math.max(s.sqlMax, sql);
                    s.sql[s.sqlIdx] = sql;
                    s.sqlIdx = (s.sqlIdx + 1) % RESERVOIR;
                    if (s.sqlFilled < RESERVOIR) s.sqlFilled++;
                    s.warmCount++;
                }
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
            int[] sqlCopy;
            long count, maxMs, sqlTotal, sqlCount, sqlMin, sqlMax, warmCount;
            int sqlFilled;
            synchronized (s) {
                count = s.count; maxMs = s.maxMs; sqlTotal = s.sqlTotal; sqlCount = s.sqlCount;
                sqlMin = s.sqlMin; sqlMax = s.sqlMax; warmCount = s.warmCount;
                sqlFilled = s.sqlFilled;
                copy = new int[s.filled];
                System.arraycopy(s.lat, 0, copy, 0, s.filled);
                sqlCopy = new int[sqlFilled];
                System.arraycopy(s.sql, 0, sqlCopy, 0, sqlFilled);
            }
            if (count == 0) continue;
            java.util.Arrays.sort(copy);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intent", e.getKey());
            m.put("count", count);
            m.put("p50Ms", median(copy));
            m.put("maxMs", maxMs);
            m.put("avgSql", sqlCount == 0 ? 0 : Math.round((double) sqlTotal / sqlCount * 10) / 10.0);
            // 三个 SQL 口径各有分工，别混着用（都只认干净样本，脏样本的定义见 record(...)）：
            //   p50Sql   —— <b>预算钉在这个数上</b>。最近 32 次干净样本的中位数：往这条链上加一次
            //              每次都要发的查询，中位数一定跟着涨；而它不会被"这一次恰好走了便宜分支"骗。
            //   minSql   —— 最省的那条分支。同一条链有好几条出口：ad.request 真签发那一次 7 条，
            //              "上一次观看还没结束"那一支 4 条；state 第一次建档要 INSERT 存档 + 版本行 +
            //              清理，之后命中会话缓存只要 1 条。拿最小值当预算，实测会把 market.consumable
            //              读成 1 而它最费的分支是 8——尺子看着在量，其实量的是最便宜的分支。
            //   maxCleanSql —— 最费的那条分支，给人看的上限。它天然偏高（一次性的建档、补存档的自愈
            //              分支都算数），所以不当预算，但运维扫一眼就知道最坏能坏到哪。
            //   avgSql   —— 含脏样本的总成本口径（重建、版本号读库、被拒的那几次都在里面），只给趋势。
            //   全是脏样本的意图这三个数都给 null——读的人必须明说"这条没判"，不能拿脏值当结论。
            m.put("minSql", warmCount == 0 ? null : sqlMin);
            m.put("maxCleanSql", warmCount == 0 ? null : sqlMax);
            java.util.Arrays.sort(sqlCopy);
            m.put("p50Sql", sqlFilled == 0 ? null : median(sqlCopy));
            m.put("warmSqlSamples", warmCount);
            m.put("coldSqlSamples", sqlCount - warmCount);
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
