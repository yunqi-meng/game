package com.chemera.server.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 权威服务 GameService 的端到端接线回归（无 DB）：载入 → 每日刷新 → 意图结算 → 写回 → {state, revision, events}。
 * 用内存 GameStore + 固定快照，覆盖 react/market/sign/挑战临时台桥接/沙盒/未知意图/会话隔离。
 */
class GameServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static volatile ContentRegistry.Snapshot SNAP;

    @SuppressWarnings("unchecked")
    private static ContentRegistry.Snapshot snap() throws Exception {
        if (SNAP != null) return SNAP;
        Map<String, Object> bundle;
        try (InputStream in = GameServiceTest.class.getResourceAsStream("/content-bundle.json")) {
            assertNotNull(in);
            bundle = OM.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
        long v = ((Number) bundle.getOrDefault("version", 0)).longValue();
        SNAP = new ContentRegistry(null, OM).build(v,
                (Map<String, Object>) bundle.getOrDefault("content", Map.of()),
                (Map<String, Object>) bundle.getOrDefault("config", Map.of()));
        return SNAP;
    }

    /** 内存存档：单槽，记录 revision。 */
    static final class MemStore implements GameStore {
        GameState g; long rev;
        public Optional<GameState> load(long uid) { return Optional.ofNullable(g); }
        public long save(long uid, GameState s) { this.g = s; return ++rev; }
    }

    private GameService svc() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameEngine e = new GameEngine();
        GameService svc = new GameService(() -> s, e, new EconomyService(e), new MemStore());
        svc.rng = () -> 0.999;
        return svc;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> res(Map<String, Object> out) { return (Map<String, Object>) out.get("result"); }
    private static GameState state(Map<String, Object> out) { return (GameState) out.get("state"); }

    @Test
    void firstCallCreatesFreshStateAndPersists() throws Exception {
        GameService svc = svc();
        Map<String, Object> out = svc.state(1L);
        assertEquals(1L, ((Number) out.get("revision")).longValue(), "首帧落库 revision=1");
        GameState g = state(out);
        assertEquals(5000L, g.coins);
        assertTrue(g.level >= 1);
        assertNotNull(g.benchStates, "benchStates 已初始化");
    }

    @Test
    void reactIntentSynthesizesAndPersistsToBag() throws Exception {
        GameService svc = svc();
        svc.state(1L);   // 建号
        assertTrue(res(svc.act(1L, "bench.temp", Map.of("temp", "ignite"))).get("ok") != null);
        assertEquals(true, res(svc.act(1L, "bench.place", Map.of("id", "H2", "n", 2))).get("ok"));
        assertEquals(true, res(svc.act(1L, "bench.place", Map.of("id", "O2", "n", 1))).get("ok"));
        Map<String, Object> out = svc.act(1L, "react", Map.of());
        Map<String, Object> r = res(out);
        String kind = (String) r.get("kind");
        assertTrue("success".equals(kind) || "partial".equals(kind), "kind=" + kind);
        Map<String, Integer> produced = (Map<String, Integer>) r.get("produced");
        assertTrue(produced.getOrDefault("H2O", 0) >= 1, "生成水");
        // 回执 events 首条带 type + 结算字段
        assertEquals("react", ((java.util.List<Map<String, Object>>) out.get("events")).get(0).get("type"));
        // 已写回背包
        assertTrue(state(out).discovered.containsKey("H2O"), "发现水并持久化");
    }

    @Test
    void sellIntentAddsCoins() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        long before = state(last(svc, 1L)).coins;
        Map<String, Object> out = svc.act(1L, "market.sell", Map.of("id", "Na", "q", 0, "n", 2));
        assertEquals(true, res(out).get("ok"), "卖出 Na");
        assertTrue(state(out).coins > before, "金币增加");
    }

    @Test
    void signIntentRewardsOncePerDay() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        Map<String, Object> r1 = res(svc.act(1L, "sign", Map.of()));
        assertEquals(true, r1.get("ok"));
        assertEquals(1, ((Number) r1.get("day")).intValue());
        Map<String, Object> r2 = res(svc.act(1L, "sign", Map.of()));
        assertEquals(false, r2.get("ok"), "同日重复签到被拒");
    }

    @Test
    void challengeTempBenchBridgesAcrossRequests() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        Map<String, Object> start = svc.act(1L, "challenge.start", Map.of());
        GameState g = state(start);
        assertNotNull(g.chal, "已生成挑战");
        assertEquals(true, res(start).get("ok"));
        String mat = g.chal.given.keySet().iterator().next();
        Map<String, Object> place = svc.act(1L, "bench.place", Map.of("id", mat, "n", 1));
        assertEquals(true, res(place).get("ok"), "材料箱内可投放");
        assertNotNull(g.chal.bench, "临时台回填进存档 (chal.bench)");
        assertTrue(g.chal.bench.placed.getOrDefault(mat, 0) >= 1, "投放计入挑战现场");
        // 退出清理现场
        svc.act(1L, "challenge.quit", Map.of());
        assertNull(state(last(svc, 1L)).chal, "退出后 chal 清空");
    }

    @Test
    void sandboxPlaceDoesNotTouchBag() throws Exception {
        GameService svc = svc();
        Map<String, Object> enter = svc.act(1L, "sandbox.enter", Map.of());
        assertEquals(Boolean.TRUE, enter.get("sandbox"), "回执标记沙盒模式");
        assertNotNull(enter.get("bench"), "沙盒回传临时台供渲染");
        Map<String, Object> place = svc.act(1L, "bench.place", Map.of("id", "Au", "n", 3));
        assertEquals(true, res(place).get("ok"), "沙盒自由投放（无需库存）");
        GameState g = state(last(svc, 1L));
        int au = 0;
        for (String k : g.bag.keySet()) if (k.startsWith("Au|")) au += g.bag.get(k);
        assertEquals(0, au, "沙盒不写入背包");
    }

    @Test
    void unknownIntentRejectedAndSessionsIsolated() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        Map<String, Object> bad = res(svc.act(1L, "does.not.exist", Map.of()));
        assertEquals(false, bad.get("ok"), "未知意图返回失败");
        // EngineCtx 按 uid 隔离：uid1 进入沙盒不应让 uid2 的投放免库存
        svc.act(1L, "sandbox.enter", Map.of());
        Map<String, Object> u2 = res(svc.act(2L, "bench.place", Map.of("id", "Au", "n", 3)));
        assertEquals(false, u2.get("ok"), "uid2 非沙盒：无 Au 库存应被拒");
    }

    @Test
    void settingsTutorialAndAdBonusAreServerSide() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        Map<String, Object> set = svc.act(1L, "settings", Map.of(
                "realMode", true, "volSfx", 42, "music", true, "tutorial", 2, "bogus", 999999));
        assertEquals(true, res(set).get("ok"));
        GameState g = state(set);
        assertTrue(g.realMode);
        assertEquals(42, g.volSfx);
        assertTrue(g.music);
        assertEquals(2, g.tutorial);
        assertEquals(5000L, g.coins, "settings 不接受白名单外的键，金币未被篡改");

        Map<String, Object> tut = res(svc.act(1L, "tutorial.step", Map.of("to", 3)));
        assertEquals(true, tut.get("ok"));
        assertEquals(0L, ((Number) tut.get("bonus")).longValue(), "非 3→4 不发奖金");
        Map<String, Object> tut2 = res(svc.act(1L, "tutorial.step", Map.of("to", 4)));
        assertTrue(((Number) tut2.get("bonus")).longValue() > 0, "完成首次实验发放启动资金");
        GameState g2 = state(svc.state(1L));
        assertEquals(4, g2.tutorial);
        assertTrue(g2.coins > 5000L);
        long after = g2.coins;
        svc.act(1L, "tutorial.step", Map.of("to", 5));
        assertEquals(after, state(last(svc, 1L)).coins, "重复推进不再发钱");

        Map<String, Object> ad = res(svc.act(1L, "ad.bonus", Map.of("kind", 0)));
        assertEquals(true, ad.get("ok"));
        assertEquals(500L, ((Number) ad.get("coins")).longValue());
        assertEquals(false, res(svc.act(1L, "ad.bonus", Map.of("kind", 0))).get("ok"), "当日慰问金只发一次");
    }

    @Test
    void reactCarriesMultiplierAndPersistsInsurance() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        svc.act(1L, "settings", Map.of("insured", true));
        assertTrue(state(last(svc, 1L)).insured, "保险偏好入库");
        svc.act(1L, "bench.temp", Map.of("temp", "ignite"));
        svc.act(1L, "bench.place", Map.of("id", "H2", "n", 2));
        svc.act(1L, "bench.place", Map.of("id", "O2", "n", 1));
        Map<String, Object> r = res(svc.act(1L, "react", Map.of("multiplier", 1)));
        assertNotNull(r.get("rid"), "回执带反应 id 供客户端查表渲染");
        assertTrue(state(svc.state(1L)).insured, "本次未出事故：保险仍在（由 react 参数消耗）");
    }

    @Test
    void tempAndElectrolysisRequireTheirEquipment() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        // 新手只带酒精灯：heat/ignite 可用，高温与电解必须有对应设备
        assertEquals(true, res(svc.act(1L, "bench.temp", Map.of("temp", "heat"))).get("ok"));
        assertEquals(false, res(svc.act(1L, "bench.temp", Map.of("temp", "highTemp"))).get("ok"), "无喷灯不得设高温");
        assertEquals(false, res(svc.act(1L, "bench.electrolysis", Map.of("on", true))).get("ok"), "无电解器不得开电解");
        state(last(svc, 1L)).equipment.put("blowtorch", true);
        state(last(svc, 1L)).equipment.put("electrolyzer", true);
        assertEquals(true, res(svc.act(1L, "bench.temp", Map.of("temp", "highTemp"))).get("ok"));
        assertEquals(true, res(svc.act(1L, "bench.electrolysis", Map.of("on", true))).get("ok"));
    }

    @Test
    void doubleClaimNeedsTheAdCoupon() throws Exception {
        GameService svc = svc();
        svc.state(1L);
        assertEquals(false, res(svc.act(1L, "claim.daily", Map.of("id", "any", "dbl", true))).get("ok"));
        assertEquals(true, res(svc.act(1L, "ad.bonus", Map.of("kind", 1))).get("ok"), "看广告得券");
        assertEquals(true, state(last(svc, 1L)).daily.claimed.get("__dblCoupon"), "券入账");
        assertEquals(false, res(svc.act(1L, "ad.bonus", Map.of("kind", 1))).get("ok"), "当日只有一张");
    }

    private static Map<String, Object> last(GameService svc, long uid) { return svc.state(uid); }
}
