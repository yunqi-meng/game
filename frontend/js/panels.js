/* 页面 v5（在线版 · 信息架构重排 2026-09-26）
   底部导航仍是 7 页，但每页内部按"动作类型"分段，功能搬到玩家真正找它的位置：
     物质 = 我的背包 / 提纯工坊（原来错放在建设页）
     图鉴 = 物质 / 方程式 / 收集节点
     市场 = 采购 / 特惠 / 黑市 / 耗材 / 出售 / 挂单（商会订单是委托，搬去【任务·今日】）
     任务 = 今日（签到 + 每日任务 + 商会订单）/ 成就 / 玩法（挑战·沙盒·答题）/ 社交
     建设 = 房间 / 仪器 / 升级
     设置 = 通用 / 外观 / 账号 / 商店（钻石·充值·月卡）/ 关于（重置存档）
   每日签到原先埋在【设置·其他】里，是最高频的动作之一，所以搬到【任务·今日】第一张卡。
   所有操作仍只发意图（CHEM.game.call），数值以服务端整帧回执为准。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var P = {};
  CHEM.panels = P;
  var st, E, U, G;

  P.init = function () { st = CHEM.state; E = CHEM.engine; U = CHEM.ui; G = CHEM.game; };

  /* ================= 页面模型 ================= */
  /* bench（实验台）也是普通一页。抽屉时代的"台面露着当投放目标"由实验台自带的
     【物质架】承担（ui.js renderQuickShelf），其余六页卡片点击即投放。 */
  P.tab = "bench";

  /* 每页记住自己的分段，切走再回来不丢上下文 */
  var segState = { bag: "have", codex: "sub", market: "buy", tasks: "today", lab: "room", settings: "general" };
  /* 长列表的本地搜索词（纯过滤，不参与结算） */
  var qState = { bag: "", codex: "", market: "", eq: "" };
  var qFocus = "";

  /** 版本号不写死：直接读入口 HTML 上那个 ?v=（改前端必顶的同一个令牌），两处不会漂移。 */
  function appVer() {
    var l = document.querySelector('link[rel="stylesheet"]');
    var m = l && /\bv=([0-9.]+)/.exec(l.getAttribute("href") || "");
    return m ? m[1] : "0";
  }
  function titleOf(tab) {
    if (tab === "bag") return E.tempBench ? (E.sandboxActive ? "🌌 沙盒物质柜" : "🎯 挑战材料箱") : "🧪 物质";
    return ({ codex: "📚 图鉴", market: "🏪 市场", tasks: "📋 任务", lab: "🏗️ 建设", settings: "⚙️ 设置" })[tab] || tab;
  }
  /** 页头那一行小字只留"我现在在哪"，具体数字交给页内统计条。 */
  function subOf(tab) {
    var c = claimCounts();
    if (tab === "bag") return E.tempBench ? "临时工作台 · 不回背包" : "持有 " + Object.keys(st.data.bag).length + " 种 · 上限 " + st.cap();
    if (tab === "codex") return "已发现 " + Object.keys(st.data.discovered).length + " 种";
    if (tab === "market") return st.repTier().zh + " · 声望 " + st.data.rep;
    if (tab === "tasks") return c.total ? "有 " + c.total + " 项奖励可领" : "今日奖励已领完";
    if (tab === "lab") return "Lv." + st.data.level + " · " + st.data.rooms.length + " 间实验室";
    if (tab === "settings") return G.guest ? "游客试玩中 · 建议转正" : "已登录：" + (G.user || "?");
    return "";
  }

  /* ================= 路由 ================= */
  /** 显示一页，并强制重放进场动画（同一容器换页时 class 没变，动画不会自己重来）。 */
  function showPage(el) {
    el.classList.add("on");
    el.classList.remove("anim");
    void el.offsetWidth;
    el.classList.add("anim");
  }
  P.go = function (tab) {
    tab = tab || "bench";
    var bench = U.$("bench"), page = U.$("page");
    if (!bench || !page) return;
    var changed = (P.tab !== tab);
    P.tab = tab;
    if (changed) qFocus = "";
    document.querySelectorAll("#bottom-nav button").forEach(function (b) {
      b.classList.toggle("on", b.dataset.tab === tab);
    });
    if (tab === "bench") {
      page.classList.remove("on");
      showPage(bench);
      /* 台面从 display:none 回来时画布尺寸是 0，必须重新量一次，
         否则回到实验台后的粒子会画在看不见的地方。 */
      if (CHEM.fx) CHEM.fx.resize();
      U.renderStage();
      U.updateNav();
      return;
    }
    bench.classList.remove("on");
    showPage(page);
    U.$("page-title").textContent = titleOf(tab);
    U.$("page-sub").textContent = subOf(tab);
    renderSegs(tab);
    P.render(tab);
    U.updateNav();
  };
  /** 老名字保留语义：抽屉时代"关掉面板"＝回到实验台。 */
  P.closeSheet = function () { P.go("bench"); };
  /** 整帧落地后重绘当前页；实验台页由 U.renderStage 负责，这里不碰。 */
  P.refreshIfOpen = function () {
    if (P.tab === "bench") return;
    U.$("page-sub").textContent = subOf(P.tab);
    renderSegs(P.tab);
    P.render();
  };

  var renderedKey = null;
  P.render = function (forTab) {
    var body = U.$("page-body");
    if (!body) return;
    var tab = forTab || P.tab;
    var key = tab + ":" + segState[tab];
    var keep = renderedKey === key ? body.scrollTop : 0;
    renderedKey = key;
    var def = PAGES[tab];
    if (def) def.draw(body, segState[tab]);
    else body.innerHTML = "";
    /* 意图回执会触发整帧 → 这里重绘，于是同一分段里的滚动位置与搜索焦点不会跳掉。 */
    body.scrollTop = keep;
    syncFab();
  };

  /** 分段条：页头下方常驻，段上带"这一类有几件事可做"的数字/小红点。 */
  function renderSegs(tab) {
    var box = U.$("page-segs");
    if (!box) return;
    var def = PAGES[tab];
    var list = def && def.segs ? def.segs() : null;
    if (!list || list.length < 2) { box.innerHTML = ""; box.classList.add("hidden"); return; }
    box.classList.remove("hidden");
    if (!list.some(function (s) { return s[0] === segState[tab]; })) segState[tab] = list[0][0];
    var cur = segState[tab];
    box.innerHTML = list.map(function (s) {
      var n = s[2] || 0;
      return '<button class="seg' + (cur === s[0] ? " on" : "") + '" data-seg="' + s[0] + '">' + s[1] +
        (n > 0 ? '<b class="pip">' + n + "</b>" : n < 0 ? '<i class="pip dot"></i>' : "") + "</button>";
    }).join("");
    box.querySelectorAll("[data-seg]").forEach(function (b) {
      b.onclick = function () {
        if (segState[tab] === b.dataset.seg) return;
        segState[tab] = b.dataset.seg;
        qFocus = "";
        P.go(tab);
      };
    });
  }
  P.seg = function (tab) { return segState[tab]; };

  /** 台面已有物质时，就地给一个回实验台的落点（省一次导航点击）。 */
  function syncFab() {
    var fab = U.$("page-fab");
    if (!fab) return;
    var n = E.lines();
    /* 挑战/沙盒的临时工作台本来就在别的页投放，同样给回台入口 */
    if (P.tab === "bench" || !n) { fab.classList.add("hidden"); return; }
    fab.classList.remove("hidden");
    fab.textContent = "⚗️ 台面 " + n + " 种 · 回实验台";
    fab.onclick = function () { P.go("bench"); };
  }
  P.syncFab = syncFab;

  /* ================= 通用片段 ================= */
  /** 一张卡 = 一个决定范围；卡头给标题与右侧补充信息，卡身装行/网格。 */
  function card(o) {
    return '<section class="card' + (o.cls ? " " + o.cls : "") + '">' +
      '<header class="card-hd"><b>' + o.t + "</b>" + (o.meta ? "<span>" + o.meta + "</span>" : "") + "</header>" +
      '<div class="card-bd">' + o.body + "</div></section>";
  }
  /** 页内统计条：进页面第一眼就知道自己有多少钱、几件事可做。 */
  function statbar(list) {
    return '<div class="statbar">' + list.map(function (s) {
      return '<div class="stat' + (s[2] ? " " + s[2] : "") + '"><b>' + s[1] + "</b><span>" + s[0] + "</span></div>";
    }).join("") + "</div>";
  }
  function chips(list, cur, attr) {
    return '<div class="chips">' + list.map(function (t) {
      return '<button data-' + attr + '="' + t[0] + '" class="' + (cur === t[0] ? "on" : "") + '">' + t[1] + "</button>";
    }).join("") + "</div>";
  }
  function searchBox(key, ph) {
    return '<div class="searchbar"><input class="inp js-search" type="search" placeholder="' + ph +
      '" value="' + U.esc(qState[key] || "") + '"></div>';
  }
  function hit(q, arr) {
    if (!q) return true;
    for (var i = 0; i < arr.length; i++) {
      if (arr[i] && String(arr[i]).toLowerCase().indexOf(q) >= 0) return true;
    }
    return false;
  }
  /** 空状态一律给出"下一步去哪儿"，不留死胡同。 */
  function emptyState(o) {
    return '<div class="empty-state"><span>' + (o.glyph || "🫙") + "</span><p>" + o.text + "</p>" +
      (o.go ? '<button class="btn-s" data-act="' + o.go + '">' + o.act + "</button>" : "") + "</div>";
  }
  function bindEmptyActions(body) {
    body.querySelectorAll("[data-act]").forEach(function (b) {
      b.onclick = function () { P.go(b.dataset.act); };
    });
  }
  function bindSearch(body, key) {
    var inp = body.querySelector(".js-search");
    if (!inp) return;
    var timer = null;
    if (qFocus === key) {
      inp.focus();
      try { inp.setSelectionRange(inp.value.length, inp.value.length); } catch (e) { /* type=search 在部分引擎不支持 */ }
    }
    inp.oninput = function () {
      qState[key] = inp.value;
      qFocus = key;
      clearTimeout(timer);
      timer = setTimeout(function () { P.render(); }, 140);
    };
  }
  /** 发意图：失败统一提示；成功不用手工刷新（服务端整帧 → G.refresh → refreshIfOpen）。 */
  function run(intent, params, ok) {
    G.call(intent, params || {}, function (r) {
      if (!r || r.ok === false) return U.toast((r && r.msg) || "操作失败，进度未变动", "bad");
      if (ok) ok(r);
    });
  }
  /** 本地报价只是预览；金币够不够由服务端裁决，这里只提前拦一下避免空跑一趟。 */
  function poor(cost) {
    if (st.data.coins < cost) { U.toast("金币不足，还差 🪙" + (cost - st.data.coins), "bad"); return true; }
    return false;
  }
  function eqTags(r) {
    var t = "";
    if (r.ionic) t += '<span class="tag ionic">离子</span>';
    if (r.rev) t += '<span class="tag rev">可逆</span>';
    if (r.thermal) t += '<span class="tag thermal">热化学</span>';
    if (r.danger) t += '<span class="tag hz">危险</span>';
    return t;
  }
  function condText(r) {
    var c = r.conditions || {}, out = [({ room: "室温", heat: "加热", ignite: "点燃", highTemp: "高温" })[c.temp || "room"]];
    if (c.catalyst) { var s = st.sub(c.catalyst); out.push("催化剂：" + (s ? s.zh : c.catalyst)); }
    if (c.electrolysis) out.push("电解");
    if (r.instrument && r.instrument.length) out.push("仪器：" + r.instrument.map(function (i) {
      var x = CHEM.INSTRUMENTS.find(function (q) { return q.id === i; }); return x ? x.zh : i;
    }).join("/"));
    return out.join(" ｜ ");
  }

  /** 有几件事"现在就能做"——分段数字与底部导航角标都从这里取，两处不会各算一套。 */
  function claimCounts() {
    var d = st.data, c = { daily: 0, orders: 0, sign: 0, ach: 0, node: 0, play: 0 };
    CHEM.DAILY_TASKS.forEach(function (t) {
      if (!d.daily.claimed[t.id] && st.dailyProgress(t) >= t.goal) c.daily++;
    });
    (d.orders.list || []).forEach(function (o) {
      if (!o.done && st.countAll(o.id) >= o.need) c.orders++;
    });
    if (!d.sign || d.sign.last !== d.daily.date) c.sign++;
    CHEM.ACHIEVEMENTS.forEach(function (a) {
      if (!d.achClaimed[a.id] && st.achDone(a)) c.ach++;
    });
    var disc = Object.keys(d.discovered).length;
    CHEM.MILESTONES.forEach(function (need) {
      if (!d.milestones[need] && disc >= need) c.node++;
    });
    if (!d.chal) c.play++;
    c.tasks = c.daily + c.orders + c.sign;
    c.today = c.tasks;
    c.total = c.tasks + c.ach + c.node;
    return c;
  }
  /** 建设页的红点：已达等级线且金币够买的仪器/房间，避免玩家漏掉能推进的东西。 */
  function buyableCount() {
    var d = st.data, n = 0;
    CHEM.ROOMS.forEach(function (r) {
      if (d.rooms.indexOf(r.id) < 0 && d.level >= r.unlockLv && d.coins >= r.cost) n++;
    });
    CHEM.INSTRUMENTS.forEach(function (i) {
      if (d.level < i.unlockLv || d.coins < i.baseCost) return;
      if (i.kind === "vessel" && !st.ownVessel(i.id)) n++;
      if (i.kind !== "vessel" && !d.equipment[i.id]) n++;
    });
    return n;
  }
  P.claimCounts = claimCounts;
  P.buyableCount = buyableCount;

  var PAGES = {
    bag: { segs: bagSegs, draw: drawBag },
    codex: { segs: function () { var c = claimCounts(); return [["sub", "物质", 0], ["eq", "方程式", 0], ["node", "收集节点", c.node]]; }, draw: drawCodex },
    market: { segs: function () { var d = st.data; return [["buy", "采购", 0], ["special", "特惠", (d.market.specials || []).length], ["black", "黑市", 0], ["consume", "耗材", 0], ["sell", "出售", 0], ["listing", "挂单", d.listings.length]]; }, draw: drawMarket },
    tasks: { segs: function () { var c = claimCounts(); return [["today", "今日", c.today], ["ach", "成就", c.ach], ["play", "玩法", 0], ["social", "社交", 0]]; }, draw: drawTasks },
    lab: { segs: function () { var n = buyableCount(); return [["room", "房间", 0], ["instr", "仪器", n], ["upgrade", "升级", 0]]; }, draw: drawLab },
    settings: { segs: function () { return [["general", "通用", 0], ["appearance", "外观", 0], ["account", "账号", G.guest ? -1 : 0], ["store", "商店", 0], ["about", "关于", 0]]; }, draw: drawSettings }
  };

  /* ================= 物质（背包 / 工坊） ================= */
  var bagFilter = "all";
  function bagSegs() {
    /* 挑战与沙盒用的是服务端临时工作台，整页就是一只材料柜，分段反而碍事 */
    if (E.tempBench) return null;
    return [["have", "我的背包", 0], ["workshop", "提纯工坊", 0]];
  }
  function drawBag(body, which) {
    if (E.tempBench && E.sandboxActive) return renderSandboxShelf(body);
    if (E.tempBench && st.data.chal) return renderChalBox(body);
    if (which === "workshop") return renderWorkshop(body);
    var all = st.allSubs();
    var groups = [["all", "全部"], ["element", "元素"], ["compound", "化合物"], ["gas", "气体/液体/溶液"], ["hz", "危险品"]];
    var q = (qState.bag || "").trim().toLowerCase();
    var ids = Object.keys(st.data.bag).map(function (k) { return k.split("|")[0]; })
      .filter(function (v, i, a) { return a.indexOf(v) === i; });
    ids.sort();
    var shown = ids.filter(function (id) {
      var s = all[id]; if (!s) return false;
      if (bagFilter === "element") return s.kind === "element";
      if (bagFilter === "compound") return s.kind === "compound";
      if (bagFilter === "gas") return s.state === "gas" || s.state === "liquid" || s.state === "solution";
      if (bagFilter === "hz") return !!s.hazard;
      return true;
    }).filter(function (id) {
      var s = all[id];
      return hit(q, [id, s.zh, s.formula, s.symbol, s.en]);
    });
    var cap = st.cap(), kinds = Object.keys(st.data.bag).length;
    var html = statbar([
      ["持有种数", kinds + " / " + cap, kinds >= cap ? "warn" : ""],
      ["总份数", totalUnits()],
      ["金币", "🪙 " + st.data.coins]
    ]);
    html += card({
      t: "🧪 我的背包", meta: shown.length + " 种 · 点卡片即投一份",
      body: chips(groups, bagFilter, "bg") + searchBox("bag", "搜索物质名 / 化学式 / 元素符号") +
        (shown.length ? '<div class="grid">' + shown.map(function (id) {
          var s = all[id];
          var total = st.countAll(id);
          return '<div class="item-card" data-id="' + U.esc(id) + '">' +
            (total ? '<i class="cnt">' + total + "</i>" : "") +
            '<div class="sq" style="background:' + U.esc(s.color) + ";color:" + pickTextColor(s.color) + '">' + U.esc(shortFm(s)) + "</div>" +
            '<div class="zh">' + U.esc(s.zh) + '</div><div class="fm">' + U.esc(s.formula || "") + qMark(id) + "</div></div>";
        }).join("") + "</div>" : (ids.length
          ? emptyState({ glyph: "🔍", text: "没有匹配的物质，换个关键词试试。" })
          : emptyState({ glyph: "🫙", text: "背包空空如也。先去【市场】采购原料，或在实验台上合成。", go: "market" }))) +
        '<p class="hint-p">容量上限 ' + cap + " 种（【建设·升级·储物柜扩容】可提高）。品质分档（粗 / 纯 / 高纯）见【物质·提纯工坊】。危险物质请分开存放。</p>"
    });
    body.innerHTML = html;
    body.querySelectorAll("[data-bg]").forEach(function (b) { b.onclick = function () { bagFilter = b.dataset.bg; P.render(); }; });
    bindSearch(body, "bag");
    bindEmptyActions(body);
    bindCards(body);
  }
  function totalUnits() {
    var n = 0, b = st.data.bag;
    Object.keys(b).forEach(function (k) { n += b[k]; });
    return n;
  }
  function qMark(id) {
    var qs = [];
    for (var q = 0; q <= 2; q++) if (st.count(id, q) > 0) qs.push(CHEM.QUALITY[q].zh.slice(0, 1) + st.count(id, q));
    return qs.length > 1 ? "（" + qs.join("/") + "）" : "";
  }
  /** 提纯工坊：原先挂在【建设】页，但它吃的是背包、产的是物质，归到【物质】页更顺。 */
  function renderWorkshop(body) {
    var d = st.data;
    var html = statbar([["金币", "🪙 " + d.coins], ["背包", Object.keys(d.bag).length + " / " + st.cap() + " 种"]]);
    if (!d.equipment.spectrometer) {
      html += card({
        t: "🔬 提纯工坊", meta: "未解锁",
        body: emptyState({ glyph: "🧬", text: "精炼提纯需要【分光光度计】。它是一台配套设备，在【建设·仪器】里购入后即可开工。", go: "lab" })
      });
      body.innerHTML = html;
      return bindEmptyActions(body);
    }
    html += card({
      t: "⚗️ 精炼提纯", meta: "服务器挑料",
      body: '<div class="row"><div class="grow"><b>粗产物 → 纯产物</b><small>5 份同档 → 1 份高一档，产物品质影响售价与图鉴</small></div><button class="btn-s g" data-ref="0">提纯</button></div>' +
        '<div class="row"><div class="grow"><b>纯产物 → 高纯产物</b><small>月卡期间不收机器费；没凑够 5 份时服务器会直接拒绝</small></div><button class="btn-s g" data-ref="1">提纯</button></div>'
    });
    html += card({
      t: "🏷️ 品质与售价", meta: "直售系数 ×" + CHEM.SELL_RATE,
      body: CHEM.QUALITY.map(function (qz) {
        return '<div class="row"><div class="grow"><b>' + qz.zh + "</b><small>售价系数 ×" + qz.mult + " · 由容器档次产出（" + CHEM.TIER_NAMES.join(" → ") + "）</small></div>" +
          '<span class="tag lv' + qz.q + '">' + qz.q + " 档</span></div>";
      }).join("") + '<p class="hint-p">更精密的容器产出更高品质的产物：升级路径在【建设·仪器】。挂单定价见【市场·挂单】。</p>'
    });
    body.innerHTML = html;
    body.querySelectorAll("[data-ref]").forEach(function (b) {
      b.onclick = function () {
        var fromQ = +b.dataset.ref;
        run("refine", { fromQ: fromQ, toQ: fromQ + 1, need: 5 }, function (r) {
          U.toast("提纯完成：" + st.sub(r.id).zh + " → " + CHEM.QUALITY[fromQ + 1].zh +
            (st.monthlyActive() ? "（月卡免机器费）" : ""), "good");
        });
      };
    });
  }
  function renderChalBox(body) {
    var ch = st.data.chal;
    var ids = Object.keys(ch.given).concat(Object.keys(ch.decoys));
    var left = ids.filter(function (id) { return E.givenLeft(id) > 0; }).length;
    var html = card({
      t: "🎯 挑战材料箱", meta: "剩余 " + ch.steps + " 步 · 服务端记账",
      body: '<div class="grid">' + ids.map(function (id) {
        var s = st.sub(id) || { zh: id, color: "#90a4ae" };
        var n = Math.max(0, E.givenLeft(id));
        return '<div class="item-card' + (n ? "" : " empty") + '" data-id="' + U.esc(id) + '">' +
          (n ? '<i class="cnt">' + n + "</i>" : "") +
          '<div class="sq" style="background:' + U.esc(s.color) + ";color:" + pickTextColor(s.color) + '">' + U.esc(shortFm(s)) + "</div>" +
          '<div class="zh">' + U.esc(s.zh) + "</div></div>";
      }).join("") + "</div>" +
        '<p class="hint-p">其中有 2 种是干扰物质，用它们反应会判负。点卡片即投放（回【实验台】用下方物质架也一样），再点【开始反应】。</p>'
    });
    body.innerHTML = statbar([["目标", U.esc((st.sub(ch.target) || {}).zh || ch.target)], ["可用物质", left + " 种"], ["奖励", "🪙 " + ch.reward]]) + html;
    bindCards(body);
  }
  function renderSandboxShelf(body) {
    var pool = Object.keys(st.allSubs()).filter(function (id) { return id !== "SLAG"; });
    var q = (qState.bag || "").trim().toLowerCase();
    pool.sort(function (a, b) { return ((st.sub(a).z || 999) - (st.sub(b).z || 999)) || (st.sub(a).price - st.sub(b).price); });
    var shown = pool.filter(function (id) { var s = st.sub(id); return hit(q, [id, s.zh, s.formula]); });
    var html = card({
      t: "🌌 沙盒物质柜", meta: "全部物质 · 无限供应",
      body: searchBox("bag", "在全部物质里搜索") + '<div class="grid">' + shown.map(function (id) {
        var s = st.sub(id);
        return '<div class="item-card" data-id="' + U.esc(id) + '"><i class="cnt">∞</i>' +
          '<div class="sq" style="background:' + U.esc(s.color) + ";color:" + pickTextColor(s.color) + '">' + U.esc(shortFm(s)) + "</div>" +
          '<div class="zh">' + U.esc(s.zh) + "</div></div>";
      }).join("") + "</div>" +
        '<p class="hint-p">点击直接投放到沙盒工作台。不产生收益与图鉴进度，可自由验证化学与彩蛋。</p>'
    });
    body.innerHTML = html;
    bindSearch(body, "bag");
    bindCards(body);
  }
  function bindCards(body) {
    /* 整页化后这一页看不到台面，跨页拖拽没有落点，所以卡片只保留"点一下投放"；
       真正的手感（拖到台面上）由实验台自己的物质架承担。 */
    body.querySelectorAll(".item-card[data-id]").forEach(function (el) {
      var id = el.dataset.id;
      if (E.tempBench || st.countAll(id) > 0) el.onclick = function () { U.place(id); };
      else el.onclick = function () { U.toast("背包中没有 " + (st.sub(id) || {}).zh, "bad"); };
    });
  }
  function shortFm(s) {
    if (s.kind === "element") return s.symbol || s.id;
    var f = s.formula || s.id;
    return f.length > 6 ? s.zh.slice(0, 4) : f;
  }
  function pickTextColor(hex) {
    if (!/^#[0-9a-f]{3,8}$/i.test(hex || "")) return "#fff";
    var h = hex.slice(1);
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    var r = parseInt(h.substr(0, 2), 16), g = parseInt(h.substr(2, 2), 16), b = parseInt(h.substr(4, 2), 16);
    return (r * 299 + g * 587 + b * 114) / 1000 > 150 ? "#37474f" : "#fff";
  }
  P.pickTextColor = pickTextColor; P.shortFm = shortFm;

  /* ================= 图鉴 ================= */
  var codexCat = "all", eqFilter = "all";
  function drawCodex(body, which) {
    var d = st.data, all = st.allSubs();
    if (which === "node") return renderNodes(body);
    if (which === "eq") return renderEq(body);
    var cats = [["all", "全部"], ["element", "元素"], ["L1", "基础化合物"], ["L2", "中级化合物"], ["L3", "高级化合物"], ["L4", "特殊物质"]];
    var subIds = Object.keys(all).filter(function (i) { return i !== "SLAG" && all[i].kind !== "consumable"; });
    var total = subIds.length, got = subIds.filter(function (i) { return d.discovered[i]; }).length;
    var q = (qState.codex || "").trim().toLowerCase();
    var list = subIds.filter(function (id) {
      var s = all[id];
      if (codexCat === "all") return true;
      if (codexCat === "element") return s.kind === "element";
      return s.kind === "compound" && ("L" + s.level) === codexCat;
    }).filter(function (id) { var s = all[id]; return hit(q, [id, s.zh, s.formula, s.symbol]); })
      .sort(function (a, b) {
        var A = d.discovered[a] ? 1 : 0, B = d.discovered[b] ? 1 : 0;
        return (A - B) || (all[a].z || 999) - (all[b].z || 999);
      });
    var known = list.filter(function (id) { return d.discovered[id]; });
    var locked = list.filter(function (id) { return !d.discovered[id]; });
    var html = statbar([
      ["已发现", got + " / " + total],
      ["完成度", Math.round(got / total * 100) + "%"],
      ["方程式", Object.keys(d.reactionsKnown).length + " / " + (CHEM.REACTIONS || []).length]
    ]);
    html += '<div class="card"><header class="card-hd"><b>✅ 已发现</b><span>' + known.length + " 种</span></header><div class=\"card-bd\">" +
      (known.length ? '<div class="grid">' + known.map(codexCard).join("") + "</div>" : '<p class="hint-p">还没有发现任何物质——先去实验台合成第一瓶吧。</p>') +
      "</div></div>";
    html += '<div class="card"><header class="card-hd"><b>❔ 待发现</b><span>' + locked.length + " 种</span></header><div class=\"card-bd\">" +
      chips(cats, codexCat, "cc") + searchBox("codex", "在图鉴里搜索物质名 / 化学式") +
      '<div class="grid">' + locked.map(codexCard).join("") + "</div></div></div>";
    html += '<p class="hint-p">收集 118 种元素与二百余种化合物，全部图鉴约 200 天自然毕业节奏。点击任意卡片看详情与今日行情。</p>';
    body.innerHTML = html;
    body.querySelectorAll("[data-cc]").forEach(function (b) { b.onclick = function () { codexCat = b.dataset.cc; P.render(); }; });
    bindSearch(body, "codex");
    body.querySelectorAll("[data-sub]").forEach(function (el) { el.onclick = function () { subDetail(el.dataset.sub); }; });
  }
  function codexCard(id) {
    var s = st.allSubs()[id], known = !!st.data.discovered[id];
    return '<div class="item-card' + (known ? "" : " locked") + '" data-sub="' + U.esc(id) + '">' +
      '<div class="sq" style="background:' + (known ? U.esc(s.color) : "#b0bec5") + ";color:" + pickTextColor(known ? s.color : "#b0bec5") + '">' +
      (known ? U.esc(shortFm(s)) : "？") + "</div>" +
      '<div class="zh">' + (known ? U.esc(s.zh) : "未发现") + '</div><div class="fm">' + (known ? U.esc(s.formula || "") : "???") + "</div></div>";
  }
  function renderEq(body) {
    var d = st.data, rs = CHEM.REACTIONS || [];
    var filters = [["all", "全部"], ["ionic", "离子方程"], ["rev", "可逆反应"], ["thermal", "热化学"], ["unknown", "未发现"]];
    var knownN = rs.filter(function (r) { return d.reactionsKnown[r.id]; }).length;
    var q = (qState.eq || "").trim().toLowerCase();
    var flt = rs.filter(function (r) {
      if (eqFilter === "all") return true;
      if (eqFilter === "unknown") return !d.reactionsKnown[r.id];
      return !!r[eqFilter];
    });
    var got = flt.filter(function (r) { return d.reactionsKnown[r.id]; })
      .filter(function (r) { return hit(q, [r.id, r.eq, r.type, r.phenomenon]); });
    /* 未发现的那些不按关键词过滤：否则输入一个化学式就能靠"命中几条"反推出它还藏着什么反应，等于白送提示 */
    var miss = flt.filter(function (r) { return !d.reactionsKnown[r.id]; });
    var html = statbar([
      ["已解锁", knownN + " / " + rs.length],
      ["完成度", Math.round(knownN / Math.max(1, rs.length) * 100) + "%"],
      ["本类", flt.length + " 条"]
    ]);
    html += card({
      t: "🧾 方程式图鉴", meta: flt.length + " 条",
      body: chips(filters, eqFilter, "ef") + searchBox("eq", "在已解锁的方程式里搜索（方程式 / 类型 / 现象）") +
        got.map(function (r) {
          return '<div class="row eqrow" data-eq="' + r.id + '"><div class="grow"><b class="eqline">' + U.esc(r.eq) + "</b>" +
            "<small>" + U.esc(r.phenomenon) + " · " + U.esc(r.type) + " · " + U.esc(condText(r)) + "</small></div>" + eqTags(r) + "</div>";
        }).join("") +
        (got.length || !q ? "" : '<p class="hint-p">已解锁的方程式里没有匹配项。</p>') +
        (miss.length ? '<div class="card-sub">尚未发现 · ' + miss.length + " 条" + (q ? "（不计入关键词搜索）" : "") + "</div>" +
          miss.map(function (r) {
            return '<div class="row"><div class="grow"><b>？？？</b><small>尚未发现 —— 多尝试不同的物质组合</small></div>' +
              '<span class="tag lv' + Math.min(4, r.discoverLv > 15 ? 4 : r.discoverLv > 10 ? 3 : r.discoverLv > 5 ? 2 : 1) + '">Lv.' + r.discoverLv + "</span></div>";
          }).join("") : "") +
        (got.length ? "" : '<p class="hint-p">还没有解锁方程式。做几次实验，第一瓶水就会点亮图鉴。</p>')
    });
    body.innerHTML = html;
    body.querySelectorAll("[data-ef]").forEach(function (b) { b.onclick = function () { eqFilter = b.dataset.ef; qFocus = "eq"; P.render(); }; });
    bindSearch(body, "eq");
    body.querySelectorAll("[data-eq]").forEach(function (el) {
      el.onclick = function () {
        var r = (CHEM.REACTIONS || []).find(function (x) { return x.id === el.dataset.eq; });
        if (r) eqDetail(r);
      };
    });
  }
  function renderNodes(body) {
    var d = st.data, disc = Object.keys(d.discovered).length, c = claimCounts();
    var html = statbar([["已发现", disc + " 种"], ["可领节点", c.node + " 项"]]);
    html += card({
      t: "🎁 图鉴收集节点奖励", meta: "当前 " + disc + " 种",
      body: CHEM.MILESTONES.map(function (need, i) {
        var taken = !!d.milestones[need];
        return '<div class="row"><div class="grow"><b>发现 ' + need + " 种物质</b>" +
          '<div class="bar"><i style="width:' + Math.min(100, Math.round(disc / need * 100)) + '%"></i></div>' +
          "<small>奖励 🪙" + need * 100 + " 与随机化合物礼包 ｜ 当前 " + disc + "</small></div>" +
          (taken ? '<span class="tag lv1">已领</span>' : '<button class="btn-s ' + (disc >= need ? "g" : "") + '" data-ms="' + i + '" ' + (disc >= need ? "" : "disabled") + ">领取</button>") + "</div>";
      }).join("") +
        '<p class="hint-p">节点奖励由服务器核账，领过的不会重复发。</p>'
    });
    body.innerHTML = html;
    body.querySelectorAll("[data-ms]").forEach(function (b) {
      b.onclick = function () {
        run("claim.milestone", { index: +b.dataset.ms }, function (r) {
          U.toast("节点奖励到账 🪙" + r.coins + (r.gift ? "，并获得 " + U.esc(r.gift) + " ×3" : ""), "gold");
        });
      };
    });
  }
  function eqDetail(r) {
    U.modal("<h3>" + U.esc(r.type) + " " + U.esc(r.id) + " " + eqTags(r) + "</h3><div class='eq'>" + U.esc(r.eq) + "</div>" +
      '<div class="kv"><span>反应条件</span><b>' + U.esc(condText(r)) + "</b></div>" +
      '<div class="ph">现象：' + U.esc(r.phenomenon) + "</div>" +
      (r.ionic ? '<div class="ph">🧲 离子反应：本质是自由离子之间的反应，删去旁观离子即得离子方程式。</div>' : "") +
      (r.rev ? '<div class="ph">🔄 可逆反应：同一条件下正逆反应同时进行，不能进行到底。</div>' : "") +
      (r.thermal ? '<div class="ph">🔥 热化学：方程式中已标注物质聚集状态与反应热 ΔH。</div>' : "") +
      (r.tip ? '<div class="ph">📖 ' + U.esc(r.tip) + "</div>" : "") +
      '<button class="ok" data-close>知道啦</button>');
  }
  function subDetail(id) {
    var s = st.sub(id); if (!s) return;
    var known = !!st.data.discovered[id];
    var lvTag = ["元素", "基础", "中级", "高级", "特殊"][s.level] || "";
    U.modal("<h3>" + U.esc(s.zh) + " <span class='tag lv" + Math.min(4, s.level) + "'>" + lvTag + "</span>" + (s.hazard ? "<span class='tag hz'>危险</span>" : "") + "</h3>" +
      '<div class="eq swatch" style="background:' + U.esc(s.color) + ";color:" + pickTextColor(known ? s.color : "#b0bec5") + '">' + U.esc(s.formula || "") + "</div>" +
      (known ?
        '<div class="kv"><span>状态</span><b>' + ({ solid: "固体", liquid: "液体", gas: "气体", solution: "溶液", unknown: "未知" }[s.state] || s.state) + "</b></div>" +
        '<div class="kv"><span>官方基准价</span><b>🪙 ' + s.price + '</b></div><div class="kv"><span>今日行情</span><b>买 🪙' + st.buyPrice(id) + " / 卖 🪙" + st.sellPrice(id, 0) + "</b></div>" +
        '<div class="kv"><span>持有</span><b>' + st.countAll(id) + " 份</b></div>" +
        '<div class="kv"><span>发现次数</span><b>' + ((st.data.discovered[id] || {}).times || 0) + "</b></div>" +
        '<div class="desc">' + U.esc(s.desc) + "</div>" +
        (s.uses ? '<div class="ph left">用途：' + U.esc(s.uses) + "</div>" : "")
        :
        '<div class="ph">尚未发现该物质。合成或购买后即可点亮图鉴。</div>') +
      '<button class="ok" data-close>关闭</button>');
  }

  /* ================= 市场 ================= */
  var marketAmt = 1;
  function drawMarket(body, which) {
    var d = st.data, tier = st.repTier();
    var html = statbar([
      ["金币", "🪙 " + d.coins],
      ["商会声望", d.rep + " · " + tier.zh],
      ["买入系数", "×" + tier.buyRate.toFixed(2)],
      ["在挂", d.listings.length + " 笔"]
    ]);
    body.innerHTML = html + (which === "buy" ? marketBuy() : which === "special" ? marketDeal("special") :
      which === "black" ? marketDeal("black") : which === "consume" ? marketConsume() :
        which === "sell" ? marketSell() : marketListing());
    bindMarket(body);
    bindSearch(body, "market");
    bindEmptyActions(body);
  }
  function marketBuy() {
    var tier = st.repTier();
    var pool = E.marketPool().filter(function (id) { return id !== "SLAG"; });
    var q = (qState.market || "").trim().toLowerCase();
    pool.sort(function (a, b) {
      var A = st.sub(a), B = st.sub(b);
      return (A.level - B.level) || (A.price - B.price);
    });
    var allN = pool.length;
    pool = pool.filter(function (id) { var s = st.sub(id); return hit(q, [id, s.zh, s.formula]); });
    var rows = pool.map(function (id) {
      var s = st.sub(id), p = Math.round(st.buyPrice(id) * marketAmt);
      return '<div class="row"><i class="dot" style="background:' + U.esc(s.color) + '"></i>' +
        '<div class="grow"><b>' + U.esc(s.zh) + '</b> <small>' + U.esc(s.formula || "") + " · 持有 " + st.countAll(id) + (s.hazard ? " · ⚠️危险" : "") + "</small></div>" +
        '<span class="price">🪙' + p + '</span><button class="btn-s" data-buy="' + U.esc(id) + '" data-p="' + p + '">买' + marketAmt + "</button></div>";
    }).join("");
    return card({
      t: "🛒 采购原料", meta: "生面孔 ×1.2 起步，声望越高越便宜",
      body: '<div class="rowtools"><div class="tabs flat">' + [1, 10, 50].map(function (n) {
        return '<button data-amt="' + n + '" class="' + (marketAmt === n ? "on" : "") + '">' + n + "份</button>";
      }).join("") + '</div><span class="rowtip">买入价 = 基准价 ×' + tier.buyRate.toFixed(2) + " ×每日行情（±8%）</span></div>" +
        searchBox("market", "在市场里搜索物质名 / 化学式") +
        (pool.length ? '<div class="rows">' + rows + "</div>"
          : emptyState({ glyph: "🔍", text: allN ? "没有匹配的货，换个关键词试试。" : "市场暂时没有上架的原料。" })) +
        '<p class="hint-p">等级不够的元素不会上架；镧系·锕系可在【设置·商店】用礼包一次性解锁。列表按化合物层级与价格排序。</p>'
    });
  }
  function marketDeal(kind) {
    var d = st.data, isSp = kind === "special";
    var list = isSp ? (d.market.specials || []) : (d.market.black || []);
    var note = isSp ? "🎉 每日 3 种限时特价（7~9 折），每种限量，今日有效。"
      : (!list.length && d.level < 11 ? "🕶️ 黑市在 Lv.11 后现身：稀有高价货，来路不明，概不退换。"
        : "🕶️ 黑市稀有货（溢价 60%~120%），每日限购由服务器记账。");
    var rows = list.map(function (sp) {
      var s = st.sub(sp.id); if (!s) return "";
      var base = isSp ? st.buyPrice(sp.id) : Math.round(s.price * st.drift(sp.id) * sp.prem);
      var bought = (d.market.specialBuy || {})[sp.id] || 0;
      var lim = sp.lim || 1;
      return '<div class="row"><i class="dot" style="background:' + U.esc(s.color) + '"></i>' +
        '<div class="grow"><b>' + U.esc(s.zh) + "</b> <small>" + (isSp ? Math.round(sp.disc * 100) + " 折" : "溢价 ×" + sp.prem) +
        " · 今日 " + bought + "/" + lim + " · 持有 " + st.countAll(sp.id) + "</small></div>" +
        '<span class="price">🪙' + base + "</span>" +
        (bought >= lim ? '<span class="tag lv2">限购</span>' : '<button class="btn-s" data-sp="' + U.esc(sp.id) + '" data-p="' + base + '" data-sp2="' + (isSp ? 1 : 0) + '">买</button>') + "</div>";
    }).join("");
    return card({
      t: isSp ? "⏱️ 今日特惠" : "🕶️ 黑市", meta: list.length + " 项",
      body: (rows || emptyState({ glyph: isSp ? "🎁" : "🕶️", text: isSp ? "今日特惠已抢完，明天上新。" : "黑市今天没有货。" })) +
        '<p class="hint-p">' + note + "</p>" +
        (isSp ? "" : '<p class="hint-p">限购份数按服务器日期结算，换设备也不会重置。</p>')
    });
  }
  function marketConsume() {
    var d = st.data;
    return card({
      t: "🧻 实验耗材", meta: "过滤要滤纸、挂单要试剂瓶",
      body: CHEM.CONSUMABLES.map(function (c) {
        return '<div class="row"><div class="grow"><b>' + U.esc(c.zh) + '</b> <small>' + U.esc(c.desc) + " · 持有 " + st.count(c.id, 0) + "</small></div>" +
          '<span class="price">🪙' + c.price + '</span><button class="btn-s" data-cb="' + c.id + '" data-p="' + c.price + '">买1</button>' +
          '<button class="btn-s g" data-cb="' + c.id + '" data-n="5" data-p="' + c.price * 5 + '">买5</button></div>';
      }).join("") + '<p class="hint-p">耗材是消耗品：挂单每 10 份吃 1 个试剂瓶，过滤每次吃 1 张滤纸，事故前戴 1 个防护罩可减损 80%。当前金币 🪙' + d.coins + "</p>"
    });
  }
  function marketSell() {
    var d = st.data, rows = [];
    Object.keys(d.bag).forEach(function (k) {
      var parts = k.split("|"), id = parts[0], q = +parts[1] || 0, n = d.bag[k];
      if (n <= 0) return;
      var s = st.sub(id); if (!s) return;
      rows.push('<div class="row"><i class="dot" style="background:' + U.esc(s.color) + '"></i>' +
        '<div class="grow"><b>' + U.esc(s.zh) + '</b> <small>' + CHEM.QUALITY[q].zh + " · 持有 " + n + " · 直售 🪙" + st.sellPrice(id, q) + "</small></div>" +
        '<button class="btn-s g" data-sell="' + U.esc(id) + "|" + q + '">卖1</button>' +
        '<button class="btn-s g" data-sellall="' + U.esc(id) + "|" + q + '">全卖</button>' +
        '<button class="btn-s o" data-list="' + U.esc(id) + "|" + q + '">挂单</button></div>');
    });
    return card({
      t: "💰 出售库存", meta: "直售秒到账 ×" + CHEM.SELL_RATE,
      body: (rows.length ? '<div class="rows">' + rows.join("") + "</div>"
        : emptyState({ glyph: "📦", text: "没有可出售的物品。先在实验台上合成物质，或去【采购】买入低买高卖的原料。" })) +
        '<p class="hint-p">首次出售同种物质有 1.5 倍尝鲜奖金；想卖高价走【挂单】，商会 NPC 会在数分钟内竞价。品质越高卖得越贵，见【物质·提纯工坊】。</p>'
    });
  }
  function marketListing() {
    var d = st.data;
    var rows = d.listings.map(function (L) {
      var s = st.sub(L.id) || { zh: L.id };
      var left = Math.max(0, Math.ceil((L.mat - Date.now()) / 1000));
      return '<div class="row"><div class="grow"><b>' + U.esc(s.zh) + " ×" + L.n + "</b><small>" + CHEM.QUALITY[L.q].zh +
        " · 挂价 🪙" + L.price + '</small><div class="bar"><i style="width:' + Math.max(4, 100 - Math.round(left / 6)) + '%"></i></div></div>' +
        "<span>⏳" + left + "s</span></div>";
    }).join("");
    return card({
      t: "📦 在挂订单", meta: d.listings.length + " 笔",
      body: (rows || emptyState({ glyph: "🧾", text: "暂无在挂订单。去【市场·出售】点「挂单」，商会 NPC 会在数分钟内竞价收购。" })) +
        '<p class="hint-p">挂单由服务器结算：关闭页面、换设备也在推进；定价越接近基准越容易成交，过期未成交自动退回背包。月卡免 10% 手续费。</p>'
    });
  }
  function bindMarket(body) {
    body.querySelectorAll("[data-amt]").forEach(function (b) { b.onclick = function () { marketAmt = +b.dataset.amt; P.render(); }; });
    body.querySelectorAll("[data-buy]").forEach(function (b) {
      b.onclick = function () {
        var id = b.dataset.buy;
        if (poor(+b.dataset.p)) return;
        run("market.buy", { id: id, amt: marketAmt }, function (r) {
          U.toast("已购入 " + st.sub(id).zh + " ×" + marketAmt + "，扣 🪙" + r.cost, "good");
        });
      };
    });
    body.querySelectorAll("[data-sp]").forEach(function (b) {
      b.onclick = function () {
        var id = b.dataset.sp, isSp = b.dataset.sp2 === "1";
        if (poor(+b.dataset.p)) return;
        run(isSp ? "market.special" : "market.black", { id: id }, function (r) {
          U.toast("已购入 " + st.sub(id).zh + "，扣 🪙" + r.cost, "good");
        });
      };
    });
    body.querySelectorAll("[data-cb]").forEach(function (b) {
      b.onclick = function () {
        var c = CHEM.CONSUMABLES.find(function (x) { return x.id === b.dataset.cb; });
        if (poor(+b.dataset.p)) return;
        run("market.consumable", { id: c.id, n: +b.dataset.n || 1 }, function (r) {
          U.toast("已购入 " + c.zh + "，扣 🪙" + r.cost, "good");
        });
      };
    });
    body.querySelectorAll("[data-sell]").forEach(function (b) {
      b.onclick = function () { var p = b.dataset.sell.split("|"); sellKey(p[0], +p[1], st.count(p[0], +p[1]) ? 1 : 0); };
    });
    body.querySelectorAll("[data-sellall]").forEach(function (b) {
      b.onclick = function () { var p = b.dataset.sellall.split("|"); sellKey(p[0], +p[1], st.count(p[0], +p[1])); };
    });
    body.querySelectorAll("[data-list]").forEach(function (b) {
      b.onclick = function () { var p = b.dataset.list.split("|"); openListingModal(p[0], +p[1]); };
    });
  }
  function openListingModal(id, q) {
    var s = st.sub(id);
    var have = st.count(id, q);
    var mult = 1.2;
    function draw() {
      var n = Math.min(have, 10);
      U.modal("<h3>📦 挂单：" + U.esc(s.zh) + "（" + CHEM.QUALITY[q].zh + "）</h3>" +
        '<div class="kv"><span>持有</span><b>' + have + " 份</b></div>" +
        '<div class="row"><div class="grow"><b>数量</b><small>每 10 份需 1 个试剂瓶</small></div><input type="number" id="ln" min="1" max="' + have + '" value="' + n + '" class="inp narrow"></div>' +
        chips([[0.9, "低价·快成交 ×0.9"], [1.2, "合理 ×1.2"], [1.6, "高价·慢出 ×1.6"]].map(function (x) { return [String(x[0]), x[1]]; }), String(mult), "pm") +
        '<button class="ok" id="lok">确认挂单</button><button class="ghost" data-close>取消</button>' +
        '<p class="hint-p">定价越高越难成交、耗时越长；未成交到期自动退回。挂单与成交全部由服务器记账。</p>', {
          after: function (card2) {
            card2.querySelectorAll("[data-pm]").forEach(function (b) {
              b.onclick = function () { mult = +b.dataset.pm; draw(); };
            });
            card2.querySelector("#lok").onclick = function () {
              var n2 = Math.max(1, Math.min(have, +card2.querySelector("#ln").value || 1));
              U.closeModal();
              run("listing.create", { id: id, q: q, n: n2, mult: mult }, function (r) { U.toast(r.msg, "good"); });
            };
          }
        });
    }
    draw();
  }
  function sellKey(id, q, n) {
    if (n <= 0) return;
    run("market.sell", { id: id, q: q, n: n }, function (r) {
      U.toast("出售 " + st.sub(id).zh + " ×" + r.n + "，入账 🪙" + r.price, "good");
    });
  }

  /* ================= 任务 / 玩法 / 社交 ================= */
  function drawTasks(body, which) {
    var d = st.data, c = claimCounts();
    if (which === "ach") return renderAch(body, c);
    if (which === "play") return renderPlay(body);
    if (which === "social") return renderSocial(body);
    var sg = d.sign || { last: "", streak: 0 };
    var signedToday = sg.last === d.daily.date;
    var html = statbar([
      ["可领奖励", c.total ? c.total + " 项" : "无", c.total ? "hot" : ""],
      ["连续签到", sg.streak + " 天"],
      ["成功实验", d.stats.success],
      ["等级", "Lv." + d.level]
    ]);
    html += card({
      t: "📅 每日签到", meta: "本周期 " + signCycle(sg),
      body: '<div class="row"><div class="grow"><b>' + (signedToday ? "今日已签到" : "今天还没签到") + "</b>" +
        "<small>" + (signedToday ? "已连续 " : "签到已连续 ") + sg.streak + " 天；每日元素礼包 + 金币，第 7 天额外 💎5</small>" +
        signInDots(sg) + "</div>" +
        '<button class="btn-s ' + (signedToday ? "" : "g") + '" id="t-sign"' + (signedToday ? " disabled" : "") + ">" + (signedToday ? "已签到" : "签到") + "</button></div>"
    });
    html += card({
      t: "📋 今日任务（" + d.daily.date + "）", meta: c.daily + " 项可领",
      body: CHEM.DAILY_TASKS.map(function (t) {
        var p = st.dailyProgress(t), done = p >= t.goal, claimed = !!d.daily.claimed[t.id];
        return '<div class="row"><div class="grow"><b>' + U.esc(t.zh) + "</b>" +
          '<div class="bar"><i style="width:' + Math.round(p / t.goal * 100) + '%"></i></div>' +
          "<small>" + p + " / " + t.goal + " · 奖励 🪙" + t.reward + "</small></div>" +
          (claimed ? '<span class="tag lv1">已领</span>'
            : (done ? '<button class="btn-s g" data-task="' + t.id + '">领取</button><button class="btn-s o" data-tdbl="' + t.id + '">📺双倍</button>' : '<button class="btn-s" disabled>未完成</button>')) + "</div>";
      }).join("") +
        (d.daily.claimed.__dblCoupon ? '<p class="hint-p coupon">🎟️ 今日双倍券在手，下次领取任务/成就奖励时自动翻倍（仅一次）。</p>' : "") +
        '<p class="hint-p">任务进度与领取都由服务器结算，换设备登录同一账号会继续。</p>'
    });
    html += card({
      t: "📮 商会订单", meta: (d.orders.list || []).filter(function (o) { return !o.done; }).length + " 笔待交付",
      body: ((d.orders.list || []).length ? (d.orders.list.map(function (o, i) {
        var have = st.countAll(o.id);
        var ready = have >= o.need;
        return '<div class="row"><div class="grow"><b>' + (o.grade === "rare" ? "🌟" : "") + U.esc(o.zh) + " ×" + o.need + "</b>" +
          '<div class="bar"><i style="width:' + Math.min(100, Math.round(have / o.need * 100)) + '%"></i></div>' +
          "<small>品质要求：" + CHEM.QUALITY[o.q].zh + " · 持有 " + have + " · " + (o.done ? "已完成" : "报酬 🪙" + o.pay + " · 声望+" + (o.grade === "rare" ? 3 : 1)) + "</small></div>" +
          (o.done ? '<span class="tag lv1">已交付</span>' : '<button class="btn-s ' + (ready ? "g" : "") + '" data-order="' + i + '" ' + (ready ? "" : "disabled") + ">交付</button>") + "</div>";
      }).join("") + '<p class="hint-p">订单每日刷新（服务器进帧时结算），交付溢价 20%~50% 并积累声望——声望越高，【市场·采购】越便宜。</p>')
        : '<p class="hint-p">今日没有订单，明天再来商会看看。</p>')
    });
    body.innerHTML = html;
    body.querySelector("#t-sign").onclick = function () {
      if (st.data.sign && st.data.sign.last === st.data.daily.date) return U.toast("今日已签到，明天再来~", "bad");
      run("sign", {}, function (r) {
        var msg = "📅 签到第 " + r.day + "/7 天：" + (r.gift && st.sub(r.gift) ? st.sub(r.gift).zh + " ×3、" : "") + "🪙" + r.coins;
        if (r.diamonds) msg += "、💎" + r.diamonds + " 大奖！";
        CHEM.sfx.play(r.diamonds ? "levelup" : "coin");
        U.toast(msg, "gold");
      });
    };
    body.querySelectorAll("[data-task]").forEach(function (b) {
      b.onclick = function () { run("claim.daily", { id: b.dataset.task }, claimToast); };
    });
    body.querySelectorAll("[data-tdbl]").forEach(function (b) {
      b.onclick = dblClaim("claim.daily", b.dataset.tdbl);
    });
    body.querySelectorAll("[data-order]").forEach(function (b) {
      b.onclick = function () {
        run("order.fulfill", { index: +b.dataset.order }, function (r) {
          U.toast("订单交付成功！🪙" + r.pay + "，商会声望 " + r.rep, "good");
        });
      };
    });
  }
  function signInDots(sg) {
    /* 与服务端同一套算法：streak 是已签天数，第 7 天签完下一天就重新起周期 */
    var filled = sg.streak > 0 ? ((sg.streak - 1) % 7) + 1 : 0;
    var out = '<div class="sign-dots">';
    for (var i = 0; i < 7; i++) out += '<i class="' + (i < filled ? "on" : "") + (i === 6 ? " big" : "") + '"></i>';
    return out + "</div>";
  }
  /** 卡片头那句"这个 7 天周期走到哪了"，与 signInDots 同源。 */
  function signCycle(sg) { return (sg.streak > 0 ? ((sg.streak - 1) % 7) + 1 : 0) + "/7 天"; }
  function renderAch(body, c) {
    var d = st.data, got = CHEM.ACHIEVEMENTS.filter(function (a) { return !!d.achClaimed[a.id]; }).length;
    var done = CHEM.ACHIEVEMENTS.filter(function (a) { return st.achDone(a); }).length;
    var sorted = CHEM.ACHIEVEMENTS.slice().sort(function (a, b) {
      var A = d.achClaimed[a.id] ? 2 : (st.achDone(a) ? 1 : 0), B = d.achClaimed[b.id] ? 2 : (st.achDone(b) ? 1 : 0);
      return B - A;
    });
    body.innerHTML = statbar([["已达成", done + " / " + CHEM.ACHIEVEMENTS.length], ["已领取", got], ["可领", c.ach + " 项", c.ach ? "hot" : ""]]) +
      card({
        t: "🏆 成就", meta: "可领 " + c.ach + " 项",
        body: sorted.map(function (a) {
          var ok = st.achDone(a), claimed = !!d.achClaimed[a.id];
          return '<div class="row"><div class="grow"><b>' + (claimed ? "✅ " : ok ? "🎁 " : "🔒 ") + U.esc(a.zh) +
            "</b><small>" + U.esc(a.desc) + " · 🪙" + a.reward + "</small></div>" +
            (claimed ? '<span class="tag lv1">已领</span>'
              : (ok ? '<button class="btn-s g" data-ach="' + a.id + '">领取</button><button class="btn-s o" data-adbl="' + a.id + '">📺双倍</button>' : '<button class="btn-s" disabled>未达成</button>')) + "</div>";
        }).join("") +
          '<p class="hint-p">达成即提示，领取才入账。双倍奖励要先有服务器签发的当日双倍券（看一段广告换券）。</p>'
      });
    body.querySelectorAll("[data-ach]").forEach(function (b) {
      b.onclick = function () { run("claim.ach", { id: b.dataset.ach }, claimToast); };
    });
    body.querySelectorAll("[data-adbl]").forEach(function (b) {
      b.onclick = dblClaim("claim.ach", b.dataset.adbl);
    });
  }
  function renderPlay(body) {
    var d = st.data;
    body.innerHTML =
      card({
        t: "🎯 合成挑战", meta: d.chal ? "进行中 · 剩 " + d.chal.steps + " 步" : "未开始",
        body: '<div class="row"><div class="grow"><b>限定步数合成指定目标</b><small>只准用材料箱里的物质，其中混有 2 种干扰物质，用了直接判负。奖励 🪙' +
          (800 + d.level * 150) + "｜失败后可看广告复活 +2 步</small></div>" +
          '<button class="btn-s ' + (d.chal ? "g" : "") + '" id="go-chal">' + (d.chal ? "回到挑战" : "发起挑战") + "</button></div>"
      }) +
      card({
        t: "🌌 创意沙盒", meta: "已完成 " + d.stats.sandbox + " 次",
        body: '<div class="row"><div class="grow"><b>全部物质无限供应</b><small>不计奖励、不涨图鉴，自由验证化学与彩蛋。退出后实验台照常营业</small></div>' +
          '<button class="btn-s o" id="go-sb">' + (E.sandboxActive ? "沙盒进行中" : "进入沙盒") + "</button></div>"
      }) +
      card({
        t: "📝 趣味化学题", meta: "答对 🪙" + CHEM.QUIZ_REWARD + " · 可能掉钻石",
        body: '<div class="row"><div class="grow"><b>按年级抽题（服务器判题）</b><small>累计答对 ' + d.stats.quizOk + " / " + d.stats.quiz +
          " 题｜答对计入「答对 2 道化学题」每日任务</small></div></div>" +
          '<div class="pick-row">' + ["all", "小学", "初中", "高中", "大学"].map(function (g) {
            return '<button class="btn-s" data-quiz="' + g + '">' + (g === "all" ? "随机" : g) + "</button>";
          }).join("") + "</div>"
      });
    body.querySelector("#go-chal").onclick = function () { U.startChallenge(); };
    body.querySelector("#go-sb").onclick = function () { U.startSandbox(); };
    body.querySelectorAll("[data-quiz]").forEach(function (b) {
      b.onclick = function () { P.openQuiz(b.dataset.quiz); };
    });
  }
  function renderSocial(body) {
    var d = st.data;
    body.innerHTML =
      card({
        t: "👥 同行好友", meta: "拜访 · 送礼 · 提升声望",
        body: CHEM.NPCS.map(function (n) {
          var f = d.friends[n.id] || {};
          var visited = f.lastVisit === d.daily.date;
          return '<div class="row"><span class="avatar">' + n.emoji + "</span><div class=\"grow\"><b>" + U.esc(n.zh) +
            "</b><small>研究方向：" + U.esc(n.focus) + " · 收集 " + n.discovered + " 种" + (f.giftedTotal ? " · 你赠过 " + f.giftedTotal + " 次礼" : "") + "</small></div>" +
            (visited ? '<span class="tag lv1">今日已访</span>' : '<button class="btn-s" data-vis="' + n.id + '">拜访</button>') +
            '<button class="btn-s o" data-gift="' + n.id + '">送礼</button></div>';
        }).join("") + '<p class="hint-p">拜访能收到回礼；送礼花 1 份背包物质，换回金币与商会声望。</p>'
      }) +
      '<div id="lb-rows">' + lbHtml() + "</div>" +
      card({
        t: "📈 我的记录", meta: "累计",
        body: [["成功实验", d.stats.success], ["实验事故", d.stats.boom], ["答对题目", d.stats.quizOk + " / " + d.stats.quiz],
        ["挑战达成", d.stats.challenges], ["沙盒实验", d.stats.sandbox], ["好友拜访", d.stats.visits],
        ["交易笔数", d.stats.trades], ["出售份数", d.stats.sold]].map(function (kv) {
          return '<div class="kv"><span>' + kv[0] + "</span><b>" + kv[1] + "</b></div>";
        }).join("") +
          '<p class="hint-p">想分享战绩？【设置·通用】里有一键分享。</p>'
      });
    body.querySelectorAll("[data-vis]").forEach(function (b) {
      b.onclick = function () {
        run("friend.visit", { npcId: b.dataset.vis }, function (r) {
          if (!r.gift) return;
          var g = st.sub(r.gift.id) || { zh: r.gift.id };
          U.toast("好友回礼：" + g.zh + " ×" + r.gift.n + "！", "gold");
        });
      };
    });
    body.querySelectorAll("[data-gift]").forEach(function (b) {
      b.onclick = function () { giftModal(b.dataset.gift); };
    });
    fetchLeaderboard();
  }
  function claimToast(r) {
    U.toast((r.dbl ? "🎟️ 双倍奖励到账 🪙" : "已领取 🪙") + r.reward, r.dbl ? "gold" : "good");
    U.updateNav();
  }
  /** 双倍必须由服务器签发的当日广告券支付：没券先看广告换券，再领双倍。 */
  function dblClaim(intent, id) {
    return function (ev) {
      var btn = ev.currentTarget;
      btn.disabled = true;
      var fire = function () {
        G.call(intent, { id: id, dbl: true }, function (r) {
          if (btn.isConnected) btn.disabled = false;
          if (!r || r.ok === false) return U.toast((r && r.msg) || "领取失败", "bad");
          claimToast(r);
        });
      };
      if (st.data.daily.claimed.__dblCoupon) return fire();
      U.simAd("看一段小广告，换一张今日双倍券", function () { run("ad.bonus", { kind: 1 }, fire); });
    };
  }

  /* ---------- 排行榜（服务器异步取，按天缓存，避免刷新回调自循环） ---------- */
  var lbRows = null, lbDay = "", lbLoading = false;
  function lbHtml() {
    if (!lbRows) return '<div class="card"><header class="card-hd"><b>📊 收集排行榜</b><span>正在向服务器查询…</span></header><div class="card-bd"><p class="hint-p">排行榜按已发现物质种数排名，含 NPC 参照。</p></div></div>';
    return card({
      t: "📊 收集排行榜", meta: "按已发现种数",
      body: lbRows.map(function (row, i) {
        return '<div class="row rank' + (row.you ? " you" : "") + '"><span class="rk">' + (i + 1) + "</span>" +
          '<div class="grow"><b>' + row.emoji + " " + U.esc(row.zh) + "</b></div><span>已收集 " + row.n + " 种</span></div>";
      }).join("") + '<p class="hint-p">你也在榜上：收集越多名次越靠前，图鉴节点奖励一并到账。</p>'
    });
  }
  function fetchLeaderboard() {
    var today = st.data.daily.date;
    if (lbRows && lbDay === today) { paintLb(); return; }
    if (lbLoading) return;
    lbLoading = true;
    G.call("leaderboard", {}, function (r) {
      lbLoading = false;
      if (!Array.isArray(r)) return paintLb("排行榜暂不可用：" + ((r && r.msg) || "服务器无数据"));
      lbRows = r; lbDay = today;
      paintLb();
    });
  }
  function paintLb(alt) {
    var box = U.$("page-body") && U.$("page-body").querySelector("#lb-rows");
    if (box) box.innerHTML = alt ? '<div class="card"><div class="card-bd"><p class="hint-p">' + U.esc(alt) + "</p></div></div>" : lbHtml();
  }

  function giftModal(npcId) {
    var d = st.data;
    var npc = CHEM.NPCS.find(function (n) { return n.id === npcId; });
    var ids = Object.keys(d.bag).map(function (k) { return k.split("|")[0]; }).filter(function (v, i, a) { return a.indexOf(v) === i && v !== "SLAG"; });
    if (!ids.length) return U.toast("背包是空的，没有可送的礼物", "bad");
    var html = "<h3>🎁 送礼给 " + npc.emoji + U.esc(npc.zh) + "</h3><small class='sub'>好友会回赠金币，且商会声望 +1</small><div class='mt'>";
    ids.slice(0, 30).forEach(function (id) {
      var s = st.sub(id);
      html += '<div class="row"><i class="dot" style="background:' + U.esc(s.color) + '"></i>' +
        '<div class="grow"><b>' + U.esc(s.zh) + "</b> <small>×" + st.countAll(id) + "</small></div>" +
        '<button class="btn-s g" data-g="' + U.esc(id) + '">送 1 份</button></div>';
    });
    html += '<button class="ghost" data-close>取消</button>';
    U.modal(html, {
      after: function (card2) {
        card2.querySelectorAll("[data-g]").forEach(function (b) {
          b.onclick = function () {
            U.closeModal();
            run("friend.gift", { npcId: npcId, id: b.dataset.g, n: 1 }, function (r) {
              U.toast(npc.zh + " 很开心，回赠了 🪙" + r.thanks + "，声望 " + r.rep, "good");
            });
          };
        });
      }
    });
  }

  /* ================= 建设 ================= */
  function drawLab(body, which) {
    var d = st.data;
    var owned = CHEM.INSTRUMENTS.filter(function (i) {
      return i.kind === "vessel" ? st.ownVessel(i.id) : !!d.equipment[i.id];
    }).length;
    var html = statbar([
      ["等级", "Lv." + d.level],
      ["实验室", d.rooms.length + " / " + CHEM.ROOMS.length],
      ["已购仪器", owned + " / " + CHEM.INSTRUMENTS.length],
      ["金币", "🪙 " + d.coins]
    ]);
    if (which === "room") html += labRooms();
    else if (which === "upgrade") html += labUpgrades();
    else html += labInstruments();
    body.innerHTML = html;
    bindLab(body);
  }
  function labRooms() {
    var d = st.data;
    return card({
      t: "🚪 实验室房间", meta: "每间 = 一台独立工作台",
      body: CHEM.ROOMS.map(function (r) {
        var owned = d.rooms.indexOf(r.id) >= 0;
        var canBuy = d.level >= r.unlockLv;
        return '<div class="row"><div class="grow"><b>' + (owned ? "✅ " : "") + U.esc(r.zh) + "</b><small>" + U.esc(r.desc) + "</small>" +
          (r.types && r.types.length ? "<small>专长：" + r.types.join("、") + "（经验+30%）</small>" : "") +
          (canBuy || owned ? "" : "<small>还需等级 Lv." + r.unlockLv + "</small>") + "</div>" +
          (owned ? '<span class="tag lv1">已建成</span>'
            : canBuy ? '<button class="btn-s o" data-room="' + r.id + '" data-p="' + r.cost + '">🪙' + r.cost + " 建成</button>"
              : '<span class="tag lv2">Lv.' + r.unlockLv + " 解锁</span>") + "</div>";
      }).join("") +
        '<p class="hint-p">多台并行：这台做电解，那台炼有机。顶部工作台栏会随房间数量增加。</p>'
    });
  }
  var instrFilter = "all";
  function labInstruments() {
    var d = st.data;
    var groups = [["all", "全部"], ["vessel", "反应容器"], ["tool", "配套设备"]];
    var list = CHEM.INSTRUMENTS.filter(function (i) {
      if (instrFilter === "all") return true;
      return instrFilter === "vessel" ? i.kind === "vessel" : i.kind !== "vessel";
    });
    var rows = list.map(function (i) {
      var owned, state, btn, extra = "";
      if (i.yieldBonus) extra += "产率+" + Math.round(i.yieldBonus * 100) + "% ";
      if (i.batchBonus) extra += "批量上限+" + i.batchBonus + " ";
      if (i.noHeat) extra += "⚠️不可加热 ";
      if (i.proc) extra += "支持工艺操作 ";
      if (i.needEquip) extra += "需配套设备 ";
      if (i.kind === "vessel") {
        owned = st.ownVessel(i.id);
        var tier = st.vesselTier(i.id);
        state = owned ? "已拥有 · " + CHEM.TIER_NAMES[tier] : (d.level >= i.unlockLv ? "未购买" : "Lv." + i.unlockLv + " 解锁");
        btn = owned
          ? (tier < 2 ? '<button class="btn-s o" data-up="' + i.id + '" data-p="' + (i.baseCost * CHEM.TIER_UP_COST[tier]) + '">升级' + CHEM.TIER_NAMES[tier + 1] + " 🪙" + (i.baseCost * CHEM.TIER_UP_COST[tier]) + "</button>" : '<span class="tag lv1">已满级</span>')
          : (d.level >= i.unlockLv ? '<button class="btn-s" data-vbuy="' + i.id + '" data-p="' + i.baseCost + '">🪙' + i.baseCost + " 购入</button>" : "");
      } else {
        owned = !!d.equipment[i.id];
        state = owned ? "已装备" : (d.level >= i.unlockLv ? "未购买" : "Lv." + i.unlockLv + " 解锁");
        btn = owned ? '<span class="tag lv1">已装备</span>' : (d.level >= i.unlockLv ? '<button class="btn-s" data-ebuy="' + i.id + '" data-p="' + i.baseCost + '">🪙' + i.baseCost + " 购入</button>" : "");
      }
      return '<div class="row"><span class="ic-slot">' + CHEM.icon.of(i) + "</span>" +
        '<div class="grow"><b>' + U.esc(i.zh) + '</b><small>' + U.esc(i.desc) + (extra ? " · " + extra.trim() : "") + " · " + state + "</small></div>" + btn + "</div>";
    }).join("");
    return card({
      t: "🔬 仪器商店", meta: list.length + " 种",
      body: chips(groups, instrFilter, "if") + '<div class="rows">' + rows + "</div>" +
        '<p class="hint-p">容器决定产物品质档（粗 / 纯 / 高纯）；配套设备解锁工艺操作与提纯工坊。买到的容器在实验台点容器名即可切换。</p>'
    });
  }
  function labUpgrades() {
    var d = st.data;
    return card({
      t: "🏗️ 实验室升级", meta: "等级越高越省钱",
      body: Object.keys(CHEM.LAB_UPGRADES).map(function (k) {
        var u = CHEM.LAB_UPGRADES[k], lv = d.lab[k];
        var cost = Math.round(u.baseCost * Math.pow(u.growth, lv));
        return '<div class="row"><div class="grow"><b>' + u.zh + " Lv." + lv + "</b><small>" + u.desc +
          (k === "storage" ? "（当前每种物质上限 " + st.cap() + "）" : "") +
          (k === "safety" ? "（事故概率/损失 " + Math.round((1 - d.lab.safety * 0.12) * 100) + "%）" : "") +
          (k === "bench" ? "（批量倍率上限 +" + (lv) * 5 + "）" : "") + "</small>" +
          '<div class="bar"><i style="width:' + Math.round(lv / u.max * 100) + '%"></i></div></div>' +
          (lv < u.max ? '<button class="btn-s" data-lab="' + k + '" data-p="' + cost + '">🪙' + cost + "</button>" : '<span class="tag lv1">MAX</span>') + "</div>";
      }).join("") +
        '<p class="hint-p">储物柜扩容影响背包种类上限；安全设施降低事故概率与损失；工作台让成本预览更准并抬高批量上限。' +
        (d.equipment.spectrometer ? "提纯已搬到【物质·提纯工坊】。" : "购入【分光光度计】后会解锁【物质·提纯工坊】。") + "</p>"
    });
  }
  function bindLab(body) {
    body.querySelectorAll("[data-if]").forEach(function (b) { b.onclick = function () { instrFilter = b.dataset.if; P.render(); }; });
    body.querySelectorAll("[data-room]").forEach(function (b) {
      b.onclick = function () {
        var r = CHEM.ROOMS.find(function (x) { return x.id === b.dataset.room; });
        if (poor(+b.dataset.p)) return;
        run("upgrade.room", { id: r.id }, function () {
          U.modal("<h3>🎉 新房间落成：" + U.esc(r.zh) + "</h3><div class='ph'>" + U.esc(r.desc) + "</div>" +
            (r.types && r.types.length ? '<div class="ph">专长加成：' + r.types.join("、") + " 类反应经验 +30%。</div>" : "") +
            '<div class="ph">顶部工作台栏已出现新的实验台！多台并行：这台做电解，那台炼有机。</div><button class="ok" data-close>开业！</button>');
        });
      };
    });
    body.querySelectorAll("[data-vbuy]").forEach(function (b) {
      b.onclick = function () {
        var i = CHEM.INSTRUMENTS.find(function (x) { return x.id === b.dataset.vbuy; });
        if (poor(+b.dataset.p)) return;
        run("upgrade.vessel", { id: i.id }, function (r) { U.toast("已购入 " + i.zh + "，扣 🪙" + r.cost, "good"); });
      };
    });
    body.querySelectorAll("[data-ebuy]").forEach(function (b) {
      b.onclick = function () {
        var i = CHEM.INSTRUMENTS.find(function (x) { return x.id === b.dataset.ebuy; });
        if (poor(+b.dataset.p)) return;
        run("upgrade.equipment", { id: i.id }, function (r) { U.toast(i.zh + " 已装备（🪙" + r.cost + "），工作台条件栏已解锁新功能", "good"); });
      };
    });
    body.querySelectorAll("[data-up]").forEach(function (b) {
      b.onclick = function () {
        var i = CHEM.INSTRUMENTS.find(function (x) { return x.id === b.dataset.up; });
        if (poor(+b.dataset.p)) return;
        run("upgrade.tier", { id: i.id }, function (r) {
          U.toast(i.zh + " 升级为 " + CHEM.TIER_NAMES[r.tier] + "，产物品质提升！", "good");
        });
      };
    });
    body.querySelectorAll("[data-lab]").forEach(function (b) {
      b.onclick = function () {
        var k = b.dataset.lab, u = CHEM.LAB_UPGRADES[k];
        if (poor(+b.dataset.p)) return;
        run("upgrade.lab", { key: k }, function (r) { U.toast(u.zh + " 升至 Lv." + r.lv + "，扣 🪙" + r.cost, "good"); });
      };
    });
  }

  /* ================= 设置 ================= */
  function drawSettings(body, which) {
    var d = st.data, mAct = st.monthlyActive();
    if (which === "appearance") return drawSkin(body, d);
    if (which === "account") return drawAccount(body);
    if (which === "store") return drawStore(body, mAct);
    if (which === "about") return drawAbout(body, d);
    var html = statbar([
      ["模式", d.realMode ? "真实" : "简单"],
      ["音效", (d.volSfx || 0) + "%"],
      ["音乐", d.music ? (d.volMus || 0) + "%" : "关闭"],
      ["皮肤", ({ default: "清水蓝", cyber: "赛博纪元", retro: "复古炼金" })[d.skins.cur] || "清水蓝"]
    ]);
    html += card({
      t: "🧪 实验判定", meta: "由服务器裁决",
      body: '<div class="row"><div class="grow"><b>反应真实度：' + (d.realMode ? "真实模式" : "简单模式") + "</b>" +
        "<small>简单模式忽略温度/催化剂等条件，物质正确即可反应；真实模式完整判定，产物与经验一致</small></div>" +
        '<button class="btn-s" id="set-mode">' + (d.realMode ? "切到简单" : "切到真实") + "</button></div>" +
        '<div class="row"><div class="grow"><b>实验保险</b><small>开启后下一次实验若出事故，理赔 50% 原料价值（实验台也会随危险组合提示）</small></div>' +
        '<button class="btn-s ' + (d.insured ? "g" : "") + '" id="set-ins">' + (d.insured ? "已开启" : "已关闭") + "</button></div>"
    });
    var sg = d.sign || { last: "", streak: 0 };
    html += card({
      t: "🔊 声音", meta: "程序合成，不占流量",
      body: '<div class="row"><div class="grow"><b>音效音量</b><small>放置/反应/金币/爆炸等合成音效；拉到 0 即关闭</small></div>' +
        '<input type="range" class="rng" id="vol-sfx" min="0" max="100" value="' + d.volSfx + '"></div>' +
        '<div class="row"><div class="grow"><b>背景音乐</b><small>程序生成的实验室氛围旋律</small></div>' +
        '<button class="btn-s" id="set-mus">' + (d.music ? "已开启" : "已关闭") + "</button>" +
        '<input type="range" class="rng" id="vol-mus" min="0" max="100" value="' + d.volMus + '"></div>'
    });
    html += card({
      t: "📤 分享与其他", meta: "签到本周期 " + signCycle(sg),
      body: '<div class="row"><div class="grow"><b>📣 分享战绩</b><small>复制一句战绩分享给同学（已发现种数由服务器统计）</small></div><button class="btn-s" id="set-share">分享</button></div>' +
        '<div class="row"><div class="grow"><b>每日签到</b><small>签到已搬到【任务·今日】，那里还能看到连续 7 天的礼包进度</small></div><button class="btn-s" data-goto="tasks">去签到</button></div>' +
        '<div class="row"><div class="grow"><b>月卡状态</b><small>' + (mAct ? "生效中：每日 💎3+🪙800 补贴、挂单免手续费、提纯免机器费" : "未开通，可在【设置·商店】购买") + "</small></div>" +
        (mAct ? '<span class="tag lv1">VIP</span>' : '<button class="btn-s o" data-goto="store">去商店</button>') + "</div>"
    });
    body.innerHTML = html;
    body.querySelectorAll("[data-goto]").forEach(function (b) {
      b.onclick = function () {
        var t = b.dataset.goto;
        if (t === "store") { segState.settings = "store"; P.go("settings"); }
        else P.go(t);
      };
    });
    bindSoundAndMode(body, d);
  }
  function bindSoundAndMode(body, d) {
    body.querySelector("#set-mus").onclick = function () {
      var on = !d.music;
      st.data.music = on;
      CHEM.sfx.setMusic(on);
      run("settings", { music: on }, function () { U.toast(on ? "🎵 背景音乐已开启" : "背景音乐已关闭", "good"); });
    };
    body.querySelector("#vol-sfx").oninput = function (ev) { st.data.volSfx = +ev.target.value; CHEM.sfx.play("click"); };
    body.querySelector("#vol-sfx").onchange = function (ev) { run("settings", { volSfx: +ev.target.value }); };
    body.querySelector("#vol-mus").oninput = function (ev) { st.data.volMus = +ev.target.value; CHEM.sfx.setMusic(true); };
    body.querySelector("#vol-mus").onchange = function (ev) { run("settings", { volMus: +ev.target.value }); };
    body.querySelector("#set-mode").onclick = function () {
      run("settings", { realMode: !d.realMode }, function () { U.toast(!d.realMode ? "已切换到真实模式" : "已切换到简单模式", "good"); });
    };
    body.querySelector("#set-ins").onclick = function () {
      run("settings", { insured: !d.insured }, function (r) {
        if (!r || r.ok === false) return U.toast((r && r.msg) || "设置失败", "bad");
        U.toast(!d.insured ? "实验保险已开启" : "实验保险已关闭", "good");
      });
    };
    body.querySelector("#set-share").onclick = function () { U.share(); };
  }
  function drawSkin(body, d) {
    var skins = [["default", "默认·清水蓝", "明亮的实验室白蓝"], ["cyber", "赛博纪元", "深色霓虹，夜间护眼"], ["retro", "复古炼金", "暖棕黄铜的旧手册质感"]];
    body.innerHTML = card({
      t: "🎨 皮肤", meta: "整套界面跟随",
      body: skins.map(function (sk) {
        var o = d.skins.owned.indexOf(sk[0]) >= 0 || sk[0] === "default";
        return '<div class="row"><span class="skin-prev skin-' + sk[0] + '"></span><div class="grow"><b>' + sk[1] +
          "</b><small>" + sk[2] + "</small></div>" +
          (d.skins.cur === sk[0] ? '<span class="tag lv1">使用中</span>' : o ? '<button class="btn-s" data-skin="' + sk[0] + '">切换</button>' : '<button class="btn-s o" data-goto="store">💎 购入</button>') + "</div>";
      }).join("") + '<p class="hint-p">皮肤只改配色令牌，不动任何玩法数值；赛博与复古在钻石商店购入。</p>'
    });
    body.querySelectorAll("[data-skin]").forEach(function (b) {
      b.onclick = function () { run("settings", { skin: b.dataset.skin }, function () { U.toast("皮肤已切换", "good"); }); };
    });
    body.querySelectorAll("[data-goto]").forEach(function (b) {
      b.onclick = function () { segState.settings = "store"; P.go("settings"); };
    });
  }
  function drawStore(body, mAct) {
    var d = st.data;
    var html = statbar([["钻石", "💎 " + d.diamonds], ["金币", "🪙 " + d.coins], ["月卡", mAct ? "生效中" : "未开通"]]);
    html += card({
      t: "💎 钻石商店", meta: "充值为模拟支付",
      body: CHEM.DSHOP.map(function (g) {
        var owned = "";
        if (g.id === "monthly" && mAct) owned = "生效中，至 " + new Date(d.monthly.until).toLocaleDateString();
        if (g.id === "elpack" && d.packs.el) owned = "已解锁";
        if (g.id === "noad" && d.noad) owned = "已移除广告";
        if (g.id === "hint5") owned = "提示次数：" + d.hints;
        if (g.id.indexOf("skin_") === 0) owned = d.skins.owned.indexOf(g.id.replace("skin_", "")) >= 0 ? "已拥有" : "";
        return '<div class="row"><div class="grow"><b>' + U.esc(g.zh) + "</b><small>" + U.esc(g.desc) + (owned ? " · " + owned : "") + "</small></div>" +
          '<button class="btn-s o" data-ds="' + g.id + '">💎' + g.d + "</button></div>";
      }).join("")
    });
    html += card({
      t: "🪙 充值中心（模拟）", meta: "演示环境，不产生真实费用",
      body: CHEM.RECHARGE.map(function (rc, i) {
        return '<div class="row"><div class="grow"><b>¥' + rc.c + " 钻石档</b><small>模拟支付：服务器直接入账钻石</small></div>" +
          '<button class="btn-s g" data-rc="' + i + '" data-rd="' + rc.d + '">充值 💎' + rc.d + "</button></div>";
      }).join("") + '<p class="hint-p">本作没有真实支付通道；钻石与金币账本都在服务器，客户端改不动。</p>'
    });
    body.innerHTML = html;
    body.querySelectorAll("[data-ds]").forEach(function (b) {
      b.onclick = function () { buyDiamondItem(b.dataset.ds); };
    });
    body.querySelectorAll("[data-rc]").forEach(function (b) {
      b.onclick = function () {
        var tier = +b.dataset.rc;
        U.modal("<h3>💳 模拟充值</h3><div class='ph'>确认支付 <b>¥" + CHEM.RECHARGE[tier].c + "</b> 购买 💎" + CHEM.RECHARGE[tier].d +
          "？（演示环境，不产生真实费用）</div>" +
          '<button class="ok" id="pay-ok">模拟支付</button><button class="ghost" data-close>取消</button>', {
            after: function (card2) {
              card2.querySelector("#pay-ok").onclick = function () {
                U.closeModal();
                run("shop.recharge", { tier: tier }, function (r) { U.toast("充值成功：💎" + r.gained + "（余额 " + r.diamonds + "）", "gold"); });
              };
            }
          });
      };
    });
  }
  function drawAccount(body) {
    var html = "";
    if (!CHEM.cloud.available()) {
      html += card({
        t: "☁️ 在线状态", meta: "未连接",
        body: '<div class="ph">本作是<b>在线游戏</b>：请先启动后端（server/ 目录 <b>mvn spring-boot:run</b>），再用浏览器访问它给出的地址（如 http://localhost:8080）。</div>'
      });
      body.innerHTML = html;
      return;
    }
    if (CHEM.game.guest) {
      html += card({
        t: "🎈 游客试玩中", meta: "存档已在服务器",
        body: '<div class="row"><input class="inp" id="acc-u" placeholder="用户名（2-24位）" maxlength="24">' +
          '<input class="inp" id="acc-p" type="password" placeholder="密码（至少6位）" maxlength="64"></div>' +
          '<div class="row"><div class="grow"><b>转正注册</b><small>注册后游客进度会<b>并入新账号</b>并继续，不会丢失；跨设备登录同一账号即可接着玩</small></div>' +
          '<button class="btn-s g" id="acc-reg">转正注册</button><button class="btn-s" id="acc-login">已有账号？登录</button></div>' +
          '<p class="hint-p">游客档没有口令，浏览器缓存清掉就找不回来了——请尽快转正。</p>'
      });
    } else {
      html += card({
        t: "👤 账号", meta: "☁️ 已登录",
        body: '<div class="row"><div class="grow"><b>' + U.esc(CHEM.game.user || "?") + "</b>" +
          "<small>每次操作即时写入服务器；换设备用同一账号登录即可继续</small></div><span class='tag lv1'>在线</span></div>" +
          '<div class="row"><div class="grow"><b>修改密码</b><small>改密会使其他设备的登录态失效</small></div><button class="btn-s" id="acc-pass">改密</button></div>' +
          '<div class="row"><div class="grow"><b>退出登录</b><small>退出后回到登录页；账号存档保留，重新登录即可继续</small></div><button class="btn-s" id="acc-out">退出</button></div>'
      });
      html += card({
        t: "⚠️ 危险操作", meta: "不可恢复", cls: "danger",
        body: '<div class="row"><div class="grow"><b>注销账号</b><small>永久删除账号与其服务器存档（含历史版本、行为记录）</small></div><button class="btn-s danger" id="acc-del">注销</button></div>'
      });
    }
    body.innerHTML = html;
    bindAccount(body);
  }
  function drawAbout(body, d) {
    var c = claimCounts();
    body.innerHTML = card({
      t: "ℹ️ 关于", meta: "v" + appVer() + " · 在线游戏",
      body: '<div class="kv"><span>内容规模</span><b>118 元素 · ' + (CHEM.REACTIONS || []).length + " 方程式 · " + CHEM.INSTRUMENTS.length + " 仪器 · " + CHEM.ROOMS.length + " 实验室</b></div>" +
        '<div class="kv"><span>结算真源</span><b>服务器（客户端只发意图）</b></div>' +
        '<div class="kv"><span>存档</span><b>服务器云存档，revision 乐观并发</b></div>' +
        '<div class="kv"><span>我的进度</span><b>Lv.' + d.level + " · 发现 " + Object.keys(d.discovered).length + " 种 · 🪙" + d.coins + " · 💎" + d.diamonds + "</b></div>" +
        '<div class="ph left">以真实化学为底座的在线实验沙盒。数据基于公开化学常识整理，实验请勿在家中模仿。</div>'
    });
    body.innerHTML += card({
      t: "🧹 存档", meta: "谨慎", cls: "danger",
      body: '<div class="row"><div class="grow"><b>重置存档</b><small>清空服务器上的全部进度（金币、钻石、图鉴、成就、挂单），无法恢复。当前还有 ' + c.total + " 项奖励未领</small></div>" +
        '<button class="btn-s danger" id="set-reset">重置</button></div>'
    });
    body.querySelector("#set-reset").onclick = function () {
      U.modal("<h3>确认重置？</h3><div class='ph'>服务器上的全部进度、金币、钻石、图鉴都会清空，无法恢复。</div>" +
        '<button class="ok danger" id="do-reset">确认重置</button><button class="ghost" data-close>取消</button>',
        {
          after: function (card2) {
            card2.querySelector("#do-reset").onclick = function () {
              U.closeModal();
              lbRows = null; lbDay = "";
              run("reset", {}, function () { U.toast("已重置为新手档", "good"); });
            };
          }
        });
    };
  }
  function buyDiamondItem(id) {
    run("shop.buy", { id: id }, function () {
      var msg = { monthly: "月卡已开通 30 天，每日补贴与特权生效", elpack: "镧系·锕系已在市场全量解锁", noad: "激励视频已移除，双倍改为直接领取", hint5: "助手精灵提示次数 +5" }[id];
      U.toast(msg || "新皮肤已装备", "gold");
    });
  }

  /* ---------- 账号动作绑定（游客转正 / 登录 / 改密 / 注销 / 退出） ---------- */
  function bindAccount(body) {
    var reg = body.querySelector("#acc-reg");
    if (reg) {
      reg.onclick = function () {
        var u = body.querySelector("#acc-u").value.trim(), p = body.querySelector("#acc-p").value;
        if (!u || !p) return U.toast("请输入用户名和密码", "bad");
        CHEM.cloud.upgrade(u, p, function (j) {
          if (!j.ok) return U.toast(j.msg || "注册失败", "bad");
          CHEM.game.guest = false; CHEM.game.user = j.user;
          U.toast("🎉 欢迎，" + j.user + "！游客进度已并入该账号", "gold");
          P.render();
          U.updateNav();
        });
      };
      body.querySelector("#acc-login").onclick = function () {
        var u = body.querySelector("#acc-u").value.trim(), p = body.querySelector("#acc-p").value;
        if (!u || !p) return U.toast("请输入用户名和密码", "bad");
        CHEM.cloud.login(u, p, function (j) {
          if (!j.ok) return U.toast(j.msg || "登录失败", "bad");
          U.toast("👤 已登录 " + j.user + "，正在载入该账号的存档", "good");
          G.restart();
        });
      };
      return;
    }
    if (!body.querySelector("#acc-pass")) return;
    body.querySelector("#acc-pass").onclick = function () {
      U.modal("<h3>🔑 修改密码</h3>" +
        '<input class="inp stack" id="pw-old" type="password" placeholder="原密码" maxlength="64">' +
        '<input class="inp stack" id="pw-new" type="password" placeholder="新密码（至少6位）" maxlength="64">' +
        '<button class="ok" id="pw-go">确认修改</button><button class="ghost" data-close>取消</button>', {
          after: function (card2) {
            card2.querySelector("#pw-go").onclick = function () {
              var o = card2.querySelector("#pw-old").value, n = card2.querySelector("#pw-new").value;
              CHEM.cloud.changePass(o, n, function (j) {
                if (!j.ok) return U.toast(j.msg || "修改失败", "bad");
                var me = CHEM.cloud.user();
                CHEM.cloud.login(me, n, function (k) {
                  U.closeModal();
                  if (!k.ok) { CHEM.cloud.clearSession(); G.restart(); return; }
                  U.toast("密码已修改，本机登录态已续期", "good");
                });
              });
            };
          }
        });
    };
    body.querySelector("#acc-del").onclick = function () {
      U.modal("<h3 class='danger-head'>⚠️ 注销账号</h3><div class='ph'>将永久删除账号与服务器存档（含历史版本、行为记录），无法恢复。输入密码确认：</div>" +
        '<input class="inp" id="del-p" type="password" placeholder="登录密码" maxlength="64">' +
        '<button class="ok danger" id="del-go">确认注销</button><button class="ghost" data-close>取消</button>', {
          after: function (card2) {
            card2.querySelector("#del-go").onclick = function () {
              CHEM.cloud.deleteAccount(card2.querySelector("#del-p").value, function (j) {
                if (!j.ok) return U.toast(j.msg || "注销失败", "bad");
                U.closeModal();
                U.toast("账号已注销，即将回到登录页", "good");
                G.restart();
              });
            };
          }
        });
    };
    body.querySelector("#acc-out").onclick = function () {
      CHEM.cloud.logout(function () { U.toast("已退出登录，服务器存档保留", "good"); G.restart(); });
    };
  }

  /* ================= 答题（题目与答案判定全在服务器） ================= */
  var quizGrade = "all";
  P.openQuiz = function (grade) {
    quizGrade = grade || "all";
    G.call("quiz.pickOne", { grade: quizGrade }, function (q) {
      if (!q || !q.ok) return U.toast((q && q.msg) || "取题失败", "bad");
      renderQuiz(q);
    });
  };
  function renderQuiz(q) {
    var head = '<div class="tabs">' + [["all", "全部"], ["小学", "小学"], ["初中", "初中"], ["高中", "高中"], ["大学", "大学"]].map(function (g) {
      return '<button data-qg="' + g[0] + '" class="' + (quizGrade === g[0] ? "on" : "") + '">' + g[1] + "</button>";
    }).join("") + "</div>";
    var html = head + "<h3>📝 趣味化学题（" + U.esc(q.grade) + "）</h3><div class='quiz-q'>" + U.esc(q.q) + "</div>" +
      (q.opts || []).map(function (o, i) { return '<button class="quiz-opt" data-i="' + i + '">' + U.esc(o) + "</button>"; }).join("");
    U.modal(html, {
      after: function (card2) {
        card2.querySelectorAll("[data-qg]").forEach(function (b) { b.onclick = function () { U.closeModal(); P.openQuiz(b.dataset.qg); }; });
        card2.querySelectorAll(".quiz-opt").forEach(function (b) {
          b.onclick = function () {
            if (card2.dataset.done) return;
            card2.dataset.done = 1;
            var chosen = +b.dataset.i;
            G.call("quiz.answer", { quizId: q.id, choice: chosen }, function (r) {
              if (!r || r.ok === false) { card2.dataset.done = ""; return U.toast((r && r.msg) || "判题失败", "bad"); }
              card2.querySelectorAll(".quiz-opt").forEach(function (x, i) {
                if (i === r.answer) x.classList.add("right");
                else if (i === chosen) x.classList.add("wrong");
              });
              var res = document.createElement("div");
              res.className = "ph mt";
              res.textContent = (r.correct ? "✅ 答对！+" + r.reward + " 金币" + (r.dropD ? "，还掉落了 💎" + r.dropD + "！" : "。") : "❌ 答错了。") + " " + (r.exp || "");
              card2.appendChild(res);
              if (r.correct) CHEM.sfx.play("coin");
              var again = document.createElement("button");
              again.className = "ghost"; again.textContent = "再来一题";
              again.onclick = function () { U.closeModal(); P.openQuiz(quizGrade); };
              var btn = document.createElement("button");
              btn.className = "ok"; btn.textContent = "继续";
              btn.onclick = function () { U.closeModal(); };
              var bar = document.createElement("div");
              bar.className = "btn-row";
              bar.appendChild(again); bar.appendChild(btn);
              card2.appendChild(bar);
              U.updateNav();
            });
          };
        });
      }
    });
  }
})();
