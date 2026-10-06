package com.chemera.server.stats;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 意图耗时与 SQL 条数（G8：三个便宜指标里最值钱的两个）。
 *
 * <p>为什么值得单独一层回归：这一层是给"以后谁加查询"看的。{@code docs/upgrade-plan.md} 里那句
 * "单次意图 ≤6 条 SQL"要靠这里的 {@code avgSql} 才能在真库上读出来；而它有两个天然风险——
 * <b>键无上界</b>（意图名来自 URL，随便发一个 {@code /api/game/aaaaaaaa...} 就能往里塞键）和
 * <b>ThreadLocal 漏清理</b>（Tomcat 复用线程，漏一次，下一个人就把上一个人的条数加到自己头上）。
 * 两条都在下面钉住。
 *
 * <p>{@link IntentMetrics} 与 {@link SqlCounter} 都是纯内存件，不需要 DB；拦截器用 spring-test 的
 * mock 请求驱动，判的是"begin/end 的配对与统计口径"。
 */
class IntentTraceTest {

    private final IntentMetrics metrics = new IntentMetrics();

    @BeforeEach
    @AfterEach
    void clean() {
        metrics.reset();
        SqlCounter.end();          // 别把上一个用例的计数带进下一个（线程是真的在复用）
    }

    /* ---------------- 1. 口径：count / p50 / max / avgSql ---------------- */

    @Test
    void countsMedianMaxAndAverageSql() {
        for (int ms : new int[]{10, 30, 20}) metrics.record("react", ms, 4);
        metrics.record("react", 900, 6);

        List<Map<String, Object>> snap = metrics.snapshot();
        assertEquals(1, snap.size());
        Map<String, Object> row = snap.get(0);
        assertEquals("react", row.get("intent"));
        assertEquals(4L, row.get("count"));
        assertEquals(900L, row.get("maxMs"));
        // 三个 4 加一个 6 = 18/4 = 4.5：平均值给一位小数，够看趋势，不至于让人误读成精确数
        assertEquals(4.5, (Double) row.get("avgSql"), 0.001);
        // p50 取最近样本的中位数：{10,20,30,900} 的中位数是 (20+30)/2 = 25，而不是被 900 拽走
        assertEquals(25L, row.get("p50Ms"), "中位数才回答'刚才那一下通常多久'，均值会被长尾骗：" + row);
    }

    /** 没在 SQL 计数里的那些次（sql&lt;0）不该把平均值算歪。 */
    @Test
    void anUncountedSampleLeavesTheSqlAverageAlone() {
        metrics.record("state", 5, 3);
        metrics.record("state", 5, -1);           // 拦截器没 begin 成功，条数未知

        Map<String, Object> row = metrics.snapshot().get(0);
        assertEquals(2L, row.get("count"));
        assertEquals(3.0, (Double) row.get("avgSql"), 0.001, "未知不等于 0 条，把它当 0 会把优化空间读没");
    }

    /**
     * minSql：结算链自己的条数。
     *
     * <p>e2e 那条"单次意图 ≤6 条 SQL"的预算读的是这个值而不是 {@code avgSql}，因为平均值里混着
     * 两种不该算进链路的噪声：内容缓存刚被后台改动失效，紧接着那条意图要多跑几条 SELECT 把包重攒
     * 出来（运营动作的代价）；而这一层是进程内存，同一台服务端连着跑两轮回归，平均值还会把上一轮
     * 也算进来。
     *
     * <p>注意标记是<b>显式</b>的（第四个参数），不是"取最小值自然就没噪声"：
     * claim.ach 在回归里只在"后台刚改过内容"那一段被调用，所以它每一个样本都带着重建的那几条，
     * 只靠最小值会把这条链读成 7 条而其实它是 4 条——那条预算就会红在排期上而不是红在链路上。
     */
    @Test
    void minSqlIsTheWarmChainNotTheRebuildNoise() {
        metrics.record("claim.ach", 5, 4);         // 温缓存：链自己就这几条
        metrics.record("claim.ach", 5, 7, true);   // 同一时刻涨了重建计数：多的是内容重载
        metrics.record("claim.ach", 5, 5);

        Map<String, Object> row = metrics.snapshot().get(0);
        assertEquals(5.3, (Double) row.get("avgSql"), 0.001, "平均值会把那次重建摊进来：" + row);
        assertEquals(4L, row.get("minSql"), "预算该钉在温缓存那一次，而不是被重建抬高：" + row);
        assertEquals(2L, row.get("warmSqlSamples"), "干净样本两次：" + row);
        assertEquals(1L, row.get("rebuildSqlSamples"), "带重建的样本一次：" + row);
    }

