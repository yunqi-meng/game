package com.chemera.server.game;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContentSchema 回归（纯单元测试，读取真实 bundle 快照，不连数据库）。
 * 正向：既有 458 行必须全部通过校验，杜绝误伤；负例：非法枚举/缺必填/坏引用/越界答案必须被拦。
 */
class ContentSchemaTest {

    static final ObjectMapper OM = new ObjectMapper();
    static ContentRegistry.Snapshot SNAP;
    static ContentSchema.Sets SETS;
    static Map<String, List<Map<String, Object>>> ROWS;

    @SuppressWarnings("unchecked")
    @BeforeAll
    static void load() throws Exception {
        Map<String, Object> bundle;
        try (InputStream in = ContentSchemaTest.class.getResourceAsStream("/content-bundle.json")) {
            assertNotNull(in, "测试快照 content-bundle.json 缺失");
            bundle = OM.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
        long v = ((Number) bundle.getOrDefault("version", 0)).longValue();
        Map<String, Object> content = (Map<String, Object>) bundle.getOrDefault("content", Map.of());
        Map<String, Object> config = (Map<String, Object>) bundle.getOrDefault("config", Map.of());
        SNAP = new ContentRegistry(null, OM).build(v, content, config);
        SETS = setsOf(SNAP);

        ROWS = new LinkedHashMap<>();
        content.forEach((type, list) -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            if (list instanceof List<?> arr)
                for (Object o : arr) if (o instanceof Map<?, ?> m) rows.add((Map<String, Object>) m);
            ROWS.put(type, rows);
        });
    }

    private static ContentSchema.Sets setsOf(ContentRegistry.Snapshot s) {
        // 存在域组装已收敛到 ContentSchema.Sets.of：测试与后台写入用同一套，杜绝"体检绿、保存红"。
        return ContentSchema.Sets.of(s);
    }

    @Test
    void everyExistingRowValidates() {
        int total = 0;
        List<String> problems = new ArrayList<>();
        for (var e : ROWS.entrySet()) {
            for (Map<String, Object> row : e.getValue()) {
                total++;
                List<String> errs = ContentSchema.validate(e.getKey(), row, SETS);
                if (!errs.isEmpty())
                    problems.add(e.getKey() + ":" + row.get("id") + " -> " + String.join(" | ", errs));
            }
        }
        assertEquals(458, total, "行数应覆盖全部 13 类内容");
        assertTrue(problems.isEmpty(), "既有内容不应被校验误伤：\n" + String.join("\n", problems));
    }

    @Test
    void allSchemaTypesPresentInBundle() {
        for (String type : ContentSchema.SCHEMAS.keySet())
            assertTrue(ROWS.containsKey(type), "快照缺少类型: " + type);
    }

    /* ---------------- 负例 ---------------- */

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void rejectsUnknownEnum() {
        Map<String, Object> r = validReaction();
        r.put("type", "不存在的反应类型");
        assertTrue(ContentSchema.validate("reaction", r, SETS).stream().anyMatch(s -> s.contains("取值非法")));
    }

    @Test
    void rejectsMissingRequired() {
        Map<String, Object> r = validReaction();
        r.remove("eq");
        assertTrue(ContentSchema.validate("reaction", r, SETS).stream().anyMatch(s -> s.contains("缺少必填")));
    }

    @Test
    void rejectsBadSubstanceRef() {
        Map<String, Object> r = validReaction();
        r.put("reactants", m("NOT_A_SUBSTANCE", 1));
        assertTrue(ContentSchema.validate("reaction", r, SETS).stream().anyMatch(s -> s.contains("不存在的物质")));
    }

    @Test
    void rejectsBadInstrumentRef() {
        Map<String, Object> r = validReaction();
        r.put("instrument", List.of("no_such_instrument"));
        assertTrue(ContentSchema.validate("reaction", r, SETS).stream().anyMatch(s -> s.contains("引用不存在")));
    }

    @Test
    void rejectsPrimitiveTypeMismatch() {
        Map<String, Object> r = validReaction();
        r.put("discoverLv", "级别字符串");
        assertTrue(ContentSchema.validate("reaction", r, SETS).stream().anyMatch(s -> s.contains("应为整数")));
    }

    @Test
    void rejectsIllegalTaskKeyAndQuizShape() {
        Map<String, Object> t = m("id", "tX", "zh", "任务", "key", "notACounter", "goal", 3, "reward", 100);
        assertTrue(ContentSchema.validate("task", t, SETS).stream().anyMatch(s -> s.contains("取值非法")));

        Map<String, Object> q = m("id", "qX", "q", "题目", "opts", List.of("a", "b", "c"), "a", 9);
        List<String> errs = ContentSchema.validate("quiz", q, SETS);
        assertTrue(errs.stream().anyMatch(s -> s.contains("必须为 4 个")));
        assertTrue(errs.stream().anyMatch(s -> s.contains("0-3")));
    }

    @Test
    void rejectsBadProcessVesselRef() {
        Map<String, Object> p = m("id", "pX", "zh", "工艺", "vessel", "beaker_not_vessel",
                "reactants", m("H2", 1), "products", m("H2O", 1), "eq", "x", "phenomenon", "y");
        // lamp 是 equipment 非 vessel：指向它应被拦
        p.put("vessel", "lamp");
        assertTrue(ContentSchema.validate("process", p, SETS).stream().anyMatch(s -> s.contains("引用不存在")));
    }

