package com.chemera.server.game;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权威游戏存档模型，字段与客户端 js/state.js 的 defaults() 一一对应。
 * 与现有 user_save.payload 的 JSON 结构保持兼容（bag 键 "id|q"，discovered {id:{times,first}} 等）。
 * ignoreUnknown=true：服务端为真源，多余的历史/前端字段被安全忽略。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GameState implements com.chemera.server.service.SaveService.Invariants {

    /** 存档结构版本，恒为 2。 */
    public int v = 2;

    /**
     * 服务端落库的结构版本位（A4）。别和上面的 {@code v} 混为一谈：
     * {@code v} 是客户端存档形状的历史标记（js/state.js 一直带着，改了会牵连旧端），
     * {@code sv} 只服务一件事——"这份 payload 是哪一版服务端写出来的"。
     *
     * <p>为什么必须有它：{@code @JsonIgnoreProperties(ignoreUnknown=true)} 让多余字段安全落地，
     * 反过来的缺字段却毫无声音——字段改个名，旧存档读进来就是默认值，玩家金币丢了也不报错。
     * 有了版本位，载入时会明确知道"该跑哪几条迁移"，而比本机新的存档会被直接拒绝载入
     * （宁可让运维看到一行原因，也不要静默发一份空档覆盖掉玩家的进度）。
     *
     * <p>{@code Integer} 而不是 {@code int}：{@code null} 表示"这份存档早于版本位"，
     * 与"版本 0"是两回事，迁移分支要靠它来决定是否补历史缺项。
     */
    public Integer sv;

    /** 当前服务端写盘用的结构版本。改名/搬字段时 +1，并在 {@link SaveMigrations} 里补一条迁移。 */
    public static final int SCHEMA_VERSION = 3;

    public long coins = 5000;      // CHEM.START_COINS
    public long diamonds = 0;
    public long exp = 0;
    public int level = 1;

    /** 背包：键为 "id|quality"（q∈{0,1,2}），值为数量。 */
    public Map<String, Integer> bag = new LinkedHashMap<>();

    /** 已发现物质：id -> {times, first}。 */
    public Map<String, Discover> discovered = new LinkedHashMap<>();

    /** 已解锁方程式：反应 id -> true。 */
    public Map<String, Boolean> reactionsKnown = new LinkedHashMap<>();

    /** 首次出售奖励是否已领：物质 id -> true。 */
    public Map<String, Boolean> firstBonusTaken = new LinkedHashMap<>();

    /** 仪器/容器：id -> {owned, tier}。 */
    public Map<String, Vessel> vessels = new LinkedHashMap<>();

    /** 设备解锁：equipmentKey -> true（lamp/blowtorch/electrolyzer/…）。 */
    public Map<String, Boolean> equipment = new LinkedHashMap<>();

    public Lab lab = new Lab();
    public List<String> rooms = new ArrayList<>(List.of("inorganic"));
    public int bi = 0;
    public List<Bench> benchStates = null;   // 由引擎按房间数惰性初始化

    public Stats stats = new Stats();
    public Daily daily = new Daily();
    public Map<String, Boolean> achClaimed = new LinkedHashMap<>();
    public Map<String, Boolean> milestones = new LinkedHashMap<>();

    public Orders orders = new Orders();
    public Market market = new Market();
    public List<Listing> listings = new ArrayList<>();

    public int rep = 0;
    public Monthly monthly = new Monthly();
    public Packs packs = new Packs();
    public boolean noad = false;
    public Skins skins = new Skins();

    public int hints = 0;
    public boolean insured = false;

    /** 激励视频经济：观看积分、当日各广告位次数、上次完成时刻、复活次数、已兑换项。 */
    public Ad ad = new Ad();

    public Map<String, Friend> friends = new LinkedHashMap<>();
    public Chal chal = null;

    public int volSfx = 80;
    public int volMus = 35;
    public boolean music = false;
    public Sign sign = new Sign();
    public int tutorial = 0;
    public boolean realMode = false;
    public long lastVisit = 0;

    /* ---------- 默认新档（镜像 state.js defaults 的初始背包/容器/设备） ---------- */
    public static GameState fresh(long now) {
        GameState g = new GameState();
        g.coins = 5000;
        Map<String, Integer> bag = g.bag;
        bag.put("H2|0", 8); bag.put("O2|0", 8); bag.put("Na|0", 5); bag.put("C|0", 6);
        bag.put("Fe|0", 5); bag.put("S|0", 5); bag.put("Zn|0", 5); bag.put("Cu|0", 4);
        bag.put("CaCO3|0", 4); bag.put("HCl|0", 4); bag.put("NaOH|0", 3);
        g.vessels.put("testtube", new Vessel(true, 0));
        g.vessels.put("beaker", new Vessel(true, 0));
        g.vessels.put("spoon", new Vessel(true, 0));
        g.equipment.put("lamp", true);
        g.lastVisit = now;
        g.daily.date = dayKey(now);
        return g;
    }

    /** ISO 日期键（UTC），与客户端 new Date().toISOString().slice(0,10) 一致。 */
    public static String dayKey(long now) {
        return java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
    }

    /**
     * 落库前的结构自检（G8）：与 {@code SaveService.validate(JsonNode)} 同几条不变量、同一套文案，
     * 区别是这里读的是已经在手里的字段，而不是为此先建一棵 JSON 树再摸一遍。
     *
     * <p>它挡的是"服务端自己算坏了"：金币被扣成负数、等级掉到 0、背包引用被谁悄悄置空——
     * 这三种只要落到库里，下次载入就会以坏档形态继续滚，比当场拒绝写回难查得多。
     * 客户端传上来的存档不走这条路（那边确实是外部输入，仍按 JsonNode 逐字段判）。
     */
    @Override
    public String defect() {
        if (v != 1 && v != 2) return "存档版本字段非法";
        if (coins < 0) return "coins 非法";
        if (level < 1) return "level 非法";
        if (bag == null || discovered == null) return "bag/discovered 结构非法";
        return null;
    }

    /** 写盘时由 {@code SaveService} 调用：版本位归写路径盖，不再靠调用方各自记得（A4／G8）。 */
    @Override
    public void stampSchemaVersion() { this.sv = SCHEMA_VERSION; }

    /* ---------- 嵌套结构 ---------- */
    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Discover { public int times; public long first; }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Vessel {
        public boolean owned; public int tier;
        public Vessel() {} public Vessel(boolean o, int t) { owned = o; tier = t; }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Lab { public int storage; public int safety; public int bench; }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bench {
        public String vessel = "beaker";
        public Map<String, Integer> placed = new LinkedHashMap<>();
        public String temp = "room";
        public boolean electrolysis = false;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stats {
        public int success, boom, quiz, quizOk, trades, sold, challenges, sandbox, visits;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Daily {
        public String date = "";
        public Map<String, Integer> counters = new LinkedHashMap<>();
        public Map<String, Boolean> claimed = new LinkedHashMap<>();
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Orders {
        public String date = "";
        public List<Map<String, Object>> list = new ArrayList<>();
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Market {
        public String date = "";
        public Map<String, Double> drift = new LinkedHashMap<>();
        public List<Map<String, Object>> specials = new ArrayList<>();
        public List<Map<String, Object>> black = new ArrayList<>();
        public Map<String, Object> specialBuy = new LinkedHashMap<>();
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Listing {
        public String id; public int q; public int n; public long price; public double mat;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Monthly { public long until; }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Packs { public boolean el; }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Skins {
        public List<String> owned = new ArrayList<>(List.of("default"));
        public String cur = "default";
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Friend {
        public String lastVisit;
        public Integer giftedTotal;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sign { public String last = ""; public int streak = 0; }

    /**
     * 激励视频台账。日期键与 {@link Daily} 各自独立滚动：广告次数按玩家"完成回调"计，
     * 而 daily 是任务计数器，两者混在一个结构里会让运营调任务时误伤广告闸门。
     */
    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ad {
        /** perDay 归属的日期键（UTC ISO），跨天清零。 */
        public String day = "";
        /** 累计可用的观看积分（兑换时扣）。 */
        public int points;
        /** 历史累计完成的广告次数，只用于展示与兑换留痕。 */
        public int total;
        /** 广告换来的复活次数，challenge.revive 消耗；没次数就不能白复活。 */
        public int revive;
        /** kind -> 当日已完成次数。 */
        public Map<String, Integer> perDay = new LinkedHashMap<>();
        /** kind -> 上次完成时刻（毫秒），算冷却。 */
        public Map<String, Long> lastAt = new LinkedHashMap<>();
        /** 兑换项 id -> 已兑次数（once 项据此拒绝二兑）。 */
        public Map<String, Integer> redeemed = new LinkedHashMap<>();

        public int dayCount(String kind) { return perDay.getOrDefault(kind, 0); }

        public void addCount(String kind, int n) { perDay.put(kind, dayCount(kind) + n); }

        public int todayTotal() {
            int n = 0;
            for (Integer v : perDay.values()) n += v == null ? 0 : v;
            return n;
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Chal {
        public String reactId;
        public String target;
        public Map<String, Integer> given = new LinkedHashMap<>();
        public Map<String, Integer> decoys = new LinkedHashMap<>();
        public int steps;
        public int max = 4;
        public boolean win;
        public boolean failed;
        public long reward;
        public Bench bench;   // 挑战现场持久化（placed/temp/vessel/electrolysis）
    }
}
