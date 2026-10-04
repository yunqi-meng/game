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
public class GameState {

    /** 存档结构版本，恒为 2。 */
    public int v = 2;

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
