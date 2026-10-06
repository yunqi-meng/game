package com.chemera.server.game;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「改配置 ⇒ 判定跟着变」的引擎侧回归（G4）。
 *
 * <p>{@link EngineConfigValidatorTest} 证明的是存前拦得住、以及 V12 种的值等于老字面量（迁移当天无人受影响）；
 * 这里证明的是另一半：运营把数改掉之后，服务端结算<b>当场按新数算</b>，不需要发版。
 * 两半合起来才是"外提"的真正验收——只做前者等于把常数换了个地方写死，只做后者则意味着迁移就把玩家经济改了。
 *
 * <p>随机源固定为 0.999（一切"是否命中"的判定都不成立的最顺利路径），所以两次运行之间唯一的差别
 * 只可能来自配置本身，金币差可以精确到个位。
 */
class GameEngineConfigTest {

    private final GameEngine e = new GameEngine();
    private final DoubleSupplier rng = () -> 0.999;

    private GameState fresh() { return GameState.fresh(System.currentTimeMillis()); }
    private EngineCtx ctx() { return new EngineCtx(); }

    private void give(GameState g, ContentRegistry.Snapshot s, String id, int n) {
        e.addItem(g, s, id, n, 0, true);
    }

    /* ---------------- bench_max_lines ---------------- */

    @Test
    void benchLinesComeFromTheConfigNotAJavaConstant() throws Exception {
        ContentRegistry.Snapshot six = Snapshots.base();
        assertEquals(6, six.config.benchMaxLines(), "夹具没配这一项时应兜到 6（原 GameEngine.MAX_LINES）");

        GameState g = fresh();
        EngineCtx c = ctx();
        give(g, six, "H2", 5); give(g, six, "O2", 5); give(g, six, "Cu", 5);
        assertTrue(e.place(g, six, c, rng, "H2", 1).ok);
        assertTrue(e.place(g, six, c, rng, "O2", 1).ok, "默认 6 档下第二种物质该放得下");

        ContentRegistry.Snapshot one = Snapshots.with("bench_max_lines", 1);
        GameState g1 = fresh();
        EngineCtx c1 = ctx();
        give(g1, one, "H2", 5); give(g1, one, "O2", 5);
        assertTrue(e.place(g1, one, c1, rng, "H2", 1).ok);
        GameEngine.PlaceResult second = e.place(g1, one, c1, rng, "O2", 1);
        assertFalse(second.ok, "台位数改成 1 之后第二种物质必须被拒");
        assertTrue(second.msg != null && second.msg.contains("1 种物质"), "拒绝理由要带上限数字：" + second.msg);
        assertTrue(e.place(g1, one, c1, rng, "H2", 1).ok, "同一种物质继续加不受台位数限制");
    }

    /** 越界值由引擎夹住而不是判负：0 会让"投放任何东西都被拒"，那是没法玩的状态。 */
    @Test
    void outOfRangeBenchLinesAreClampedNotFatal() throws Exception {
        ContentRegistry.Snapshot s = Snapshots.with("bench_max_lines", 0);
        assertEquals(1, s.config.benchMaxLines());
        GameState g = fresh();
        EngineCtx c = ctx();
        give(g, s, "H2", 2);
        assertTrue(e.place(g, s, c, rng, "H2", 1).ok, "夹到 1 之后游戏仍然可玩");

        ContentRegistry.Snapshot huge = Snapshots.with("bench_max_lines", 9999);
        assertEquals(64, huge.config.benchMaxLines());
    }

    /* ---------------- level_exp ---------------- */

    @Test
    void levelCurveComesFromTheConfig() throws Exception {
        ContentRegistry.Snapshot def = Snapshots.base();
        assertEquals(80 + 25, def.config.expNeeded(1), "默认曲线就是外提前那条 80 + 25·lv²");
        GameState g = fresh();
        assertEquals(1, e.addExp(g, def, 130), "默认曲线：130 经验只够升 1 级（下一级要 180）");
        assertEquals(2, g.level);

        // 同一笔经验、同一条结算路径，只换 level_exp：升上去的级数必须跟着变（值仍是校验器认可的平曲线）
        ContentRegistry.Snapshot flat = Snapshots.with("level_exp", Map.of("base", 20, "coef", 0));
        GameState g2 = fresh();
        assertEquals(6, e.addExp(g2, flat, 130), "恒定 20 经验一级时，130 该连升 6 级");
        assertEquals(7, g2.level);

        // 反方向：把曲线抬起来，同样的经验就该一级都升不动
        ContentRegistry.Snapshot steep = Snapshots.with("level_exp", Map.of("base", 90000, "coef", 0));
        GameState g3 = fresh();
        assertEquals(0, e.addExp(g3, steep, 130), "90000 经验一级时 130 点不该升级");
        assertEquals(1, g3.level);
    }

