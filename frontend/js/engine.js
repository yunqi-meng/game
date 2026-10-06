/* 反应引擎（客户端侧）：只负责“预览/渲染”——从服务端整帧推导当前台、匹配候选方程式、估算成本。
   一切会改变存档的动作（投放/取回/清空/温度/仪器/反应/挑战/沙盒）都是发给服务端的意图，这里不再本地结算。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var E = {};
  CHEM.engine = E;

  /* 台位上限（单容器同时容纳的物质种数）自 G4 起是 app_config.bench_max_lines，判定在服务端 GameEngine.place。
     这里不写死 6、也不做本地拦截：客户端只负责"还能放几种"的提示口径，超了由服务端拒并回原因。
     必须动态读 CHEM：内容包到位前这个数还不存在，写死就会在运营改数后变成假信息。 */
  E.maxLines = function () {
    var n = CHEM.BENCH_MAX_LINES;
    return (typeof n === "number" && n >= 1) ? Math.min(64, Math.floor(n)) : 6;
  };
  E.benches = null;       /* 持久化实验台（每房间一台），来自服务端存档 benchStates */
  E.tempBench = null;     /* 挑战/沙盒临时台：由 game.js 从服务端回执 bench 字段落地 */
  E.bi = 0;
  E.sandboxActive = false;
  E.multiplier = 1;       /* 批量倍数：仅作为 react 意图的参数，服务端自行裁剪 */

  E.init = function () {
    var st = CHEM.state, d = st.data;
    var n = Math.max(1, d.rooms.length);
    var arr = d.benchStates && d.benchStates.length ? d.benchStates.filter(function (b) { return b && b.vessel; }) : [];
    while (arr.length < n) arr.push({ vessel: "beaker", placed: {}, temp: "room", electrolysis: false });
    E.benches = arr.slice(0, n);
    E.bi = Math.min(d.bi || 0, n - 1);
  };
  E.cur = function () { return E.tempBench || E.benches[E.bi] || E.benches[0]; };
  E.curRoom = function () {
    var d = CHEM.state.data;
    var id = d.rooms[Math.min(E.bi, d.rooms.length - 1)] || "inorganic";
    return CHEM.ROOMS.find(function (r) { return r.id === id; }) || CHEM.ROOMS[0];
  };
  function instr(id) { return CHEM.INSTRUMENTS.find(function (x) { return x.id === id; }); }

  /* ---------- 条件可用性 ---------- */
  E.canTemp = function (t) {
    var eq = CHEM.state.data.equipment;
    if (t === "room") return true;
    if (t === "heat" || t === "ignite") return !!eq.lamp;
    if (t === "highTemp") return !!eq.blowtorch;
    return false;
  };
  E.canElectrolysis = function () { return !!CHEM.state.data.equipment.electrolyzer; };
  E.batchCap = function () {
    var cap = CHEM.state.data.lab.bench >= 3 ? 20 : 10;
    var v = instr(E.cur().vessel);
    if (v && v.batchBonus) cap += v.batchBonus;
    return cap;
  };

  /* ---------- 现场只读 ---------- */
  E.lines = function () { return Object.keys(E.cur().placed).length; };
  E.givenLeft = function (id) {
    if (!E.tempBench) return -1;
    var ch = CHEM.state.data.chal;
    return (ch.given[id] || 0) + (ch.decoys[id] || 0) - (E.tempBench.placed[id] || 0);
  };

  /* ---------- 匹配（仅用于成本/方程式预览） ---------- */
  function subsetOrEqual(need, have) {
    for (var k in need) if ((have[k] || 0) < need[k]) return false;
    return true;
  }
  E.catalystId = function (r) { return r.conditions && r.conditions.catalyst ? r.conditions.catalyst : null; };
  E.extraPlacedAllowed = function (r, placed) {
    var cat = E.catalystId(r);
    for (var k in placed) {
      if (r.reactants[k] || k === cat) continue;
      return false;
    }
    return true;
  };
  E.conditionOk = function (r, b) {
    var c = r.conditions || {};
    if ((c.temp || "room") !== b.temp) return false;
    if (!!c.electrolysis !== !!b.electrolysis) return false;
    var cat = c.catalyst;
    if (cat && !(b.placed[cat] > 0)) return false;
    if (r.instrument && r.instrument.length && r.instrument.indexOf(b.vessel) === -1) return false;
    return true;
  };

  /**
   * 是否恰好按配比消耗完所有投放物（催化剂除外）——与 GameEngine.consumesExactly 同一条判断。
   * H1 之前客户端没有这个概念：预览只会念"匹配列表的第一条"，而服务端优先结算刚好消耗干净的那条，
   * 于是台面上放 2H₂+1O₂ 时界面写着 2H₂+O₂→2H₂O，落账的却是另一条方程式。
   */
  E.consumesExactly = function (r, placed) {
    var cat = E.catalystId(r), rk = Object.keys(r.reactants || {});
    var pk = Object.keys(placed).filter(function (k) { return k !== cat; });
    if (pk.length !== rk.length) return false;
    for (var i = 0; i < rk.length; i++) {
      var k = rk[i];
      if ((placed[k] || 0) !== r.reactants[k]) return false;
    }
    return true;
  };
  function processToReaction(p) {
    return { id: p.id, reactants: p.reactants, conditions: { temp: p.temp }, products: p.products,
      instrument: [p.vessel], type: p.type, eq: p.eq, phenomenon: p.phenomenon, fx: p.fx,
      discoverLv: 1, exp: p.exp, tip: p.tip, _process: p };
  }
  E.processMatch = function () {
    var b = E.cur(), st = CHEM.state, d = st.data;
    if (E.tempBench && !E.sandboxActive) return null;
    return (CHEM.PROCESSES || []).find(function (p) {
      if (b.vessel !== p.vessel) return false;
      if (!subsetOrEqual(p.reactants, b.placed)) return false;
      if ((p.temp || "room") !== b.temp) return false;
      if (p.needEquip && !d.equipment[p.needEquip]) return false;
      if (p.consume && st.count(p.consume, 0) <= 0 && !E.sandboxActive) return false;
      return true;
    });
  };
  E.matches = function () {
    var st = CHEM.state, b = E.cur(), out = [];
    var real = st.data.realMode;
    (CHEM.REACTIONS || []).forEach(function (r) {
      if (!subsetOrEqual(r.reactants, b.placed)) return;
      if (!E.extraPlacedAllowed(r, b.placed)) return;
      if (real && !E.conditionOk(r, b)) return;
      out.push(r);
    });
    var p = E.processMatch();
    if (p) out.unshift(processToReaction(p));
    return out;
  };

  /**
   * 这一次台面会结算哪条方程式（H1：与 GameEngine.pick 逐字对齐的规则）。
   *
   * <p>排序不是审美问题：工艺排最前（服务端就是这么定的），其次优先"刚好把台面消耗干净"的组合，
   * 同类里反应物种数多的赢（2Na+Cl₂ 不该被 Na+Cl 抢走），再按 discoverLv 从小到大。
   * 客户端以前直接取 matches()[0]，内容与运营一改动顺序，预览就会指着另一条方程式报价——
   * 玩家按预览投料、按回执对账，两边念不同的方程式就是"成本对不上"那类投诉的来源。
   */
  E.pick = function () {
    var b = E.cur(), rs = E.matches();
    if (!rs.length) return null;
    if (rs[0]._process) return rs[0];
    var exact = rs.filter(function (r) { return E.consumesExactly(r, b.placed); });
    var cand = exact.length ? exact.slice() : rs.slice();
    if (exact.length) {
      cand.sort(function (a, c) {
        var d = Object.keys(c.reactants || {}).length - Object.keys(a.reactants || {}).length;
        return d !== 0 ? d : (a.discoverLv || 1) - (c.discoverLv || 1);
      });
    } else {
      cand.sort(function (a, c) { return (a.discoverLv || 1) - (c.discoverLv || 1); });
    }
    return cand[0];
  };

  /* 内容表里查不到的物质按这个价估值（H1）。Java 侧同名常数在 GameEngine.UNKNOWN_PRICE，
     事故损失与成本预览都必须用它：同一坨原料在预览里算 10、结算时被扣 20，
     玩家看到的数字就永远对不上账。test/golden/run.js 会静态比这两个源文件的取值是否一致。 */
  E.UNKNOWN_PRICE = 20;

  E.maxMultiplier = function (r) {
    var b = E.cur(), m = Infinity, cap = E.batchCap();
    /* 系数 ≤0 的这一项不参与限流：配比为 0 的反应物根本不消耗，
       拿它做除法在 JS 里得到 Infinity/NaN、在 Java 里直接是算术异常，两端必须同一个处理。 */
    for (var k in r.reactants) {
      var need = r.reactants[k];
      if (!(need > 0)) continue;
      m = Math.min(m, Math.floor((b.placed[k] || 0) / need));
    }
    return Math.max(1, Math.min(isFinite(m) ? m : 1, cap));
  };
  E.costPreview = function (r) {
    var st = CHEM.state, cost = 0, worth = 0;
    for (var k in r.reactants) {
      var s = st.sub(k);
      cost += (s ? s.price : E.UNKNOWN_PRICE) * r.reactants[k];
    }
    for (var p in r.products) { var q = st.sub(p); worth += (q ? q.price : 0) * r.products[p]; }
    return { cost: Math.round(cost), worth: Math.round(worth) };
  };

  function rollDanger(placed) {
    var out = null;
    (CHEM.DANGERS || []).forEach(function (d) { if (!out && subsetOrEqual(d.reactants, placed)) out = d; });
    return out;
  }
  E.hasDanger = function () { return !!rollDanger(E.cur().placed); };
  E.dangerInfo = function () { return rollDanger(E.cur().placed); };

  /* ---------- 解锁线（展示用，服务端 buyMarket 有同名权威判定） ---------- */
  E.elementOpenByLevel = function (s) {
    var lv = CHEM.state.data.level;
    if (CHEM.state.data.packs.el && (s.cat === "lanthanide" || s.cat === "actinide")) return true;
    if (lv >= 16) return true;
    if (lv >= 11) return (s.z || 0) <= 56 || ((s.z || 0) >= 72 && (s.z || 0) <= 80);
    if (lv >= 6) return (s.z || 0) <= 36;
    return (s.z || 0) <= 20;
  };
  E.compoundOpenByLevel = function (c) {
    var lv = CHEM.state.data.level;
    return c.level <= 1 || (c.level === 2 && lv >= 6) || (c.level === 3 && lv >= 11) || (c.level === 4 && lv >= 16);
  };
  E.marketPool = function () {
    var all = CHEM.state.allSubs();
    return Object.keys(all).filter(function (id) {
      if (id === "SLAG") return false;
      var s = all[id];
      if (s.kind === "element") return E.elementOpenByLevel(s);
      return E.compoundOpenByLevel(s);
    });
  };
})();
