package com.chemera.server.game;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code app_config} 里四个"引擎结算参数"键的写入守卫（G4）：{@code level_exp}、{@code bench_max_lines}、
 * {@code accident}、{@code quiz_grade_mult}。
 *
 * <p>这四个键是刚从 Java 常数里搬出来的（{@code Content.levelExp}、{@code GameEngine.MAX_LINES}、
 * 事故那串数字、{@code EconomyService.GRADE_MULT}）。搬出来之前它们改不动，也就没人能改错；
 * 搬出来之后它们和 {@code sell_rate} 一样是"拨一下就影响全体玩家结算"的旋钮，
 * 而 {@code Content.Config} 的读法一律是"字段缺失回落到内置默认、类型不对被 Jackson 静默丢掉"——
 * 也就是说<b>拼错的键名不会报错，只会让改动整个不生效</b>。这类"看着存成功、实际按老值跑"
 * 的事故在运行期完全没有回声，只能存前拦（与 {@link AdConfigValidator}、{@link ComplianceConfigValidator} 同一理由）。
 *
 * <p>规则以 {@link Content.Config} 的真实读法为准：{@code expNeeded} / {@code benchMaxLines} /
 * {@code accidentOr} / {@code quizGradeMult} 每个 accessor 兜了什么，这里就拦什么，不多设看起来更严的限制。
 */
public final class EngineConfigValidator {

    static final Set<String> LEVEL_EXP_FIELDS = Set.of("base", "coef");
    /** 事故键的全部字段：与 {@link Content.Accident} 的 record 组件逐字对应，多写一个就是拼错。 */
    static final Set<String> ACCIDENT_FIELDS = Set.of(
            "hit_base", "hit_floor", "safety_step",
            "danger_base", "danger_floor", "danger_step",
            "loss_base", "loss_step", "loss_ratio",
            "repair_base", "repair_exp_mult", "repair_exp_floor",
            "protect_mult", "insured_refund");
    /**
     * 年级词表：真源是 {@link ContentSchema#QUIZ_GRADE}（题目数据里 grade 字段的闭集），
     * 这里只借用它，不再抄第二份——因为引擎是按 {@code q.grade()} 这个字符串去查倍率的，
     * 题目侧写不出来的年级，在倍率表里就是永远读不到的一行。
     */
    static final Set<String> QUIZ_GRADES = new LinkedHashSet<>(ContentSchema.QUIZ_GRADE);

    /**
     * 升级曲线的边界。下限不是随便取的：
     * {@code base} 低于 5（或 {@code coef}=0 且 base 很小）会让"加一次经验连升几十级"，
     * 上限则是防住"把升级要求调成天文数字"——那等于在玩家不知情的情况下关掉等级成长。
     */
    static final int EXP_BASE_MIN = 5, EXP_BASE_MAX = 100_000;
    static final int EXP_COEF_MIN = 0, EXP_COEF_MAX = 1_000;
    /** 台位上限：0 会让投放全部被拒，超过 64 等于取消这条玩法约束（且面板会排不下）。 */
    static final int LINES_MIN = 1, LINES_MAX = 64;
    /** 倍率上限：答题奖励再离谱也不该到"答一道顶一个月产量"。 */
    static final double GRADE_MULT_MAX = 10.0;
    static final long REPAIR_MAX = 100_000_000L;

    private EngineConfigValidator() {}

    /** 按 cfg_key 分派；不认识的键直接放过（其余键有各自的守卫或是标量）。 */
    public static List<String> problems(String key, JsonNode raw) {
        return switch (key) {
            case "level_exp" -> levelExp(raw);
            case "bench_max_lines" -> benchLines(raw);
            case "accident" -> accident(raw);
            case "quiz_grade_mult" -> gradeMult(raw);
            default -> List.of();
        };
    }

    /** 这四个键是不是"引擎结算参数"家族——回归测试拿它确认键集不漏（见 EngineConfigValidatorTest）。 */
    public static Set<String> keys() {
        return Set.of("level_exp", "bench_max_lines", "accident", "quiz_grade_mult");
    }

    /* ---------------- level_exp ---------------- */

