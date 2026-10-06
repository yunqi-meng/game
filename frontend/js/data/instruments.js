/* 仪器与全局配置数据 */
window.CHEM = window.CHEM || {};

/* kind: vessel=反应容器(工作台上可放置) equipment=辅助设备(解锁条件控制) */
CHEM.INSTRUMENTS = [
  { id:"testtube", zh:"试管", kind:"vessel", cat:"反应容器", baseCost:80, desc:"少量试剂的反应容器，可直接加热。", unlockLv:1 },
  { id:"beaker", zh:"烧杯", kind:"vessel", cat:"反应容器", baseCost:150, desc:"配制溶液、较大量试剂反应。", unlockLv:1 },
  { id:"flask", zh:"锥形瓶", kind:"vessel", cat:"反应容器", baseCost:320, desc:"振荡方便，滴定与制气常用。", unlockLv:6 },
  { id:"evapor", zh:"蒸发皿", kind:"vessel", cat:"反应容器", baseCost:260, desc:"蒸发结晶，从溶液中获得晶体。", unlockLv:8 },
  { id:"crucible", zh:"坩埚", kind:"vessel", cat:"反应容器", baseCost:600, desc:"耐高温，固体灼烧专用。", unlockLv:11 },
  { id:"spoon", zh:"燃烧匙", kind:"vessel", cat:"反应容器", baseCost:120, desc:"盛放少量固体进行燃烧实验。", unlockLv:2 },

  { id:"lamp", zh:"酒精灯", kind:"equipment", cat:"加热工具", baseCost:200, desc:"提供『加热』条件（约500℃）。", unlockLv:1 },
  { id:"blowtorch", zh:"酒精喷灯", kind:"equipment", cat:"加热工具", baseCost:1500, desc:"提供『高温』条件（约1000℃）。", unlockLv:9 },
  { id:"waterbath", zh:"水浴锅", kind:"equipment", cat:"加热工具", baseCost:900, desc:"温和均匀加热，某些有机反应必需。", unlockLv:14 },
  { id:"electrolyzer", zh:"电解槽", kind:"equipment", cat:"计量仪器", baseCost:1800, desc:"提供『电解』条件。", unlockLv:5 },
  { id:"balance", zh:"电子天平", kind:"equipment", cat:"计量仪器", baseCost:700, desc:"精确称量，批量合成损耗降低。", unlockLv:10 },
  { id:"phmeter", zh:"pH计", kind:"equipment", cat:"精密仪器", baseCost:2600, desc:"精确监控中和反应，中和类反应经验+20%。", unlockLv:12 },
  { id:"spectrometer", zh:"分光光度计", kind:"equipment", cat:"精密仪器", baseCost:9000, desc:"可提纯物质：粗产物精炼升级。", unlockLv:16 },

  /* —— 分离提纯与量器扩展 —— */
  { id:"funnel", zh:"漏斗", kind:"vessel", cat:"分离提纯", baseCost:90, desc:"配合滤纸进行过滤，可分离沉淀与溶液。", unlockLv:4, proc:"filter" },
  { id:"sepfunnel", zh:"分液漏斗", kind:"vessel", cat:"分离提纯", baseCost:400, desc:"分离互不相溶的液体；有机类反应产率+5%。", unlockLv:14, yieldBonus:0.05 },
  { id:"distflask", zh:"蒸馏烧瓶", kind:"vessel", cat:"分离提纯", baseCost:650, desc:"蒸馏分离液体混合物（需冷凝管）。", unlockLv:15, proc:"distill" },
  { id:"watchglass", zh:"表面皿", kind:"vessel", cat:"反应容器", baseCost:70, desc:"微量反应与观察载体。", unlockLv:5 },
  { id:"spotplate", zh:"点滴板", kind:"vessel", cat:"反应容器", baseCost:60, desc:"微量实验，沉淀类反应产率+5%。", unlockLv:5, yieldBonus:0.05 },
  { id:"mortar", zh:"研钵", kind:"vessel", cat:"辅助工具", baseCost:130, desc:"研磨固体增大接触面，产率+5%。", unlockLv:6, yieldBonus:0.05 },
  { id:"cylinder", zh:"量筒", kind:"vessel", cat:"计量仪器", baseCost:110, desc:"量取液体。⚠️不可加热，误加热会炸裂！", unlockLv:3, noHeat:true },
  { id:"volumetric", zh:"容量瓶", kind:"vessel", cat:"计量仪器", baseCost:360, desc:"精确配制溶液，批量上限+5。⚠️不可加热。", unlockLv:10, noHeat:true, batchBonus:5 },
  { id:"condenser", zh:"冷凝管", kind:"equipment", cat:"分离提纯", baseCost:520, desc:"蒸馏必备，与蒸馏烧瓶配套使用。", unlockLv:15 },
  { id:"centrifuge", zh:"离心机", kind:"equipment", cat:"分离提纯", baseCost:1300, desc:"沉淀类反应产物 +1 份。", unlockLv:13 },
  { id:"thermometer", zh:"温度计", kind:"equipment", cat:"辅助工具", baseCost:180, desc:"监控温度，事故概率 -15%。", unlockLv:7 },
  { id:"dropper", zh:"胶头滴管", kind:"equipment", cat:"辅助工具", baseCost:50, desc:"精确滴加，投放时 15% 概率不消耗物质。", unlockLv:2 },
  { id:"spatula", zh:"药匙", kind:"equipment", cat:"辅助工具", baseCost:40, desc:"取用固体粉末，产率+3%。", unlockLv:2 },
  { id:"stand", zh:"铁架台（含试管夹）", kind:"equipment", cat:"辅助工具", baseCost:160, desc:"稳固装置，事故概率再 -10%。", unlockLv:4 }
];

