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
     * 出来（运营动作的代价）；版本号那份 1 秒缓存到期时又多一条 {@code content_version} 的读数
     * （跟链的形状无关，只是谁踩在那一秒上）。而这一层是进程内存，同一台服务端连着跑两轮回归，
     * 平均值还会把上一轮也算进来。
     *
     * <p>注意标记是<b>显式</b>的（第四个参数），不是"取最小值自然就没噪声"：
     * claim.ach 在回归里只在"后台刚改过内容"那一段被调用，所以它每一个样本都带着重建的那几条，
     * 只靠最小值会把这条链读成 7 条而其实它是 4 条——那条预算就会红在排期上而不是红在链路上。
     */
    @Test
    void minSqlIsTheWarmChainNotTheCacheNoise() {
        metrics.record("claim.ach", 5, 4);         // 温缓存：链自己就这几条
        metrics.record("claim.ach", 5, 7, true);   // 同一时刻涨了缓存计数：多的是内容重载
        metrics.record("claim.ach", 5, 5);

        Map<String, Object> row = metrics.snapshot().get(0);
        assertEquals(5.3, (Double) row.get("avgSql"), 0.001, "平均值会把那次重建摊进来：" + row);
        assertEquals(4L, row.get("minSql"), "预算该钉在温缓存那一次，而不是被重建抬高：" + row);
        assertEquals(2L, row.get("warmSqlSamples"), "干净样本两次：" + row);
        assertEquals(1L, row.get("coldSqlSamples"), "替内容缓存干过活的样本一次：" + row);
    }

    /**
     * 现场那一幕：{@code sandbox.exit} 只在脚本最后被调用一次，那一次恰好跨过版本号 TTL。
     *
     * <p>样本数 = 1 的时候"最小值"就等于那一次的运气，同一条链会在 6 与 7 之间来回跳，红的那一轮
     * 查半天其实链没变胖。标上缓存计数以后，这一次只进平均值；等一个干净样本到了，下界才是链自己。
     */
    @Test
    void aVersionPollSampleDoesNotSetTheFloor() {
        metrics.record("sandbox.exit", 8, 7, true);   // 多那一条是 SELECT version FROM content_version
        Map<String, Object> row = metrics.snapshot().get(0);
        assertNull(row.get("minSql"), "唯一样本带着 TTL 读库就不给下界：" + row);
        assertEquals(7.0, (Double) row.get("avgSql"), 0.001, "那条 SELECT 照样是真实成本，平均值该看得见");

        metrics.record("sandbox.exit", 8, 6);
        assertEquals(6L, metrics.snapshot().get(0).get("minSql"), "干净样本一到，量到的就是这条链自己");
    }

    /**
     * 预算读的是干净样本的<b>中位数</b>（{@code p50Sql}），不是最省那一次。
     *
     * <p>同一条链有好几条分支：{@code ad.request} 真签发那一次要查在途工单、清过期、插新单、写回存档（7 条），
     * 而"上一次观看还没结束"那一支只走 4 条就回一个 200。两者都是干净样本（都进了结算、都没被拒），
     * 所以最小值天然读到便宜的那一支。实测更夸张：{@code market.consumable} 的 minSql 是 1（会话缓存命中），
     * 而它最费的那条分支是 8——拿最小值当预算，往贵分支加 N+1 是量不到的。
     * 最大值又不能当预算：一次性的建档分支（{@code state} 首次 INSERT 存档 + 版本行 + 清理）天然把每个
     * 意图的上限都抬高一条，那条预算会常红。中位数两边都不骗：加一次每次都发的查询，它就跟着涨。
     */
    @Test
    void theBudgetRidesTheMedianCleanSampleNotTheCheapest() {
        metrics.record("ad.request", 6, 4);
        metrics.record("ad.request", 6, 4);          // 重复工单那一支
        metrics.record("ad.request", 9, 7);
        metrics.record("ad.request", 9, 7);
        metrics.record("ad.request", 9, 7);          // 真签发那一支：占多数
        metrics.record("ad.request", 9, 12, true);   // 替内容缓存干过活：三个数都不进

        Map<String, Object> row = metrics.snapshot().get(0);
        assertEquals(4L, row.get("minSql"), "最省的那条分支，留着给运维看形状：" + row);
        assertEquals(7L, row.get("maxCleanSql"), "最费的干净分支：一次性的建档会把它抬高，所以不当预算：" + row);
        assertEquals(7L, row.get("p50Sql"), "多数调用走贵的那条分支时，中位数就该落在 7：" + row);
        assertEquals(6.8, (Double) row.get("avgSql"), 0.001, "平均值是含脏样本的另一个口径：" + row);
    }

    /**
     * 一条意图如果<b>每个</b>样本都落在缓存窗口里，宁可没有下界也不给一个假的。
     *
     * <p>这是上一段注释里那个坑的正面：读的人必须看得见"这条没判"，而不是拿 7 当成链的形状去追因。
     */
    @Test
    void anIntentSeenOnlyThroughCacheWorkHasNoFloor() {
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

    /** 内容缓存总账的替身计数器：模拟"这一次请求里缓存替这条意图干了活"，测试自己就能拨它。 */
    private long cacheWorkCounter;

    private IntentTraceInterceptor interceptor() {
        return new IntentTraceInterceptor(metrics, () -> cacheWorkCounter);
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
     * 请求进行中内容缓存干了活（整包重攒，或版本号真读了一次库）⇒ 这一次被标成脏样本，不进预算的下界。
     *
     * <p>拦截器判的是"preHandle 读到几个、afterCompletion 读到几个"，所以哪怕这件事发生在结算中途
     * （正是真实的那一种：意图里第一次 {@code registry.current()} 才会重攒／第一次跨过 TTL），也抓得到。
     * 这里喂的是一个自己拨的计数器——{@code ContentRegistry.cacheWork()} 怎么把两种活合成一个会涨的数，
     * 另有 {@code ContentCacheWorkTest} 钉。
     */
    @Test
    void cacheWorkDuringTheRequestIsMarkedAndKeptOutOfTheFloor() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/game/claim.ach");
        IntentTraceInterceptor it = interceptor();
        cacheWorkCounter = 3;
        assertTrue(it.preHandle(req, new MockHttpServletResponse(), new Object()));
        for (int i = 0; i < 7; i++) SqlCounter.inc();
        cacheWorkCounter = 4;                      // 结算中途内容缓存替这条意图干了一次活
        it.afterCompletion(req, new MockHttpServletResponse(), new Object(), null);

        Map<String, Object> row = metrics.snapshot().get(0);
        assertEquals(1L, row.get("coldSqlSamples"), "该被标成脏样本：" + row);
        assertNull(row.get("minSql"), "只有脏样本时不给下界，别让 7 条被读成链路的形状：" + row);

        // 同一线程再来一次干净的：这次该进下界，平均值也照常合并
        MockHttpServletRequest again = new MockHttpServletRequest("POST", "/api/game/claim.ach");
        IntentTraceInterceptor it2 = interceptor();
        it2.preHandle(again, new MockHttpServletResponse(), new Object());
        for (int i = 0; i < 4; i++) SqlCounter.inc();
        it2.afterCompletion(again, new MockHttpServletResponse(), new Object(), null);
        assertEquals(4L, metrics.snapshot().get(0).get("minSql"), "温缓存那一次才是这条链自己");
    }

    /**
     * 被挡回去的那一次不许当上"最小值"。
     *
     * <p>401 令牌失效、403 宵禁、429 限流都只走到会话校验就返回，一条 SQL 而已。回归里这种请求
     * 到处都是（每一个负向断言都是一次），把它们算进下界，"单次意图 ≤6 条 SQL"就会永远读到 1 而全绿
     * ——那是比报错了更难发现的失效：尺子看着在量，其实量的是拒单。
     */
    @Test
    void aRejectedRequestDoesNotBecomeTheCheapestSample() {
        MockHttpServletRequest denied = new MockHttpServletRequest("POST", "/api/game/bench.place");
        IntentTraceInterceptor it = interceptor();
        it.preHandle(denied, new MockHttpServletResponse(), new Object());
        SqlCounter.inc();                                  // 只查了会话就回 403
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(403);
        it.afterCompletion(denied, res, new Object(), null);

        Map<String, Object> row = metrics.snapshot().get(0);
        assertEquals(1L, row.get("coldSqlSamples"), "被拒的那次该标成脏样本：" + row);
        assertNull(row.get("minSql"), "只有被拒样本时不给下界：一条会话校验不是这条链的形状");

        // 真跑通的那一次才是链：200 + 六条 SQL
        MockHttpServletRequest done = new MockHttpServletRequest("POST", "/api/game/bench.place");
        IntentTraceInterceptor it2 = interceptor();
        it2.preHandle(done, new MockHttpServletResponse(), new Object());
        for (int i = 0; i < 6; i++) SqlCounter.inc();
        it2.afterCompletion(done, new MockHttpServletResponse(), new Object(), null);
        assertEquals(6L, metrics.snapshot().get(0).get("minSql"), "200 的那次一进，下界就是这条链自己");
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
