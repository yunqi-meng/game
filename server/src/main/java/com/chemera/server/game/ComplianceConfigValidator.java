package com.chemera.server.game;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code app_config} 里三个合规/发布键的写入守卫：{@code curfew}、{@code app_version}、{@code analytics_enabled}。
 *
 * <p>前两个和 {@code ad} 一样是"人手工填的对象"，但后果方向相反：{@code ad} 填坏了是发不出奖励，
 * 这两个填坏了是**把玩家关在门外**。防沉迷闸门在 {@code GameService.act} 的第一行，
 * 时段写错（比如把 {@code from} 打成 {@code startTime}）不会报错，只会让所有未成年人
 * 在任何时刻都被判成"不在放行时段"——这类事故运行期完全没有回声，只能存前拦。
 *
 * <p>{@code analytics_enabled} 是标量，但同样属于"关掉之后不会报错、只是数据不再长"的那一类，
 * 所以也只认布尔：填 0 或 "false" 会被 Jackson 静默 coerce 成关停。
 *
 * <p>校验规则以 {@link Content.Curfew} / {@link Content.AppVersion} / {@link Content.Config#analyticsOn()}
 * 的真实读法为准：只拦"引擎会静默吞掉"或"合规上不可接受"的写法，不加看起来更严的多余限制。
 */
public final class ComplianceConfigValidator {

    static final Set<String> CURFEW_FIELDS = Set.of("enabled", "zone", "days", "from", "to", "extraDates", "hint");
    static final Set<String> VERSION_FIELDS = Set.of("minBuild", "latestBuild", "note", "url");

    /** ISO 周几：一=1……日=7。种子给的 5,6,7 即周五六日，与法规口径一致。 */
    private static final int DOW_MIN = 1, DOW_MAX = 7;
    private static final int BUILD_MAX = 100_000;
    private static final int HINT_MAX = 120;

    private ComplianceConfigValidator() {}

    /** 按 cfg_key 分派；不认识的键直接放过（其它键有各自的守卫或本来就是标量/数组）。 */
    public static List<String> problems(String key, JsonNode raw) {
        return switch (key) {
            case "curfew" -> curfew(raw);
            case "app_version" -> appVersion(raw);
            case "analytics_enabled" -> analytics(raw);
            default -> List.of();
        };
    }

    /**
     * 埋点采集开关：引擎读法是 {@code !Boolean.FALSE.equals(analytics_enabled)}，
     * 所以字符串 "false" 会被 Jackson  coerce 成 false、数字 0 coerce 成 false，看着"存了个 0"却等于关停——
     * 而这种键一旦关停是不会有任何运行期报错的（看板只是不再长），所以只认布尔与 null。
     */
    private static List<String> analytics(JsonNode raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isNull()) return out;          // 删键/置空 = 回默认（开）
        if (!raw.isBoolean())
            out.add("analytics_enabled 只能是 true 或 false（现在是 " + describe(raw)
                    + "）。填 0 / \"false\" 这类值会被静默转成 false，等于悄悄关停采集，"
                    + "而关停不会有任何报错，只会让看板从此不再增长。");
        return out;
    }

    /** curfew 删键 = 回退 {@code Content.Curfew.DEFAULT}，而默认值是"开启 + 周五六日 20-21 点"，所以不拦。 */
    private static List<String> curfew(JsonNode raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isNull()) return out;
        if (!raw.isObject()) {
            out.add("curfew 必须是一个对象（形如 {\"enabled\":true,\"days\":[5,6,7],\"from\":\"20:00\",\"to\":\"21:00\"}），现在是 " + describe(raw) + "。");
            return out;
        }
        unknown(raw, CURFEW_FIELDS, "curfew", out);
        flag(raw, "enabled", out);            // 只管类型；"开关关了但雷还埋着"的措辞由 curfewOn 判断

        JsonNode zone = raw.get("zone");
        if (present(zone)) {
            if (!zone.isTextual() || zone.asText().isBlank()) {
                out.add("curfew.zone 必须是时区 id（如 Asia/Shanghai），现在是 " + describe(zone) + "。");
            } else {
                String z = zone.asText().trim();
                try {
                    ZoneId.of(z);
                } catch (Exception e) {
                    out.add("curfew.zone「" + z + "」不是合法时区 id，会被引擎回落到 Asia/Shanghai（改了不生效）。"
                            + "闸门按这个时区判「现在是几点」，写错就等于给玩家发了一个错的放行窗口。");
                }
            }
        }

        Set<Integer> days = days(raw.get("days"), out);
        LocalTime from = hhmm(raw, "from", out);
        LocalTime to = hhmm(raw, "to", out);
        if (from != null && to != null && from.equals(to))
            out.add("curfew 的 from 与 to 相同（" + fmt(from) + "），窗口长度为零——开了总开关后"
                    + "未成年人任何时刻都不放行。要全天停玩请关总开关前先想想：那等于关掉整个游戏。");
        dates(raw, out);
        // 只看"显式写了空数组"这一种情况：days 缺省走默认五六日，而 days 全是非法值已经逐条报过了，
        // 再叠一条"永远无法游玩"只会让运营对着三条报错猜哪条才是真问题
        JsonNode d = raw.get("days");
        boolean deliberatelyClosed = present(d) && d.isArray() && d.isEmpty() && days.isEmpty();
        if (deliberatelyClosed && extraDates(raw).isEmpty())
            out.add("curfew 的 days 与 extraDates 都是空的：没有任何一天放行，未成年人将永久无法游玩。"
                    + (curfewOn(raw) ? "" : "（当前总开关是关的，此刻不影响结算，但哪天拨回来之前请先补上放行日；）")
                    + "要临时全停请走封禁而不是把这个配置项留空。");

        JsonNode hint = raw.get("hint");
        if (present(hint)) {
            // 留空不拦：hintOr() 会回落到内置那句"未成年人仅在周五、周六、周日…"，玩家看到的是完整句子。
            // 但类型和长度必须管——非文本会让整句 403 变成 Jackson 异常，超长会在校门页上折成好几屏。
            if (!hint.isTextual()) out.add("curfew.hint 必须是文本，现在是 " + describe(hint) + "。");
            else if (hint.asText().length() > HINT_MAX)
                out.add("curfew.hint 超过 " + HINT_MAX + " 字，会在校门页上折成好几屏，请压缩到一句。");
        }
        return out;
    }

    /**
     * app_version 是"版本门"的唯一真源：{@code minBuild} 填高一位，旧包玩家就打不开游戏。
     * 所以这里只拦两种写法——会崩的（非法 URL / 类型错）和会误伤全体的（minBuild 高于 latestBuild）。
     */
    private static List<String> appVersion(JsonNode raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isNull()) return out;
        if (!raw.isObject()) {
            out.add("app_version 必须是一个对象（形如 {\"minBuild\":1,\"latestBuild\":1,\"note\":\"\",\"url\":\"\"}），现在是 " + describe(raw) + "。");
            return out;
        }
        unknown(raw, VERSION_FIELDS, "app_version", out);
        int min = build(raw, "minBuild", out);
        int latest = build(raw, "latestBuild", out);
        if (min >= 0 && latest >= 0 && latest < min)
            out.add("app_version.latestBuild(" + latest + ") 小于 minBuild(" + min + ")：客户端拿到的最新包比强制线还旧，"
                    + "等于宣布所有安卓包都必须升级到一个不存在的版本。正常写法是 latestBuild ≥ minBuild。");
        text(raw, "note", "app_version.note", 200, out);
        JsonNode url = raw.get("url");
        if (present(url)) {
            if (!url.isTextual()) out.add("app_version.url 必须是文本，现在是 " + describe(url) + "。");
            else {
                String u = url.asText().trim();
                if (!u.isEmpty() && !u.startsWith("http://") && !u.startsWith("https://"))
                    out.add("app_version.url「" + u + "」不是 http(s) 地址：壳内会用系统浏览器打开它，"
                            + "给个非 URL 字符串只会让玩家点完没反应。留空表示不引导下载。");
            }
        }
        return out;
    }

    /* ---------------- 逐项检查 ---------------- */

    /** days 缺省=用默认的周五六日，所以不要求必填；一旦填了就必须是 1~7 的整数数组。 */
    private static Set<Integer> days(JsonNode n, List<String> out) {
        Set<Integer> seen = new LinkedHashSet<>();
        if (!present(n)) return new LinkedHashSet<>(Content.Curfew.DEFAULT.daysOr());
        if (!n.isArray()) {
            out.add("curfew.days 必须是周几数组（如 [5,6,7]），现在是 " + describe(n) + "。");
            return seen;
        }
        for (JsonNode x : n) {
            if (!x.isIntegralNumber() || x.intValue() < DOW_MIN || x.intValue() > DOW_MAX) {
                out.add("curfew.days 里的 " + describe(x) + " 不合法：只能是 1~7（一是 1，日是 7）。");
                continue;
            }
            seen.add(x.intValue());
        }
        return seen;
    }

    private static void dates(JsonNode root, List<String> out) {
        JsonNode n = root.get("extraDates");
        if (!present(n)) return;
        if (!n.isArray()) {
            out.add("curfew.extraDates 必须是日期数组（如 [\"2026-10-01\"]），现在是 " + describe(n) + "。");
            return;
        }
        for (int i = 0; i < n.size(); i++) {
            JsonNode x = n.get(i);
            if (!x.isTextual() || x.asText().isBlank()) {
                out.add("curfew.extraDates #" + (i + 1) + " 必须是文本日期。");
                continue;
            }
            String s = x.asText().trim();
            try {
                LocalDate.parse(s);   // 只认 ISO yyyy-MM-dd：isPlayDay 就是拿 LocalDate.toString() 比的
            } catch (Exception e) {
                out.add("curfew.extraDates #" + (i + 1) + "「" + s + "」不是 yyyy-MM-dd 格式，"
                        + "引擎按 ISO 日期比对，写错的日子永远匹配不上（等于节假日没补上）。");
            }
        }
    }

    private static List<String> extraDates(JsonNode root) {
        List<String> out = new ArrayList<>();
        JsonNode n = root.get("extraDates");
        if (present(n) && n.isArray()) n.forEach(x -> {
            if (x.isTextual() && !x.asText().isBlank()) out.add(x.asText().trim());
        });
        return out;
    }

    private static LocalTime hhmm(JsonNode root, String f, List<String> out) {
        JsonNode n = root.get(f);
        if (!present(n)) return null;
        if (!n.isTextual()) {
            out.add("curfew." + f + " 必须是 \"HH:mm\" 文本，现在是 " + describe(n) + "。");
            return null;
        }
        String s = n.asText().trim();
        try {
            LocalTime.parse(s);
        } catch (Exception e) {
            out.add("curfew." + f + "「" + s + "」不是合法的 HH:mm（如 20:00、9:00），"
                    + "解析不了会被引擎回落到默认窗口，改了等于没改。");
            return null;
        }
        return LocalTime.parse(s);
    }

    private static int build(JsonNode root, String f, List<String> out) {
        JsonNode n = root.get(f);
        if (!present(n)) return -1;
        if (!n.isIntegralNumber() || n.intValue() < 0 || n.intValue() > BUILD_MAX) {
            out.add("app_version." + f + " 必须是 0~" + BUILD_MAX + " 的整数（安卓 versionCode），现在是 " + describe(n) + "。");
            return -1;
        }
        return n.intValue();
    }

    private static void text(JsonNode root, String f, String at, int max, List<String> out) {
        JsonNode n = root.get(f);
        if (!present(n)) return;
        if (!n.isTextual()) out.add(at + " 必须是文本，现在是 " + describe(n) + "。");
        else if (n.asText().length() > max) out.add(at + " 超过 " + max + " 字，弹窗会溢出屏幕。");
    }

    private static boolean flag(JsonNode n, String f, List<String> out) {
        JsonNode v = n.get(f);
        if (!present(v)) return true;                      // 缺省按引擎默认：开
        if (!v.isBoolean()) {
            out.add("curfew." + f + " 必须是 true/false，现在是 " + describe(v) + "。");
            return true;
        }
        return v.booleanValue();
    }

    private static boolean present(JsonNode n) { return n != null && !n.isNull(); }

    /** 只读总开关、不再补报错（报错归 {@link #flag} 管），用于判断"雷此刻到底炸不炸"。 */
    private static boolean curfewOn(JsonNode raw) {
        JsonNode v = raw.get("enabled");
        return !present(v) || !v.isBoolean() || v.booleanValue();
    }

    private static void unknown(JsonNode n, Set<String> allowed, String at, List<String> out) {
        List<String> extra = new ArrayList<>();
        n.fieldNames().forEachRemaining(f -> { if (!allowed.contains(f)) extra.add(f); });
        if (!extra.isEmpty()) {
            out.add(at + " 有引擎不读的字段：" + String.join("、", extra) + "（认识的是 "
                    + String.join("、", allowed.stream().sorted().toList())
                    + "）。拼错的键会被 Jackson 静默丢掉：看着存成功了，实际按默认值跑。");
        }
    }

    private static String fmt(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private static String describe(JsonNode n) {
        if (n == null) return "空";
        String s = n.toString();
        return (n.isObject() ? "对象 " : n.isArray() ? "数组 " : n.isTextual() ? "字符串 " : n.isNumber() ? "数字 " : "")
                + (s.length() > 60 ? s.substring(0, 60) + "…" : s);
    }

    /**
     * 面板说明书：边界与默认值都由后端出，前端不抄第二份清单。
     * 与 {@link AdConfigValidator#schema()} 同一个理由——两份答案总有一天跟引擎对不上。
     */
    public static java.util.Map<String, Object> schema() {
        Content.Curfew d = Content.Curfew.DEFAULT;
        Content.AppVersion v = Content.AppVersion.DEFAULT;
        java.util.Map<String, Object> curfew = new java.util.LinkedHashMap<>();
        curfew.put("fields", CURFEW_FIELDS.stream().sorted().toList());
        // 周几用 ISO 编号而不是中文名：面板上的标签由前端映射，边界只在这里出一次
        curfew.put("bounds", java.util.Map.of("days", List.of(DOW_MIN, DOW_MAX), "hint", HINT_MAX,
                "build", List.of(0, BUILD_MAX)));
        java.util.Map<String, Object> cd = new java.util.LinkedHashMap<>();
        cd.put("enabled", d.on());
        cd.put("days", d.daysOr());
        cd.put("from", d.from());
        cd.put("to", d.to());
        cd.put("zone", d.zoneOr().getId());
        cd.put("hint", d.hintOr());
        curfew.put("defaults", cd);
        java.util.Map<String, Object> version = new java.util.LinkedHashMap<>();
        version.put("fields", VERSION_FIELDS.stream().sorted().toList());
        version.put("bounds", java.util.Map.of("build", List.of(0, BUILD_MAX)));
        java.util.Map<String, Object> vd = new java.util.LinkedHashMap<>();
        vd.put("minBuild", v.minOr());
        vd.put("latestBuild", v.latestOr());
        vd.put("note", v.note());
        vd.put("url", v.url());
        version.put("defaults", vd);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> analytics = new java.util.LinkedHashMap<>();
        analytics.put("type", "boolean");
        analytics.put("defaults", java.util.Map.of("analytics_enabled", true));
        out.put("curfew", curfew);
        out.put("app_version", version);
        out.put("analytics_enabled", analytics);
        return out;
    }
}