    /**
     * 一条意图如果<b>每个</b>样本都落在重建窗口里，宁可没有下界也不给一个假的。
     *
     * <p>这是上一段注释里那个坑的正面：读的人必须看得见"这条没判"，而不是拿 7 当成链的形状去追因。
     */
    @Test
    void anIntentSeenOnlyThroughARebuildHasNoFloor() {
        metrics.record("cold.only", 5, 7, true);
        metrics.record("cold.only", 5, 8, true);

        Map<String, Object> row = metrics.snapshot().get(0);
        assertNull(row.get("minSql"), "全是重建样本就不该编一个最小值出来：" + row);
        assertEquals(0L, row.get("warmSqlSamples"));
        assertEquals(7.5, (Double) row.get("avgSql"), 0.001, "平均值照样给：那是运营动作的成本，运维有权看见");

        metrics.record("cold.only", 5, 4);         // 终于来了一次温缓存
        assertEquals(4L, metrics.snapshot().get(0).get("minSql"), "来一个干净样本就立刻有下界");
    }

    /** 没计数的那次（sql&lt;0）既不能当 0 条，也不能把最小值压成 0。 */
    @Test
    void anUncountedSampleDoesNotFabricateAZeroFloor() {
        metrics.record("state", 5, 4);
        metrics.record("state", 5, -1);

        Map<String, Object> row = metrics.snapshot().get(0);
        assertEquals(4L, row.get("minSql"), "未知不等于 0 条：" + row);
    }

    /** 快照按调用次数降序：运维打开那一页最先看到的就该是最热的那条意图。 */
    @Test
    void snapshotIsOrderedByTraffic() {
        metrics.record("cold", 1, 1);
        for (int i = 0; i < 5; i++) metrics.record("hot", 1, 2);
        metrics.record("middle", 1, 2);
        metrics.record("middle", 1, 2);

        List<Map<String, Object>> snap = metrics.snapshot();
        assertEquals("hot", snap.get(0).get("intent"));
        assertEquals("middle", snap.get(1).get("intent"));
        assertEquals("cold", snap.get(2).get("intent"));
    }

    /* ---------------- 2. 两个有界设计 ---------------- */

    /**
     * p50 的样本池只留最近 32 次。
     *
     * <p>全历史要么存所有样本（无上界，迟早 OOM），要么存直方图（复杂度不值）。
     * 这一条钉的是"上界真的存在"：灌 200 次慢样本，中位数只会跟着最近 32 次走。
     */
    @Test
    void theReservoirKeepsOnlyTheLastFewSamples() {
        for (int i = 0; i < 40; i++) metrics.record("react", 1, 1);      // 环形池里只剩最后 32 个 1ms
        metrics.record("react", 8000, 9);                                // 一次长尾：该在 maxMs 上露出来
        Map<String, Object> row = metrics.snapshot().get(0);
        assertEquals(41L, row.get("count"));
        assertEquals(8000L, row.get("maxMs"));
        assertTrue(((Long) row.get("p50Ms")) <= 3L, "单点慢查询该在 maxMs 里露出来，不该把 p50 顶飞：" + row);
    }

    /**
     * 键数有上界：意图名来自 URL，不能让人拿任意字符串把内存撑大。
     * 超了只丢统计，<b>不丢功能</b>——这条是这一层存在的伦理前提。
     */
    @Test
    void newKeysAreDroppedOnceTheTableIsFull() {
        for (int i = 0; i < IntentMetrics.MAX_KEYS; i++) metrics.record("intent" + i, 1, 1);
        assertEquals(IntentMetrics.MAX_KEYS, metrics.snapshot().size());

        metrics.record("someone-else-keeps-posting", 5, 5);
        assertEquals(IntentMetrics.MAX_KEYS, metrics.snapshot().size(), "满了就不再收新键");
        assertEquals(0, metrics.countOf("someone-else-keeps-posting"));
        assertEquals(1L, metrics.countOf("intent0"), "已有的键不受影响");
    }

    @Test
    void aBlankIntentIsIgnored() {
        metrics.record(null, 1, 1);
        metrics.record("  ", 1, 1);
        assertTrue(metrics.snapshot().isEmpty());
    }

    /* ---------------- 3. 拦截器：begin/end 必须配对 ---------------- */

    /** 重建计数器：模拟"这一次请求里注册表重攒了包"，测试自己就能拨它。 */
    private long rebuildCounter;

    private IntentTraceInterceptor interceptor() {
        return new IntentTraceInterceptor(metrics, () -> rebuildCounter);
    }