/* 分离提纯工艺：先于普通反应匹配 */
CHEM.PROCESSES = [
  { id:"P01", zh:"粗盐过滤", vessel:"funnel", consume:"filterpaper", reactants:{"SLAG":2, "NaCl":2}, products:{"NaCl":2}, temp:"room", eq:"过滤：除去粗盐中的不溶性杂质", phenomenon:"滤纸上留下固体残渣，得到澄清滤液", fx:["dissolve"], type:"分离提纯", exp:15, q:1, tip:"一贴二低三靠。" },
  { id:"P02", zh:"无水乙醇蒸馏", vessel:"distflask", needEquip:"condenser", reactants:{"C2H5OH":2, "H2O":1}, products:{"C2H5OH":2}, temp:"heat", eq:"蒸馏：乙醇与水混合液 → 收集 78℃ 馏分", phenomenon:"温度计示数稳定在 78℃ 左右，锥形瓶收集到无色液体", fx:["bubble","smoke"], type:"分离提纯", exp:25, q:1, tip:"加碎瓷片防暴沸，冷凝水下进上出。" },
  { id:"P03", zh:"碘的升华分离", vessel:"crucible", reactants:{"I2":1, "NaCl":2}, products:{"I2":1}, temp:"heat", eq:"加热升华：I₂(固) → I₂(蒸气) → I₂(纯)", phenomenon:"紫色蒸气升起，在冷表面重新凝为紫黑色有光泽晶体", fx:["smoke","glow"], type:"分离提纯", exp:20, q:1, tip:"碘升华是物理变化，可用于分离碘与不挥发盐。" }
];

/* 实验室房间：每个已解锁房间 = 一台独立工作台；专长反应类型经验+30% */
CHEM.ROOMS = [
  { id:"inorganic", zh:"基础无机室", cost:0, unlockLv:1, types:[], desc:"一切开始的地方，通用无机反应实验室。" },
  { id:"analysis", zh:"分析化学室", cost:60000, unlockLv:12, types:["沉淀反应", "中和反应", "复分解反应"], desc:"鉴定与提纯 specialists，沉淀/中和类反应经验 +30%。" },
  { id:"physchem", zh:"物理化学室", cost:80000, unlockLv:13, types:["电解反应", "燃烧反应", "催化反应", "氧化还原反应"], desc:"能量与动力学，电解/燃烧/催化类经验 +30%。" },
  { id:"organic", zh:"有机化学室", cost:120000, unlockLv:15, types:["有机反应", "水解反应", "聚合反应"], desc:"碳的世界，有机/水解/聚合类经验 +30%。" },
  { id:"hiprecision", zh:"高精尖实验室", cost:200000, unlockLv:18, types:["聚合反应", "有机反应", "电解反应"], desc:"前沿阵地，全部条件判定宽松一档（产率+10%）。" }
];

/* 消耗品 */
CHEM.CONSUMABLES = [
  { id:"filterpaper", zh:"滤纸", price:6, desc:"过滤操作每使用一次消耗 1 张。" },
  { id:"reagentbottle", zh:"试剂瓶", price:18, desc:"存放纯产物需要，挂单售卖每 10 份消耗 1 个。" },
  { id:"protectormask", zh:"防护罩", price:120, desc:"佩戴后下一次实验事故损失 -80%（消耗品）。" }
];