    /**
     * 配成"零经验"也不会把服务端挂在 while 循环里。
     * 这个值 {@link EngineConfigValidator} 存前就会拒，但读的时候仍要兜住——库里残留的历史值、
     * 或直接改库绕过面板的操作都可能让它出现，而 {@code addExp} 是结算热路径上的循环。
     */
    @Test
    void flatZeroCurveCannotHangTheLevelLoop() throws Exception {
        ContentRegistry.Snapshot s = Snapshots.with("level_exp", Map.of("base", 0, "coef", 0));
        assertFalse(EngineConfigValidator.problems("level_exp",
                Snapshots.OM.valueToTree(Map.of("base", 0, "coef", 0))).isEmpty(), "这种曲线本就该被存前拦住");
        assertEquals(1, s.config.expNeeded(0));
        GameState g = fresh();
        int ups = e.addExp(g, s, 10);
        assertEquals(10, ups, "need 兜到 1 之后 10 经验正好 10 级");
    }

    /* ---------------- accident ---------------- */

    /**
     * 危险方程式成功后的"装置受冲击"赔付：概率与金额都读 {@code accident}。
     * 两次运行只差这一个键，金币差必须精确等于修复费——这才说明结算真的在读配置，而不是碰巧没变。
     */
    @Test
    void accidentHitAndRepairFeeFollowTheConfig() throws Exception {
        Run never = run("accident", Map.of("hit_base", 0, "hit_floor", 0));
        assertNull(never.res.msg, "概率 0 时不该出现冲击赔付");
        assertEquals("success", never.res.kind);

        Run always = run("accident", Map.of("hit_base", 1.0, "hit_floor", 1.0));
        assertEquals("partial", always.res.kind, "概率 1 时该判成带冲击的成功");
        assertTrue(always.res.msg.contains("支付修复费 240 金币"),
                "修复费 = repair_base 100 + 反应经验 70×2：" + always.res.msg);
        assertEquals(240, never.coins - always.coins, "两次运行只差事故配置，金币差必须正好是赔付");

        // 只动赔付参数：概率不变，金额跟着变
        Run pricier = run("accident", Map.of("hit_base", 1.0, "hit_floor", 1.0, "repair_base", 1000));
        assertTrue(pricier.res.msg.contains("支付修复费 1140 金币"), pricier.res.msg);
        assertEquals(1140, never.coins - pricier.coins, "只换 repair_base 就该精确多扣 900");
    }

    private static final class Run {
        GameEngine.Result res;
        long coins;
    }

    /** 走 R142（硝酸铵受热，hazard=true）：固定 rng、同一初始档，所以只有配置能让结果不同。 */
    private Run run(String cfgKey, Map<String, ?> accident) throws Exception {
        ContentRegistry.Snapshot s = Snapshots.with(cfgKey, accident);
        GameState g = fresh();
        EngineCtx c = ctx();
        g.reactionsKnown.put("R142", true);          // 去掉"首次掌握方程式"奖金这个干扰项
        give(g, s, "NH4NO3", 3);
        e.setTemp(g, c, "heat");
        assertTrue(e.place(g, s, c, rng, "NH4NO3", 1).ok);
        Run r = new Run();
        r.res = e.react(g, s, c, rng);
        r.coins = g.coins;
        return r;
    }

    /* ---------------- quiz_grade_mult ---------------- */

    /** 年级倍率也走同一条路：EconomyService 直接读 config，所以这里测读出的数会变。 */
    @Test
    void quizGradeMultiplierFollowsTheConfig() throws Exception {
        assertEquals(1.5, Snapshots.base().config.quizGradeMult("大学"), 1e-9);
        assertEquals(3.0, Snapshots.with("quiz_grade_mult", Map.of("小学", 1.0, "初中", 1.0,
                "高中", 1.2, "大学", 3.0)).config.quizGradeMult("大学"), 1e-9, "倍率改了读出来必须跟着改");
        assertEquals(1.5, Snapshots.without("quiz_grade_mult").config.quizGradeMult("大学"), 1e-9,
                "删掉整个键要回到外提前那张表（大学 1.5），不是当成 1.0 取消加成");
    }
}
