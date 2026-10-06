package com.chemera.server.game;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 成就判定数据化（G4）的回归。
 *
 * <p>三件事必须钉住：
 * ① 描述符求值与改造前那 19 路 switch <b>逐条等价</b>（白名单是把老判定翻成数据，不是重写一遍）；
 * ② 行里写了 cond 就以行为准——运营把阈值从 100 改成 5，判定必须当场跟着变，这是本轮唯一的目的；
 * ③ 念不出来的 metric 一律判未达成，且 {@code problem} 必须当场说原因（"静默永不达成"就是要关掉的那个洞）。
 */
class AchievementRuleTest {

    private final GameEngine e = new GameEngine();

    private static ContentRegistry.Snapshot snap() throws Exception {
        return Snapshots.base();
    }

    private static ContentRegistry.Snapshot snapWith(String cfgKey, Object value) throws Exception {
        return Snapshots.with(cfgKey, value);
    }

    private static Content.AchCond cond(String metric, String subject, String op, Double value) {
        return new Content.AchCond(metric, subject, op, value);
    }

    private static Content.AchievementDef ach(String id, Content.AchCond c) {
        return new Content.AchievementDef(id, "测试成就", "desc", 100L, c);
    }

    /* ---------------- ① 白名单与老 switch 等价 ---------------- */

    /**
     * 白名单形状：正好是那 19 条老 id。多一条＝老判定被悄悄换掉，少一条＝某条成就在没跑到 V12 的库上永远判不达成。
     */
    @Test
    void legacyWhitelistCoversExactlyTheNineteenOriginalAchs() {
        assertEquals(19, AchievementRule.legacyIds().size());
        for (String id : List.of("aFirst", "aWater", "aGold", "aBoom", "aS100", "aD20", "aD80", "aD200",
                "aEq30", "aLv10", "aLv20", "aRich", "aOrganic", "aAqua", "aQuiz50", "aSnake", "aRep",
                "aChallenge", "aSandbox"))
            assertNotNull(AchievementRule.legacy(id), "老成就 " + id + " 的过渡判定丢了");
        assertTrue(AchievementRule.legacyIds().containsAll(
                List.of("aFirst", "aSnake", "aSandbox")), "白名单与老 id 集不符");
    }

    /** 每条白名单描述符都必须是引擎可解释的，否则"兜底"本身就是坏的。 */
    @Test
    void everyLegacyDescriptorIsExplainable() {
        for (String id : AchievementRule.legacyIds())
            assertNull(AchievementRule.problem(id, AchievementRule.legacy(id)), id + " 的过渡条件解释不出来");
    }

    /**
     * 逐条对照改造前 switch 的语义（阈值、比较方向、成员式指标）。
     * 这里刻意不用反射或快照比对，而是把"当年那 19 行 Java 在什么输入下为真"手写一遍：
     * 只有把老行为独立表达出来，才能证明新解释器与它等价。
     */
    @Test
    void legacyDescriptorsMatchTheOriginalSwitchSemantics() {
        // 边界值各测一次：恰好达到 / 差一点
        assertTrue(done("aFirst", g -> g.stats.success = 1));
        assertFalse(done("aFirst", g -> g.stats.success = 0));
        assertTrue(done("aBoom", g -> g.stats.boom = 1));
        assertFalse(done("aBoom", g -> g.stats.boom = 0));
        assertTrue(done("aS100", g -> g.stats.success = 100));
        assertFalse(done("aS100", g -> g.stats.success = 99));
        assertTrue(done("aD20", g -> { for (int i = 0; i < 20; i++) g.discovered.put("D" + i, new GameState.Discover()); }));
        assertFalse(done("aD80", g -> { for (int i = 0; i < 79; i++) g.discovered.put("D" + i, new GameState.Discover()); }));
        assertTrue(done("aD200", g -> { for (int i = 0; i < 200; i++) g.discovered.put("D" + i, new GameState.Discover()); }));
        assertTrue(done("aEq30", g -> { for (int i = 0; i < 30; i++) g.reactionsKnown.put("R" + i, true); }));
        assertFalse(done("aEq30", g -> { for (int i = 0; i < 29; i++) g.reactionsKnown.put("R" + i, true); }));
        assertTrue(done("aLv10", g -> g.level = 10));
        assertFalse(done("aLv20", g -> g.level = 19));
        assertTrue(done("aRich", g -> g.coins = 50_000));
        assertFalse(done("aRich", g -> g.coins = 49_999));
        assertTrue(done("aRep", g -> g.rep = 50));
        assertFalse(done("aRep", g -> g.rep = 49));
        assertTrue(done("aChallenge", g -> g.stats.challenges = 1));
        assertTrue(done("aSandbox", g -> g.stats.sandbox = 5));
        assertFalse(done("aSandbox", g -> g.stats.sandbox = 4));
        assertTrue(done("aQuiz50", g -> g.stats.quizOk = 50));
        // 成员式：物质在不在图鉴 / 方程式是否掌握（reactionsKnown 里存 false 不算掌握）
        assertTrue(done("aWater", g -> g.discovered.put("H2O", new GameState.Discover())));
        assertFalse(done("aWater", g -> g.discovered.put("CO2", new GameState.Discover())));
        assertTrue(done("aGold", g -> g.discovered.put("Au", new GameState.Discover())));
        assertTrue(done("aOrganic", g -> g.discovered.put("CH3COOC2H5", new GameState.Discover())));
        assertTrue(done("aAqua", g -> g.discovered.put("aqua_regia", new GameState.Discover())));
        assertTrue(done("aSnake", g -> g.reactionsKnown.put("R141", true)));
        assertFalse(done("aSnake", g -> g.reactionsKnown.put("R141", false)));
    }

