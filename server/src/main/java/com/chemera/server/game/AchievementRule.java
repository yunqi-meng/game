package com.chemera.server.game;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成就判定词表（G4）：把"达成"从 Java 里的 19 路 switch 变成后台能写、引擎能读的一份数据。
 *
 * <p>改造前的问题是：后台 achievement 那 13 类表单什么都能配，唯独"达成条件"配不了——
 * 判定写在 {@code GameEngine.achDone} 的 switch 里，于是<b>新增一行成就而不动这段 Java，
 * 它就永远判未达成</b>，而面板看起来一切正常。现在条件是个描述符 {@code {metric,op,value}}（成员式指标用
 * {@code subject}），引擎按 {@link Metric} 解释执行；词表是闭合枚举，念不出来的名字一律按未达成处理，
 * 并且在 strict 写入与内容体检时当场点名——"静默永不达成"正是这次要关掉的那类错误。
 *
 * <p>词表只有 {@link Metric} 一处：{@code ContentSchema} 的下拉、后台表单、引擎读的都是这份，
 * 所以加一个可读指标只需在这里加一个常量（面板自动多一个选项）。
 */
public final class AchievementRule {
    private AchievementRule() {}

    /** 比较符。闭集，默认 {@link #ge}（"达到多少"是成就唯一的常见形状，其余是特例）。 */
    public enum Op {
        ge("≥ 达到"), gt("> 超过"), le("≤ 不超过"), eq("= 恰好");

        private final String zh;
        Op(String zh) { this.zh = zh; }
        public String zh() { return zh; }

        public static Op of(String name) {
            if (name == null) return ge;                        // 省略 op＝"达到"，老数据不受影响
            for (Op o : values()) if (o.name().equals(name)) return o;
            return null;
        }

        public boolean test(double actual, double want) {
            return switch (this) {
                case ge -> actual >= want;
                case gt -> actual > want;
                case le -> actual <= want;
                case eq -> Double.compare(actual, want) == 0;
            };
        }
    }

    /** 指标种类：数值式（要 value）与成员式（要 subject）。 */
    public enum Kind { NUM, MEMBER }

    /**
     * 引擎能从存档里读出来的量。<b>这是后台唯一能写的词汇表</b>，不在这里的一律读不出来。
     *
     * <p>读法全部走 {@link GameState} 的既有字段，所以"能不能读"与"存档里到底存没存这个数"是同一件事：
     * 想加一个存档没记的量（比如"连续签到天数"），得先在引擎侧把它记下来，再在这里加一个常量。
     */
    public enum Metric {
        success("累计成功实验", Kind.NUM, g -> g.stats.success),
        boom("累计实验事故", Kind.NUM, g -> g.stats.boom),
        quiz("累计答题次数", Kind.NUM, g -> g.stats.quiz),
        quizOk("累计答对题数", Kind.NUM, g -> g.stats.quizOk),
        trades("累计交易次数", Kind.NUM, g -> g.stats.trades),
        sold("累计卖出物质数", Kind.NUM, g -> g.stats.sold),
        challenges("累计完成挑战", Kind.NUM, g -> g.stats.challenges),
        sandbox("累计沙盒合成", Kind.NUM, g -> g.stats.sandbox),
        visits("累计访问好友", Kind.NUM, g -> g.stats.visits),
        discoveredCount("图鉴已收录物质数", Kind.NUM, g -> g.discovered.size()),
        reactionsKnownCount("已掌握方程式数", Kind.NUM, g -> g.reactionsKnown.size()),
        level("当前等级", Kind.NUM, g -> g.level),
        coins("当前金币", Kind.NUM, g -> g.coins),
        reputation("商会声望", Kind.NUM, g -> g.rep),
        equipmentCount("已购设备数", Kind.NUM, g -> countTrue(g.equipment)),
        roomCount("已购房间数", Kind.NUM, g -> g.rooms.size()),
        friendCount("好友数", Kind.NUM, g -> g.friends.size()),
        successToday("今日成功实验", Kind.NUM, g -> daily(g, "success")),
        discoverToday("今日新发现", Kind.NUM, g -> daily(g, "discover")),
        tradeToday("今日交易次数", Kind.NUM, g -> daily(g, "trade")),
        quizToday("今日答题次数", Kind.NUM, g -> daily(g, "quiz")),
        discoveredSubstance("图鉴收录了某个物质", Kind.MEMBER, g -> 0),
        knownReaction("掌握了某个方程式", Kind.MEMBER, g -> 0);

        private final String zh;
        private final Kind kind;
        private final java.util.function.ToDoubleFunction<GameState> reader;

        Metric(String zh, Kind kind, java.util.function.ToDoubleFunction<GameState> reader) {
            this.zh = zh; this.kind = kind; this.reader = reader;
        }

        public String zh() { return zh; }
        public Kind kind() { return kind; }
        public boolean isMember() { return kind == Kind.MEMBER; }

        /** 成员式指标读不出数（它问的是"在不在"，不是"多少"）。 */
        public double read(GameState g) { return isMember() ? Double.NaN : reader.applyAsDouble(g); }

        public static Metric of(String name) {
            if (name == null) return null;
            for (Metric m : values()) if (m.name().equals(name)) return m;
            return null;
        }

