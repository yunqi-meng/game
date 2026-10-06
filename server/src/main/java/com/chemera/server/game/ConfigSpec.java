package com.chemera.server.game;

import java.util.List;
import java.util.Set;

/**
 * 运营配置说明书：每个 app_config 键的中文名、作用范围、改动风险的颜色、编辑该用哪种表单、
 * 改了会怎样、以及读它的代码在哪。
 *
 * <p>刻意写死在代码里而不是存库：这份说明的唯一价值是"和引擎实际行为一致"，落到库里就没人保证。
 * {@code ConfigSpecTest} 会拿 {@link Content.Config} 的 record 组件比对，加字段不写说明即测试失败。
 *
 * <p><b>H6：面板上不再留第二份答案。</b>{@code form}（编辑器形态）与 {@code tone}（风险色阶）
 * 原来各是 {@code Config.vue} 里的一张表，于是后端加了 {@code RETIRED} 一档、前端那份色阶表却没有对应行，
 * "已退役"的 recharge 就跟着"仅前端"一起显示成温和的灰色——这类漂移不可能靠 review 挡住，
 * 只能靠"前端没有可漂移的副本"。所以：
 * <ul>
 *   <li>{@code form} 只有这里一份，格式 {@code 形态} 或 {@code 形态:步进:精度}（后者只给 number 用，
 *       步进与精度也是后端的话）；{@link #FORMS} 是形态的闭合集合，与面板的分支一一对应，
 *       {@code validate.js} 会拿它去核对 {@code Config.vue} 里真的每个分支都在。</li>
 *   <li>{@code tone} 不由人填，由 {@link #toneOf} 从 {@code scope} 推导；switch 遇到不认识的 scope
 *       直接抛——加一档 scope 而没定它的颜色，类初始化当场炸，而不是面板上安静地用错色。</li>
 * </ul>
 *
 * <p>scope 六档，按"改完到底影不影响结算"划分——运营同学真正需要区分的是这个：
 * <ul>
 *   <li>{@link #ENGINE} 服务端结算：引擎读它算钱/算奖励，改了立即影响所有玩家的对局结果。</li>
 *   <li>{@link #BOTH} 服务端+前端：引擎读，前端也拿同一份渲染文案。</li>
 *   <li>{@link #PARTIAL} 部分生效：键里只有一部分字段被引擎读，其余是展示文案或已被写死常数覆盖。</li>
 *   <li>{@link #SERVER} 服务端行为（不动结算）：服务端据此改变行为（采不采集、放不放行），但不碰资产与奖励。</li>
 *   <li>{@link #UI} 仅前端：只下发给客户端，服务端权威结算不读它——改它不会改变结算。</li>
 *   <li>{@link #RETIRED} 已退役：付费面下线后留下的历史键，服务端与前端都不再读取。行还在库里（不能改动已应用的迁移），
 *       但改它什么都不会发生。</li>
 * </ul>
 */