    private boolean done(String id, java.util.function.Consumer<GameState> setup) {
        GameState g = new GameState();
        setup.accept(g);
        return AchievementRule.done(g, AchievementRule.legacy(id));
    }

    /* ---------------- ② cond 生效：改阈值 ⇒ 判定跟着变 ---------------- */

    @Test
    void rowCondOverridesTheLegacyThreshold() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = new GameState();
        g.stats.success = 50;                       // 老aS100：50 次不达成
        assertFalse(e.achDone(g, s.achievement("aS100")), "默认阈值 100 下不该达成");

        Content.AchievementDef lowered = ach("aS100", cond("success", null, "ge", 20.0));
        assertTrue(e.achDone(g, lowered), "把阈值降到 20 后当场就该达成");

        Content.AchievementDef raised = ach("aS100", cond("success", null, "ge", 100.0));
        assertFalse(e.achDone(g, raised), "写回的 100 仍以行为准，不是走白名单");
    }

    /** 比较符四种都可用；写不认识的按未达成（并且 problem 会点名）。 */
    @Test
    void operatorsBehindTheClosedSet() {
        GameState g = new GameState();
        g.stats.success = 10;
        assertTrue(AchievementRule.done(g, cond("success", null, "ge", 10.0)));
        assertFalse(AchievementRule.done(g, cond("success", null, "gt", 10.0)));
        assertTrue(AchievementRule.done(g, cond("success", null, "le", 10.0)));
        assertTrue(AchievementRule.done(g, cond("success", null, "eq", 10.0)));
        assertFalse(AchievementRule.done(g, cond("success", null, "eq", 11.0)));
        assertTrue(AchievementRule.done(g, cond("success", null, null, 10.0)), "省略 op＝达到");
        assertFalse(AchievementRule.done(g, cond("success", null, "约等于", 10.0)), "非法比较符判未达成");
        assertNotNull(AchievementRule.problem("x1", cond("success", null, "约等于", 10.0)));
    }

    /** 数值式没给 value、成员式没给 subject：都是"看起来配了其实没配"，必须报原因。 */
    @Test
    void incompleteDescriptorsAreNamed() {
        assertNotNull(AchievementRule.problem("x2", cond("coins", null, "ge", null)));
        assertNotNull(AchievementRule.problem("x3", cond("discoveredSubstance", null, null, null)));
        assertNull(AchievementRule.problem("x4", cond("discoveredSubstance", "H2O", null, null)));
    }

    /** 引擎念不出来的指标：判定为假，而且必须当场给运营一句能照着改的话。 */
    @Test
    void unknownMetricIsFalseAndExplained() {
        GameState g = new GameState();
        g.stats.success = 999;
        assertFalse(AchievementRule.done(g, cond("连续签到天数", null, "ge", 7.0)));
        String p = AchievementRule.problem("x5", cond("连续签到天数", null, "ge", 7.0));
        assertNotNull(p);
        assertTrue(p.contains("连续签到天数"), "报错要把念不出来的名字带出来：" + p);

        // 新行不写 cond 也不行：这正是改造前"面板能配、引擎判不出的"那个洞
        String q = AchievementRule.problem("aBrandNew", null);
        assertNotNull(q);
        assertTrue(q.contains("aBrandNew") && q.contains("永远判它未达成"), q);
        assertNull(AchievementRule.problem("aFirst", null), "老 19 行走白名单，不该被拦");
    }

    /** 词表与 ContentSchema 的下拉必须是同一份（防止"面板能选、引擎读不出"）。 */
    @Test
    void metricVocabularyIsTheSingleSourceForTheAdminForm() {
        assertEquals(ContentSchema.ACH_METRIC, AchievementRule.Metric.names());
        for (String name : ContentSchema.ACH_METRIC)
            assertNotNull(AchievementRule.Metric.of(name), "下拉里有引擎不认识的指标: " + name);
        assertEquals(ContentSchema.ACH_OP,
                java.util.Arrays.stream(AchievementRule.Op.values()).map(Enum::name).toList());
    }

    /** 今日计数器与每日任务同源：计数器名必须落在 ContentSchema.TASK_KEYS 里。 */
    @Test
    void todayMetricsReadTheSameCountersAsDailyTasks() {
        for (String key : ContentSchema.TASK_KEYS) {
            GameState g = new GameState();
            g.daily.counters.put(key, 3);
            String metric = switch (key) {
                case "success" -> "successToday";
                case "discover" -> "discoverToday";
                case "trade" -> "tradeToday";
                case "quiz" -> "quizToday";
                default -> null;
            };
            assertNotNull(metric);
            assertTrue(AchievementRule.done(g, cond(metric, null, "ge", 3.0)), key + " 的今日指标没读到计数器");
        }
    }

    /* ---------------- ③ 成就行走真实 bundle：V12 之前的快照也必须全部判得动 ---------------- */

    /**
     * 内嵌 bundle（测试夹具）里那 19 行没有 cond，走白名单；任何一行被 {@code problem} 认定为
     * "解释不出来"都意味着线上库会在 strict 写入或体检时炸，所以这里当回归用。
     */
    @Test
    void everyShippedAchievementIsExplainable() throws Exception {
        ContentRegistry.Snapshot s = snap();
        int n = 0;
        for (Content.AchievementDef a : s.achievements) {
            n++;
            assertNull(AchievementRule.problem(a.id(), a.cond()), a.id() + "： " + AchievementRule.problem(a.id(), a.cond()));
            assertNotNull(AchievementRule.legacy(a.id()), "夹具里的成就应当都是老 19 条（新行需自带 cond）");
        }
        assertEquals(19, n, "夹具成就行数变了，白名单需要同步核对");
    }

    /**
     * 成就的 reward 与 zh 仍然读行本身：数据化的是判定，不是奖励数额。
     * 这条顺手钉住 cond 不会把老字段挤掉（Jackson 对未知字段忽略，对新增字段要求形状对得上）。
     */
    @Test
    void rewardStillComesFromTheRow() throws Exception {
        ContentRegistry.Snapshot s = snap();
        Content.AchievementDef a = s.achievement("aS100");
        assertEquals(3000L, a.rewardOr(), "老成就的奖励金额变了，说明 bundle 或解析被动过");
        assertEquals("百炼成钢", a.zh());
        assertNull(a.cond(), "夹具仍是 V12 之前的形状：判定靠白名单兜底");
    }

    /* ---------------- 配置兜底：删键必须回到外提前那一档 ---------------- */

    @Test
    void quizGradeFallbackIsThePreExtractionTable() throws Exception {
        Content.Config without = snapWith("quiz_grade_mult", null).config;
        assertEquals(1.0, without.quizGradeMult("初中"), 1e-9);
        assertEquals(1.2, without.quizGradeMult("高中"), 1e-9);
        assertEquals(1.5, without.quizGradeMult("大学"), 1e-9);
        // 键在但少了某档＝运营主动取消该档加成，不能被默认值盖回去
        Content.Config partial = snapWith("quiz_grade_mult", Map.of("高中", 2.0)).config;
        assertEquals(2.0, partial.quizGradeMult("高中"), 1e-9);
        assertEquals(1.0, partial.quizGradeMult("大学"), 1e-9);
    }
}
