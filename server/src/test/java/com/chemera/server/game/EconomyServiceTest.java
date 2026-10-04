package com.chemera.server.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EconomyService（state.js 经济 + panels.js 市场/挂单/签到/答题/订单/升级）移植的确定性回归。
 * 场景对齐 test/smoke.js 的经济/挂单/提示部分；随机源固定，价格/手续费/声望等纯函数逐条校验。
 */
class EconomyServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static volatile ContentRegistry.Snapshot SNAP;

    private final GameEngine e = new GameEngine();
    private final EconomyService eco = new EconomyService(e);
    private final DoubleSupplier rng = () -> 0.999;   // 顺利路径：不触发退货/降产/稀有掉落

    @SuppressWarnings("unchecked")
    private static ContentRegistry.Snapshot snap() throws Exception {
        if (SNAP != null) return SNAP;
        Map<String, Object> bundle;
        try (InputStream in = EconomyServiceTest.class.getResourceAsStream("/content-bundle.json")) {
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
    private void give(GameState g, ContentRegistry.Snapshot s, String id, int n, int q) {
        e.addItem(g, s, id, n, q, true);
    }

    /* 1. 行情漂移：同日确定性 + 跨日重置 */
    @Test
    @SuppressWarnings("unchecked")
    void driftIsDeterministicAndResetsDaily() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh();
        long now = System.currentTimeMillis();
        double a = eco.drift(g, s, "NaCl", now);
        double b = eco.drift(g, s, "NaCl", now);
        assertEquals(a, b, 1e-9, "同一天同一物质漂移值应稳定");
        assertTrue(a >= 0.925 && a <= 1.075, "漂移在 ±7.5% 内: " + a);
        // 伪造为昨日 → 触发按 substance 全量重算，date 更新为今天
        g.market.drift = new java.util.LinkedHashMap<>();
        g.market.date = "2000-01-01";
        double c = eco.drift(g, s, "NaCl", now);
        assertEquals(GameState.dayKey(now), g.market.date, "跨日后 market.date 刷新为今天");
        assertTrue(c >= 0.925 && c <= 1.075);
    }

    /* 2. 买价 > 回收价（含首次奖励），高纯 > 粗产 */
    @Test
    void buyExceedsSellAndQualityRaisesPrice() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh();
        long now = System.currentTimeMillis();
        long buy = eco.buyPrice(g, s, "NaCl", now);
        long sell = eco.sellPrice(g, s, "NaCl", 0, now);
        assertTrue(buy > sell, "无法买入即卖出套利: buy=" + buy + " sell=" + sell);
        assertTrue(eco.sellPrice(g, s, "NaCl", 2, now) > eco.sellPrice(g, s, "NaCl", 0, now), "高纯售价更高");
    }

    /* 3. 声望档位阈值 */
    @Test
    void repTierThresholds() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh();
        g.rep = 0;   assertEquals("生面孔", eco.repTier(g, s).zh);
        g.rep = 20;  assertEquals("常客", eco.repTier(g, s).zh);
        assertEquals(1.16, eco.repTier(g, s).buyRate, 1e-9);
        g.rep = 50;  assertEquals("贵宾", eco.repTier(g, s).zh);
        g.rep = 100; assertEquals("荣誉会员", eco.repTier(g, s).zh);
        assertEquals(0.6, eco.repTier(g, s).speed, 1e-9);
    }

    /* 4. 直售：首次溢价用尽后单价回落、库存与统计更新 */
    @Test
    void instantSellAppliesFirstBonusOnce() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh();
        long now = System.currentTimeMillis();
        give(g, s, "NaCl", 6, 0);
        assertTrue(!Boolean.TRUE.equals(g.firstBonusTaken.get("NaCl")));
        long perFirst = eco.sellPrice(g, s, "NaCl", 0, now);
        Map<String, Object> r = eco.instantSell(g, s, "NaCl", 0, 3, now);
        assertEquals(true, r.get("ok"));
        assertEquals(3, g.stats.sold);
        assertEquals(perFirst * 3, ((Number) r.get("price")).longValue(), "首次溢价入账");
        assertTrue(Boolean.TRUE.equals(g.firstBonusTaken.get("NaCl")), "首次奖励已消耗");
        long perLater = eco.sellPrice(g, s, "NaCl", 0, now);
        assertTrue(perLater < perFirst, "二次出售失去首卖溢价: " + perLater + "<" + perFirst);
        assertEquals(3, e.count(g, "NaCl", 0), "库存扣减 6→3");
    }

    /* 5. 挂单：消耗试剂瓶、到期结算并退回/售出 */
    @Test
    void createAndProcessListing() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh();
        long now = System.currentTimeMillis();
        give(g, s, "Cu", 12, 1);
        give(g, s, "reagentbottle", 5, 0);
        int lb = g.listings.size();
        int trades = g.stats.trades;
        Map<String, Object> r = eco.createListing(g, s, now, rng, "Cu", 1, 10, 1.0);
        assertEquals(true, r.get("ok"), "挂单成立");
        assertEquals(lb + 1, g.listings.size());
        assertEquals(4, e.count(g, "reagentbottle", 0), "挂单消耗试剂瓶");
        assertEquals(2, e.count(g, "Cu", 1), "Cu 库存 12→2");
        g.listings.get(g.listings.size() - 1).mat = now - 1;   // 伪造到期
        int settled = eco.processListings(g, s, now, rng);
        assertEquals(1, settled, "到期挂单被结算");
        assertEquals(lb, g.listings.size());
        assertEquals(trades + 1, g.stats.trades);
    }

    /* 6. 每日刷新：新的一天发放补贴并刷新订单 */
    @Test
    void rollDailyGrantsStipendOnNewDay() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh();
        g.daily.date = "2000-01-01";   // 强制跨日
        long now = System.currentTimeMillis();
        long coins = g.coins;
        Map<String, Object> evt = eco.rollDaily(g, s, now, rng);
        assertEquals(true, evt.get("newDay"));
        long stipend = 200 + (long) g.level * 20 + (long) g.rooms.size() * 400;   // 无月卡
        assertEquals(stipend, ((Number) evt.get("stipend")).longValue());
        assertEquals(coins + stipend, g.coins);
        assertEquals(5, g.orders.list.size(), "刷新出 5 条商会订单");
        assertEquals(GameState.dayKey(now), g.daily.date);
    }

    /* 7. 签到：连续天数、金币递增、同日不可重复 */
    @Test
    void signStreakAndDailyOnce() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh();
        long now = System.currentTimeMillis();
        long coins = g.coins;
        Map<String, Object> r = eco.sign(g, s, now, rng);
        assertEquals(true, r.get("ok"));
        assertEquals(1, ((Number) r.get("day")).intValue(), "首签为第 1 天");
        assertEquals(100, ((Number) r.get("coins")).intValue());
        assertTrue(g.coins >= coins + 100, "至少 +签到金币（首次发现赠送元素另有奖励）");
        String gift = (String) r.get("gift");
        if (gift != null) assertTrue(e.countAll(g, gift) >= 3, "签到赠送元素 ×3");
        Map<String, Object> again = eco.sign(g, s, now, rng);
        assertEquals(false, again.get("ok"), "同日重复签到被拒");
    }

    /* 8. 答题奖励按学段倍率放大 */
    @Test
    void quizRewardScalesByGrade() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh();
        Content.QuizDef q = eco.pickQuiz(g, s, "all", rng);
        assertNotNull(q, "有题库");
        String grade = q.grade() == null ? "初中" : q.grade();
        double mult = "大学".equals(grade) ? 1.5 : "高中".equals(grade) ? 1.2 : 1.0;
        long expected = Math.round(s.config.quizReward() * mult);
        Map<String, Object> r = eco.answerQuiz(g, s, q.id(), q.answer(), rng);
        assertEquals(true, r.get("correct"), "按 correct 索引判对");
        assertEquals(expected, ((Number) r.get("reward")).longValue(), grade + " 奖励倍率 " + mult);
        assertEquals(1, g.stats.quizOk);
    }

    /* 9. 钻石商店与充值档位 */
    @Test
    void diamondShopAndRecharge() throws Exception {
        ContentRegistry.Snapshot s = snap();
        GameState g = fresh();
        long now = System.currentTimeMillis();
        // 提示次数礼包：先给足钻石
        g.diamonds += 9999;
        Map<String, Object> hint = eco.buyDiamondItem(g, s, "hint5", now);
        assertEquals(true, hint.get("ok"), "购买提示次数");
        assertEquals(5, g.hints);
        // 去广告幂等
        eco.buyDiamondItem(g, s, "noad", now);
        assertTrue(g.noad);
        assertEquals(false, eco.buyDiamondItem(g, s, "noad", now).get("ok"), "重复购买被拒");
        // 充值档位
        long d0 = g.diamonds;
        int tiers = s.config.rechargeOr().size();
        Map<String, Object> rc = eco.recharge(g, s, tiers - 1);
        assertEquals(true, rc.get("ok"));
        assertTrue(g.diamonds > d0, "充值到账");
        assertEquals(false, eco.recharge(g, s, tiers + 5).get("ok"), "越界档位被拒");
    }

    /* N. 双倍领取必须由看广告换来的当日券支付，防止广告与倍率叠加 */
    @Test
    void doubleClaimConsumesTheAdCoupon() throws Exception {
        ContentRegistry.Snapshot s = snap();
        Content.TaskDef t1 = s.tasks.get(0), t2 = s.tasks.get(1);
        GameState g = fresh();
        g.daily.counters.put(t1.key(), t1.goalOr());
        Map<String, Object> plain = eco.claimDaily(g, s, t1.id(), true);
        assertEquals(true, plain.get("ok"));
        assertEquals(false, plain.get("dbl"), "没券就按普通金额结算");
        assertEquals(t1.rewardOr(), ((Number) plain.get("reward")).longValue());

        g.daily.claimed.put("__dblCoupon", true);
        g.daily.counters.put(t2.key(), t2.goalOr());
        Map<String, Object> dbl = eco.claimDaily(g, s, t2.id(), true);
        assertEquals(true, dbl.get("dbl"));
        assertEquals(t2.rewardOr() * 2, ((Number) dbl.get("reward")).longValue());
        assertEquals(false, g.daily.claimed.get("__dblCoupon"), "券是一次性的");
    }
}