    /**
     * 删键=回默认曲线（80 + 25·lv²），不拦。填了就必须是 {@code {base,coef}} 两个整数：
     * {@code expNeeded} 读的是 {@code base + coef × lv × lv}，写成 {@code {min,step}} 这种"看着像"的形状
     * 会被 Jackson 丢成 null 然后按默认跑，改了等于没改。
     */
    private static List<String> levelExp(JsonNode raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isNull()) return out;
        if (!raw.isObject()) {
            out.add("level_exp 必须是一个对象（形如 {\"base\":80,\"coef\":25}，升到 lv+1 需要 base + coef×lv² 经验），现在是 "
                    + describe(raw) + "。");
            return out;
        }
        unknown(raw, LEVEL_EXP_FIELDS, "level_exp", out);
        int base = integer(raw, "base", EXP_BASE_MIN, EXP_BASE_MAX, out,
                "升 1 级就要这么点经验，等于宣布等级不再是一种成长");
        int coef = integer(raw, "coef", EXP_COEF_MIN, EXP_COEF_MAX, out,
                "coef=0 是平曲线（每级同价），可以；但负数会让高等级反而更容易");
        if (base >= 0 && coef >= 0 && coef == 0 && base < 20)
            out.add("level_exp 是 base=" + base + "、coef=0：升级需求恒定且不随等级变，"
                    + "一个" + base + "经验的反应就能连升好几级。要平曲线请把 base 提到 20 以上。");
        return out;
    }

    /* ---------------- bench_max_lines ---------------- */

    private static List<String> benchLines(JsonNode raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isNull()) return out;               // 删键=默认 6 种
        if (!raw.isIntegralNumber()) {
            out.add("bench_max_lines 必须是一个整数（单个容器同时容纳的物质种数，现在是 " + describe(raw)
                    + "）。引擎拿它和 1..64 之外的值都会兜底，写小数或字符串等于没改。");
            return out;
        }
        int n = raw.intValue();
        if (n < LINES_MIN || n > LINES_MAX) {
            out.add("bench_max_lines 只能是 " + LINES_MIN + "~" + LINES_MAX + "，现在是 " + n + "。"
                    + (n < LINES_MIN ? "0 或负数会让投放任何物质都被拒，游戏没法玩；"
                                     : "超过 " + LINES_MAX + " 等于取消这条玩法约束，实验台也排不下。")
                    + "（引擎读数时会把越界值夹到 " + LINES_MIN + "~" + LINES_MAX + "，所以存进去也不会报错——"
                    + "报错只能在这一步做。）");
        }
        return out;
    }

    /* ---------------- accident ---------------- */

    private static List<String> accident(JsonNode raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isNull()) return out;               // 删键=Accident.DEFAULT，与外提前一致
        if (!raw.isObject()) {
            out.add("accident 必须是一个对象（事故概率与赔付参数，形如 " + accidentShape() + "），现在是 "
                    + describe(raw) + "。");
            return out;
        }
        unknown(raw, ACCIDENT_FIELDS, "accident", out);

        Double hitBase = prob(raw, "hit_base", out);
        prob(raw, "hit_floor", out);
        step(raw, "safety_step", out);
        Double dangerBase = prob(raw, "danger_base", out);
        Double dangerFloor = prob(raw, "danger_floor", out);
        step(raw, "danger_step", out);
        prob(raw, "loss_base", out);
        step(raw, "loss_step", out);
        prob(raw, "loss_ratio", out);
        nonNeg(raw, "repair_base", REPAIR_MAX, out);
        nonNeg(raw, "repair_exp_mult", 100_000L, out);
        nonNeg(raw, "repair_exp_floor", 100_000L, out);
        prob(raw, "protect_mult", out);
        prob(raw, "insured_refund", out);

        // 下限压过基准值时，max() 让下限永远赢：安全设施与道具减免全部失效，而面板上看着一切正常。
        floorOverridesBase(raw, "hit_floor", "hit_base", hitBase, "safety_step", out);
        floorOverridesBase(raw, "danger_floor", "danger_base", dangerBase, "danger_step", out);
        if (dangerFloor != null && dangerFloor >= 0.999)
            out.add("accident.danger_floor=" + fmt(dangerFloor) + "：危险混放基本必炸，安全设施与温度计铁架台都救不回来。"
                    + "留下限的本意是别让安全线把事故调成零，调到 1 是另一头过头。");
        if (probOf(raw, "protect_mult") == 1.0)
            out.add("accident.protect_mult=1：防护罩等于不吸收任何冲击，玩家花了钱却一点效果都没有。");
        return out;
    }

    private static String accidentShape() {
        return "{\"hit_base\":0.5,\"hit_floor\":0.08,\"safety_step\":0.1,\"danger_base\":0.3,\"danger_floor\":0.05,"
                + "\"danger_step\":0.04,\"loss_base\":0.9,\"loss_step\":0.12,\"loss_ratio\":0.5,\"repair_base\":100,"
                + "\"repair_exp_mult\":2,\"repair_exp_floor\":10,\"protect_mult\":0.2,\"insured_refund\":0.5}";
    }

    /* ---------------- quiz_grade_mult ---------------- */

    /**
     * 键在但缺某个年级 = 该年级不加成（{@code quizGradeMult} 按 1.0 算），这是"运营主动取消加成"，
     * 所以这里不补默认值也不拦；只有拼错年级名（引擎永远查不到这一项）与取值不成比例才报。
     */
    private static List<String> gradeMult(JsonNode raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isNull()) return out;
        if (!raw.isObject()) {
            out.add("quiz_grade_mult 必须是一个对象（形如 {\"小学\":1.0,\"初中\":1.0,\"高中\":1.2,\"大学\":1.5}），现在是 "
                    + describe(raw) + "。");
            return out;
        }
        if (raw.isEmpty())
            out.add("quiz_grade_mult 是空对象：引擎按「键在但查不到该年级＝1.0」处理，等于所有年级都不加成。"
                    + "要恢复默认请删掉这个键，而不是留一个空对象——空对象和删键在这个键上结果一样，说明意图却不一样。");
        raw.fields().forEachRemaining(e -> {
            if (!QUIZ_GRADES.contains(e.getKey()))
                out.add("quiz_grade_mult 里的年级「" + e.getKey() + "」引擎查不到（认识的是 "
                        + String.join("、", QUIZ_GRADES.stream().sorted().toList())
                        + "）。年级名取自题目数据的 grade 字段，拼错的项永远不会被取到，等于那道题的加成没配。");
            JsonNode v = e.getValue();
            if (!v.isNumber()) {
                out.add("quiz_grade_mult." + e.getKey() + " 必须是数字倍率，现在是 " + describe(v) + "。");
                return;
            }
            double d = v.doubleValue();
            if (d < 0) out.add("quiz_grade_mult." + e.getKey() + "=" + fmt(d) + "：负数会让答对题倒扣金币。0 是取消加成，够用。");
            else if (d > GRADE_MULT_MAX)
                out.add("quiz_grade_mult." + e.getKey() + "=" + fmt(d) + " 超过 " + GRADE_MULT_MAX
                        + "：答一道题的奖励会盖过一整天的实验产出。");
        });
        return out;
    }

    /* ---------------- 逐项检查 ---------------- */

    /** 概率类字段：只认 0~1 之间的数。写 5 不是"更严"，而是每次危险反应必炸。 */
    private static Double prob(JsonNode root, String f, List<String> out) {
        JsonNode n = root.get(f);
        if (!present(n)) return null;
        if (!n.isNumber()) {
            out.add("accident." + f + " 必须是 0~1 之间的数，现在是 " + describe(n) + "。");
            return null;
        }
        double d = n.doubleValue();
        if (d < 0 || d > 1) {
            out.add("accident." + f + "=" + fmt(d) + " 越界：这一档是概率或比例，只能写 0~1。"
                    + "引擎不会报错，它会直接拿这个数算结算（比如 5 会让每次危险反应都触发事故）。");
            return null;
        }
        return d;
    }

    private static double probOf(JsonNode root, String f) {
        JsonNode n = root.get(f);
        return present(n) && n.isNumber() ? n.doubleValue() : -1;
    }

    /** 递减步长：只能是 0~1 的正数（它乘在安全设施等级上）。 */
    private static void step(JsonNode root, String f, List<String> out) {
        JsonNode n = root.get(f);
        if (!present(n)) return;
        if (!n.isNumber()) {
            out.add("accident." + f + " 必须是 0~1 之间的数，现在是 " + describe(n) + "。");
            return;
        }
        double d = n.doubleValue();
        if (d < 0 || d > 1) {
            out.add("accident." + f + "=" + fmt(d) + " 越界：步长乘在安全设施等级上，负数会让设施越全事故越多。");
        }
    }

    private static void floorOverridesBase(JsonNode root, String floorF, String baseF, Double base,
                                           String stepF, List<String> out) {
        JsonNode fn = root.get(floorF);
        if (!present(fn) || !fn.isNumber() || base == null) return;
        double floor = fn.doubleValue();
        if (floor < 0 || floor > 1) return;                        // 越界已在 prob() 报过
        if (floor >= base) {
            JsonNode sn = root.get(stepF);
            String extra = present(sn) && sn.isNumber() && sn.doubleValue() > 0
                    ? "（" + stepF + " 也一并失效）" : "";
            out.add("accident." + floorF + "=" + fmt(floor) + " 不低于 " + baseF + "=" + fmt(base)
                    + "：概率取的是两者较大值，于是这条线恒等于 " + fmt(floor) + extra
                    + "。想提高事故率请调基准值，而不是把下限抬到基准之上。");
        }
    }

    private static void nonNeg(JsonNode root, String f, long max, List<String> out) {
        JsonNode n = root.get(f);
        if (!present(n)) return;
        if (!n.isIntegralNumber()) {
            out.add("accident." + f + " 必须是整数（金币或倍率下界），现在是 " + describe(n) + "。");
            return;
        }
        long v = n.longValue();
        if (v < 0 || v > max)
            out.add("accident." + f + "=" + v + " 越界：只能是 0~" + max + "。负数会反向给玩家送钱。");
    }

    /**
     * 整数区间检查。返回 -1 表示没填或已报错（调用方据此不再做组合判断）。
     * hint 只在"这一档特有的坑"上说一句话，通用的"越界"由区间自己讲清楚。
     */
    private static int integer(JsonNode root, String f, int min, int max, List<String> out, String hint) {
        JsonNode n = root.get(f);
        if (!present(n)) return -1;                                 // 缺字段由 accessor 兜默认
        if (!n.isIntegralNumber()) {
            out.add("level_exp." + f + " 必须是整数，现在是 " + describe(n) + "。");
            return -1;
        }
        int v = n.intValue();
        if (v < min || v > max) {
            out.add("level_exp." + f + "=" + v + " 越界：只能是 " + min + "~" + max + "。" + hint + "。"
                    + "（越界值引擎不会报错，它按公式直接算，所以这一步必须拦住。）");
            return -1;
        }
        return v;
    }

    private static boolean present(JsonNode n) { return n != null && !n.isNull(); }

    private static void unknown(JsonNode n, Set<String> allowed, String at, List<String> out) {
        List<String> extra = new ArrayList<>();
        n.fieldNames().forEachRemaining(f -> { if (!allowed.contains(f)) extra.add(f); });
        if (!extra.isEmpty()) {
            out.add(at + " 有引擎不读的字段：" + String.join("、", extra) + "（认识的是 "
                    + String.join("、", allowed.stream().sorted().toList())
                    + "）。拼错的键会被 Jackson 静默丢掉：看着存成功了，实际按默认值跑，改动整个不生效。");
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String describe(JsonNode n) {
        if (n == null) return "空";
        String s = n.toString();
        return (n.isObject() ? "对象 " : n.isArray() ? "数组 " : n.isTextual() ? "字符串 " : n.isNumber() ? "数字 " : "")
                + (s.length() > 60 ? s.substring(0, 60) + "…" : s);
    }

    /* 刻意不出 schema() 端点：ad 与 compliance 需要它，是因为面板要为那几个键渲染专门的表单控件，
       奖励清单与窗口边界得有唯一答案。这四个键在面板上走 JSON 兜底编辑器，而"这项是什么、边界多少"
       的说明书已经由 ConfigSpec 那一处下发（面板按 key 渲染 effect/note/refs），再来一份就是第二个答案。 */
}
