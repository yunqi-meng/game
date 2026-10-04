package com.chemera.server.game;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 内容注册表使用的强类型记录：由 content_item.data（JSON）与 app_config 反序列化而来，
 * 与前端 js/data/*.js 的字段一一对应。全部标注 ignoreUnknown，安全忽略 __type 等多余字段。
 */
public final class Content {
    private Content() {}

    /** 升到 lv+1 所需经验，对应 CHEM.LEVEL_EXP。 */
    public static int levelExp(int lv) { return 80 + lv * lv * 25; }

    /* ---------------- 反应 ---------------- */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Conditions(String temp, String catalyst, Boolean electrolysis) {
        public String tempOrRoom() { return temp == null || temp.isEmpty() ? "room" : temp; }
        public boolean elec() { return Boolean.TRUE.equals(electrolysis); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reaction(String id, Map<String, Integer> reactants, Map<String, Integer> products,
                           Conditions conditions, List<String> instrument, String type, String eq,
                           String phenomenon, List<String> fx, Integer discoverLv, Integer exp, String tip,
                           String ionic, Boolean rev, String thermal, Boolean hazard, Boolean danger,
                           String dangerMsg) {
        public Map<String, Integer> reactantsOr() { return reactants == null ? Map.of() : reactants; }
        public Map<String, Integer> productsOr() { return products == null ? Map.of() : products; }
        public Conditions cond() { return conditions == null ? new Conditions("room", null, false) : conditions; }
        public List<String> instrumentOr() { return instrument == null ? List.of() : instrument; }
        public List<String> fxOr() { return fx == null ? List.of() : fx; }
        public int lv() { return discoverLv == null ? 1 : discoverLv; }
        public int expOr() { return exp == null ? 0 : exp; }
        public boolean isDanger() { return Boolean.TRUE.equals(danger); }
        public boolean isHazard() { return Boolean.TRUE.equals(hazard); }
    }

    /* ---------------- 物质（元素/化合物/耗材） ---------------- */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ElementDef(String id, String zh, String en, String symbol, Integer z, Double mass,
                             String cat, Integer group, Integer period, String state, String color,
                             Integer price, String desc) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompoundDef(String id, String zh, String en, String formula, Integer level, String state,
                              String color, Boolean hazard, Integer price, String desc, String uses,
                              List<String> elements) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConsumableDef(String id, String zh, Integer price, String desc) {}

    /** 归一化物质视图，对应 state.js allSubs()：合并元素/化合物/耗材/废渣。 */
    public record Substance(String id, String kind, String zh, String formula, int level, int price,
                            String state, String color, boolean hazard, List<String> elements,
                            String cat, int z, String uses, String desc) {}

    /* ---------------- 仪器 / 房间 / 工艺 / 危险 ---------------- */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InstrumentDef(String id, String zh, String kind, String cat, Integer baseCost, String desc,
                                Integer unlockLv, Boolean noHeat, Double yieldBonus, Integer batchBonus,
                                String proc) {
        public boolean isVessel() { return "vessel".equals(kind); }
        public boolean noHeatOr() { return Boolean.TRUE.equals(noHeat); }
        public double yield() { return yieldBonus == null ? 0 : yieldBonus; }
        public int batch() { return batchBonus == null ? 0 : batchBonus; }
        public int cost() { return baseCost == null ? 0 : baseCost; }
        public int unlock() { return unlockLv == null ? 1 : unlockLv; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoomDef(String id, String zh, Long cost, Integer unlockLv, String desc, List<String> types) {
        public List<String> typesOr() { return types == null ? List.of() : types; }
        public long costOr() { return cost == null ? 0 : cost; }
        public int unlock() { return unlockLv == null ? 1 : unlockLv; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProcessDef(String id, String zh, String vessel, String consume, String needEquip,
                             Map<String, Integer> reactants, Map<String, Integer> products, String temp,
                             String eq, String phenomenon, List<String> fx, String type, Integer exp,
                             Integer q, String tip) {
        public Map<String, Integer> reactantsOr() { return reactants == null ? Map.of() : reactants; }
        public Map<String, Integer> productsOr() { return products == null ? Map.of() : products; }
        public String tempOrRoom() { return temp == null || temp.isEmpty() ? "room" : temp; }
        public List<String> fxOr() { return fx == null ? List.of() : fx; }
        public int expOr() { return exp == null ? 0 : exp; }
        public int quality() { return q == null ? 0 : q; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DangerDef(String id, String msg, Map<String, Integer> reactants, Boolean explosion) {
        public Map<String, Integer> reactantsOr() { return reactants == null ? Map.of() : reactants; }
    }

    /* ---------------- 成就 / 任务 / NPC / 商店 / 题库 ---------------- */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AchievementDef(String id, String zh, String desc, Long reward) {
        public long rewardOr() { return reward == null ? 0 : reward; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskDef(String id, String zh, String key, Integer goal, Long reward) {
        public int goalOr() { return goal == null ? 0 : goal; }
        public long rewardOr() { return reward == null ? 0 : reward; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NpcDef(String id, String zh, String emoji, String focus, Integer discovered, Gift gift) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Gift(String id, Integer n) {}
        public int discoveredOr() { return discovered == null ? 0 : discovered; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShopDef(String id, String zh, String desc, Integer d) {
        public int price() { return d == null ? 0 : d; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuizDef(String id, String q, List<String> opts, Integer a, String exp, String grade) {
        public List<String> optsOr() { return opts == null ? List.of() : opts; }
        public int answer() { return a == null ? 0 : a; }
    }

    /* ---------------- 配置常量（app_config） ---------------- */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quality(int q, String zh, double mult) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabUpgrade(String zh, String desc, int step, long baseCost, double growth, int max) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Recharge(int c, int d) {}

    /** config 字段名与 app_config 的 cfg_key 逐字对应（snake_case）。缺失字段有兜底默认。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(Double buy_rate, Double sell_rate, Double first_sell_bonus, Long start_coins,
                         Long tutorial_coins, Long quiz_reward,
                         Map<String, int[]> discover_bonus,
                         Map<String, LabUpgrade> lab_upgrades,
                         List<Quality> quality, List<String> tier_names, List<Integer> tier_up_cost,
                         List<Integer> milestones, List<Recharge> recharge) {

        public double buyRate() { return buy_rate == null ? 1.2 : buy_rate; }
        public double sellRate() { return sell_rate == null ? 0.8 : sell_rate; }
        public double firstSellBonus() { return first_sell_bonus == null ? 1.5 : first_sell_bonus; }
        public long startCoins() { return start_coins == null ? 5000 : start_coins; }
        public long tutorialCoins() { return tutorial_coins == null ? 2000 : tutorial_coins; }
        public long quizReward() { return quiz_reward == null ? 120 : quiz_reward; }

        /** 发现奖金区间（level 1..4），对应 DISCOVER_BONUS。 */
        public int[] discoverBonus(int level) {
            if (discover_bonus != null) {
                int[] v = discover_bonus.get(String.valueOf(level));
                if (v != null && v.length >= 2) return v;
            }
            return new int[]{50, 200};
        }

        public LabUpgrade lab(String key) { return lab_upgrades == null ? null : lab_upgrades.get(key); }

        public double qualityMult(int q) {
            if (quality != null) {
                for (Quality x : quality) if (x.q() == q) return x.mult();
            }
            return q == 0 ? 0.7 : q == 2 ? 1.5 : 1.0;
        }

        public List<String> tierNames() { return tier_names == null ? List.of("普通", "精密", "专业") : tier_names; }

        public long tierUpCost(int tier) {
            if (tier_up_cost != null && tier >= 0 && tier < tier_up_cost.size()) return tier_up_cost.get(tier);
            return tier == 0 ? 3 : 8;
        }

        public List<Integer> milestonesOr() { return milestones == null ? List.of() : milestones; }
        public List<Recharge> rechargeOr() { return recharge == null ? List.of() : recharge; }
    }
}