CHEM.MILESTONES = [20, 50, 90, 140, 190];  /* 图鉴收集节点奖励（种数） */

CHEM.NPCS = [
  { id:"npc1", zh:"卷耳研究员", emoji:"🐱", focus:"酸碱盐", discovered: 96, gift:{ id:"Na2CO3", n: 3 } },
  { id:"npc2", zh:"阿尔法博士", emoji:"🤖", focus:"有机合成", discovered: 142, gift:{ id:"C2H5OH", n: 2 } },
  { id:"npc3", zh:"小煤球", emoji:"🔥", focus:"燃烧与爆炸", discovered: 61, gift:{ id:"KMnO4", n: 2 } },
  { id:"npc4", zh:"柠檬酸女士", emoji:"🍋", focus:"生活化学", discovered: 118, gift:{ id:"CH3COOH", n: 3 } }
];

/* 【历史种子，不再上架】CHEM.DSHOP / CHEM.RECHARGE 只用来生成 Flyway V2/V3 的种子数据（tools/export-seed.mjs），
   客户端已不再渲染它们：付费面下线后，钻石礼包/月卡/皮肤全部改到【设置·广告】用激励视频积分兑换，
   服务端对 shop.buy、shop.recharge 也回了回绝。库里这几行仍在（不能改已应用的迁移），运营要清理请在后台下架。 */
CHEM.DSHOP = [
  { id:"monthly", zh:"月卡会员", d: 30, desc: "30 天：每日补贴 💎3+🪙800，挂单免手续费，提纯不耗机器。" },
  { id:"elpack", zh:"镧系·锕系礼包", d: 20, desc: "无视等级线，市场直接解锁全部镧系/锕系元素。" },
  { id:"noad", zh:"永久去广告", d: 25, desc: "移除所有激励视频（含复活/双倍/刷新）。" },
  { id:"hint5", zh:"合成提示 ×5", d: 5, desc: "助手精灵可揭示 5 个未发现的方程式。" },
  { id:"skin_cyber", zh:"皮肤·赛博纪元", d: 15, desc: "深色霓虹实验室主题。" },
  { id:"skin_retro", zh:"皮肤·复古炼金", d: 15, desc: "暖棕黄铜的复古主题。" }
];
CHEM.RECHARGE = [
  { c: 6, d: 60 }, { c: 30, d: 330 }, { c: 98, d: 1180 }
];

/* ========================= 以下为静态配置 ========================= */

/* 仪器品质等级：容器可升级到 精密/专业，决定产物品质 */
CHEM.QUALITY = [
  { q:0, zh:"粗产物", mult:0.7 },
  { q:1, zh:"纯产物", mult:1.0 },
  { q:2, zh:"高纯产物", mult:1.5 }
];
CHEM.TIER_NAMES = ["普通", "精密", "专业"];

/* 升级容器费用 = baseCost * [3, 8] */
CHEM.TIER_UP_COST = [3, 8];

CHEM.LAB_UPGRADES = {
  storage: { zh:"储物柜扩容", desc:"每种物质库存上限 +50", step:50, baseCost:800, growth:1.6, max:10 },
  safety:  { zh:"安全设施", desc:"事故损失与概率降低", step:1, baseCost:1500, growth:1.8, max:5 },
  bench:   { zh:"工作台", desc:"合成成本预览更精准、批量倍率上限+5", step:1, baseCost:2500, growth:1.7, max:5 }
};

/* ---------- 结算参数（G4 起真源在 app_config，下面这份只是"后端还没下发时"的基线） ----------
   这些默认值必须与服务端 Content.Config 的兜底逐字相同：由 test/config-parity.js 与
   EngineConfigValidatorTest 两头钉住（一头改了另一头就红），否则首帧显示的数字与服务端判定会分裂。 */
CHEM.LEVEL_EXP_CFG = { base: 80, coef: 25 };       /* 升到 lv+1 需要 base + coef×lv² 经验 */
CHEM.BENCH_MAX_LINES = 6;                          /* 单个容器同时容纳的物质种数 */
CHEM.QUIZ_GRADE_MULT = { 小学: 1.0, 初中: 1.0, 高中: 1.2, 大学: 1.5 };
CHEM.ACCIDENT = {
  hit_base: 0.5, hit_floor: 0.08, safety_step: 0.1,
  danger_base: 0.3, danger_floor: 0.05, danger_step: 0.04,
  loss_base: 0.9, loss_step: 0.12, loss_ratio: 0.5,
  repair_base: 100, repair_exp_mult: 2, repair_exp_floor: 10,
  protect_mult: 0.2, insured_refund: 0.5
};

