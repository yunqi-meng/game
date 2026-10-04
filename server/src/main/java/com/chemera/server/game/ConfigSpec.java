package com.chemera.server.game;

import java.util.List;

/**
 * 运营配置说明书：每个 app_config 键的中文名、作用范围、改了会怎样、以及读它的代码在哪。
 *
 * <p>刻意写死在代码里而不是存库：这份说明的唯一价值是"和引擎实际行为一致"，落到库里就没人保证。
 * {@code ConfigSpecTest} 会拿 {@link Content.Config} 的 record 组件比对，加字段不写说明即测试失败。
 *
 * <p>scope 四档，按"改完到底影不影响结算"划分——运营同学真正需要区分的是这个：
 * <ul>
 *   <li>{@link #ENGINE} 服务端结算：引擎读它算钱/算奖励，改了立即影响所有玩家的对局结果。</li>
 *   <li>{@link #BOTH} 服务端+前端：引擎读，前端也拿同一份渲染文案。</li>
 *   <li>{@link #PARTIAL} 部分生效：键里只有一部分字段被引擎读，其余是展示文案或已被写死常数覆盖。</li>
 *   <li>{@link #UI} 仅前端：只下发给客户端，服务端权威结算不读它——改它不会改变结算。</li>
 * </ul>
 */
public record ConfigSpec(String key, String zh, String scope, String effect, String note, String refs) {

    public static final String ENGINE = "服务端结算";
    public static final String BOTH = "服务端+前端";
    public static final String PARTIAL = "部分生效";
    public static final String UI = "仅前端";

    public static final List<ConfigSpec> ALL = List.of(
            new ConfigSpec("sell_rate", "卖出基础倍率", ENGINE,
                    "玩家把物质卖给市场时的单价系数：卖价 = 物质原价 × sell_rate × 品质倍率 × 当日行情漂移。",
                    "调高会直接放大全局金币产出，是最容易把经济打崩的一个键；1.0 意味着原价回收。",
                    "EconomyService.sellPrice"),
            new ConfigSpec("buy_rate", "买入倍率（仅低声望档）", PARTIAL,
                    "商会声望为「生面孔」（rep < 20）时，玩家从市场买入的单价系数。买价 = 原价 × buy_rate × 行情漂移。",
                    "声望 ≥20/50/100 后引擎改用写死的常客 1.16 / 贵宾 1.12 / 荣誉会员 1.08，不受本键影响。",
                    "EconomyService.repTier, buyPrice"),
            new ConfigSpec("first_sell_bonus", "首次出售加成", ENGINE,
                    "每种物质第一次卖出时额外乘一次的价格系数，用来给「开图鉴就有钱」的开局节奏。",
                    "只作用于该物质的首卖，之后按 sell_rate 正常计价；调到 1 等于取消首卖奖励。",
                    "EconomyService.sellPrice"),
            new ConfigSpec("quality", "品质档与价格倍率", BOTH,
                    "定义产物品质（q 下标）的名称与计价倍率，参与卖价、合成投料估值与收购价三处结算。",
                    "q 是引擎算出来的档位下标，改 mult 会改结算；改 zh 只改显示；删掉某一档会让该品质回落到代码兜底倍率（0.7/1.0/1.5）。",
                    "EconomyService.sellPrice, Evaluator；Content.Config.qualityMult"),
            new ConfigSpec("milestones", "图鉴收集里程碑节点", PARTIAL,
                    "按「累计发现物质数」排布的奖励节点列表，玩家在面板上逐个领取。",
                    "节点本身生效；奖励金额引擎写死为 节点值 × 100 金币，并额外送一个 Lv.2 化合物，改本键改不动这个换算。",
                    "EconomyService.claimMilestone"),
            new ConfigSpec("tier_names", "容器档位名称", UI,
                    "实验台/仪器上显示的三档容器名字（普通 / 精密 / 专业），纯文案。",
                    "引擎只按 tier 下标 0..2 计算，不看名字；数组长度改变会让前端显示错位，务必保持 3 项。",
                    "frontend/js/panels.js, ui.js"),
            new ConfigSpec("tier_up_cost", "容器升档费用倍率", ENGINE,
                    "升级容器到下一档的花费倍率：费用 = 仪器原价 × tier_up_cost[当前 tier]。",
                    "数组下标即当前档位，只有两跳（0→1、1→2），所以只用到前两个元素；多出来的项不会报错也不会生效。",
                    "EconomyService.upgradeVessel"),
            new ConfigSpec("lab_upgrades", "实验室三条升级线", PARTIAL,
                    "储物柜扩容 / 安全设施 / 工作台升级的名称、价格曲线与等级上限，购买流程（扣币、判上限）全部读它。",
                    "生效的字段：baseCost、growth、max（决定花费与封顶），以及 storage 的 step（库存上限 = 50 + 等级 × step）。不生效的字段：safety 与 bench 的实际效果写死在引擎里——事故概率 0.3 − 等级×0.04、事故损失 0.9 − 等级×0.12、批量上限 bench≥3 时为 20，改 desc/step 都不会改变这些数字。",
                    "EconomyService.upgradeLab；GameEngine.cap, rollDanger, boom, Candidate 上限"),
            new ConfigSpec("discover_bonus", "发现奖励区间", ENGINE,
                    "按物质稀有度等级 1..4 给出的发现奖金区间，取值在 [下限, 上限] 之间按物质 id 的哈希确定（同一物质恒定），再取整到 10。",
                    "反应带来的「顺带发现」折半（×0.5 写死）；缺某个等级时回落到 50~200。",
                    "GameEngine.discoverBonus"),
            new ConfigSpec("quiz_reward", "答题基础奖励", PARTIAL,
                    "答对一道题的基础金币，实际发放 = quiz_reward × 年级倍率。",
                    "年级倍率写死为 小学/初中 1.0、高中 1.2、大学 1.5，不在本键里。",
                    "EconomyService.answerQuiz, GRADE_MULT"),
            new ConfigSpec("tutorial_coins", "新手引导完成奖励", ENGINE,
                    "玩家走完教程第 3 步时一次性发放的金币。",
                    "只发一次（教程进度存在存档里），调高不会让老玩家重领。",
                    "GameService（g.tutorial == 3 分支）"),
            new ConfigSpec("start_coins", "新号初始金币", UI,
                    "前端创建本地初始存档时写入的金币数（state.js 用 CHEM.START_COINS）。",
                    "注意：服务端权威结算新建档时用的是 GameEngine 里写死的 5000，所以改这个值不会改变玩家实际到手的金币。要调开局经济请改引擎初始值或发补偿邮件，别指望本键。",
                    "frontend/js/state.js；服务端初始值未接本键"),
            new ConfigSpec("recharge", "模拟充值档位", BOTH,
                    "充值面板的档位列表：c = 标价（元），d = 到账钻石，按下标 tier 选择，点了直接加钻石。",
                    "当前是模拟支付、不接真实渠道；删档位只影响之后能选哪些档，已经到账的钻石不会回收。",
                    "EconomyService.recharge"));

    public static ConfigSpec of(String key) {
        for (ConfigSpec s : ALL) if (s.key().equals(key)) return s;
        return null;
    }
}