        /** 后台下拉用：稳定顺序就是这里的声明顺序。 */
        public static List<String> names() {
            return java.util.Arrays.stream(values()).map(Enum::name).toList();
        }
    }

    private static double countTrue(Map<String, Boolean> m) {
        int n = 0;
        for (Boolean v : m.values()) if (Boolean.TRUE.equals(v)) n++;
        return n;
    }

    /** 今日计数器（与每日任务同源：{@code ContentSchema.TASK_KEYS}）。 */
    private static double daily(GameState g, String key) {
        Integer v = g.daily == null ? null : g.daily.counters.get(key);
        return v == null ? 0 : v;
    }

    /**
     * 按描述符求值。读不出来（metric 不认识、成员式没给 subject、数值式没给 value）一律 false，
     * 不抛异常：判定在结算热路径上（每帧 checkAch 遍历全部成就），一条脏数据不该把玩家的实验打断。
     * "这行到底可不可解释"是写入与体检那两道闸的事，见 {@link #problem}。
     */
    public static boolean done(GameState g, Content.AchCond c) {
        if (c == null) return false;
        Metric m = Metric.of(c.metric());
        if (m == null) return false;
        if (m.isMember()) {
            String id = c.subject();
            if (id == null || id.isBlank()) return false;
            return m == Metric.discoveredSubstance
                    ? g.discovered.containsKey(id)
                    : Boolean.TRUE.equals(g.reactionsKnown.get(id));
        }
        Op op = Op.of(c.op());
        if (op == null || c.value() == null) return false;
        return op.test(m.read(g), c.value());
    }

    /**
     * 这一行的条件能不能被引擎解释。返回 null 表示可解释，否则返回要给运营看的那句原因。
     *
     * <p>{@code ContentSchema} 的 strict 写入与 {@code /content/health} 都调它：老 19 行没写 cond
     * 也算可解释（走 {@link #legacy} 的过渡白名单），新行必须自带条件——这正是本次要关掉的那个洞。
     */
    public static String problem(String id, Content.AchCond c) {
        if (c == null) return legacy(id) == null
                ? "成就 " + id + " 没有达成条件(cond)，且不在过渡白名单里：引擎永远判它未达成" : null;
        Metric m = Metric.of(c.metric());
        if (m == null) return "达成条件的指标(metric)引擎读不出来：" + c.metric();
        if (m.isMember()) {
            if (c.subject() == null || c.subject().isBlank()) return "指标 " + m.name() + " 要知道对象(subject)是哪个";
            return null;
        }
        if (c.value() == null) return "指标 " + m.name() + " 需要目标值(value)";
        if (Op.of(c.op()) == null) return "比较符(op)不合法：" + c.op();
        return null;
    }

    /**
     * 过渡白名单：改造前那 19 条成就的判定，原样翻成描述符。
     *
     * <p>为什么留着而不是直接要求每行都写 cond：V12 会给这 19 行回填 cond，但线上库的迁移与发版
     * 之间有先后，回滚内容、或者哪一行被删掉 cond 字段时，判定不该整体消失。白名单是只读兜底，
     * <b>行里写了 cond 就以行为准</b>（见 {@code GameEngine.achDone}），所以运营改阈值立刻生效，
     * 而"新行忘了配条件"由 {@link #problem} 在写入时就拦住，不会走到这里。
     */
    private static final Map<String, Content.AchCond> LEGACY = legacy();

    private static Map<String, Content.AchCond> legacy() {
        Map<String, Content.AchCond> m = new LinkedHashMap<>();
        m.put("aFirst", num("success", "ge", 1));
        m.put("aWater", member("discoveredSubstance", "H2O"));
        m.put("aGold", member("discoveredSubstance", "Au"));
        m.put("aBoom", num("boom", "ge", 1));
        m.put("aS100", num("success", "ge", 100));
        m.put("aD20", num("discoveredCount", "ge", 20));
        m.put("aD80", num("discoveredCount", "ge", 80));
        m.put("aD200", num("discoveredCount", "ge", 200));
        m.put("aEq30", num("reactionsKnownCount", "ge", 30));
        m.put("aLv10", num("level", "ge", 10));
        m.put("aLv20", num("level", "ge", 20));
        m.put("aRich", num("coins", "ge", 50000));
        m.put("aOrganic", member("discoveredSubstance", "CH3COOC2H5"));
        m.put("aAqua", member("discoveredSubstance", "aqua_regia"));
        m.put("aQuiz50", num("quizOk", "ge", 50));
        m.put("aSnake", member("knownReaction", "R141"));
        m.put("aRep", num("reputation", "ge", 50));
        m.put("aChallenge", num("challenges", "ge", 1));
        m.put("aSandbox", num("sandbox", "ge", 5));
        return Map.copyOf(m);
    }

    private static Content.AchCond num(String metric, String op, double value) {
        return new Content.AchCond(metric, null, op, value);
    }

    private static Content.AchCond member(String metric, String subject) {
        return new Content.AchCond(metric, subject, null, null);
    }

    /** 这一条 id 是否有过渡判定（{@code problem} 与回归测试都用它，白名单形状不靠数数记）。 */
    public static Content.AchCond legacy(String id) { return id == null ? null : LEGACY.get(id); }

    public static java.util.Set<String> legacyIds() { return LEGACY.keySet(); }
}
