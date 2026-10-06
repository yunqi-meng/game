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

    /* 等级曲线/台位上限/事故参数/答题倍率这些数以前写在这里和引擎里（G4 之前的债）。
       现在它们的真源是 app_config，读法见下面 Config 的 expNeeded/benchMaxLines/accidentOr/quizGradeMult，
       每个键都带兜底默认——库里那行被删也照旧结算，但"改了不生效"从此只可能是键写错，不可能是代码写死。 */

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
    /**
     * 成就达成条件（G4）：引擎按这份描述符求值，而不是按 id 认人。
     *
     * <p>{@code metric} 是 {@link AchievementRule.Metric} 的枚举名——闭集，写得出但读不出的一律判不达成，
     * 且 strict 写入当场拒（见 {@code ContentSchema} 的 achievement 分支）。{@code subject} 只给
     * {@code discoveredSubstance/knownReaction} 这两个"集合成员"式指标用；{@code value} 只给数值式指标用。
     * 两者互斥不是洁癖：一条既没 subject 又没 value 的条件，后台面板上看着像配好了，玩家却永远领不到。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AchCond(String metric, String subject, String op, Double value) {}

    /** 成就行。{@code cond} 为 null 的老行由 {@link AchievementRule#legacy} 的过渡白名单兜住（V12 回填后不再出现）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AchievementDef(String id, String zh, String desc, Long reward, AchCond cond) {
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

    /** 旧充值档位：随付费面退役，保留字段只为让库里的历史行照常解析并原样下发，服务端不再按它发任何资产。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Recharge(int c, int d) {}

    /* ---------------- 激励视频（app_config.ad，付费面的替代品） ---------------- */

    /** 一个广告位：看完一段激励视频，服务器按 reward/amount 给一次奖励；daily 与 cooldownSec 是它的闸门。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdSlot(String kind, String zh, String desc, String reward, Integer amount,
                         Integer daily, Integer cooldownSec) {
        public int amountOr() { return amount == null || amount < 0 ? 0 : amount; }
        public int dailyOr() { return daily == null || daily < 0 ? 0 : daily; }
        public int cooldownOr() { return cooldownSec == null || cooldownSec < 0 ? 0 : cooldownSec; }
    }

    /** 积分兑换项：用累计观看积分换一次性/可重复的解锁，取代原来的钻石商品。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdUnlock(String id, String zh, String desc, Integer cost, String reward,
                           Integer amount, String target, Boolean once) {
        public int costOr() { return cost == null || cost < 0 ? 0 : cost; }
        public int amountOr() { return amount == null || amount < 0 ? 0 : amount; }
        public boolean onceOr() { return Boolean.TRUE.equals(once); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdConfig(Boolean enabled, Integer dailyTotal, Integer minLevel, Integer ticketTtlSec,
                           Integer viewPoints, List<AdSlot> slots, List<AdUnlock> unlocks) {

        /** 结算只认这几种奖励类型：写个引擎不认的字符串，广告看完了也发不出东西，所以要在配置说明里钉死。 */
        public static final java.util.Set<String> REWARDS = java.util.Set.of(
                "coins", "diamonds", "hints", "coupon", "revive", "monthly_days", "pack_el", "skin");

        /**
         * 是否认这种奖励。必须先判 null：Set.of() 的不可变集合对 contains(null) 直接抛 NPE，
         * 而运营在后台把一个广告位的 reward 字段漏填恰恰就是 null——那会让整帧请求崩掉。
         */
        public static boolean knownReward(String reward) {
            return reward != null && REWARDS.contains(reward);
        }

        public static final List<AdSlot> DEFAULT_SLOTS = List.of(
                new AdSlot("boom", "事故慰问金", "炸锅之后回口血", "coins", 500, 2, 300),
                new AdSlot("dbl", "双倍领取券", "今日领取任务/成就奖励可翻倍", "coupon", 1, 1, 0),
                new AdSlot("diamond", "钻石补给", "服务器直接入账钻石", "diamonds", 8, 4, 180),
                new AdSlot("hint", "精灵提示", "助手精灵提示次数 +2", "hints", 2, 2, 300),
                new AdSlot("revive", "挑战复活", "换取 1 次复活（失败挑战 +2 步）", "revive", 1, 3, 60),
                new AdSlot("monthly", "月卡时长", "月卡 +1 天", "monthly_days", 1, 1, 0));

        public static final List<AdUnlock> DEFAULT_UNLOCKS = List.of(
                new AdUnlock("elpack", "镧系·锕系礼包", "市场无视等级线全量解锁镧系/锕系", 20, "pack_el", 1, null, true),
                new AdUnlock("skin_cyber", "皮肤·赛博纪元", "深色霓虹实验室主题", 12, "skin", 1, "cyber", true),
                new AdUnlock("skin_retro", "皮肤·复古炼金", "暖棕黄铜的旧手册质感", 12, "skin", 1, "retro", true),
                new AdUnlock("monthly30", "月卡·30 天", "一次补足一个月月卡时长，可重复兑换", 25, "monthly_days", 30, null, false));

        public static final AdConfig DEFAULT = new AdConfig(true, 16, 1, 900, 1, DEFAULT_SLOTS, DEFAULT_UNLOCKS);

        public boolean on() { return !Boolean.FALSE.equals(enabled); }
        public int dailyTotalOr() { return dailyTotal == null || dailyTotal < 0 ? 16 : dailyTotal; }
        public int minLevelOr() { return minLevel == null || minLevel < 1 ? 1 : minLevel; }
        public int ttlSecOr() { return ticketTtlSec == null || ticketTtlSec < 30 ? 900 : ticketTtlSec; }
        public int viewPointsOr() { return viewPoints == null || viewPoints < 0 ? 1 : viewPoints; }

        /** 空目录=运营把广告位删光了；这时回落到代码默认值会让人以为"改了没生效"，所以宁可返回空表。 */
        public List<AdSlot> slotsOr() { return slots == null ? DEFAULT_SLOTS : slots; }
        public List<AdUnlock> unlocksOr() { return unlocks == null ? DEFAULT_UNLOCKS : unlocks; }

        public AdSlot slot(String kind) {
            if (kind == null) return null;
            for (AdSlot s : slotsOr()) if (kind.equals(s.kind()) && knownReward(s.reward())) return s;
            return null;
        }

        public AdUnlock unlock(String id) {
            if (id == null) return null;
            for (AdUnlock u : unlocksOr()) if (id.equals(u.id()) && knownReward(u.reward())) return u;
            return null;
        }
    }

    /* ---------------- 上架合规与发布（app_config.curfew / app_version） ---------------- */

    /**
     * 青少年模式时段闸门：只有 {@code app_user.minor=1} 的账号受约束。
     *
     * <p>{@code days} 用 ISO 周几（1=周一 … 7=周日），{@code extraDates} 是运营按年补的法定节假日
     * （{@code yyyy-MM-dd}，判定按**日期所在时区的当天**算，不是按周几）。刻意不做"每年自动更新节假日"：
     * 国务院公告是人工发布的，写死一份日历表反而会在下一年变成错的。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Curfew(Boolean enabled, String zone, List<Integer> days, String from, String to,
                         List<String> extraDates, String hint) {

        public static final Curfew DEFAULT = new Curfew(true, "Asia/Shanghai", List.of(5, 6, 7),
                "20:00", "21:00", List.of(),
                "未成年人仅在周五、周六、周日及法定节假日的 20:00-21:00 可游玩");

        public boolean on() { return !Boolean.FALSE.equals(enabled); }

        /** 时区缺失或写错（比如填了 "Asia/Shangai"）时回落上海，而不是抛异常把整帧请求带崩。 */
        public java.time.ZoneId zoneOr() {
            if (zone != null && !zone.isBlank()) {
                try { return java.time.ZoneId.of(zone.trim()); } catch (Exception ignore) { }
            }
            return java.time.ZoneId.of("Asia/Shanghai");
        }

        /**
         * 缺字段（null）回落到默认的周五六日，但显式写 {@code []} 就是"哪一天都不放行"——
         * 两者在引擎里必须不同，否则运营想临时收紧扣时会被兜底悄悄推翻（同 ad.slots 的 null/[] 之分）。
         */
        public List<Integer> daysOr() { return days == null ? DEFAULT.days() : days; }
        public List<String> extraDatesOr() { return extraDates == null ? List.of() : extraDates; }
        public String hintOr() { return hint == null || hint.isBlank() ? DEFAULT.hint() : hint; }
    }

    /** APP 版本门：壳启动时问一次，低于 minBuild 硬拦去更新，低于 latestBuild 给"建议更新"。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AppVersion(Integer minBuild, Integer latestBuild, String note, String url) {

        public static final AppVersion DEFAULT = new AppVersion(1, 1, "", "");

        public int minOr() { return minBuild == null || minBuild < 1 ? 1 : minBuild; }

        /** latest 落后于 min 是运营笔误，按 min 处理，别让"建议更新"永远不出现。 */
        public int latestOr() { return latestBuild == null || latestBuild < minOr() ? minOr() : latestBuild; }
    }

    /**
     * 升级经验曲线（G4 外提）：升到 lv+1 需要 {@code base + coef × lv²} 经验。
     *
     * <p>形状没变，只是搬进了配置，所以老存档的 exp/level 一律不用迁移。真正需要知道的是
     * <b>改了它会改变"在途经验"到等级的映射</b>：调低＝老玩家手里那笔经验立刻更值钱（可能一夜连升几级），
     * 调高＝同一笔经验换到的等级变少，面板上经验条会倒退。所以这条按"一次性发版动作"来用，
     * 不要在被投诉的当天随手拨。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LevelCurve(Integer base, Integer coef) {}

    /**
     * 事故结算参数（G4 外提）：三个概率 + 修复费 + 两件减损道具的倍率。
     *
     * <p>每一个都直接决定玩家掉多少金币，所以 {@code EngineConfigValidator} 存前逐个查范围：
     * 概率写成 5 会让每次成功实验都触发事故，修复费写成 0 等于取消惩罚——这两条都不该到引擎里才发生。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Accident(Double hit_base, Double hit_floor, Double safety_step,
                           Double danger_base, Double danger_floor, Double danger_step,
                           Double loss_base, Double loss_step, Double loss_ratio,
                           Long repair_base, Integer repair_exp_mult, Integer repair_exp_floor,
                           Double protect_mult, Double insured_refund) {

        public static final Accident DEFAULT = new Accident(0.5, 0.08, 0.1,
                0.3, 0.05, 0.04, 0.9, 0.12, 0.5, 100L, 2, 10, 0.2, 0.5);

        private static double or(Double v, double dft) { return v == null ? dft : v; }
        private static long or(Long v, long dft) { return v == null ? dft : v; }
        private static int or(Integer v, int dft) { return v == null ? dft : v; }

        /**
         * 成功实验时"装置受到冲击"的概率：下限保底，安全设施与温度计/铁架台各自再往下减。
         * 留下限是因为零事故会让安全设施与防护道具失去意义，玩法上也不该出现"怎么混都不炸"。
         */
        public double hitChance(int safety, double equipReduce) {
            return Math.max(or(hit_floor, 0.08), or(hit_base, 0.5) - safety * or(safety_step, 0.1) - equipReduce);
        }

        /** 危险混放直接炸台的比例（同样留下限，理由同上）。 */
        public double dangerChance(int safety, double equipReduce) {
            return Math.max(or(danger_floor, 0.05), or(danger_base, 0.3) - safety * or(danger_step, 0.04) - equipReduce);
        }

        /** 炸台损失：投入物质原价合计 × (损失系数 − 安全设施减免) × 折算比例。 */
        public long damage(long loss, int safety) {
            return Math.round(loss * Math.max(0d, or(loss_base, 0.9) - safety * or(loss_step, 0.12)) * or(loss_ratio, 0.5));
        }

        /** 冲击修复费：按反应经验定价，取不到经验时用兜底值，最终以玩家余额封顶。 */
        public long repairFee(int exp, long coins) {
            long unit = exp != 0 ? exp : or(repair_exp_floor, 10);
            return Math.min(coins, or(repair_base, 100L) + unit * or(repair_exp_mult, 2));
        }

        /** 防护罩吸收后的残留比例。 */
        public long absorbed(long dmg) { return Math.round(dmg * or(protect_mult, 0.2)); }

        /** 保险赔付比例（按投入价值算）。 */
        public long refund(long loss) { return Math.round(loss * or(insured_refund, 0.5)); }
    }

    /** config 字段名与 app_config 的 cfg_key 逐字对应（snake_case）。缺失字段有兜底默认。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Config(Double buy_rate, Double sell_rate, Double first_sell_bonus, Long start_coins,
                         Long tutorial_coins, Long quiz_reward,
                         Map<String, int[]> discover_bonus,
                         Map<String, LabUpgrade> lab_upgrades,
                         List<Quality> quality, List<String> tier_names, List<Integer> tier_up_cost,
                         List<Integer> milestones, List<Recharge> recharge, AdConfig ad,
                         Curfew curfew, AppVersion app_version, Boolean analytics_enabled,
                         LevelCurve level_exp, Integer bench_max_lines, Accident accident,
                         Map<String, Double> quiz_grade_mult) {

        /** 答题年级倍率的兜底：外提前写死在 EconomyService.GRADE_MULT 里的那份。 */
        private static final Map<String, Double> GRADE_DEFAULT =
                Map.of("小学", 1.0, "初中", 1.0, "高中", 1.2, "大学", 1.5);

        public double buyRate() { return buy_rate == null ? 1.2 : buy_rate; }
        public double sellRate() { return sell_rate == null ? 0.8 : sell_rate; }
        public double firstSellBonus() { return first_sell_bonus == null ? 1.5 : first_sell_bonus; }
        public long startCoins() { return start_coins == null ? 5000 : start_coins; }
        public long tutorialCoins() { return tutorial_coins == null ? 2000 : tutorial_coins; }
        public long quizReward() { return quiz_reward == null ? 120 : quiz_reward; }

        /**
         * 升到 lv+1 所需经验：{@code level_exp} 缺省（键还没灌 / JSON 写坏）时回落到外提前那条曲线。
         *
         * <p>下限锁 1：{@code addExp} 是 {@code while (exp >= need)} 的循环，一条 base 与 coef 都为 0
         * 的配置会让服务端在"加经验"这一帧里转不出来——这是唯一一个"配错会把进程挂住"的键，
         * 所以除了 {@code EngineConfigValidator} 存前拦，读的时候也兜一层。
         */
        public int expNeeded(int lv) {
            long base = level_exp == null || level_exp.base() == null ? 80 : level_exp.base();
            long coef = level_exp == null || level_exp.coef() == null ? 25 : level_exp.coef();
            long need = base + coef * (long) lv * lv;
            return (int) Math.max(1L, need);
        }

        /**
         * 单个容器同时容纳的物质种数（引擎投放时的台位上限）。锁在 1..64：
         * 0 会让"投放任何东西都被拒"，而一个离谱的大数等于取消这条玩法约束。
         */
        public int benchMaxLines() {
            int n = bench_max_lines == null ? 6 : bench_max_lines;
            return Math.max(1, Math.min(64, n));
        }

        public Accident accidentOr() { return accident == null ? Accident.DEFAULT : accident; }

        /** 年级倍率的兜底表：面板说明书与校验器都读它，避免仓库里出现第二份"默认倍率清单"。 */
        public static Map<String, Double> gradeFallback() { return GRADE_DEFAULT; }

        /**
         * 答题年级倍率。整个键缺失时回落到写死过的那份；<b>键在但少了某个年级</b>按 1.0（不加成）算——
         * 那是"运营把这个年级的加成取消了"，不是配置丢失，拿默认值盖掉会让人以为改不动。
         */
        public double quizGradeMult(String grade) {
            if (quiz_grade_mult == null) return GRADE_DEFAULT.getOrDefault(grade, 1.0);
            Double m = quiz_grade_mult.get(grade);
            return m == null ? 1.0 : m;
        }

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

        /** 退役键的历史透传：没有任何结算代码读它，见 {@code ConfigSpec("recharge")}。 */
        public List<Recharge> rechargeOr() { return recharge == null ? List.of() : recharge; }

        /** 广告目录缺失（库里还没这一键 / JSON 写坏了）时用代码默认值兜住，保证付费面下线后玩家仍有获取途径。 */
        public AdConfig adOr() { return ad == null ? AdConfig.DEFAULT : ad; }

        /**
         * 防沉迷缺失时**按默认开启**：合规项的兜底方向必须是"更严"，库里这行被误删不能变成对所有孩子放行。
         * 要主动关停是运营在面板里显式把 enabled 拨到 false，那是一条有意的、被审计记录在案的决定。
         */
        public Curfew curfewOr() { return curfew == null ? Curfew.DEFAULT : curfew; }

        public AppVersion appVersionOr() { return app_version == null ? AppVersion.DEFAULT : app_version; }

        /**
         * 埋点采集开关。默认开（现行行为），运营显式写 {@code false} 才是停。
         *
         * <p>关掉的只有"新事件写库"这一件事：接口照常回 200，客户端不需要为此发新版，
         * 也不会有玩家因为一条埋点失败看到错误。看板会因此停止增长，那是这个开关的用途而不是故障。
         */
        public boolean analyticsOn() { return !Boolean.FALSE.equals(analytics_enabled); }
    }
}
