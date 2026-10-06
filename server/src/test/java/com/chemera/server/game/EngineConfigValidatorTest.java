package com.chemera.server.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 四个"引擎结算参数"键（{@code level_exp} / {@code bench_max_lines} / {@code accident} /
 * {@code quiz_grade_mult}）的存前守卫回归（G4）。
 *
 * <p>这条测试要同时证明两件相反的事：
 * ① <b>迁移当天没人受影响</b>——V12 种进去的值必须与引擎自己的兜底逐字相同（由 {@link #seededValuesAreExactlyTheEngineFallbacks} 证明）；
 * ② <b>迁移之后每一天都能被拨动</b>——改一个数，判定就要跟着变（由 {@code GameEngineConfigTest} 的另一组用例证明）。
 * 只做②会悄悄把玩家的经济打崩而没人知道，只做①则外提本身没有意义。
 *
 * <p>负例只测"这一档特有的坑"，不测通用格式：概率写成 5、下限抬到基准之上、拼错的字段名——
 * 这三类都是"存成功但按老值跑"或"存成功但每次必炸"，运行期没有回声，只能靠这里拦。
 */
class EngineConfigValidatorTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static List<String> problems(String key, String json) throws Exception {
        return EngineConfigValidator.problems(key, OM.readTree(json));
    }

    private static void assertOk(String key, String json) throws Exception {
        assertTrue(problems(key, json).isEmpty(), key + " 不该被拦：" + json + " -> " + problems(key, json));
    }

    private static String assertBad(String key, String json) throws Exception {
        List<String> p = problems(key, json);
        assertFalse(p.isEmpty(), key + " 这个值必须被拦住：" + json);
        return String.join("；", p);
    }

    /* ---------------- V12 种的那份值必须原样通过，且等于引擎兜底 ---------------- */

    /** 从迁移脚本里取出 {@code INSERT INTO app_config} 的 cfg_key → cfg_value 原文。 */
    private static Map<String, String> seeded() throws IOException {
        String sql = new String(new ClassPathResource("db/migration/V12__data_driven_rules.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\('(\\w+)',\\s*'([^']*)',\\s*'(?:progression|economy)'")
                .matcher(sql);
        Map<String, String> out = new java.util.LinkedHashMap<>();
        while (m.find()) out.put(m.group(1), m.group(2));
        // 解析自证：少一个键说明正则没跟上，那下面所有断言都会安静地什么都不检查。
        assertEquals(EngineConfigValidator.keys(), out.keySet(), "没从 V12 里解析出这四个键：" + out.keySet());
        return out;
    }

    @Test
    void theSeededValuesAreAcceptedByTheGuard() throws Exception {
        Map<String, String> seeded = seeded();
        for (String key : EngineConfigValidator.keys()) assertOk(key, seeded.get(key));
    }

    /**
     * 最关键的一条等价性：V12 灌进去的数 == 键不存在时引擎自己用的数。
     * 只有这样才谈得上"迁移当天没有任何玩家结算发生变化"。
     */
    @Test
    void seededValuesAreExactlyTheEngineFallbacks() throws Exception {
        Map<String, String> seeded = seeded();
        Content.Config fallback = OM.convertValue(Map.of(), Content.Config.class);   // 全空 = 外提前那份行为

        // accident：整条 record 逐字段比
        Content.Accident seededAccident = OM.readValue(seeded.get("accident"), Content.Accident.class);
        assertEquals(Content.Accident.DEFAULT, seededAccident, "V12 种的事故参数与引擎兜底不同，迁移会当场改变结算");
        assertEquals(Content.Accident.DEFAULT, fallback.accidentOr());

        // level_exp：把两级曲线各算一遍（常数项与随等级增长项都要对得上）
        assertEquals(fallback.expNeeded(1), 80 + 25 * 1);
        assertEquals(fallback.expNeeded(7), 80 + 25 * 49);
        Content.Config withCurve = OM.convertValue(Map.of("level_exp", OM.readTree(seeded.get("level_exp"))),
                Content.Config.class);
        for (int lv = 1; lv <= 30; lv++)
            assertEquals(fallback.expNeeded(lv), withCurve.expNeeded(lv), "lv" + lv + " 的升级需求变了");

        // bench_max_lines：与外提前 GameEngine.MAX_LINES 的 6 一致
        assertEquals(6, fallback.benchMaxLines());
        assertEquals(6, Integer.parseInt(seeded.get("bench_max_lines")));

        // quiz_grade_mult：与 Content.Config 自带的兜底表逐档一致（那一份就是原 EconomyService.GRADE_MULT）
        Content.Config withMult = OM.convertValue(Map.of("quiz_grade_mult", OM.readTree(seeded.get("quiz_grade_mult"))),
                Content.Config.class);
        for (String grade : ContentSchema.QUIZ_GRADE)
            assertEquals(fallback.quizGradeMult(grade), withMult.quizGradeMult(grade), 1e-9, grade + " 档倍率变了");
        assertEquals(Content.Config.gradeFallback(),
                OM.convertValue(OM.readTree(seeded.get("quiz_grade_mult")),
                        OM.getTypeFactory().constructMapType(java.util.LinkedHashMap.class, String.class, Double.class)),
                "V12 的倍率表与引擎兜底表不再是同一份");
    }

    /** 事故参数仍然真的算得出老结果（防"字段搬了家、公式忘了跟"）。 */
    @Test
    void accidentFormulasStillProduceTheOriginalNumbers() {
        Content.Accident a = Content.Accident.DEFAULT;
        assertEquals(0.5, a.hitChance(0, 0), 1e-9, "无安全设施时的冲击概率仍是原来的 0.5");
        assertEquals(0.1, a.hitChance(4, 0), 1e-9, "4 级设施按 0.5−4×0.1 减");
        assertEquals(0.08, a.hitChance(9, 0), 1e-9, "减到下限 0.08 封住，不会调成零事故");
        assertEquals(0.3, a.dangerChance(0, 0), 1e-9);
        assertEquals(0.22, a.dangerChance(2, 0), 1e-9, "危险概率按 0.3−等级×0.04");
        assertEquals(450L, a.damage(1000, 0), 1e-9, "1000 投入按 0.9×0.5 损失 450");
        assertEquals(150L, a.damage(1000, 5), 1e-9, "5 级安全设施：0.9−5×0.12=0.3，再×0.5");
        assertEquals(300L, a.repairFee(100, 10_000), 1e-9, "修复费 = 100 + 经验×2");
        assertEquals(120L, a.repairFee(0, 10_000), 1e-9, "取不到经验时按兜底 10×2 再加基数");
        assertEquals(50L, a.repairFee(100, 50), 1e-9, "以余额封顶，不能把玩家扣成负数");
        assertEquals(200L, a.absorbed(1000), 1e-9, "防护罩留 20%");
        assertEquals(500L, a.refund(1000), 1e-9, "保险赔一半");
    }

    /* ---------------- 成就 cond 回填 == 过渡白名单 ---------------- */

    /**
     * V12 里那 19 条 {@code WHEN … THEN '{"metric":…}'} 必须与 {@link AchievementRule#legacy} 逐条相等。
     * 白名单存在的意义是"迁移没跑到的库也判得对"，一旦两边分叉，同一个成就会在两个环境判出不同结果。
     */
    @Test
    void theBackfilledCondsEqualTheLegacyWhitelist() throws Exception {
        String sql = new String(new ClassPathResource("db/migration/V12__data_driven_rules.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("WHEN\\s+'(\\w+)'\\s+THEN\\s+'(\\{[^']*\\})'").matcher(sql);
        int n = 0;
        while (m.find()) {
            n++;
            Content.AchCond c = OM.readValue(m.group(2), Content.AchCond.class);
            assertEquals(AchievementRule.legacy(m.group(1)), c, m.group(1) + " 的回填条件与白名单不同");
            assertNull(AchievementRule.problem(m.group(1), c), m.group(1) + " 的回填条件解释不出来");
        }
        assertEquals(19, n, "V12 回填的成就条数变了（白名单是 " + AchievementRule.legacyIds().size() + " 条）");
    }

    /** 回填的 WHERE 必须带 item_id 白名单与"已有 cond 不覆盖"，否则会把运营自己配的条件冲掉。 */
    @Test
    void backfillIsScopedAndDoesNotStompOperatorEdits() throws Exception {
        String sql = new String(new ClassPathResource("db/migration/V12__data_driven_rules.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(sql.contains("JSON_CONTAINS_PATH(data, 'one', '$.cond') = 0"),
                "没有'已经配过条件就不覆盖'的守卫");
        assertTrue(Pattern.compile("item_id IN \\('[^)]*\\)").matcher(sql).find(),
                "CASE 没有 item_id 白名单兜住，非白名单行会被写成 JSON null");
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE cfg_key = cfg_key"),
                "配置插入必须保空：本地已被运营改过的值不能被迁移覆盖回来");
    }

    /* ---------------- 词表对齐 ---------------- */

    @Test
    void guardedKeysAreDocumentedInTheSpecSheet() {
        for (String key : EngineConfigValidator.keys()) {
            ConfigSpec s = ConfigSpec.of(key);
            assertNotNull(s, key + " 有存前校验却没有作用说明，面板上会是个黑盒");
            assertEquals(ConfigSpec.ENGINE, s.scope(), key + " 是结算旋钮，不该被标成 UI/PARTIAL");
        }
        // 反方向：说明书里被标成 ENGINE 的复合键，除 ad/compliance 那两个专门守卫的外，都必须在本校验器里有分派
        for (ConfigSpec s : ConfigSpec.ALL) {
            if (!ConfigSpec.ENGINE.equals(s.scope())) continue;
            if (EngineConfigValidator.keys().contains(s.key())) continue;
            if (List.of("ad", "curfew", "app_version").contains(s.key())) continue;
            assertTrue(s.refs().contains("EconomyService") || s.refs().contains("GameEngine")
                            || s.refs().contains("Content") || s.refs().contains("GameService")
                            || s.refs().contains("AdService") || s.refs().contains("ProgressService"),
                    s.key() + " 标成结算却指不出一处结算代码：" + s.refs());
        }
    }

    /* ---------------- level_exp 负例 ---------------- */

    @Test
    void levelExpRejectsShapesTheEngineWouldSilentlyIgnore() throws Exception {
        assertOk("level_exp", "null");                                   // 删键 = 默认曲线
        assertOk("level_exp", "{\"base\":80,\"coef\":25}");
        assertOk("level_exp", "{\"base\":80}");                           // 缺 coef 由 accessor 兜，不是错

        assertTrue(assertBad("level_exp", "6").contains("必须是一个对象"), "标量会让 Jackson 整个丢字段");
        assertTrue(assertBad("level_exp", "{\"min\":80,\"step\":25}").contains("min"),
                "拼错的字段名必须点名，否则改了等于没改");
        assertTrue(assertBad("level_exp", "{\"base\":\"80\",\"coef\":25}").contains("整数"));
        assertTrue(assertBad("level_exp", "{\"base\":3,\"coef\":25}").contains("level_exp.base"));
        assertTrue(assertBad("level_exp", "{\"base\":80,\"coef\":-1}").contains("level_exp.coef"));
        assertTrue(assertBad("level_exp", "{\"base\":80,\"coef\":100000}").contains("越界"));
        // base 太小 + coef=0：升级需求恒定且极低，一次反应连升数级
        assertTrue(assertBad("level_exp", "{\"base\":10,\"coef\":0}").contains("平曲线"));
        assertOk("level_exp", "{\"base\":80,\"coef\":0}");                // 平曲线本身是合法设计
    }

    /* ---------------- bench_max_lines 负例 ---------------- */

    @Test
    void benchLinesRejectsTheUnplayableEnds() throws Exception {
        assertOk("bench_max_lines", "null");
        assertOk("bench_max_lines", "6");
        assertOk("bench_max_lines", "1");
        assertOk("bench_max_lines", "64");
        assertTrue(assertBad("bench_max_lines", "0").contains("游戏没法玩"));
        assertTrue(assertBad("bench_max_lines", "65").contains("排不下"));
        assertTrue(assertBad("bench_max_lines", "\"6\"").contains("整数"));
        assertTrue(assertBad("bench_max_lines", "6.5").contains("整数"));
    }

    /* ---------------- accident 负例 ---------------- */

    @Test
    void accidentRejectsNumbersThatWouldSilentlyRewriteTheGame() throws Exception {
        assertOk("accident", "null");
        assertOk("accident", "{\"hit_base\":0.5,\"protect_mult\":0.2}");   // 部分字段合法：其余由 record 兜

        assertTrue(assertBad("accident", "[1,2]").contains("必须是一个对象"));
        assertTrue(assertBad("accident", "{\"hit_bas\":0.5}").contains("hit_bas"), "少一个字母的键必须被点名");
        assertTrue(assertBad("accident", "{\"hit_base\":5}").contains("越界"), "5 不是'更严'，是每次必炸");
        assertTrue(assertBad("accident", "{\"hit_base\":\"0.5\"}").contains("0~1"));
        assertTrue(assertBad("accident", "{\"safety_step\":-0.1}").contains("设施越全事故越多"));
        assertTrue(assertBad("accident", "{\"loss_ratio\":1.5}").contains("越界"));

        // 下限压过基准 = 这条减免线永久失效，而面板上看着一切正常
        String floor = assertBad("accident", "{\"hit_base\":0.5,\"hit_floor\":0.6,\"safety_step\":0.1}");
        assertTrue(floor.contains("恒等于") && floor.contains("safety_step 也一并失效"), floor);
        assertTrue(assertBad("accident", "{\"danger_base\":0.3,\"danger_floor\":1}")
                .contains("基本必炸"));
        assertTrue(assertBad("accident", "{\"protect_mult\":1}").contains("一点效果都没有"));

        assertTrue(assertBad("accident", "{\"repair_base\":-100}").contains("送钱"));
        assertTrue(assertBad("accident", "{\"repair_base\":999999999}").contains("越界"));
        assertTrue(assertBad("accident", "{\"repair_exp_mult\":1.5}").contains("整数"));
    }

    /* ---------------- quiz_grade_mult 负例 ---------------- */

    @Test
    void gradeMultRejectsNamesTheEngineCanNeverLookUp() throws Exception {
        assertOk("quiz_grade_mult", "null");
        assertOk("quiz_grade_mult", "{\"小学\":1.0,\"初中\":1.0,\"高中\":1.2,\"大学\":1.5}");
        assertOk("quiz_grade_mult", "{\"高中\":0}");                       // 0 = 主动取消加成，合法

        assertTrue(assertBad("quiz_grade_mult", "{\"研究生\":2.0}").contains("研究生"),
                "题目里写不出这个年级，这一项永远读不到");
        assertTrue(assertBad("quiz_grade_mult", "{\"高中\":\"1.2\"}").contains("数字"));
        assertTrue(assertBad("quiz_grade_mult", "{\"高中\":-1}").contains("倒扣金币"));
        assertTrue(assertBad("quiz_grade_mult", "{\"高中\":99}").contains("超过"));
        assertTrue(assertBad("quiz_grade_mult", "[]").contains("必须是一个对象"));
        // 空对象与删键结果相同、说明意图不同，所以只提示不静默
        assertTrue(assertBad("quiz_grade_mult", "{}").contains("空对象"));
    }

    @Test
    void otherConfigKeysAreLeftToTheirOwnGuards() throws Exception {
        assertOk("sell_rate", "0.8");
        assertOk("lab_upgrades", "{\"storage\":{\"a\":1}}");               // 形状由各自的标量/JSON 路径负责
        assertTrue(EngineConfigValidator.problems("start_coins", OM.readTree("5000")).isEmpty());
    }
}
