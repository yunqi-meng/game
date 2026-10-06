/* 客户端视图状态：只承载服务端下发的整帧存档（setServer）+ 只读查询（背包计数、行情、图鉴进度、等级门槛）。
   在线游戏没有本地真源：这里不再读写 localStorage，也不做任何结算——写操作一律通过 /api/game/<intent>。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var S = {};
  CHEM.state = S;

  var dayKey = function () { return new Date().toISOString().slice(0, 10); };
  /* 与 GameEngine.hash() 完全一致的 FNV-1a 映射，只用于展示发现奖金/排行波动 */
  var hash = function (s) {
    var h = 2166136261;
    for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
    return ((h >>> 0) % 2000) / 1000 - 1;
  };

  /** 新档模板：服务端缺字段时补齐（保证 UI 取用安全），真源仍是 GameState（Java）。 */
  S.defaults = function () {
    return {
      v: 2,
      coins: CHEM.START_COINS, diamonds: 0,
      exp: 0, level: 1,
      bag: {}, discovered: {}, reactionsKnown: {}, firstBonusTaken: {},
      vessels: {}, equipment: {},
      lab: { storage: 0, safety: 0, bench: 0 },
      rooms: ["inorganic"], bi: 0,
      benchStates: null,
      stats: { success: 0, boom: 0, quiz: 0, quizOk: 0, trades: 0, sold: 0, challenges: 0, sandbox: 0, visits: 0 },
      daily: { date: dayKey(), counters: {}, claimed: {} },
      achClaimed: {}, milestones: {},
      orders: { date: "", list: [] },
      market: { date: "", drift: {}, specials: [], black: [], specialBuy: {} },
      listings: [],
      rep: 0, monthly: { until: 0 }, packs: { el: false },
      noad: false, skins: { owned: ["default"], cur: "default" },
      /* 广告台账（服务端 AdService 记账，客户端只读）：points=兑换积分，revive=看视频换来的复活次数 */
      ad: { points: 0, total: 0, revive: 0, day: "", perDay: {}, lastAt: {}, redeemed: {} },
      hints: 0, insured: false,
      friends: {},
      chal: null,
      volSfx: 80, volMus: 35, music: false,
      sign: { last: "", streak: 0 }, tutorial: 0, realMode: false, lastVisit: Date.now()
    };
  };

  S.data = null;
  S.revision = 0;

  /**
   * 服务端整帧落地：唯一的写状态入口。
   * 这里**不再**动 subMap——那张表（约 214 个物质对象）只由内容包决定，跟存档无关，
   * 而 setServer 是"每一帧"都走的路径：一次反应、一次翻页、一次心跳同步都重建一遍，
   * 等于每点一下屏幕就白造 214 个对象再丢掉（H3）。作废的权责交给内容侧：
   * 后台换版时 content.js 的 onRefresh（game.js 挂的）会清 subMap，
   * 而 allSubs 自己还按 CHEM.content.version 兜一道，两个入口都不会留旧表。
   */
  S.setServer = function (raw, revision) {
    var def = S.defaults();
    var d = Object.assign(def, raw || {});
    d.stats = Object.assign(S.defaults().stats, raw && raw.stats);
    d.lab = Object.assign(S.defaults().lab, raw && raw.lab);
    d.market = Object.assign(S.defaults().market, raw && raw.market);
    d.skins = Object.assign(S.defaults().skins, raw && raw.skins);
    d.packs = Object.assign(S.defaults().packs, raw && raw.packs);
    d.daily = Object.assign(S.defaults().daily, raw && raw.daily);
    /* 没看过广告的老存档里服务端可能不带 ad 或给 null，补空壳让页面取用安全 */
    d.ad = Object.assign(S.defaults().ad, raw && raw.ad);
    d.v = 2;
    S.data = d;
    S.revision = revision || 0;
  };

  /* ---------- 物质查询 ---------- */
  S.subMap = null;
  /** 建这张表时所用的内容版本；与 CHEM.content.version 不一致就重建（后台换版走这一道）。 */
  S.subVersion = -1;
  S.contentVersion = function () { return (CHEM.content && CHEM.content.version) || 0; };
  /** 内容侧的显式作废入口：换版时调它，不要再各处手写 subMap = null。 */
  S.invalidateSubs = function () {
    S.subMap = null;
    S.subVersion = -1;
  };
  S.allSubs = function () {
    if (S.subMap && S.subVersion === S.contentVersion()) return S.subMap;
    var m = {};
    (CHEM.ELEMENTS || []).forEach(function (e) {
      m[e.id] = { kind: "element", id: e.id, zh: e.zh, en: e.en, symbol: e.symbol, formula: e.symbol, level: 0, z: e.z,
        state: e.state, color: e.color, hazard: e.cat === "halogen" || e.cat === "actinide" || e.cat === "alkali",
        price: e.price, desc: e.desc, uses: "", elements: [e.id], cat: e.cat };
    });
    (CHEM.COMPOUNDS || []).forEach(function (c) {
      if (!m[c.id]) m[c.id] = Object.assign({ kind: "compound" }, c);
    });
    m.SLAG = m.SLAG || { kind: "compound", id: "SLAG", zh: "实验废渣", formula: "?", level: 1, state: "solid",
      color: "#8a8578", hazard: false, elements: [], price: 5, desc: "反应失败的混合物。", uses: "低价回收" };
    (CHEM.CONSUMABLES || []).forEach(function (c) {
      if (!m[c.id]) m[c.id] = { kind: "consumable", id: c.id, zh: c.zh, formula: "耗材", level: 0, state: "solid",
        color: "#78909c", hazard: false, elements: [], price: c.price, desc: c.desc, uses: "实验耗材" };
    });
    S.subMap = m;
    S.subVersion = S.contentVersion();
    return m;
  };
  S.sub = function (id) { return S.allSubs()[id]; };

  /* ---------- 背包（只读） ---------- */
  S.itemKey = function (id, q) { return id + "|" + (q || 0); };
  S.count = function (id, q) { return S.data.bag[S.itemKey(id, q)] || 0; };
  S.countAll = function (id) { var n = 0; for (var q = 0; q <= 2; q++) n += S.count(id, q); return n; };
  S.cap = function () { return 50 + S.data.lab.storage * CHEM.LAB_UPGRADES.storage.step; };

  /* ---------- 行情（服务端每日下发 drift，这里只读取用于展示） ---------- */
  S.drift = function (id) { return (S.data.market.drift || {})[id] || 1; };
  S.repTier = function () {
    var r = S.data.rep;
    if (r >= 100) return { zh: "荣誉会员", buyRate: 1.08, speed: 0.6 };
    if (r >= 50) return { zh: "贵宾", buyRate: 1.12, speed: 0.75 };
    if (r >= 20) return { zh: "常客", buyRate: 1.16, speed: 0.88 };
    return { zh: "生面孔", buyRate: CHEM.BUY_RATE, speed: 1 };
  };
  S.monthlyActive = function () { return Date.now() < (S.data.monthly.until || 0); };
  S.buyPrice = function (id) {
    var s = S.sub(id); if (!s) return 999999;
    return Math.max(1, Math.round(s.price * S.repTier().buyRate * S.drift(id)));
  };
  S.sellPrice = function (id, q) {
    var s = S.sub(id); if (!s) return 0;
    var mult = CHEM.QUALITY[q || 0].mult;
    var p = s.price * CHEM.SELL_RATE * mult * S.drift(id);
    if (!S.data.firstBonusTaken[id]) p *= CHEM.FIRST_SELL_BONUS;
    return Math.max(1, Math.round(p));
  };
  /**
   * 发现奖金预览（展示用；发钱在服务端）。规则与 GameEngine.discoverBonus 逐字对齐（H1）：
   * 单质/耗材（level&lt;1）没有"新奇感溢价"，定额 50、反应奖金折半 25；化合物按 level 档区间 + 物质 id 哈希取值。
   * 这条以前少一段：Java 的 onDiscover 有"单质定额"这一档，而 eqBonus 那条路没有，
   * 同一种物质在两个入口值不同的金币——对表之后统一收进本方法，两端各自只剩一份。
   */
  S.discoverBonus = function (id, forReaction) {
    var s = S.sub(id); if (!s) return 0;
    if (!(s.level >= 1)) return forReaction ? 25 : 50;
    var r = CHEM.DISCOVER_BONUS[s.level] || CHEM.DISCOVER_BONUS[1];
    var v = r[0] + (r[1] - r[0]) * Math.abs(hash(id));
    v = Math.round(v / 10) * 10;
    return forReaction ? Math.round(v * 0.5) : v;
  };

  /* ---------- 进度展示（只读） ---------- */
  S.dailyProgress = function (task) { return Math.min(task.goal, S.data.daily.counters[task.key] || 0); };

  /* ---------- 成就达成（只用来点亮角标，判定与发放在服务端） ----------
     G4 之前这里是第二份 19 路 id switch：运营在后台把阈值从 100 改成 5，界面仍按老数字亮着，
     而"这条成就按什么算"在仓库里也就有了两个答案。现在两边读同一份 cond 描述符，
     指标名与服务端 AchievementRule.Metric 逐字对齐；服务端加了新指标而这里没跟上，
     后果只是那条成就的角标不亮（钱仍由服务端算），不会出现"界面说达成了、服务端不发货"。 */
  function countTrue(o) { var n = 0; for (var k in o) if (o[k] === true) n++; return n; }
  var ACH_NUM = {
    success: function (d) { return d.stats.success; },
    boom: function (d) { return d.stats.boom; },
    quiz: function (d) { return d.stats.quiz; },
    quizOk: function (d) { return d.stats.quizOk; },
    trades: function (d) { return d.stats.trades; },
    sold: function (d) { return d.stats.sold; },
    challenges: function (d) { return d.stats.challenges; },
    sandbox: function (d) { return d.stats.sandbox; },
    visits: function (d) { return d.stats.visits; },
    discoveredCount: function (d) { return Object.keys(d.discovered).length; },
    reactionsKnownCount: function (d) { return Object.keys(d.reactionsKnown).length; },
    level: function (d) { return d.level; },
    coins: function (d) { return d.coins; },
    reputation: function (d) { return d.rep; },
    equipmentCount: function (d) { return countTrue(d.equipment || {}); },
    roomCount: function (d) { return (d.rooms || []).length; },
    friendCount: function (d) { return Object.keys(d.friends || {}).length; },
    successToday: function (d) { return (d.daily.counters || {}).success || 0; },
    discoverToday: function (d) { return (d.daily.counters || {}).discover || 0; },
    tradeToday: function (d) { return (d.daily.counters || {}).trade || 0; },
    quizToday: function (d) { return (d.daily.counters || {}).quiz || 0; }
  };
  S.achDone = function (a) {
    var c = a && a.cond, d = S.data;
    if (!c || !c.metric) return false;                        // 没条件＝引擎也念不出来，一致地判未达成
    if (c.metric === "discoveredSubstance") return !!c.subject && !!d.discovered[c.subject];
    if (c.metric === "knownReaction") return !!c.subject && d.reactionsKnown[c.subject] === true;
    var read = ACH_NUM[c.metric];
    if (!read || c.value == null) return false;
    var v = read(d);
    switch (c.op || "ge") {                                   // 省略 op＝"达到"，与服务端 Op.of(null) 同
      case "ge": return v >= c.value;
      case "gt": return v > c.value;
      case "le": return v <= c.value;
      case "eq": return v === c.value;
      default: return false;
    }
  };

  /* ---------- 仪器（只读） ---------- */
  S.vesselTier = function (id) { var v = S.data.vessels[id]; return v && v.owned ? v.tier : -1; };
  S.ownVessel = function (id) { var v = S.data.vessels[id]; return !!(v && v.owned); };
})();