/* 经验条用的曲线：读 LEVEL_EXP_CFG，且与引擎一样把单次需求兜到 ≥1（0 会让进度条除成 Infinity）。 */
CHEM.LEVEL_EXP = function (lv) {
  var c = CHEM.LEVEL_EXP_CFG || {};
  var base = c.base == null ? 80 : c.base, coef = c.coef == null ? 25 : c.coef;
  return Math.max(1, base + coef * lv * lv);
};
/** 答对一题给多少金币：与服务端 EconomyService 同一个算法（基础值 × 年级倍率后取整）。
    键在但查不到该年级 = 1.0（运营主动取消加成），与 Content.Config.quizGradeMult 的读法一致。 */
CHEM.quizReward = function (grade) {
  var m = CHEM.QUIZ_GRADE_MULT || {};
  var mult = (m[grade] == null) ? 1.0 : m[grade];
  return Math.round((CHEM.QUIZ_REWARD || 120) * mult);
};
/** 年级倍率不止一档时，入口文案给个区间（"120~180"）而不是某一档的数，免得和实际到账不符。 */
CHEM.quizRewardRange = function () {
  var m = CHEM.QUIZ_GRADE_MULT || {}, vals = [];
  Object.keys(m).forEach(function (k) { vals.push(Math.round((CHEM.QUIZ_REWARD || 120) * (m[k] == null ? 1.0 : m[k]))); });
  if (!vals.length) return String(CHEM.quizReward("all"));
  var lo = Math.min.apply(null, vals), hi = Math.max.apply(null, vals);
  return lo === hi ? String(lo) : lo + "~" + hi;
};
/** 保险理赔比例的展示文本：唯一真源是 accident.insured_refund，别再在 UI 里写死 50%。 */
CHEM.insurancePct = function () {
  var r = (CHEM.ACCIDENT && CHEM.ACCIDENT.insured_refund != null) ? CHEM.ACCIDENT.insured_refund : 0.5;
  return Math.round(r * 100) + "%";
};

CHEM.START_COINS = 5000;
CHEM.TUTORIAL_COINS = 2000;
CHEM.QUIZ_REWARD = 120;
CHEM.SELL_RATE = 0.8;
CHEM.BUY_RATE = 1.2;
CHEM.FIRST_SELL_BONUS = 1.5;

CHEM.DISCOVER_BONUS = { 1:[50,200], 2:[200,800], 3:[800,3000], 4:[3000,10000] };

CHEM.DAILY_TASKS = [
  { id:"tSynth3",  zh:"完成 3 次成功实验",    goal:3, reward:600,  key:"success" },
  { id:"tNew2",    zh:"发现 2 种新物质",      goal:2, reward:900,  key:"discover" },
  { id:"tTrade1",  zh:"在市场完成 1 笔交易",  goal:1, reward:400,  key:"trade" },
  { id:"tQuiz2",   zh:"答对 2 道化学题",      goal:2, reward:500,  key:"quiz" }
];

/* 成就：cond 是"达成条件"的描述符（G4 起与 content_item 的 cond 同源，词表见服务端 AchievementRule.Metric）。
   客户端只用它点亮角标与成就面板，真正的领取判定在服务端；但两边读同一份描述符，
   运营把阈值从 100 改成 5 时，界面不会还按老阈值亮着。 */