public record ConfigSpec(String key, String zh, String scope, String tone, String form,
                         String effect, String note, String refs) {

    /**
     * 面板上的每个键都必须显式回答"该给它哪种编辑器"，包括答案就是 {@code json} 的那几个，
     * 所以这里只有带 form 的规范构造器可用（不提供"少一个参数"的旧形状，免得新键悄悄漏掉）。
     */
    public ConfigSpec(String key, String zh, String scope, String form,
                      String effect, String note, String refs) {
        this(key, zh, scope, toneOf(scope), form, effect, note, refs);
    }

    public static final String ENGINE = "服务端结算";
    public static final String BOTH = "服务端+前端";
    public static final String PARTIAL = "部分生效";
    public static final String UI = "仅前端";
    public static final String RETIRED = "已退役（改它不生效）";
    /**
     * 服务端读取并据此改变行为，但**不参与任何资产/奖励结算**。
     *
     * <p>单独一档而不是塞进 {@link #ENGINE}：这一档的键（埋点采集、合规闸门）动的是"留不留记录、让不让人进"，
     * 调错的方向不是经济崩盘而是合规事故，运营看板的颜色与措辞需要区分开。
     */
    public static final String SERVER = "服务端行为（不动结算）";

    public static final List<ConfigSpec> ALL = List.of(
            new ConfigSpec("sell_rate", "卖出基础倍率", ENGINE, "number:0.1:2",
                    "玩家把物质卖给市场时的单价系数：卖价 = 物质原价 × sell_rate × 品质倍率 × 当日行情漂移。",
                    "调高会直接放大全局金币产出，是最容易把经济打崩的一个键；1.0 意味着原价回收。",
                    "EconomyService.sellPrice"),
            new ConfigSpec("buy_rate", "买入倍率（仅低声望档）", PARTIAL, "number:0.1:2",
                    "商会声望为「生面孔」（rep < 20）时，玩家从市场买入的单价系数。买价 = 原价 × buy_rate × 行情漂移。",
                    "声望 ≥20/50/100 后引擎改用写死的常客 1.16 / 贵宾 1.12 / 荣誉会员 1.08，不受本键影响。",
                    "EconomyService.repTier, buyPrice"),
            new ConfigSpec("first_sell_bonus", "首次出售加成", ENGINE, "number:0.1:2",
                    "每种物质第一次卖出时额外乘一次的价格系数，用来给「开图鉴就有钱」的开局节奏。",
                    "只作用于该物质的首卖，之后按 sell_rate 正常计价；调到 1 等于取消首卖奖励。",
                    "EconomyService.sellPrice"),
            new ConfigSpec("quality", "品质档与价格倍率", BOTH, "quality",
                    "定义产物品质（q 下标）的名称与计价倍率，参与卖价、合成投料估值与收购价三处结算。",
                    "q 是引擎算出来的档位下标，改 mult 会改结算；改 zh 只改显示；删掉某一档会让该品质回落到代码兜底倍率（0.7/1.0/1.5）。",
                    "EconomyService.sellPrice, Evaluator；Content.Config.qualityMult"),
            new ConfigSpec("milestones", "图鉴收集里程碑节点", PARTIAL, "numList",
                    "按「累计发现物质数」排布的奖励节点列表，玩家在面板上逐个领取。",
                    "节点本身生效；奖励金额引擎写死为 节点值 × 100 金币，并额外送一个 Lv.2 化合物，改本键改不动这个换算。",
                    "EconomyService.claimMilestone"),
            new ConfigSpec("tier_names", "容器档位名称", UI, "strList",
                    "实验台/仪器上显示的三档容器名字（普通 / 精密 / 专业），纯文案。",
                    "引擎只按 tier 下标 0..2 计算，不看名字；数组长度改变会让前端显示错位，务必保持 3 项。",
                    "frontend/js/panels.js, ui.js"),
            new ConfigSpec("tier_up_cost", "容器升档费用倍率", ENGINE, "numList",
                    "升级容器到下一档的花费倍率：费用 = 仪器原价 × tier_up_cost[当前 tier]。",
                    "数组下标即当前档位，只有两跳（0→1、1→2），所以只用到前两个元素；多出来的项不会报错也不会生效。",
                    "EconomyService.upgradeVessel"),
            new ConfigSpec("lab_upgrades", "实验室三条升级线", PARTIAL, "labMap",
                    "储物柜扩容 / 安全设施 / 工作台升级的名称、价格曲线与等级上限，购买流程（扣币、判上限）全部读它。",
                    "生效的字段：baseCost、growth、max（决定花费与封顶），以及 storage 的 step（库存上限 = 50 + 等级 × step）。"
                            + "G4 之后 safety 与 bench 的<b>效果参数也不再写死了，但它们不在本键里</b>：事故概率、事故损失、"
                            + "修复费、防护与理赔倍率全部搬到了 accident 键（按 accident 的说明改），"
                            + "单容器物质种数搬到了 bench_max_lines。本键里仍然不生效的只剩 desc 这类文案，"
                            + "以及批量上限 bench≥3 时为 20（还在引擎里写死，见 Candidate 上限）。",
                    "EconomyService.upgradeLab；GameEngine.cap；Content.Config.accidentOr / benchMaxLines"),
            new ConfigSpec("discover_bonus", "发现奖励区间", ENGINE, "bonusMap",
                    "按物质稀有度等级 1..4 给出的发现奖金区间，取值在 [下限, 上限] 之间按物质 id 的哈希确定（同一物质恒定），再取整到 10。",
                    "反应带来的「顺带发现」折半（×0.5 写死）；缺某个等级时回落到 50~200。",
                    "GameEngine.discoverBonus"),
            new ConfigSpec("quiz_reward", "答题基础奖励", ENGINE, "number:10:0",
                    "答对一道题的基础金币，实际发放 = quiz_reward × 年级倍率（年级倍率见 quiz_grade_mult）。",
                    "G4 之前年级倍率写死在 EconomyService 里，所以现在两个键要一起看：本键管基数，quiz_grade_mult 管各年级乘多少。",
                    "EconomyService.answerQuiz；Content.Config.quizGradeMult"),
            // G3/G4 那四个键刻意留在 json 编辑器：它们每一项的边界都由 EngineConfigValidator 逐条报出来
            // （"accident.repair_base 必须是整数"这类原话比任何控件都准），前端再为它们做一套数字框
            // 就是把同一份边界抄第二遍——正是这一轮要消掉的东西。见 EngineConfigValidator 末尾的说明。
            new ConfigSpec("level_exp", "升级经验曲线", ENGINE, "json",
                    "升到 lv+1 所需经验 = base + coef × lv²（默认 {\"base\":80,\"coef\":25}，即外提前 Content.levelExp 那条曲线）。",
                    "改它会改变「在途经验」到等级的映射：调低等于给所有玩家手里那笔经验升值（可能一夜连升几级），调高则面板经验条倒退。"
                            + "所以按一次性发版动作用，别在被投诉的当天随手拨。base 只能是 5~100000、coef 0~1000，"
                            + "存前由 EngineConfigValidator 拦；引擎读数时还会把单次所需经验兜到 ≥1，"
                            + "因为升级是 while 循环，一条 0 需求的曲线会把服务端那一帧转不出来。",
                    "GameEngine.addExp；Content.Config.expNeeded；EngineConfigValidator.levelExp"),
            new ConfigSpec("bench_max_lines", "单容器物质种数上限", ENGINE, "json",
                    "一个容器同时能放几种物质（默认 6）。投放第 N+1 种时服务端直接拒（「容器最多容纳 N 种物质」），前端文案读的是同一个数。",
                    "只能是 1~64：0 或负数会让投放任何东西都被拒（等于关掉游戏），大于 64 等于取消这条玩法约束、实验台也排不下。"
                            + "引擎读数时会把越界值夹到 1~64，所以报错只能靠存前这一道闸。改小会让玩家台上已有的组合当场放不下新物质。",
                    "GameEngine.place；Content.Config.benchMaxLines；EngineConfigValidator.benchLines"),
            new ConfigSpec("accident", "事故概率与赔付参数", ENGINE, "json",
                    "14 个数管着三件事：什么时候出事（hit_base/hit_floor/safety_step 是成功实验后「装置受到冲击」的概率，"
                            + "danger_base/danger_floor/danger_step 是危险混放直接炸台的概率，都要减去安全设施等级 × step 与温度计/铁架台的减免）；"
                            + "出事亏多少（loss_base/loss_step/loss_ratio：损失 = 投料原价 ×(loss_base − 安全等级×loss_step)× loss_ratio，"
                            + "protect_mult 是防护罩剩下的比例，insured_refund 是实验保险的赔付比例）；"
                            + "以及修复费（repair_base + 反应经验 × repair_exp_mult，取不到经验时用 repair_exp_floor，最终以玩家余额封顶）。",
                    "默认值就是 G4 之前写死在 GameEngine 里的那一串，所以迁移当天没有任何玩家结算发生变化。"
                            + "概率与比例只能 0~1，步长只能 0~1，repair_* 只能非负整数；floor 不低于 base 会让安全设施与道具减免整个失效——"
                            + "这些都由 EngineConfigValidator 存前拦，因为引擎拿到越界值不报错，它直接按那个数算钱。"
                            + "调高 hit/danger 是「提高出事频率」，调高 loss_* 是「提高单次惩罚」，两者对玩家的体感完全不同，别混着拨。",
                    "GameEngine.react(hitChance/repairFee), rollDanger(dangerChance), boom(damage/absorbed/refund)；"
                            + "Content.Accident；EngineConfigValidator.accident"),
            new ConfigSpec("quiz_grade_mult", "答题年级倍率", ENGINE, "json",
                    "答对题的年级加成：实发 = quiz_reward × quiz_grade_mult[题目 grade]。默认 {\"小学\":1.0,\"初中\":1.0,\"高中\":1.2,\"大学\":1.5}。",
                    "整个键删掉＝回落到这份默认（与外提前一致）；<b>键在但少了某个年级＝该年级不加成（按 1.0 算）</b>，"
                            + "那是「主动取消这一档的加成」，不会被默认值悄悄盖回去。年级名必须来自题目的 grade 闭集（现在含小学）。"
                            + "答对掉钻的概率仍写死在 EconomyService（大学 0.3 / 高中 0.15 / 其余 0.05），不在本键里。",
                    "EconomyService.answerQuiz；Content.Config.quizGradeMult；EngineConfigValidator.gradeMult"),
            new ConfigSpec("tutorial_coins", "新手引导完成奖励", ENGINE, "number:100:0",
                    "玩家走完教程第 3 步时一次性发放的金币。",
                    "只发一次（教程进度存在存档里），调高不会让老玩家重领。",
                    "GameService（g.tutorial == 3 分支）"),
            new ConfigSpec("start_coins", "新号初始金币", UI, "number:100:0",
                    "前端创建本地初始存档时写入的金币数（state.js 用 CHEM.START_COINS）。",
                    "注意：服务端权威结算新建档时用的是 GameEngine 里写死的 5000，所以改这个值不会改变玩家实际到手的金币。要调开局经济请改引擎初始值或发补偿邮件，别指望本键。",
                    "frontend/js/state.js；服务端初始值未接本键"),
            new ConfigSpec("ad", "激励视频目录与闸门", ENGINE, "ad",
                    "本作唯一的获取途径：slots 定义每个广告位看完给什么（reward ∈ coins/diamonds/hints/coupon/revive/monthly_days/pack_el/skin，"
                            + "amount 是数量，daily 是每人每日次数上限，cooldownSec 是同一位两次之间的冷却），"
                            + "unlocks 定义用累计观看积分兑换的项目（cost 是积分价，once 项只能兑一次）；"
                            + "enabled=false 直接关停整个广告中心，dailyTotal 是每人每日可完成的广告总数上限，"
                            + "minLevel 是进入广告中心需要的等级，ticketTtlSec 是工单有效期（签发后多久内回调算数），"
                            + "viewPoints 是每次完成给的观看积分。",
                    "全部由服务端结算：签发时把 reward/amount 定格进 ad_ticket，改配置只影响之后新签发的工单，"
                            + "已经签发但没回调的工单仍按原承诺发放，所以调低数值不会让老玩家少拿。"
                            + "reward 写成引擎不认的字符串＝该广告位/兑换项直接消失（不报错也不发），改完务必看 health 与玩家端有没有该位。"
                            + "扣掉的档位（daily=0）等于下线该位。真实到账依赖广告网络回调验签通过：口令没配（CHEMERA_AD_SECURITY_KEY）"
                            + "且没开演示模式时，广告看完也不会发奖励。",
                    "AdService.request/settle/exchange；Content.AdConfig"),
            new ConfigSpec("curfew", "青少年模式时段闸门", SERVER, "curfew",
                    "只约束 app_user.minor=1 的账号（玩家自行开启青少年模式，或运营在【用户管理】里标记）。"
                            + "days 是允许游玩的 ISO 周几（1=周一 … 7=周日），from/to 是当天的放行区间（HH:mm），"
                            + "extraDates 是运营按年补的法定节假日（yyyy-MM-dd，命中即当天按 from/to 放行），"
                            + "zone 决定「现在几点」按哪个时区算，hint 是给玩家看的那句话。",
                    "闸门在 GameService.act 最前面：落在时段外的**所有玩法意图一律回绝**（含取帧），"
                            + "不是前端把按钮置灰——客户端能改的东西都不算数。enabled=false 等于对所有账号放行，"
                            + "这是合规开关，除非有明确指示否则不要关。from≥to 的窗口（跨零点）按「当天 from 起、次日 to 止」理解，"
                            + "但监管口径里的 20:00-21:00 不跨零点，别拿这个当常态用。改完要拿一个 minor 账号取一次帧确认仍被拒。",
                    "CurfewGuard.assertAllowed/view；GameService.act；ComplianceConfigValidator"),
            new ConfigSpec("app_version", "APP 版本门（强更/提示更新）", SERVER, "appVersion",
                    "minBuild=可接受的最低 versionCode，latestBuild=当前最新一版，note 是更新说明，url 是下载/商店页地址。"
                            + "壳启动时问 GET /api/app/version：低于 minBuild 弹「必须更新」并拦住进入，"
                            + "介于两者之间给「建议更新」（可跳过一次）。",
                    "只影响安卓/iOS 壳的启动流程，H5 浏览器端不读它，所以改错不会伤害现有玩家。"
                            + "真正的硬约束在服务端：minBuild 抬高后老包进不来，这是防止「旧客户端 + 新协议」打出无从解释错误的唯一手段。"
                            + "latestBuild 小于 minBuild 会被按 minBuild 处理（视为笔误）。发版时记得同步递增。",
                    "AppVersionController；Content.Config.appVersionOr"),
            new ConfigSpec("analytics_enabled", "行为埋点采集开关", SERVER, "flag",
                    "控制服务端要不要把 POST /api/analytics/event 上报的行为写进 analytics_event 表"
                            + "（看板上的反应成功/Boom/发现/买卖等曲线全部来自这张表）。缺省按开。",
                    "关掉不改变任何结算，也不会让玩家看到错误：接口照旧回 200，只是这一条不再入库，"
                            + "于是看板从关闭那一刻起停止增长、日活与留存断崖——那是这个开关的用途，不是故障。"
                            + "已入库的历史记录不会因此删除，要清理得单独走 DB。它是隐私政策里"
                            + "「你可以要求停止收集行为数据」这句话唯一的兑现点，所以别当成省字节的开关乱拨。",
                    "AnalyticsController.event；Content.Config.analyticsOn"),
            new ConfigSpec("recharge", "旧充值档位表（已随付费面下线）", RETIRED, "recharge",
                    "原来按人民币金额换算钻石的档位表（6/30/98 元三档）。本作不再收费，钻石全部由看激励视频与玩法产出，服务端不再按此键发任何资产。",
                    "改它不会有任何效果：GameService 已把 shop.recharge / shop.buy 改成回绝提示，引擎里也没有代码再读这个字段。"
                            + "库里保留这一行只是因为不能改动已应用的 Flyway 迁移（校验和），要彻底清理得等新库重跑种子。",
                    "GameService.shop.recharge（回绝）；Content.Config.recharge 仅作历史下发"));

    /**
     * 面板编辑器的形态闭合集合（冒号后的参数只对 {@code number} 有意义：步进与小数位）。
     *
     * <p>这里列的每一项都必须在 {@code admin/src/views/Config.vue} 有对应的分支，反之亦然——
     * {@code test/validate.js} 拿这份集合去核对 Vue 源码，两边多出来的都算失败。
     * 之所以由后端出这张表：编辑器形态取决于"引擎真正怎么读这个值"，那句话只在这里有。
     */
    public static final Set<String> FORMS = Set.of(
            "json", "number", "strList", "numList", "bonusMap", "quality",
            "recharge", "ad", "curfew", "appVersion", "flag", "labMap");

    /** 色阶令牌：面板把它映射成 el-tag / el-alert 的类型，前端不再按 scope 中文自己判断。 */
    public static final String TONE_DANGER = "danger";
    public static final String TONE_WARNING = "warning";
    /** 服务端行为档：不动一分钱，但动的是合规，要显眼又不能和经济风险同色。 */
    public static final String TONE_GUARD = "guard";
    public static final String TONE_INFO = "info";
    public static final String TONE_RETIRED = "retired";

    /**
     * 生效范围 → 风险色阶。刻意用会抛的 switch 而不是"找不到就给个默认灰"：
     * 加一档 scope 却忘了定颜色，宁可在类初始化当场炸（后台整页打不开，一定有人问），
     * 也不要安静地用最温和的那档显示——上一轮 {@code 已退役} 就是这么被显示成 {@code 仅前端} 的灰色的。
     */
    static String toneOf(String scope) {
        return switch (scope) {
            case ENGINE -> TONE_DANGER;
            case BOTH, PARTIAL -> TONE_WARNING;
            case SERVER -> TONE_GUARD;
            case UI -> TONE_INFO;
            case RETIRED -> TONE_RETIRED;
            default -> throw new IllegalArgumentException("ConfigSpec 出现未知的 scope，面板不知道该用什么风险色提示运营：" + scope);
        };
    }

    /** 表单形态（去掉冒号后的参数）。 */
    public String formKind() {
        int i = form.indexOf(':');
        return i < 0 ? form : form.substring(0, i);
    }

    public static ConfigSpec of(String key) {
        for (ConfigSpec s : ALL) if (s.key().equals(key)) return s;
        return null;
    }
}
