package com.chemera.server.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内容字段描述符：13 类 content_item 的字段形状、合法枚举与引用目标的<strong>单一真相</strong>。
 * 用途有三：① 后台写入时按此校验（{@link #validate}）；② {@code GET /admin/api/content/schema} 下发给
 * 管理 SPA 动态渲染类型化表单；③ 内容体检（{@code /content/health}）复用同一套规则。
 *
 * <p>刻意保守：只硬拒"必然出错"的情况（缺必填、primitive 类型不符、闭集枚举取值非法、引用指向不存在的
 * 物质/仪器/工艺）。既有 458 行数据必须全部通过，由 ContentSchemaTest 用真实 bundle 兜底回归。</p>
 */
public final class ContentSchema {
    private ContentSchema() {}

    /** kind ∈ text/textarea/int/num/bool/enum/enums/stringList/ref/refs/subMap/obj。 */
    public record Field(String key, String label, String kind, boolean required,
                        List<String> options, String ref, List<Field> sub) {
        static Field of(String key, String label, String kind) { return new Field(key, label, kind, false, List.of(), null, List.of()); }
        static Field req(String key, String label, String kind) { return new Field(key, label, kind, true, List.of(), null, List.of()); }
        static Field en(String key, String label, boolean rq, List<String> opts) { return new Field(key, label, "enum", rq, opts, null, List.of()); }
        static Field ens(String key, String label, List<String> opts) { return new Field(key, label, "enums", false, opts, null, List.of()); }
        static Field ref(String key, String label, String ref) { return new Field(key, label, "ref", false, List.of(), ref, List.of()); }
        static Field refs(String key, String label, String ref) { return new Field(key, label, "refs", false, List.of(), ref, List.of()); }
        static Field obj(String key, String label, List<Field> sub) { return new Field(key, label, "obj", false, List.of(), null, sub); }
    }

    /* ---- 闭集枚举（取值域来自真实数据，见 tools 抽取） ---- */
    public static final List<String> TEMPS = List.of("room", "heat", "ignite", "highTemp");
    public static final List<String> FX = List.of("bubble", "smoke", "flame", "glow", "precip", "dissolve", "colorchange");
    public static final List<String> RX_TYPES = List.of(
            "化合反应", "分解反应", "置换反应", "复分解反应", "氧化还原反应", "燃烧反应", "电解反应",
            "中和反应", "催化反应", "沉淀反应", "有机反应", "水解反应", "聚合反应", "脱水反应");
    public static final List<String> ELEMENT_CAT = List.of(
            "alkali", "alkaline", "transition", "post-transition", "metalloid", "nonmetal", "halogen",
            "noble", "lanthanide", "actinide", "unknown");
    public static final List<String> ELEMENT_STATE = List.of("solid", "liquid", "gas", "unknown");
    public static final List<String> COMPOUND_STATE = List.of("solid", "liquid", "gas", "solution");
    public static final List<String> INSTR_KIND = List.of("vessel", "equipment");
    public static final List<String> INSTR_CAT = List.of("反应容器", "加热工具", "分离提纯", "计量仪器", "精密仪器", "辅助工具");
    public static final List<String> QUIZ_GRADE = List.of("初中", "高中", "大学");
    /** 合法每日计数器名（引擎 dailyEvent 用到的键）。 */
    public static final List<String> TASK_KEYS = List.of("success", "discover", "trade", "quiz");
    /** 仪器关联工艺类别（instrument.proc 取值）。 */
    public static final List<String> PROC = List.of("filter", "distill");

    public static final Map<String, List<Field>> SCHEMAS = build();

    private static Map<String, List<Field>> build() {
        Map<String, List<Field>> m = new LinkedHashMap<>();

        m.put("reaction", List.of(
                Field.req("id", "反应ID", "text"),
                subMap("reactants", "反应物(物质→份数)", true),
                subMap("products", "产物(物质→份数)", true),
                Field.obj("conditions", "反应条件", List.of(
                        Field.en("temp", "温度", false, TEMPS),
                        Field.ref("catalyst", "催化剂", "substance"),
                        Field.of("electrolysis", "电解", "bool"))),
                Field.refs("instrument", "可用容器", "instrument"),
                Field.en("type", "反应类型", true, RX_TYPES),
                Field.req("eq", "化学方程式", "text"),
                Field.req("phenomenon", "实验现象", "textarea"),
                Field.ens("fx", "特效", FX),
                Field.req("discoverLv", "解锁等级", "int"),
                Field.of("exp", "经验", "int"),
                Field.of("tip", "提示", "textarea"),
                Field.of("ionic", "离子方程式", "text"),
                Field.of("rev", "可逆", "bool"),
                Field.of("thermal", "热化学", "text"),
                Field.of("hazard", "危险品", "bool"),
                Field.of("danger", "危险混放", "bool"),
                Field.of("dangerMsg", "危险提示", "textarea")));

        m.put("element", List.of(
                Field.req("id", "物质ID", "text"),
                Field.req("zh", "中文名", "text"),
                Field.of("en", "英文名", "text"),
                Field.req("symbol", "元素符号", "text"),
                Field.req("z", "原子序数", "int"),
                Field.of("mass", "相对原子质量", "num"),
                Field.en("cat", "类别", true, ELEMENT_CAT),
                Field.of("group", "族", "int"),
                Field.of("period", "周期", "int"),
                Field.en("state", "常温状态", true, ELEMENT_STATE),
                Field.of("color", "颜色", "text"),
                Field.req("price", "价格", "int"),
                Field.of("desc", "说明", "textarea")));

        m.put("compound", List.of(
                Field.req("id", "物质ID", "text"),
                Field.req("zh", "中文名", "text"),
                Field.of("en", "英文名", "text"),
                Field.req("formula", "化学式", "text"),
                Field.req("level", "解锁等级", "int"),
                Field.en("state", "状态", true, COMPOUND_STATE),
                Field.of("color", "颜色", "text"),
                Field.of("hazard", "危险品", "bool"),
                Field.req("price", "价格", "int"),
                Field.of("desc", "说明", "textarea"),
                Field.of("uses", "用途", "textarea"),
                Field.refs("elements", "组成元素", "element")));

        m.put("instrument", List.of(
                Field.req("id", "仪器ID", "text"),
                Field.req("zh", "名称", "text"),
                Field.en("kind", "种类", true, INSTR_KIND),
                Field.en("cat", "分类", true, INSTR_CAT),
                Field.req("baseCost", "购入价", "int"),
                Field.req("unlockLv", "解锁等级", "int"),
                Field.of("desc", "说明", "textarea"),
                Field.of("noHeat", "不可加热", "bool"),
                Field.of("yieldBonus", "产率加成", "num"),
                Field.of("batchBonus", "批量上限", "int"),
                Field.en("proc", "关联工艺", false, PROC)));

        m.put("process", List.of(
                Field.req("id", "工艺ID", "text"),
                Field.req("zh", "名称", "text"),
                new Field("vessel", "容器", "ref", true, List.of(), "vessel", List.of()),
                Field.ref("consume", "消耗耗材", "substance"),
                Field.ref("needEquip", "所需设备", "instrument"),
                subMap("reactants", "投料(物质→份数)", true),
                subMap("products", "产出(物质→份数)", true),
                Field.en("temp", "温度", false, TEMPS),
                Field.req("eq", "说明方程式", "text"),
                Field.req("phenomenon", "现象", "textarea"),
                Field.ens("fx", "特效", FX),
                Field.en("type", "工艺类别", false, INSTR_CAT),
                Field.of("exp", "经验", "int"),
                Field.of("q", "产物品质", "int"),
                Field.of("tip", "提示", "textarea")));

        m.put("room", List.of(
                Field.req("id", "房间ID", "text"),
                Field.req("zh", "名称", "text"),
                Field.req("cost", "购买价", "int"),
                Field.req("unlockLv", "解锁等级", "int"),
                Field.of("desc", "说明", "textarea"),
                strList("types", "可运行反应类型")));

        m.put("danger", List.of(
                Field.req("id", "危险ID", "text"),
                Field.req("msg", "安全教育提示", "textarea"),
                subMap("reactants", "混放物质(物质→份数)", true),
                Field.of("explosion", "是否爆炸", "bool")));

        m.put("consumable", List.of(
                Field.req("id", "耗材ID", "text"),
                Field.req("zh", "名称", "text"),
                Field.req("price", "价格", "int"),
                Field.of("desc", "说明", "textarea")));

        m.put("npc", List.of(
                Field.req("id", "NPC ID", "text"),
                Field.req("zh", "名称", "text"),
                Field.of("emoji", "头像", "text"),
                Field.of("focus", "研究方向", "text"),
                Field.of("discovered", "解锁所需发现数", "int"),
                Field.obj("gift", "回礼", List.of(
                        new Field("id", "礼物物质", "ref", false, List.of(), "substance", List.of()),
                        Field.of("n", "数量", "int")))));

        m.put("shop", List.of(
                Field.req("id", "商品ID", "text"),
                Field.req("zh", "名称", "text"),
                Field.of("desc", "说明", "textarea"),
                Field.req("d", "钻石价", "int")));

        m.put("achievement", List.of(
                Field.req("id", "成就ID", "text"),
                Field.req("zh", "名称", "text"),
                Field.of("desc", "描述", "textarea"),
                Field.req("reward", "奖励金币", "int")));

        m.put("task", List.of(
                Field.req("id", "任务ID", "text"),
                Field.req("zh", "名称", "text"),
                Field.en("key", "计数器键", true, TASK_KEYS),
                Field.req("goal", "目标值", "int"),
                Field.req("reward", "奖励金币", "int")));

        m.put("quiz", List.of(
                Field.req("id", "题目ID", "text"),
                Field.req("q", "题干", "textarea"),
                strList("opts", "四个选项"),
                Field.req("a", "正确答案下标(0-3)", "int"),
                Field.of("exp", "解析", "textarea"),
                Field.en("grade", "年级", false, QUIZ_GRADE)));

        return m;
    }

    private static Field subMap(String key, String label, boolean req) {
        return new Field(key, label, "subMap", req, List.of(), "substance", List.of());
    }
    private static Field strList(String key, String label) {
        return new Field(key, label, "stringList", false, List.of(), null, List.of());
    }

    /** 校验引用/枚举所需的存在域。由调用方从当前 {@link ContentRegistry.Snapshot} 组装。 */
    public record Sets(Set<String> substances, Set<String> instruments, Set<String> vessels,
                       Set<String> processes, Set<String> elements) {
        boolean has(String ref, String id) {
            Set<String> s = switch (ref) {
                case "substance" -> substances;
                case "instrument", "equipment" -> instruments;
                case "vessel" -> vessels;
                case "process" -> processes;
                case "element" -> elements;
                default -> null;
            };
            return s == null || s.contains(id); // 未知 ref 目标不误伤
        }
    }

    public static List<String> validate(String type, Map<?, ?> data, Sets sets) {
        List<String> errs = new ArrayList<>();
        List<Field> fields = SCHEMAS.get(type);
        if (fields == null) { errs.add("未知内容类型: " + type); return errs; }
        if (data == null) { errs.add("data 不能为空"); return errs; }
        for (Field f : fields) check(f, data, "", errs, sets);
        if ("quiz".equals(type)) {
            Object opts = data.get("opts");
            if (opts instanceof List<?> l && l.size() != 4) errs.add("选项(opts)必须为 4 个");
            Object a = data.get("a");
            if (a instanceof Number n && (n.intValue() < 0 || n.intValue() > 3)) errs.add("答案下标 a 需在 0-3");
        }
        return errs;
    }

    private static void check(Field f, Map<?, ?> map, String path, List<String> errs, Sets sets) {
        String where = path + f.label();
        Object v = map.get(f.key());
        if (v == null) {
            if (f.required()) errs.add("缺少必填：" + where);
            return;
        }
        switch (f.kind()) {
            case "text", "textarea" -> {
                if (!(v instanceof String s)) errs.add(where + " 应为文本");
                else if (f.required() && s.isBlank()) errs.add(where + " 不能为空");
            }
            case "int" -> {
                if (!(v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof java.math.BigInteger))
                    errs.add(where + " 应为整数");
            }
            case "num" -> {
                if (!(v instanceof Number)) errs.add(where + " 应为数字");
            }
            case "bool" -> {
                if (!(v instanceof Boolean)) errs.add(where + " 应为 true/false");
            }
            case "enum" -> {
                if (!(v instanceof String s)) errs.add(where + " 应为文本");
                else if (!f.options().contains(s)) errs.add(where + " 取值非法: " + s);
            }
            case "enums" -> {
                if (!(v instanceof List<?> l)) errs.add(where + " 应为数组");
                else for (Object o : l) {
                    if (!(o instanceof String s) || !f.options().contains(s))
                        errs.add(where + " 含非法取值: " + o);
                }
            }
            case "stringList" -> {
                if (!(v instanceof List<?> l)) errs.add(where + " 应为数组");
                else for (Object o : l) if (!(o instanceof String)) errs.add(where + " 每项应为文本");
            }
            case "ref" -> {
                if (!(v instanceof String s)) errs.add(where + " 应为文本");
                else if (!s.isEmpty() && !sets.has(f.ref(), s)) errs.add(where + " 引用不存在: " + s);
            }
            case "refs" -> {
                if (!(v instanceof List<?> l)) errs.add(where + " 应为数组");
                else for (Object o : l) {
                    if (!(o instanceof String s)) errs.add(where + " 每项应为文本");
                    else if (!sets.has(f.ref(), s)) errs.add(where + " 引用不存在: " + s);
                }
            }
            case "subMap" -> {
                if (!(v instanceof Map<?, ?> mm)) errs.add(where + " 应为 {物质ID: 份数} 对象");
                else {
                    if (f.required() && mm.isEmpty()) errs.add(where + " 不能为空");
                    for (Map.Entry<?, ?> e : mm.entrySet()) {
                        if (!(e.getKey() instanceof String k)) { errs.add(where + " 键应为物质ID"); continue; }
                        if (!sets.has("substance", k)) errs.add(where + " 含不存在的物质: " + k);
                        if (!(e.getValue() instanceof Number n) || n.intValue() < 1)
                            errs.add(where + "[" + k + "] 份数应为正整数");
                    }
                }
            }
            case "obj" -> {
                if (!(v instanceof Map<?, ?> mm)) errs.add(where + " 应为对象");
                else for (Field sf : f.sub()) check(sf, mm, where + ".", errs, sets);
            }
            default -> { /* 未知 kind 不拦 */ }
        }
    }
}