CHEM.ACHIEVEMENTS = [
  { id:"aFirst",    zh:"初次合成",     desc:"完成第一次成功实验", reward:300, cond:{ metric:"success", op:"ge", value:1 } },
  { id:"aWater",    zh:"生命之源",     desc:"合成水", reward:500, cond:{ metric:"discoveredSubstance", subject:"H2O" } },
  { id:"aGold",     zh:"点石成金",     desc:"获得金元素或其化合物", reward:2000, cond:{ metric:"discoveredSubstance", subject:"Au" } },
  { id:"aBoom",     zh:"第一次爆炸",   desc:"经历一次实验事故", reward:200, cond:{ metric:"boom", op:"ge", value:1 } },
  { id:"aS100",     zh:"百炼成钢",     desc:"累计成功实验 100 次", reward:3000, cond:{ metric:"success", op:"ge", value:100 } },
  { id:"aD20",      zh:"元素探索者",   desc:"图鉴收集 20 种物质", reward:800, cond:{ metric:"discoveredCount", op:"ge", value:20 } },
  { id:"aD80",      zh:"物质收藏家",   desc:"图鉴收集 80 种物质", reward:4000, cond:{ metric:"discoveredCount", op:"ge", value:80 } },
  { id:"aD200",     zh:"大化学家",     desc:"图鉴收集 200 种物质", reward:20000, cond:{ metric:"discoveredCount", op:"ge", value:200 } },
  { id:"aEq30",     zh:"方程式大师",   desc:"解锁 30 个化学方程式", reward:2500, cond:{ metric:"reactionsKnownCount", op:"ge", value:30 } },
  { id:"aLv10",     zh:"资深研究员",   desc:"等级达到 Lv.10", reward:1500, cond:{ metric:"level", op:"ge", value:10 } },
  { id:"aLv20",     zh:"首席科学家",   desc:"等级达到 Lv.20", reward:8000, cond:{ metric:"level", op:"ge", value:20 } },
  { id:"aRich",     zh:"化学实业家",   desc:"持有金币超过 50000", reward:5000, cond:{ metric:"coins", op:"ge", value:50000 } },
  { id:"aOrganic",  zh:"有机化学家",   desc:"合成乙酸乙酯", reward:1500, cond:{ metric:"discoveredSubstance", subject:"CH3COOC2H5" } },
  { id:"aAqua",     zh:"王水溶解者",   desc:"获得王水", reward:2000, cond:{ metric:"discoveredSubstance", subject:"aqua_regia" } },
  { id:"aQuiz50",   zh:"答题学霸",     desc:"累计答对 50 道题", reward:2000, cond:{ metric:"quizOk", op:"ge", value:50 } },
  { id:"aSnake",    zh:"法老之蛇",     desc:"完成蔗糖浓硫酸脱水实验", reward:1200, cond:{ metric:"knownReaction", subject:"R141" } },
  { id:"aRep",      zh:"商会贵宾",     desc:"商会声望达到 50", reward:2000, cond:{ metric:"reputation", op:"ge", value:50 } },
  { id:"aChallenge",zh:"极限合成",     desc:"完成一次挑战模式", reward:1500, cond:{ metric:"challenges", op:"ge", value:1 } },
  { id:"aSandbox",  zh:"疯狂科学家",   desc:"沙盒模式中合成 5 种物质", reward:800, cond:{ metric:"sandbox", op:"ge", value:5 } }
];

