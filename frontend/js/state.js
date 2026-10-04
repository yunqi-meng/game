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
      hints: 0, insured: false,
      friends: {},
      chal: null,
      volSfx: 80, volMus: 35, music: false,
      sign: { last: "", streak: 0 }, tutorial: 0, realMode: false, lastVisit: Date.now()
    };
  };

  S.data = null;
  S.revision = 0;

  /** 服务端整帧落地：唯一的写状态入口。subMap 作废以便内容覆盖后重建。 */
  S.setServer = function (raw, revision) {
    var def = S.defaults();
    var d = Object.assign(def, raw || {});
    d.stats = Object.assign(S.defaults().stats, raw && raw.stats);
    d.lab = Object.assign(S.defaults().lab, raw && raw.lab);
    d.market = Object.assign(S.defaults().market, raw && raw.market);
    d.skins = Object.assign(S.defaults().skins, raw && raw.skins);
    d.packs = Object.assign(S.defaults().packs, raw && raw.packs);
    d.daily = Object.assign(S.defaults().daily, raw && raw.daily);
    d.v = 2;
    S.data = d;
    S.revision = revision || 0;
    S.subMap = null;
  };

  /* ---------- 物质查询 ---------- */
  S.subMap = null;
  S.allSubs = function () {
    if (S.subMap) return S.subMap;
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
  S.discoverBonus = function (id, forReaction) {
    var s = S.sub(id); if (!s) return 0;
    var lv = s.level || 1;
    var r = CHEM.DISCOVER_BONUS[lv] || CHEM.DISCOVER_BONUS[1];
    var v = r[0] + (r[1] - r[0]) * Math.abs(hash(id));
    v = Math.round(v / 10) * 10;
    return forReaction ? Math.round(v * 0.5) : v;
  };

  /* ---------- 进度展示（只读） ---------- */
  S.dailyProgress = function (task) { return Math.min(task.goal, S.data.daily.counters[task.key] || 0); };
  S.achDone = function (a) {
    var d = S.data;
    var disc = Object.keys(d.discovered).length;
    switch (a.id) {
      case "aFirst": return d.stats.success >= 1;
      case "aWater": return !!d.discovered.H2O;
      case "aGold": return !!d.discovered.Au;
      case "aBoom": return d.stats.boom >= 1;
      case "aS100": return d.stats.success >= 100;
      case "aD20": return disc >= 20;
      case "aD80": return disc >= 80;
      case "aD200": return disc >= 200;
      case "aEq30": return Object.keys(d.reactionsKnown).length >= 30;
      case "aLv10": return d.level >= 10;
      case "aLv20": return d.level >= 20;
      case "aRich": return d.coins >= 50000;
      case "aOrganic": return !!d.discovered.CH3COOC2H5;
      case "aAqua": return !!d.discovered.aqua_regia;
      case "aQuiz50": return d.stats.quizOk >= 50;
      case "aSnake": return !!d.reactionsKnown["R141"];
      case "aRep": return d.rep >= 50;
      case "aChallenge": return d.stats.challenges >= 1;
      case "aSandbox": return d.stats.sandbox >= 5;
    }
    return false;
  };

  /* ---------- 仪器（只读） ---------- */
  S.vesselTier = function (id) { var v = S.data.vessels[id]; return v && v.owned ? v.tier : -1; };
  S.ownVessel = function (id) { var v = S.data.vessels[id]; return !!(v && v.owned); };
})();