    /** 参照真实数据构造一条合法反应，用于逐项破坏。 */
    private static Map<String, Object> validReaction() {
        return m("id", "RTEST", "reactants", m("H2", 2), "products", m("H2O", 2),
                "conditions", m("temp", "ignite"), "instrument", List.of(),
                "type", "化合反应", "eq", "2H2+O2->2H2O", "phenomenon", "淡蓝色火焰",
                "fx", List.of("flame"), "discoverLv", 1, "exp", 10);
    }

    /* ---------------- 成就条件（G4 主闸） ---------------- */

    /** 校验器返回的是给人看的错误清单，断言失败时要原样带出来。 */
    private static String lst(List<String> errs) {
        return String.join(" | ", errs);
    }

    private static Map<String, Object> ach(String id, Object cond) {
        Map<String, Object> r = m("id", id, "zh", "成就名", "desc", "描述", "reward", 100);
        if (cond != null) r.put("cond", cond);
        return r;
    }

    /** 老 19 行没有 cond 照样能存（白名单兜着）；新行不写 cond 必须当场拒——这正是 G4 要关的洞。 */
    @Test
    void achievementWithoutCondIsAllowedOnlyOnLegacyRows() {
        assertTrue(ContentSchema.validate("achievement", ach("aFirst", null), SETS).isEmpty(),
                "老成就行不该被误伤");
        List<String> errs = ContentSchema.validate("achievement", ach("aBrandNew", null), SETS);
        assertTrue(errs.stream().anyMatch(s -> s.contains("永远判它未达成")),
                "新成就行缺条件必须点名后果：" + errs);
    }

    @Test
    void acceptsAWellFormedCondOnANewRow() {
        assertTrue(ContentSchema.validate("achievement",
                ach("aBrandNew", m("metric", "success", "op", "ge", "value", 7)), SETS).isEmpty());
        // 成员式：只要对象存在，不需要 value
        assertTrue(ContentSchema.validate("achievement",
                ach("aBrandNew", m("metric", "discoveredSubstance", "subject", "H2O")), SETS).isEmpty());
    }

    @Test
    void rejectsMetricTheEngineCannotRead() {
        List<String> errs = ContentSchema.validate("achievement",
                ach("aBrandNew", m("metric", "连续签到天数", "op", "ge", "value", 7)), SETS);
        assertTrue(errs.stream().anyMatch(s -> s.contains("读不出来") && s.contains("连续签到天数")), lst(errs));
    }

    @Test
    void rejectsHalfWrittenCond() {
        // 数值式缺目标值
        assertTrue(ContentSchema.validate("achievement", ach("aNew", m("metric", "coins", "op", "ge")), SETS)
                .stream().anyMatch(s -> s.contains("需要目标值")));
        // 成员式缺对象
        assertTrue(ContentSchema.validate("achievement", ach("aNew", m("metric", "discoveredSubstance")), SETS)
                .stream().anyMatch(s -> s.contains("要知道对象")));
        // 空对象等于没写
        assertTrue(ContentSchema.validate("achievement", ach("aNew", m()), SETS)
                .stream().anyMatch(s -> s.contains("永远判它未达成")));
    }

    @Test
    void rejectsCondSubjectsThatDoNotExist() {
        assertTrue(ContentSchema.validate("achievement",
                ach("aNew", m("metric", "discoveredSubstance", "subject", "NOT_A_SUBSTANCE")), SETS)
                .stream().anyMatch(s -> s.contains("不是已知物质")));
        assertTrue(ContentSchema.validate("achievement",
                ach("aNew", m("metric", "knownReaction", "subject", "RZZZ")), SETS)
                .stream().anyMatch(s -> s.contains("不是已知方程式")));
    }

    /** 比较符走闭集下拉：面板上选不出非法值，直接写库/导入 JSON 的越界值由这里拦住。 */
    @Test
    void rejectsUnknownOpAndWrongFieldTypes() {
        List<String> op = ContentSchema.validate("achievement",
                ach("aNew", m("metric", "coins", "op", "约等于", "value", 1)), SETS);
        assertTrue(op.stream().anyMatch(s -> s.contains("取值非法")), lst(op));

        List<String> shape = ContentSchema.validate("achievement", ach("aFirst", "success"), SETS);
        assertTrue(shape.stream().anyMatch(s -> s.contains("应为对象")), lst(shape));

        List<String> num = ContentSchema.validate("achievement",
                ach("aNew", m("metric", "coins", "op", "ge", "value", "很多")), SETS);
        assertTrue(num.stream().anyMatch(s -> s.contains("应为数字")), lst(num));
        assertTrue(num.stream().anyMatch(s -> s.contains("需要目标值")), lst(num));
    }

    /** 指标下拉的词表必须与引擎枚举同源，否则会出现"面板能选、引擎读不出"。 */
    @Test
    void condEnumOptionsComeFromTheEngineVocabulary() {
        for (String metric : ContentSchema.ACH_METRIC)
            assertNotNull(AchievementRule.Metric.of(metric), "下拉里有引擎不认识的指标: " + metric);
        for (String metric : ContentSchema.ACH_METRIC) {
            AchievementRule.Metric m = AchievementRule.Metric.of(metric);
            // 成员式要一个真实存在的对象（方程式与物质的域不同），数值式要一个数
            String subj = m == AchievementRule.Metric.knownReaction ? "R008" : "H2O";
            Object cond = m.isMember() ? m("metric", metric, "subject", subj) : m("metric", metric, "value", 1);
            List<String> errs = ContentSchema.validate("achievement", ach("aNew", cond), SETS);
            assertTrue(errs.isEmpty(), metric + " 的合法写法被误伤: " + errs);
        }
    }
}
