package com.chemera.server.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GameEngine（engine.js 移植）与服务端权威结算的回归。
 * 场景对齐 test/smoke.js 的反应引擎部分；随机源固定为 0.999（不触发事故/降产/滴管/危险扣费），以获得确定性最佳路径。
 */
class GameEngineTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static volatile ContentRegistry.Snapshot SNAP;

    private final GameEngine e = new GameEngine();
    private final DoubleSupplier rng = () -> 0.999;   // 一切 Math.random() 判定都不命中的"最顺利"路径

    @SuppressWarnings("unchecked")
    private static ContentRegistry.Snapshot snap() throws Exception {
        if (SNAP != null) return SNAP;
        Map<String, Object> bundle;
        try (InputStream in = GameEngineTest.class.getResourceAsStream("/content-bundle.json")) {
            assertNotNull(in, "content-bundle.json 缺失");
            bundle = OM.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
        long v = ((Number) bundle.getOrDefault("version", 0)).longValue();
        SNAP = new ContentRegistry(null, OM).build(v,
                (Map<String, Object>) bundle.getOrDefault("content", Map.of()),
                (Map<String, Object>) bundle.getOrDefault("config", Map.of()));
        return SNAP;
    }

    private GameState fresh() { return GameState.fresh(System.currentTimeMillis()); }
    private EngineCtx ctx() { return new EngineCtx(); }

    private void give(GameState g, ContentRegistry.Snapshot s, String id, int n) {
        e.addItem(g, s, id, n, 0, true);
    }

    /* 1. H2+O2 点燃 → H2O */
    @Test
    void synthesisWater() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh(); EngineCtx c = ctx();
        e.setTemp(g, c, "ignite");
        assertTrue(e.place(g, s, c, rng, "H2", 2).ok);
        assertTrue(e.place(g, s, c, rng, "O2", 1).ok);
        GameEngine.Result r = e.react(g, s, c, rng);
        assertTrue("success".equals(r.kind) || "partial".equals(r.kind), "kind=" + r.kind);
        assertTrue(r.produced.getOrDefault("H2O", 0) >= 1, "生成水");
        assertTrue(e.countAll(g, "H2O") >= 1, "水入背包");
        assertFalse(g.reactionsKnown.isEmpty(), "方程式已解锁");
        assertEquals("R008", r.reaction.id());
    }

    /* 2. Zn + HCl 置换 */
    @Test
    void displacement() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh(); EngineCtx c = ctx();
        assertTrue(e.place(g, s, c, rng, "Zn", 1).ok);
        assertTrue(e.place(g, s, c, rng, "HCl", 2).ok);
        GameEngine.Result r = e.react(g, s, c, rng);
        assertTrue(r.produced.getOrDefault("ZnCl2", 0) + r.produced.getOrDefault("H2", 0) > 0, "置换成功");
        assertEquals("R003", r.reaction.id());
    }

    /* 3. 无匹配组合 → 失败结算 */
    @Test
    void noMatchFails() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh(); EngineCtx c = ctx();
        give(g, s, "Au", 2); give(g, s, "KCl", 2);
        assertTrue(e.place(g, s, c, rng, "Au", 1).ok);
        assertTrue(e.place(g, s, c, rng, "KCl", 1).ok);
        GameEngine.Result r = e.react(g, s, c, rng);
        assertTrue("boom".equals(r.kind) || "fail-cond".equals(r.kind), "给出失败结算: " + r.kind);
    }

    /* 4. 真实模式条件判定 */
    @Test
    void realModeConditions() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh(); EngineCtx c = ctx();
        g.realMode = true;
        e.place(g, s, c, rng, "H2", 2); e.place(g, s, c, rng, "O2", 1);
        assertEquals(0, e.matches(g, s, c).size(), "真实模式室温 H2+O2 不匹配");
        e.setTemp(g, c, "ignite");
        assertTrue(e.matches(g, s, c).size() >= 1, "点燃后匹配");
    }

    /* 6. 市场池随等级扩大 */
    @Test
    void marketPoolGrows() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh();
        assertTrue(e.marketPool(g, s).size() > 10);
        int a = e.marketPool(g, s).size();
        g.level = 16;
        assertTrue(e.marketPool(g, s).size() > a, "升级后市场池扩大");
    }

    /* 7. 多工作台状态独立 */
    @Test
    void benchIndependence() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh(); EngineCtx c = ctx();
        g.rooms = List.of("inorganic", "analysis");
        e.ensureBenches(g);
        assertEquals(2, g.benchStates.size());
        assertTrue(e.goBench(g, c, 1));
        give(g, s, "NaCl", 4);
        e.place(g, s, c, rng, "NaCl", 2);
        assertEquals(1, g.benchStates.get(1).placed.size(), "bench1 有投放");
        assertEquals(0, g.benchStates.get(0).placed.size(), "bench0 不受影响");
        assertEquals(1, g.bi);
    }

    /* 8. 分离提纯工艺：粗盐过滤 */
    @Test
    void processFilter() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh(); EngineCtx c = ctx();
        g.vessels.put("funnel", new GameState.Vessel(true, 0));
        give(g, s, "SLAG", 3); give(g, s, "NaCl", 3); give(g, s, "filterpaper", 2);
        assertTrue(e.setVessel(g, s, c, "funnel"));
        e.place(g, s, c, rng, "SLAG", 2); e.place(g, s, c, rng, "NaCl", 2);
        GameEngine.Result r = e.react(g, s, c, rng);
        assertNotNull(r.proc, "命中工艺");
        assertEquals("P01", r.proc.id());
        assertTrue(r.produced.getOrDefault("NaCl", 0) >= 1, "过滤产出 NaCl");
        assertEquals(1, e.count(g, "filterpaper", 0), "消耗一张滤纸");
    }

    /* 9. 不可加热仪器受热炸裂 */
    @Test
    void noHeatVesselExplodes() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh(); EngineCtx c = ctx();
        g.vessels.put("cylinder", new GameState.Vessel(true, 0));
        give(g, s, "H2O", 2);
        e.setVessel(g, s, c, "cylinder");
        e.setTemp(g, c, "heat");
        e.place(g, s, c, rng, "H2O", 1);
        GameEngine.Result r = e.react(g, s, c, rng);
        assertEquals("boom", r.kind, "量筒加热炸裂");
    }

    /* 10. 危险混放 + 保险理赔 */
    @Test
    void dangerAndInsurance() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh(); EngineCtx c = ctx();
        give(g, s, "KClO3", 3); give(g, s, "S", 3);
        e.place(g, s, c, rng, "KClO3", 1); e.place(g, s, c, rng, "S", 1);
        assertTrue(e.hasDanger(g, s, c), "KClO3+S 危险");
        c.insured = true;
        long before = g.coins;
        GameEngine.Result r = e.react(g, s, c, rng);
        assertEquals("boom", r.kind);
        assertFalse(c.insured, "保险一次事故后失效");
        assertTrue(g.coins >= before, "保险理赔后金币不降：" + before + " -> " + g.coins);
    }

    /* 11. 挑战胜利结算 */
    @Test
    void challengeWin() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh(); EngineCtx c = ctx();
        g.chal = mkChal("R010", "CO", Map.of("C", 4, "O2", 2), Map.of(), 950);
        GameState.Bench tb = new GameState.Bench();
        tb.vessel = "crucible"; tb.temp = "ignite";
        tb.placed.put("C", 2); tb.placed.put("O2", 1);
        c.tempBench = tb;
        long before = g.coins;
        GameEngine.Result r = e.react(g, s, c, rng);
        assertNotNull(r.chal);
        assertTrue(r.chal.win, "挑战胜利");
        assertNull(g.chal, "胜利后清空挑战");
        assertNull(c.tempBench, "关闭临时台");
        assertTrue(g.coins >= before + 950, "发放挑战奖励（另有首解方程式奖）");
        assertEquals(1, g.stats.challenges);
    }

    /* 12. 沙盒：不写背包、不产金币 */
    @Test
    void sandboxNoSideEffects() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh(); EngineCtx c = ctx();
        c.sandboxActive = true;
        GameState.Bench tb = new GameState.Bench();
        tb.temp = "ignite"; tb.placed.put("H2", 2); tb.placed.put("O2", 1);
        c.tempBench = tb;
        g.bag.clear();
        long coins = g.coins;
        GameEngine.Result r = e.react(g, s, c, rng);
        assertTrue("success".equals(r.kind) || "partial".equals(r.kind));
        assertTrue(g.bag.isEmpty(), "沙盒不写入背包");
        assertEquals(coins, g.coins, "沙盒不产生金币");
        assertEquals(1, g.stats.sandbox);
    }

    /* 16. 笑气链路：NH3+HNO3→NH4NO3（R144），NH4NO3 受热→N2O（R142） */
    @Test
    void laughingGasChain() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh(); EngineCtx c = ctx();
        give(g, s, "NH3", 2); give(g, s, "HNO3", 2);
        e.place(g, s, c, rng, "NH3", 1); e.place(g, s, c, rng, "HNO3", 1);
        GameEngine.Result r1 = e.react(g, s, c, rng);
        assertEquals("R144", r1.reaction.id());
        assertTrue(r1.produced.getOrDefault("NH4NO3", 0) >= 1, "合成硝酸铵");

        e.setTemp(g, c, "heat");
        e.place(g, s, c, rng, "NH4NO3", 1);
        GameEngine.Result r2 = e.react(g, s, c, rng);
        assertEquals("R142", r2.reaction.id(), "硝酸铵受热分解");
        assertTrue(r2.produced.getOrDefault("N2O", 0) >= 1 || r2.yieldWarn, "生成笑气");
    }

    /* 17. 挑战混入干扰物质：立即判负 */
    @Test
    void challengeDecoyInstantLoss() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh(); EngineCtx c = ctx();
        g.chal = mkChal("R010", "__T__", Map.of("H2", 6, "O2", 3, "Au", 2), Map.of("Au", 2), 950);
        GameState.Bench tb = new GameState.Bench();
        tb.temp = "ignite";
        tb.placed.put("H2", 2); tb.placed.put("O2", 1); tb.placed.put("Au", 1);
        c.tempBench = tb;
        GameEngine.Result r = e.react(g, s, c, rng);
        assertEquals("fail-chal", r.kind);
        assertTrue(r.chal.decoy);
        assertNull(g.chal);
        assertNull(c.tempBench);
    }

    /* 18. 挑战步骤用尽：保留现场等待复活 */
    @Test
    void challengeOutOfStepsKeepsScene() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh(); EngineCtx c = ctx();
        GameState.Chal ch = mkChal("R010", "__T__", Map.of("H2", 6, "O2", 3), Map.of("Au", 2), 950);
        ch.steps = 1; ch.max = 1;
        g.chal = ch;
        GameState.Bench tb = new GameState.Bench();
        tb.temp = "ignite"; tb.placed.put("H2", 2); tb.placed.put("O2", 1);
        c.tempBench = tb;
        GameEngine.Result r = e.react(g, s, c, rng);
        assertTrue(r.chal != null && r.chal.outOfSteps, "步骤用尽");
        assertTrue(g.chal.failed, "标记失败可复活");
        assertNotNull(c.tempBench, "现场保留");
    }

    /* 19. 哈希与 state.js hash() 跨语言一致：始终落在 [-1,1)，锁定无符号取模语义 */
    @Test
    void hashMatchesJsReference() {
        // 参考值由 node 直接执行 state.js 的 hash() 得到
        assertEquals(-0.153, GameEngine.hash("NaCl"), 1e-6);
        assertEquals(0.456, GameEngine.hash("H2O"), 1e-6);
        assertEquals(0.637, GameEngine.hash("Cu"), 1e-6);
        assertEquals(-0.486, GameEngine.hash("SLAG"), 1e-6);
        assertEquals(-0.739, GameEngine.hash(""), 1e-6);
        assertEquals(-0.696, GameEngine.hash("inorganic2026-09-24"), 1e-6);
        assertEquals(0.318, GameEngine.hash("npc_kate"), 1e-6);
        // 泛化：任意字符串都必须落在 [-1,1)，否则 drift/发现奖励/排行榜会越界
        for (String x : new String[]{"NaCl2026-09-24", "H2SO4", "Zn", "abc", "元素", "µ"}) {
            double h = GameEngine.hash(x);
            assertTrue(h >= -1 && h < 1, "hash 越界: " + x + " => " + h);
        }
    }

    private GameState.Chal mkChal(String reactId, String target, Map<String, Integer> given,                                  Map<String, Integer> decoys, long reward) {
        GameState.Chal ch = new GameState.Chal();
        ch.reactId = reactId; ch.target = target;
        ch.given.putAll(given); ch.decoys.putAll(decoys);
        ch.steps = 0; ch.max = 4; ch.win = false; ch.reward = reward;
        return ch;
    }
}