CHEM.QUIZZES = [
  { q:"地壳中含量最多的元素是？", opts:["氧","硅","铝","铁"], a:0, exp:"地壳元素含量前四：氧、硅、铝、铁。" },
  { q:"实验室制取二氧化碳常用哪两种药品？", opts:["石灰石与稀盐酸","碳酸钠与稀硫酸","木炭与氧气","大理石与稀硫酸"], a:0, exp:"CaCO₃+2HCl=CaCl₂+H₂O+CO₂↑，硫酸钙微溶会覆盖反应物。" },
  { q:"下列物质中，属于纯净物的是？", opts:["蒸馏水","矿泉水","食盐水","空气"], a:0, exp:"蒸馏水只含 H₂O 一种分子。" },
  { q:"金属钠着火应该用什么扑灭？", opts:["沙土","水","二氧化碳灭火器","湿布"], a:0, exp:"钠遇水剧烈反应，过氧化钠与CO₂反应放氧，只能用沙土。" },
  { q:"焰色反应中，火焰呈黄色的金属元素是？", opts:["钠","钾","铜","钙"], a:0, exp:"钠焰色为黄色，钾透过蓝色钴玻璃观察呈紫色。" },
  { q:"下列气体中，能使澄清石灰水变浑浊的是？", opts:["二氧化碳","氧气","氢气","氮气"], a:0, exp:"CO₂+Ca(OH)₂=CaCO₃↓+H₂O。" },
  { q:"浓硫酸稀释时的正确操作是？", opts:["酸入水并搅拌","水入酸并搅拌","快速混合","无需搅拌"], a:0, exp:"酸入水，沿器壁慢慢倒并不断搅拌，防止液滴飞溅。" },
  { q:"下列物质属于电解质的是？", opts:["氯化钠","铜","蔗糖","二氧化碳"], a:0, exp:"NaCl在水溶液或熔融态导电；铜是单质，蔗糖非电解质。" },
  { q:"湿法炼铜的原理属于哪种反应？", opts:["置换反应","复分解反应","分解反应","化合反应"], a:0, exp:"Fe+CuSO₄=FeSO₄+Cu，早在宋代即已应用。" },
  { q:"空气中体积分数最大的气体是？", opts:["氮气","氧气","二氧化碳","氩气"], a:0, exp:"氮气约占78%，氧气约占21%。" },
  { q:"下列哪种仪器可以直接放在酒精灯火焰上加热？", opts:["试管","量筒","容量瓶","表面皿"], a:0, exp:"量筒、容量瓶等精密量器不可加热。" },
  { q:"铁在纯氧中燃烧的产物是？", opts:["四氧化三铁","氧化铁","氢氧化铁","硫化亚铁"], a:0, exp:"3Fe+2O₂—点燃→Fe₃O₄，火星四射生成黑色固体。" },
  { q:"pH 值小于 7 的溶液呈？", opts:["酸性","碱性","中性","无法确定"], a:0, exp:"常温下 pH<7 酸性，=7 中性，>7 碱性。" },
  { q:"下列物质露置于空气中质量不发生变化的是？", opts:["氯化钠固体","浓硫酸","氢氧化钠固体","生石灰"], a:0, exp:"浓硫酸吸水、NaOH潮解且吸CO₂、生石灰吸水反应。" },
  { q:"电解水实验中，正极产生的气体是？", opts:["氧气","氢气","氮气","二氧化碳"], a:0, exp:"正氧负氢，体积比约 1:2。" },
  { q:"【安全】燃着的酒精灯打翻起火，应如何处置？", opts:["用湿抹布扑盖","用水冲","跑回宿舍拿灭火器","用嘴吹灭"], a:0, grade:"初中", exp:"隔绝空气并降温，切用水冲会扩大火势。" },
  { q:"【安全】浓碱液溅到皮肤上，正确处理是？", opts:["大量水冲后涂硼酸","直接涂醋酸","用布擦干即可","不管它"], a:0, grade:"初中", exp:"先大量水冲洗，再涂3%硼酸溶液。" },
  { q:"【安全】实验室闻气体气味的正确方法是？", opts:["手扇法轻扇入鼻","把鼻子凑到瓶口","倒入手中品尝","密闭集气瓶里深吸"], a:0, grade:"初中", exp:"招气入鼻，严禁直接凑近闻。" },
  { q:"【安全】钠、钾等着火不能用什么灭火？", opts:["CO₂灭火器","沙土","干燥食盐","石棉布"], a:0, grade:"高中", exp:"Na₂O₂与CO₂反应放O₂助燃。" },
  { q:"物质的量浓度 0.5 mol/L 的 NaCl 含义是？", opts:["每升溶液含 0.5 mol NaCl","每升水溶解 0.5 mol NaCl","溶液质量分数 0.5%","0.5 g NaCl 溶于水"], a:0, grade:"高中", exp:"浓度以‘溶液体积’而非溶剂体积计。" },
  { q:"下列微粒中氧化性最强的是？", opts:["Fe³⁺","Fe²⁺","Cu²⁺","H⁺"], a:0, grade:"高中", exp:"Fe³⁺可氧化 Cu：2Fe³⁺+Cu=2Fe²⁺+Cu²⁺。" },
  { q:"勒夏特列原理：合成氨反应增大压强，平衡如何移动？", opts:["正向移动","逆向移动","不移动","先逆后正"], a:0, grade:"高中", exp:"N₂+3H₂⇌2NH₃ 正向气体分子数减小，加压正向移。" },
  { q:"【大学】一级反应半衰期与初始浓度的关系是？", opts:["无关","成正比","成反比","平方根相关"], a:0, grade:"大学", exp:"t₁/₂=ln2/k，与浓度无关。" },
  { q:"【大学】下列配合物中心离子 d 电子数为 5 的是？", opts:["Fe³⁺","Cu²⁺","Ni²⁺","Cr³⁺"], a:0, grade:"大学", exp:"Fe³⁺:3d⁵ 半充满较稳定。" },
  { q:"【大学】熵增原理判据适用于？", opts:["孤立系统","封闭系统","开放系统","任意系统"], a:0, grade:"大学", exp:"ΔS(孤立)≥0 为方向判据。" }
];
