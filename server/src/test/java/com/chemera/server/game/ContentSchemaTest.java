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
import java.util.TreeSet;

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
        var substances = new TreeSet<>(s.substances().keySet());
        var instruments = new TreeSet<String>();
        var vessels = new TreeSet<String>();
        for (Content.InstrumentDef i : s.instruments) {
            instruments.add(i.id());
            if (i.isVessel()) vessels.add(i.id());
        }
        var processes = new TreeSet<String>();
        s.processes.forEach(p -> processes.add(p.id()));
        var elements = new TreeSet<String>();
        s.elements.forEach(e -> elements.add(e.id()));
        return new ContentSchema.Sets(substances, instruments, vessels, processes, elements);
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
}