    /** 一次真实形状：preHandle 起表，中途跑三条 SQL，afterCompletion 结算并清线程。 */
    @Test
    void oneIntentIsRecordedWithItsSqlCount() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/game/react");
        IntentTraceInterceptor it = interceptor();
        assertTrue(it.preHandle(req, new MockHttpServletResponse(), new Object()));

        SqlCounter.inc();
        SqlCounter.inc();
        SqlCounter.inc();
        it.afterCompletion(req, new MockHttpServletResponse(), new Object(), null);

        assertEquals(1L, metrics.countOf("react"), "键名取 /api/game/ 后面那一段，别把前缀也当成意图名");
        Map<String, Object> row = metrics.snapshot().get(0);
        assertEquals(3.0, (Double) row.get("avgSql"), 0.001);
        assertEquals(-1, SqlCounter.snapshot(), "线程要还给容器了，计数必须已经清空");
    }

    /**
     * 请求进行中内容快照涨了 ⇒ 这一次被标成"带着重建"，不进预算的下界。
     *
     * <p>拦截器判的是"preHandle 读到几个、afterCompletion 读到几个"，所以哪怕重建发生在结算中途
     * （正是真实的那一种：意图里第一次 {@code registry.current()} 才会重建），也抓得到。
     */
    @Test
    void aRebuildDuringTheRequestIsMarkedAndKeptOutOfTheFloor() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/game/claim.ach");
        IntentTraceInterceptor it = interceptor();
        rebuildCounter = 3;
        assertTrue(it.preHandle(req, new MockHttpServletResponse(), new Object()));
        for (int i = 0; i < 7; i++) SqlCounter.inc();
        rebuildCounter = 4;                      // 结算中途注册表重攒了包
        it.afterCompletion(req, new MockHttpServletResponse(), new Object(), null);

        Map<String, Object> row = metrics.snapshot().get(0);
        assertEquals(1L, row.get("rebuildSqlSamples"), "该被标成重建窗口：" + row);
        assertNull(row.get("minSql"), "只有重建样本时不给下界，别让 7 条被读成链路的形状：" + row);

        // 同一线程再来一次干净的：这次该进下界，平均值也照常合并
        MockHttpServletRequest again = new MockHttpServletRequest("POST", "/api/game/claim.ach");
        IntentTraceInterceptor it2 = interceptor();
        it2.preHandle(again, new MockHttpServletResponse(), new Object());
        for (int i = 0; i < 4; i++) SqlCounter.inc();
        it2.afterCompletion(again, new MockHttpServletResponse(), new Object(), null);
        assertEquals(4L, metrics.snapshot().get(0).get("minSql"), "温缓存那一次才是这条链自己");
    }

    /**
     * 抛异常也要清。
     *
     * <p>Tomcat 会复用线程：{@code afterCompletion} 是唯一保证会跑的那一步，而 {@code end()}
     * 必须在 {@code finally} 里——漏一次清理，下一个请求就会把上一个人的条数加到自己头上，
     * 那种数字比没有数字更害人。
     */
    @Test
    void theThreadLocalIsClearedEvenWhenTheRequestBlowsUp() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/game/bench.place");
        IntentTraceInterceptor it = interceptor();
        it.preHandle(req, new MockHttpServletResponse(), new Object());
        SqlCounter.inc();
        it.afterCompletion(req, new MockHttpServletResponse(), new Object(), new IllegalStateException("boom"));

        assertEquals(-1, SqlCounter.snapshot());
        assertEquals(1L, metrics.countOf("bench.place"));
    }

    /** 非意图路径不进这张表：静态资源、登录页、后台列表各有自己的成本结构，混进来只会看不出问题。 */
    @Test
    void onlyIntentPathsAreMeasured() {
        for (String uri : new String[]{"/api/healthz", "/admin/api/users", "/api/game", "/", "/static/app.js"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
            IntentTraceInterceptor it = interceptor();
            assertTrue(it.preHandle(req, new MockHttpServletResponse(), new Object()), uri + " 该放行");
            SqlCounter.inc();
            it.afterCompletion(req, new MockHttpServletResponse(), new Object(), null);
        }
        assertTrue(metrics.snapshot().isEmpty(), "一条都不该记：" + metrics.snapshot());
        assertEquals(-1, SqlCounter.snapshot(), "没起表就不该留下计数");
    }

    /** 拦截器之外（比如后台那条链）{@code inc()} 只能是空操作，不能攒出一个看不见的上界。 */
    @Test
    void countingOutsideARequestIsANoOp() {
        SqlCounter.inc();
        SqlCounter.inc();
        assertEquals(-1, SqlCounter.snapshot());
    }
}
