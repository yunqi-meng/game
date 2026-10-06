package com.chemera.server.game;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code app_config.ad} 的写入守卫。
 *
 * <p>为什么要在"存进去"这一步拦：广告目录是整条变现链唯一由人手工填的表，而引擎对坏行的态度是
 * <b>静默跳过</b>——{@code AdService.view/settle} 遇到 {@code reward} 不认的槽位直接 {@code continue}，
 * {@code Content.AdConfig} 的字段全按 {@code ignoreUnknown} 反序列化，所以把 {@code cooldownSec}
 * 拼成 {@code cooldown} 不会报错，只会让冷却凭空消失；把 {@code amount} 填成 0 不会报错，
 * 只会让玩家看完一段广告、服务器给他发了个零。这些都只能在后台点保存的那一刻拦下来。
 *
 * <p>规则以 {@code AdService} 的真实读法为准，不做"看起来更严"的额外限制：
 * {@code slots} 传空数组是合法的（等于把广告中心清空，运营的下架动作），但传了坏行就逐行点名。
 */
public final class AdConfigValidator {

    /** 顶层认识的字段；名字对不上的一律报错，因为 Jackson 会把它悄悄丢掉（详见类注释）。 */
    static final Set<String> TOP_FIELDS = Set.of("enabled", "dailyTotal", "minLevel", "ticketTtlSec", "viewPoints", "slots", "unlocks");
    static final Set<String> SLOT_FIELDS = Set.of("kind", "zh", "desc", "reward", "amount", "daily", "cooldownSec");
    static final Set<String> UNLOCK_FIELDS = Set.of("id", "zh", "desc", "cost", "reward", "amount", "target", "once");

    /** 引擎认得的皮肤 id（AdService.SKIN_ZH）：reward=skin 时 target 必须是其中之一，否则兑换发的是无名皮肤。 */
    static final Set<String> SKINS = Set.of("default", "cyber", "retro");

    /**
     * 数量真正参与结算的奖励类型。
     *
     * <p>{@code coupon/pack_el/skin} 是"有没有"而不是"多少个"：{@code AdService.applyReward} 对它们直接置位，
     * 读都不读 amount。所以对这三类不要求填数量（内置目录里皮肤行本来就没有 amount 字段），
     * 硬要它们填等于让校验器把自家种子判成坏行。
     */
    static final Set<String> COUNTED = Set.of("coins", "diamonds", "hints", "revive", "monthly_days");

    /** 提示里的清单排序后再拼：{@code Set.of} 的迭代顺序不保证稳定，同一份配置两次报错措辞不该不同。 */
    private static final String REWARD_TXT = String.join(" / ", Content.AdConfig.REWARDS.stream().sorted().toList());
    private static final String SKIN_TXT = String.join(" / ", SKINS.stream().sorted().toList());

    /* 下面这几个上界不是拍脑袋：值超出范围后引擎的兜底逻辑会把配置吞掉（见 Content.AdConfig 的 *Or()）。 */
    private static final int DAILY_TOTAL_MAX = 200;
    private static final int SLOT_DAILY_MAX = 99;
    private static final int COOLDOWN_MAX = 86400;
    private static final int TTL_MIN = 30;      // ttlSecOr()：低于 30 回落 900
    private static final int TTL_MAX = 3600;
    private static final int MIN_LEVEL_MAX = 30; // 等级线越高越接近"玩家到不了 = 中心永久关闭"
    private static final int AMOUNT_MAX = 1_000_000;

    private AdConfigValidator() {}

    /**
     * @param raw 即将写入 app_config 的值（controller 已把 JSON 转成树）
     * @return 问题清单，空即通过；文案直接给运营看，所以逐条带"在哪一行、怎么改"
     */
    public static List<String> problems(JsonNode raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isNull()) return out;  // 删键=回退内置默认，走的是 delete 分支，不该被这里拦住
        if (!raw.isObject()) {
            out.add("ad 必须是一个对象（形如 {\"enabled\":true,\"slots\":[…]}），现在给的是 " + describe(raw) + "。");
            return out;
        }
        unknownFields(raw, TOP_FIELDS, "ad", out);

        boolean en = boolField(raw, "enabled", out);
        intEnum(raw, "dailyTotal", 0, DAILY_TOTAL_MAX, en,
                "每日总次数 0 表示停发工单（广告中心会显示但点了没用），要真下架请关总开关", out);
        intEnum(raw, "minLevel", 1, MIN_LEVEL_MAX, en,
                "低于 1 会被引擎当 1 处理，设成玩家够不到的等级等于永久关闭广告中心", out);
        intEnum(raw, "ticketTtlSec", TTL_MIN, TTL_MAX, en,
                "低于 " + TTL_MIN + " 秒会被引擎回落到 900 秒（改了不生效），工单过期即作废不补发", out);
        intEnum(raw, "viewPoints", 0, 100, en,
                "看完一段给的积分；填 0 时积分兑换整块功能等于停用", out);

        slots(raw, out);
        unlocks(raw, out);
        return out;
    }

    private static void slots(JsonNode root, List<String> out) {
        JsonNode arr = root.get("slots");
        if (arr == null || arr.isNull()) return;      // 缺省=用 Content.AdConfig.DEFAULT_SLOTS
        if (!arr.isArray()) {
            out.add("ad.slots 必须是数组，现在是 " + describe(arr) + "。");
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode n = arr.get(i);
            String at = "广告位 #" + (i + 1);
            if (!n.isObject()) {
                out.add(at + " 必须是对象。");
                continue;
            }
            unknownFields(n, SLOT_FIELDS, at, out);
            String kind = textField(n, "kind", at, true, out);
            if (kind != null && !kind.isBlank()) {
                if (!seen.add(kind)) out.add(at + " 的 kind「" + kind + "」重复：同一 kind 只会有第一行生效，"
                        + "每日次数与冷却也都按它记账。");
                if (!kind.matches("[A-Za-z0-9_]{1,24}"))
                    out.add(at + " 的 kind「" + kind + "」只能由字母/数字/下划线组成且不超过 24 字符——它要当作客户端参数与工单里的位标识。");
            }
            String r = reward(n, at, out);
            amount(n, "amount", at, r, out);
            intField(n, "daily", 0, SLOT_DAILY_MAX, at + " 的每日次数", "填 0 即下线该位（玩家侧不再显示）", out);
            intField(n, "cooldownSec", 0, COOLDOWN_MAX, at + " 的冷却", "单位是秒；0 表示无冷却", out);
            textField(n, "zh", at, true, out);
        }
    }

    private static void unlocks(JsonNode root, List<String> out) {
        JsonNode arr = root.get("unlocks");
        if (arr == null || arr.isNull()) return;
        if (!arr.isArray()) {
            out.add("ad.unlocks 必须是数组，现在是 " + describe(arr) + "。");
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode n = arr.get(i);
            String at = "兑换项 #" + (i + 1);
            if (!n.isObject()) {
                out.add(at + " 必须是对象。");
                continue;
            }
            unknownFields(n, UNLOCK_FIELDS, at, out);
            String id = textField(n, "id", at, true, out);
            if (id != null && !id.isBlank()) {
                if (!seen.add(id)) out.add(at + " 的 id「" + id + "」重复：兑换记录按 id 记账，两行同 id 会互相顶掉。");
                if (!id.matches("[a-z0-9_]{1,24}"))
                    out.add(at + " 的 id「" + id + "」只能由小写字母/数字/下划线组成（它是存档里的兑换键）。");
            }
            String r = reward(n, at, out);
            intField(n, "cost", 0, 100000, at + " 的积分价", "0 分等于白送，慎用", out);
            amount(n, "amount", at, r, out);
            textField(n, "zh", at, true, out);
            JsonNode once = n.get("once");
            if (once != null && !once.isNull() && !once.isBoolean())
                out.add(at + " 的 once 必须是 true/false（能否重复兑换），现在是 " + describe(once) + "。");
            JsonNode target = n.get("target");
            if ("skin".equals(r)) {
                String t = target == null || target.isNull() ? null : target.asText();
                if (t == null || t.isBlank())
                    out.add(at + " 的 reward 是 skin，必须指定 target（可选：" + SKIN_TXT + "），否则兑换到的是无名皮肤。");
                else if (!SKINS.contains(t))
                    out.add(at + " 的皮肤「" + t + "」引擎里没有（可选：" + SKIN_TXT + "）。");
            } else if (target != null && !target.isNull() && !target.asText().isBlank()) {
                out.add(at + " 的 reward 是 " + r + "，target 不会被读取，请留空以免误解。");
            }
        }
    }

    /** 校验 reward，并把合法值返回给调用方继续做联动判断（不合法返回 null）。 */
    private static String reward(JsonNode n, String at, List<String> out) {
        JsonNode r = n.get("reward");
        String v = r == null || r.isNull() ? null : r.isTextual() ? r.asText().trim() : null;
        if (v == null || v.isEmpty()) {
            out.add(at + " 缺少 reward：引擎只认 " + REWARD_TXT
                    + "，写成别的值这一行会被直接跳过（玩家看完广告什么都得不到）。");
            return null;
        }
        if (!Content.AdConfig.knownReward(v)) {
            out.add(at + " 的 reward「" + v + "」不被引擎支持，可用：" + REWARD_TXT + "。");
            return null;
        }
        return v;
    }

    /**
     * 数量字段：只有 {@link #COUNTED} 那几类真的按数量结算，所以只有它们必须填且必须为正；
     * 皮肤/礼包/双倍券这类"有没有"型奖励缺 amount 是正常写法（内置种子就这么写）。
     */
    private static void amount(JsonNode n, String f, String at, String reward, List<String> out) {
        boolean counted = reward == null || COUNTED.contains(reward);   // reward 本身已经报错时按需要处理
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) {
            if (counted) out.add(at + " 缺少 " + f + "：奖励类型 " + reward + " 按数量发放，必须 ≥ 1（填 0 等于让玩家看完广告什么都收不到）。");
            return;
        }
        if (!v.isIntegralNumber()) {
            out.add(at + " 的 " + f + " 必须是整数，现在是 " + describe(v) + "。");
            return;
        }
        int i = v.intValue();
        if (i < (counted ? 1 : 0) || i > AMOUNT_MAX)
            out.add(at + " 的 " + f + " = " + i + " 不合法（" + reward + " 的数量范围 "
                    + (counted ? 1 : 0) + "~" + AMOUNT_MAX + "；填 0 等于播完广告不发东西）。");
    }

    private static void unknownFields(JsonNode n, Set<String> allowed, String at, List<String> out) {
        List<String> extra = new ArrayList<>();
        n.fieldNames().forEachRemaining(f -> {
            if (!allowed.contains(f)) extra.add(f);
        });
        if (!extra.isEmpty()) {
            out.add(at + " 有引擎不读的字段：" + String.join("、", extra)
                    + "（认识的是 " + String.join("、", allowed.stream().sorted().toList())
                    + "）。拼错的字段会被静默丢弃，看起来存成功、实际按默认值生效。");
        }
    }

    private static boolean boolField(JsonNode n, String f, List<String> out) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) return true;             // 缺省按引擎默认（enabled 默认为开）
        if (!v.isBoolean()) {
            out.add("ad." + f + " 必须是 true/false，现在是 " + describe(v) + "。");
            return false;
        }
        return v.booleanValue();
    }

    /**
     * 总闸数值边界。开关关掉时依然报错，但话要说清楚"此刻不参与结算"：
     * 存一个越界值进库，等着它的下一次是某天开关被拨回来，那时没人记得这里埋过雷。
     */
    private static void intEnum(JsonNode n, String f, int min, int max, boolean switchOn, String why, List<String> out) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) return;
        if (!v.isIntegralNumber()) {
            out.add("ad." + f + " 必须是整数，现在是 " + describe(v) + "。");
            return;
        }
        int i = v.intValue();
        if (i < min || i > max) {
            String tail = switchOn ? "" : "（当前总开关是关的，此刻不影响结算，但重开之前请改回来）";
            out.add("ad." + f + " 的取值范围是 " + min + "~" + max + "，现在是 " + i + "。" + why + "。" + tail);
        }
    }

    private static void intField(JsonNode n, String f, int min, int max, String at, String why, List<String> out) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull()) {
            if (min > 0) out.add(at + " 缺少 " + f + "：必须 ≥ " + min + "（" + why + "）。");
            return;
        }
        if (!v.isIntegralNumber()) {
            out.add(at + " 的 " + f + " 必须是整数，现在是 " + describe(v) + "。");
            return;
        }
        int i = v.intValue();
        if (i < min || i > max) out.add(at + " 的 " + f + " = " + i + " 超出 " + min + "~" + max + "（" + why + "）。");
    }

    private static String textField(JsonNode n, String f, String at, boolean required, List<String> out) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull() || !v.isTextual() || v.asText().isBlank()) {
            if (required) out.add(at + " 缺少 " + f + "（它是这一行的标识/名称，空着玩家和运营都认不出）。");
            return null;
        }
        String s = v.asText().trim();
        if (s.length() > 60) out.add(at + " 的 " + f + " 超过 60 字符，会被界面截断。");
        return s;
    }

    private static String describe(JsonNode n) {
        if (n == null) return "空";
        String s = n.toString();
        return (n.isObject() ? "对象 " : n.isArray() ? "数组 " : n.isTextual() ? "字符串 " : n.isNumber() ? "数字 " : "")
                + (s.length() > 60 ? s.substring(0, 60) + "…" : s);
    }

    /** 给配置面板当文案用：把可用 reward 交给前端做下拉，避免两端各写一套清单。 */
    public static List<String> rewards() {
        return Content.AdConfig.REWARDS.stream().sorted().toList();
    }

    /**
     * 广告目录的编辑说明书（面板据此生成下拉与数值边界）。
     *
     * <p>之所以由后端出而不是前端抄一份：抄的那份总有一天跟引擎对不上，而这一刻的错位是"能存进去、
     * 存进去不发奖"——正是本类要消灭的那类事故。
     */
    public static java.util.Map<String, Object> schema() {
        java.util.Map<String, Object> bounds = new java.util.LinkedHashMap<>();
        bounds.put("dailyTotal", List.of(0, DAILY_TOTAL_MAX));
        bounds.put("minLevel", List.of(1, MIN_LEVEL_MAX));
        bounds.put("ticketTtlSec", List.of(TTL_MIN, TTL_MAX));
        bounds.put("viewPoints", List.of(0, 100));
        bounds.put("slotAmount", List.of(1, AMOUNT_MAX));
        bounds.put("slotDaily", List.of(0, SLOT_DAILY_MAX));
        bounds.put("cooldownSec", List.of(0, COOLDOWN_MAX));
        bounds.put("unlockCost", List.of(0, 100000));
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("rewards", rewards());
        out.put("skins", SKINS.stream().sorted().toList());
        out.put("fields", java.util.Map.of("top", TOP_FIELDS.stream().sorted().toList(),
                "slot", SLOT_FIELDS.stream().sorted().toList(),
                "unlock", UNLOCK_FIELDS.stream().sorted().toList()));
        out.put("bounds", bounds);
        return out;
    }

    /**
     * 内置默认目录（{@code Content.AdConfig.DEFAULT}）的 JSON 形态，随说明书一起给面板。
     *
     * <p>存在的理由是一个语义陷阱：{@code slots} 缺省 = 用内置默认，{@code slots:[]} = 目录清空，
     * 两者在引擎里完全不同。老库里 {@code ad} 只有闸门没有目录时，表单必须能把"其实生效的那六行"展开给运营看，
     * 而不是显示一个空列表、让人以为广告位已经下架了。默认值由后端出，前端不抄第二份。
     */
    public static JsonNode defaults(com.fasterxml.jackson.databind.ObjectMapper om) {
        return om.valueToTree(Content.AdConfig.DEFAULT);
    }
}
