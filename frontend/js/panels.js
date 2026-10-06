/* 页面 v5（在线版 · 信息架构重排 2026-09-26）
   底部导航仍是 7 页，但每页内部按"动作类型"分段，功能搬到玩家真正找它的位置：
     物质 = 我的背包 / 提纯工坊（原来错放在建设页）
     图鉴 = 物质 / 方程式 / 收集节点
     市场 = 采购 / 特惠 / 黑市 / 耗材 / 出售 / 挂单（商会订单是委托，搬去【任务·今日】）
     任务 = 今日（签到 + 每日任务 + 商会订单）/ 成就 / 玩法（挑战·沙盒·答题）/ 社交
     建设 = 房间 / 仪器 / 升级
     设置 = 通用 / 外观 / 账号 / 广告（激励视频与积分兑换，取代原来的钻石商店与充值）/ 关于（重置存档）
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
  /* 广告中心的视图缓存：数值只来自服务端 ad.status，adWant=该重新拉，adTimer=冷却倒计时（离开本页即停） */
  var adView = null, adWant = true, adTimer = null;

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
    if (changed) { qFocus = ""; resetPages(); }   // 换页 = 换上下文，上一张列表展开到第几轮不该带过去
    document.querySelectorAll("#bottom-nav button").forEach(function (b) {
      var on = b.dataset.tab === tab;
      b.classList.toggle("on", on);
      // 当前页原来只有一个高亮色：读屏玩家问"我在哪一页"是得不到回答的（H5）
      if (on) b.setAttribute("aria-current", "page"); else b.removeAttribute("aria-current");
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
      // 分段条就是页内的 tabs：只靠 .on 的高亮，读屏听不出"现在看的是哪一段"（H5）
      var sel = cur === s[0];
      return '<button class="seg' + (sel ? " on" : "") + '" role="tab" aria-selected="' + (sel ? "true" : "false") +
        '" aria-controls="page-body" data-seg="' + s[0] + '">' + s[1] +
        (n > 0 ? '<b class="pip">' + n + "</b>" : n < 0 ? '<i class="pip dot" aria-hidden="true"></i>' : "") +
        (n > 0 ? '<span class="sr-only">项可办</span>' : "") + "</button>";
    }).join("");
    box.querySelectorAll("[data-seg]").forEach(function (b) {
      b.onclick = function () {
        if (segState[tab] === b.dataset.seg) return;
        segState[tab] = b.dataset.seg;
        qFocus = "";
        resetPages();
        P.go(tab);
      };
    });
  }
  P.seg = function (tab) { return segState[tab]; };

  /**
   * 安卓返回键的第一层：把当前页退回"干净状态"——先收起展开过的长列表（H3 的"更多"），
   * 再清掉搜索词（玩家输入过东西），最后把分段退回默认段（他切换过）。
   * 三步都是纯 UI，不发意图、不动结算。
   * 返回 true 表示这次返回被这一层消化了，调用方（game.js）就不该再回实验台。
   */
  P.backStep = function () {
    var tab = P.tab;
    if (tab === "bench") return false;
    var keys = [tab];
    if (tab === "codex") keys.push("eq");                 // 图鉴页的方程式搜索是另一个输入框
    if (anyPageOpen(keys)) {                              // 展开过就先收回去：这一步最"就近"
      keys.forEach(function (k) { resetPages(k); });
      P.render(tab);
      return true;
    }
    var had = keys.some(function (k) { return !!qState[k]; });
    if (had) {
      keys.forEach(function (k) { qState[k] = ""; });
      qFocus = "";
      P.render(tab);
      return true;
    }
    var def = PAGES[tab];
    var first = def && def.segs ? def.segs()[0][0] : null;
    if (first && segState[tab] !== first) { segState[tab] = first; P.go(tab); return true; }
    return false;
  };

  /** 台面已有物质时，就地给一个回实验台的落点（省一次导航点击）。 */
  function syncFab() {
    var fab = U.$("page-fab");
    if (!fab) return;
    var n = E.lines();
    /* 挑战/沙盒的临时工作台本来就在别的页投放，同样给回台入口 */
    if (P.tab === "bench" || !n) { fab.classList.add("hidden"); return; }
    fab.classList.remove("hidden");
    fab.textContent = "⚗️ 台面 " + n + "/" + E.maxLines() + " 种 · 回实验台";
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
  /* ---------- 长列表分页（H3） ----------
     图鉴一次铺 214 张物质卡、方程式 143 行、沙盒材料柜 213 项：拼字符串、innerHTML 解析、
     再对每张卡 querySelectorAll 绑一次 onclick，这三段是低端 WebView 首屏里最大的一块固定开销，
     而玩家一屏只看得到十几张。做法沿用实验台物质架那一套（ui.js 的 QS_MAX）：先切一页，
     多出来的挂一张"更多"，点一下在原位续展——不弹窗、不换页，人不用重新找位置。
     展开轮数按"列表 key"记忆（key 的前缀就是搜索框那把 key），换搜索词 / 换分类 / 换分段时作废：
     结果集都变了还留着上一轮的展开，会把新列表撑得比原来更长，等于分页白做。 */
  var PAGE_N = 24;
  var pageOpen = {};
  function resetPages(prefix) {
    if (!prefix) { pageOpen = {}; return; }
    Object.keys(pageOpen).forEach(function (k) { if (k.indexOf(prefix + ":") === 0) delete pageOpen[k]; });
  }
  /** 这几把 key 底下有没有列表被展开过——安卓返回键要先把这一层收回去。 */
  function anyPageOpen(prefixes) {
    return Object.keys(pageOpen).some(function (k) {
      return pageOpen[k] > 0 && prefixes.some(function (p) { return k.indexOf(p + ":") === 0; });
    });
  }
  /** 取这一页。list 必须已经排好序（截断按位置，重排会让已看过的条目跳走）。 */
  function pageSlice(key, list) {
    var n = PAGE_N * (1 + (pageOpen[key] || 0));
    return list.length <= n ? { list: list, left: 0 } : { list: list.slice(0, n), left: list.length - n };
  }
  /** 网格里的"更多"卡：与物质架那张 qs-more 同款样式。它是 div，键盘按不到（H5 那轮判的就是这个），
      所以补 role/tabindex 与一句读得出的名字。 */
  function moreCard(key, left) {
    return '<div class="item-card qs-more" role="button" tabindex="0" aria-label="还有 ' + left + ' 项，展开下一批"' +
      ' data-more="' + U.esc(key) + '">' +
      '<div class="sq">+' + left + '</div><div class="zh">更多</div></div>';
  }
  /** 行式列表用整行按钮：一条卡片塞在长列表末尾不好点，也不像"还有东西"。 */
  function moreRow(key, left) {
    return '<div class="row more-row"><div class="grow"><b>还有 ' + left + ' 项未显示</b>' +
      '<small>每次展开 ' + PAGE_N + ' 项，原位续接、不会跳回顶部</small></div>' +
      '<button class="btn-s" data-more="' + U.esc(key) + '">显示更多</button></div>';
  }
  function bindMore(body) {
    body.querySelectorAll("[data-more]").forEach(function (b) {
      b.onclick = function () {
        var k = b.dataset.more;
        pageOpen[k] = (pageOpen[k] || 0) + 1;
        P.render();            // P.render 对同一 tab:seg 会保住 scrollTop，续展不弹回顶部
      };
      if (b.tagName !== "BUTTON") b.onkeydown = function (e) {
        if (e.key === "Enter" || e.key === " " || e.key === "Spacebar") { e.preventDefault(); b.click(); }
      };
    });
  }
  P.pageStats = function () { return { n: PAGE_N, open: Object.keys(pageOpen).length }; };
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
      resetPages(key);         // 关键词变了 = 结果集变了，上一轮的展开必须作废
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

  /**
   * "这一帧算过一次的答案"记忆表。角标与统计条这类计算有个共同形状：输入只有当前存档帧 +
   * 内容版本，而一次导航里同一样东西要被取 4~6 遍（页内分段条、底部角标、页面统计条各一次）。
   * 帧的判据用对象身份而不是 revision：state.setServer 每落一帧都换一个新 data 对象，
   * 换帧天然重算，不需要谁记得去比版本号；内容换版不动存档，所以那一路由 invalidateCounts 显式清。
   */
  var countsCache = null, countsFrame = null, countsVer = -1;
  function invalidateCounts() { countsCache = null; countsFrame = null; countsVer = -1; }
  function contentVer() { return st.contentVersion ? st.contentVersion() : 0; }
  function memoGet(name, compute) {
    var cv = contentVer();
    if (!countsCache || countsFrame !== st.data || countsVer !== cv) {
      countsCache = {}; countsFrame = st.data; countsVer = cv;
    }
    if (!Object.prototype.hasOwnProperty.call(countsCache, name)) countsCache[name] = compute();
    return countsCache[name];
  }
  /** 有几件事"现在就能做"——分段数字与底部导航角标都从这里取，两处不会各算一套。 */
  function claimCounts() { return memoGet("claim", computeClaimCounts); }
  function computeClaimCounts() {
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
  function buyableCount() { return memoGet("buyable", computeBuyable); }
  function computeBuyable() {
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
  P.invalidateCounts = invalidateCounts;

  var PAGES = {
    bag: { segs: bagSegs, draw: drawBag },
    codex: { segs: function () { var c = claimCounts(); return [["sub", "物质", 0], ["eq", "方程式", 0], ["node", "收集节点", c.node]]; }, draw: drawCodex },
    market: { segs: function () { var d = st.data; return [["buy", "采购", 0], ["special", "特惠", (d.market.specials || []).length], ["black", "黑市", 0], ["consume", "耗材", 0], ["sell", "出售", 0], ["listing", "挂单", d.listings.length]]; }, draw: drawMarket },
    tasks: { segs: function () { var c = claimCounts(); return [["today", "今日", c.today], ["ach", "成就", c.ach], ["play", "玩法", 0], ["social", "社交", 0]]; }, draw: drawTasks },
    lab: { segs: function () { var n = buyableCount(); return [["room", "房间", 0], ["instr", "仪器", n], ["upgrade", "升级", 0]]; }, draw: drawLab },
    settings: { segs: function () { return [["general", "通用", 0], ["appearance", "外观", 0], ["account", "账号", G.guest ? -1 : 0], ["ad", "广告", 0], ["about", "关于", 0]]; }, draw: drawSettings }
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
    var pb = pageSlice("bag:grid", shown);
    var html = statbar([
      ["持有种数", kinds + " / " + cap, kinds >= cap ? "warn" : ""],
      ["总份数", totalUnits()],
      ["金币", "🪙 " + st.data.coins]
    ]);
    html += card({
      t: "🧪 我的背包", meta: shown.length + " 种 · 点卡片即投一份",
      body: chips(groups, bagFilter, "bg") + searchBox("bag", "搜索物质名 / 化学式 / 元素符号") +
        (shown.length ? '<div class="grid">' + pb.list.map(function (id) {
          var s = all[id];
          var total = st.countAll(id);
          return '<div class="item-card" data-id="' + U.esc(id) + '">' +
            (total ? '<i class="cnt">' + total + "</i>" : "") +
            '<div class="sq" style="background:' + U.esc(s.color) + ";color:" + pickTextColor(s.color) + '">' + U.esc(shortFm(s)) + "</div>" +
            '<div class="zh">' + U.esc(s.zh) + '</div><div class="fm">' + U.esc(s.formula || "") + qMark(id) + "</div></div>";
        }).join("") + (pb.left ? moreCard("bag:grid", pb.left) : "") + "</div>" : (ids.length
          ? emptyState({ glyph: "🔍", text: "没有匹配的物质，换个关键词试试。" })
          : emptyState({ glyph: "🫙", text: "背包空空如也。先去【市场】采购原料，或在实验台上合成。", go: "market" }))) +
        '<p class="hint-p">容量上限 ' + cap + " 种（【建设·升级·储物柜扩容】可提高）。品质分档（粗 / 纯 / 高纯）见【物质·提纯工坊】。危险物质请分开存放。</p>"
    });
    body.innerHTML = html;
    body.querySelectorAll("[data-bg]").forEach(function (b) { b.onclick = function () { bagFilter = b.dataset.bg; resetPages(); P.render(); }; });
    bindSearch(body, "bag");
    bindEmptyActions(body);
    bindCards(body);
    bindMore(body);
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
    var psb = pageSlice("bag:sandbox", shown);
    var html = card({
      t: "🌌 沙盒物质柜", meta: "全部物质 · 无限供应",
      body: searchBox("bag", "在全部物质里搜索") + '<div class="grid">' + psb.list.map(function (id) {
        var s = st.sub(id);
        return '<div class="item-card" data-id="' + U.esc(id) + '"><i class="cnt">∞</i>' +
          '<div class="sq" style="background:' + U.esc(s.color) + ";color:" + pickTextColor(s.color) + '">' + U.esc(shortFm(s)) + "</div>" +
          '<div class="zh">' + U.esc(s.zh) + "</div></div>";
      }).join("") + (psb.left ? moreCard("bag:sandbox", psb.left) : "") + "</div>" +
        '<p class="hint-p">点击直接投放到沙盒工作台。不产生收益与图鉴进度，可自由验证化学与彩蛋。</p>'
    });
    body.innerHTML = html;
    bindSearch(body, "bag");
    bindCards(body);
    bindMore(body);
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
  /* H5：物质色是后台可编辑的数据，不能假设它"够深"或"够浅"。
     原来用 YIQ 亮度 >150 挑黑白（上世纪的启发式），只保证不刺眼、不保证读得出：
     按 WCAG 相对亮度实测，214 个物质色里有 29 个的符号压在自己的色块上不到 4.5:1。
     现在两个候选各算一次对比度取高的：白，或近黑的 --sq-ink（深字那档把门槛从
     L≤0.183 抬到 L≥0.221，中间的死区只剩 4 个色，已随 V13 迁移把它们挪出去）。
     口径与 test/a11y.js 完全一致，裁判和运行时算的是同一件事。 */
  var SQ_INK = "#0f1b26";   /* 相对亮度 0.010249：白底卡上的深墨，压在亮物质色上 */
  var SQ_INK_L = 0.010249;
  function chan(v) { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
  function relLum(hexStr) {
    var h = hexStr.slice(1);
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    return 0.2126 * chan(parseInt(h.substr(0, 2), 16))
      + 0.7152 * chan(parseInt(h.substr(2, 2), 16))
      + 0.0722 * chan(parseInt(h.substr(4, 2), 16));
  }
  function pickTextColor(hex) {
    if (!/^#[0-9a-f]{3,8}$/i.test(hex || "")) return "#fff";
    var L = relLum(hex);
    return (1.05 / (L + 0.05)) >= ((L + 0.05) / (SQ_INK_L + 0.05)) ? "#fff" : SQ_INK;
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
    var pk = pageSlice("codex:known", known), pl = pageSlice("codex:locked", locked);
    var html = statbar([
      ["已发现", got + " / " + total],
      ["完成度", Math.round(got / total * 100) + "%"],
      ["方程式", Object.keys(d.reactionsKnown).length + " / " + (CHEM.REACTIONS || []).length]
    ]);
    html += '<div class="card"><header class="card-hd"><b>✅ 已发现</b><span>' + known.length + " 种</span></header><div class=\"card-bd\">" +
      (known.length ? '<div class="grid">' + pk.list.map(codexCard).join("") + (pk.left ? moreCard("codex:known", pk.left) : "") + "</div>" : '<p class="hint-p">还没有发现任何物质——先去实验台合成第一瓶吧。</p>') +
      "</div></div>";
    html += '<div class="card"><header class="card-hd"><b>❔ 待发现</b><span>' + locked.length + " 种</span></header><div class=\"card-bd\">" +
      chips(cats, codexCat, "cc") + searchBox("codex", "在图鉴里搜索物质名 / 化学式") +
      (locked.length ? '<div class="grid">' + pl.list.map(codexCard).join("") + (pl.left ? moreCard("codex:locked", pl.left) : "") + "</div>"
        : '<p class="hint-p">这一类已经全部发现了。</p>') +
      "</div></div>";
    html += '<p class="hint-p">收集 118 种元素与二百余种化合物，全部图鉴约 200 天自然毕业节奏。点击任意卡片看详情与今日行情。</p>';
    body.innerHTML = html;
    body.querySelectorAll("[data-cc]").forEach(function (b) { b.onclick = function () { codexCat = b.dataset.cc; resetPages(); P.render(); }; });
    bindSearch(body, "codex");
    bindMore(body);
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
    var pg = pageSlice("eq:got", got), pm = pageSlice("eq:miss", miss);
    var html = statbar([
      ["已解锁", knownN + " / " + rs.length],
      ["完成度", Math.round(knownN / Math.max(1, rs.length) * 100) + "%"],
      ["本类", flt.length + " 条"]
    ]);
    html += card({
      t: "🧾 方程式图鉴", meta: flt.length + " 条",
      body: chips(filters, eqFilter, "ef") + searchBox("eq", "在已解锁的方程式里搜索（方程式 / 类型 / 现象）") +
        pg.list.map(function (r) {
          return '<div class="row eqrow" data-eq="' + r.id + '"><div class="grow"><b class="eqline">' + U.esc(r.eq) + "</b>" +
            "<small>" + U.esc(r.phenomenon) + " · " + U.esc(r.type) + " · " + U.esc(condText(r)) + "</small></div>" + eqTags(r) + "</div>";
        }).join("") + (pg.left ? moreRow("eq:got", pg.left) : "") +
        (got.length || !q ? "" : '<p class="hint-p">已解锁的方程式里没有匹配项。</p>') +
        (miss.length ? '<div class="card-sub">尚未发现 · ' + miss.length + " 条" + (q ? "（不计入关键词搜索）" : "") + "</div>" +
          pm.list.map(function (r) {
            return '<div class="row"><div class="grow"><b>？？？</b><small>尚未发现 —— 多尝试不同的物质组合</small></div>' +
              '<span class="tag lv' + Math.min(4, r.discoverLv > 15 ? 4 : r.discoverLv > 10 ? 3 : r.discoverLv > 5 ? 2 : 1) + '">Lv.' + r.discoverLv + "</span></div>";
          }).join("") + (pm.left ? moreRow("eq:miss", pm.left) : "") : "") +
        (got.length ? "" : '<p class="hint-p">还没有解锁方程式。做几次实验，第一瓶水就会点亮图鉴。</p>')
    });
    body.innerHTML = html;
    body.querySelectorAll("[data-ef]").forEach(function (b) { b.onclick = function () { eqFilter = b.dataset.ef; qFocus = "eq"; resetPages(); P.render(); }; });
    bindSearch(body, "eq");
    bindMore(body);
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
    bindMore(body);
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
    var pp = pageSlice("market:buy", pool);
    var rows = pp.list.map(function (id) {
      var s = st.sub(id), p = Math.round(st.buyPrice(id) * marketAmt);
      return '<div class="row"><i class="dot" style="background:' + U.esc(s.color) + '"></i>' +
        '<div class="grow"><b>' + U.esc(s.zh) + '</b> <small>' + U.esc(s.formula || "") + " · 持有 " + st.countAll(id) + (s.hazard ? " · ⚠️危险" : "") + "</small></div>" +
        '<span class="price">🪙' + p + '</span><button class="btn-s" data-buy="' + U.esc(id) + '" data-p="' + p + '">买' + marketAmt + "</button></div>";
    }).join("") + (pp.left ? moreRow("market:buy", pp.left) : "");
    return card({
      t: "🛒 采购原料", meta: "生面孔 ×1.2 起步，声望越高越便宜",
      body: '<div class="rowtools"><div class="tabs flat">' + [1, 10, 50].map(function (n) {
        return '<button data-amt="' + n + '" class="' + (marketAmt === n ? "on" : "") + '">' + n + "份</button>";
      }).join("") + '</div><span class="rowtip">买入价 = 基准价 ×' + tier.buyRate.toFixed(2) + " ×每日行情（±8%）</span></div>" +
        searchBox("market", "在市场里搜索物质名 / 化学式") +
        (pool.length ? '<div class="rows">' + rows + "</div>"
          : emptyState({ glyph: "🔍", text: allN ? "没有匹配的货，换个关键词试试。" : "市场暂时没有上架的原料。" })) +
        '<p class="hint-p">等级不够的元素不会上架；镧系·锕系可在【设置·广告】用看广告攒的积分一次性解锁。列表按化合物层级与价格排序。</p>'
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
    var ps = pageSlice("market:sell", rows);
    return card({
      t: "💰 出售库存", meta: "直售秒到账 ×" + CHEM.SELL_RATE,
      body: (rows.length ? '<div class="rows">' + ps.list.join("") + (ps.left ? moreRow("market:sell", ps.left) : "") + "</div>"
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
        "<span>⏳" + left + "s</span>" +
        '<button class="btn-s" data-report="' + U.esc(L.id + "|" + L.q) + '">举报</button></div>';
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
    body.querySelectorAll("[data-report]").forEach(function (b) {
      var p = b.dataset.report.split("|");
      b.onclick = function () { openReportModal("listing", p[0] + "|" + p[1]); };
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
        t: "📝 趣味化学题", meta: "答对 🪙" + CHEM.quizRewardRange() + " · 可能掉钻石",
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
      // 双倍券同样要经服务器工单：先看一段激励视频换券，到账后才发领取意图
      U.watchAd("dbl", function (ok) {
        if (!ok && btn.isConnected) btn.disabled = false;   // 成功时整帧回执会重绘这张卡
        if (ok) fire();
      });
      return;
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
    if (which === "ad") return drawAds(body, mAct);
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
        '<div class="row"><div class="grow"><b>实验保险</b><small>开启后下一次实验若出事故，理赔 ' + CHEM.insurancePct() + " 原料价值（实验台也会随危险组合提示）</small></div>" +
        '<button class="btn-s ' + (d.insured ? "g" : "") + '" id="set-ins">' + (d.insured ? "已开启" : "已关闭") + "</button></div>"
    });
    var sg = d.sign || { last: "", streak: 0 };
    html += card({
      t: "🔊 声音", meta: "程序合成，不占流量",
      body: '<div class="row"><div class="grow"><b>音效音量</b><small>放置/反应/金币/爆炸等合成音效；拉到 0 即关闭</small></div>' +
        '<input type="range" class="rng" id="vol-sfx" min="0" max="100" value="' + d.volSfx + '"></div>' +
        '<div class="row"><div class="grow"><b>背景音乐</b><small>程序生成的实验室氛围旋律</small></div>' +
        '<button class="btn-s" id="set-mus">' + (d.music ? "已开启" : "已关闭") + "</button>" +
        // 触感与音量无关，也不进存档：换台设备不该带走上一台的振动习惯，所以它是本机偏好（见 sfx.js）
        '<input type="range" class="rng" id="vol-mus" min="0" max="100" value="' + d.volMus + '"></div>' +
        '<div class="row"><div class="grow"><b>📳 触感反馈</b><small>合成成功／实验事故／升级时短振一下（10–20ms）；静音时也照振，设备不支持则自动跳过</small></div>' +
        '<button class="btn-s' + (CHEM.sfx.hapticOn() ? " g" : "") + '" id="set-hap">' + (CHEM.sfx.hapticOn() ? "已开启" : "已关闭") + "</button></div>"
    });
    html += card({
      t: "📤 分享与其他", meta: "签到本周期 " + signCycle(sg),
      body: '<div class="row"><div class="grow"><b>📣 分享战绩</b><small>复制一句战绩分享给同学（已发现种数由服务器统计）</small></div><button class="btn-s" id="set-share">分享</button></div>' +
        '<div class="row"><div class="grow"><b>每日签到</b><small>签到已搬到【任务·今日】，那里还能看到连续 7 天的礼包进度</small></div><button class="btn-s" data-goto="tasks">去签到</button></div>' +
        '<div class="row"><div class="grow"><b>月卡状态</b><small>' + (mAct ? "生效中：每日 💎3+🪙800 补贴、挂单免手续费、提纯免机器费（到期前可再续 " + monthlyLeft(d) + " 天）" : "未开通：在【设置·广告】用看广告攒的积分兑换 30 天") + "</small></div>" +
        (mAct ? '<span class="tag lv1">VIP</span>' : '<button class="btn-s o" data-goto="ad">去兑换</button>') + "</div>"
    });
    html += complianceCard();
    html += consentCard();
    body.innerHTML = html;
    body.querySelectorAll("[data-goto]").forEach(function (b) {
      b.onclick = function () {
        var t = b.dataset.goto;
        if (t === "ad") { adWant = true; segState.settings = "ad"; P.go("settings"); }
        else P.go(t);
      };
    });
    bindSoundAndMode(body, d);
    bindCompliance(body);
    bindConsent(body);
  }

  /* 第三方 SDK 授权（只在壳里出现）：同意状态的真源是原生那份 SharedPreferences，
     这里只负责把它念出来，并提供两个方向的动作——同意（重新问一次）与撤回。
     撤回入口是法定要求（个保法第十五条：处理目的改变或玩家撤回后，必须能方便地停止处理），
     "只能同意、要撤回就去清数据"那种写法在提审时会被直接问住。 */
  var consentView = null;
  P.consentInvalidate = function () { consentView = null; };
  function consentCard() {
    if (!CHEM.shell.inShell() || !CHEM.ad) return "";
    if (!consentView) {
      CHEM.ad.consentStatus(function (st) {
        consentView = st || { consented: false };
        if (P.tab === "settings" && segState.settings === "general") P.render();
      });
      return card({
        t: "🔐 第三方 SDK 授权", meta: "以壳内记录为准",
        body: '<div class="row"><div class="grow"><b>正在读取授权状态…</b>' +
          "<small>激励视频（广告网络）与 TapTap 登录都要先取得你的同意才会启动</small></div></div>"
      });
    }
    var on = consentView.consented === true;
    return card({
      t: "🔐 第三方 SDK 授权", meta: on ? "已同意" : "未同意",
      body: '<div class="row"><div class="grow"><b>' + (on ? "广告与 TapTap 登录已授权" : "尚未授权：激励视频与 TapTap 登录不可用") + "</b>" +
        "<small>未授权时游戏照常可玩，只是看不到激励视频、也不能用 TapTap 账号一键登录。" +
        "同意之前壳不会初始化任何第三方 SDK，也不会采集设备标识。</small></div>" +
        '<button class="btn-s ' + (on ? "" : "o") + '" id="set-consent">' + (on ? "撤回授权" : "去同意") + "</button></div>" +
        '<p class="hint-p">政策全文在【关于】里，可随时读。</p>'
    });
  }
  function bindConsent(body) {
    var b = body.querySelector("#set-consent");
    if (!b) return;
    b.onclick = function () {
      if (consentView && consentView.consented === true) {
        CHEM.ad.revokeConsent(function (ok) {
          if (!ok) return U.toast("撤回失败：壳没有记下这次操作", "bad");
          P.consentInvalidate();
          U.toast("已撤回授权：激励视频与 TapTap 登录停止", "info");
          if (P.tab === "settings") P.render();
        });
        return;
      }
      CHEM.ad.askConsent(function () {
        P.consentInvalidate();
        U.toast("已同意：可以观看激励视频了", "good");
        if (P.tab === "settings") P.render();
      }, function () { /* 在这个入口里点"不同意"就什么也不做，状态仍是未授权 */ });
    };
  }

  /* ================= 合规与设备（青少年模式 / 省电模式 / 版本更新） =================
     青少年模式的时间窗由服务端 curfew 配置决定，这一页只负责"打开/关闭"和把服务器给的窗口
     原样念出来——客户端不参与判定，所以哪怕有人把这里的文案改掉，闸门照旧生效。 */
  var cfView = null, cfWant = true;
  /** 广告到账、后台改配置后都可能让时段变化，给外部一个作废视图的钩子。 */
  P.curfewInvalidate = function () { cfWant = true; };
  function complianceCard() {
    if (cfWant) {
      cfWant = false;
      // 读不到时给 false（不是 null）：null 表示"还没问过服务器"，两者在文案上必须分得开
      CHEM.curfew.refresh(function (v) {
        cfView = v || false;
        if (P.tab === "settings" && segState.settings === "general") P.render();
      });
    }
    var minor = !!(cfView && cfView.minor), enforced = cfView && cfView.enforced;
    var low = CHEM.shell.lowFx();
    var win = cfView && cfView.window ? cfView.window : null;
    var html = '<div class="row"><div class="grow"><b>青少年模式</b><small>' +
      (cfView === null ? "正在从服务器读取状态…"
        : !cfView ? "暂时读不到服务器的时段配置。开关照旧可用，最终以服务器返回的结果为准。"
        : "开启后由<b>服务器</b>限定可玩时段" + (win ? "（" + U.esc(curfewWindowText(win)) + "）" : "") +
          "，时段外实验台会给出倒计时；这是给家长的监护工具，随时可以再关掉。" +
          (enforced ? "" : "（运营侧的防沉迷总开关当前是关的，所以开启后暂时不会真的拦人）")) +
      "</small></div><button class='btn-s " + (minor ? "g" : "") + "' id='set-minor'>" + (minor ? "已开启" : "已关闭") + "</button></div>" +
      '<div class="row"><div class="grow"><b>省电模式</b><small>低端机或想更省电时打开：粒子上限减半、关掉背景光斑，只影响画面，不动任何玩法数值。当前判定：' +
      (low ? "省电（检测到较弱的设备，或你手动开启过）" : "完整特效") + "</small></div>" +
      '<button class="btn-s ' + (low ? "g" : "") + '" id="set-lowfx">' + (low ? "已开启" : "已关闭") + "</button></div>";
    if (CHEM.shell.inShell() && CHEM.game.updateHint) {
      html += '<div class="row"><div class="grow"><b>检查更新</b><small>服务器建议升级到 ' + U.esc(CHEM.game.updateHint) +
        "：新版可能带新的反应内容与奖励，旧版不会被强制下线</small></div><button class='btn-s o' id='set-upd'>去更新</button></div>";
    }
    return card({ t: "🛡 合规与设备", meta: minor ? "限时段游玩" : "不受限", body: html });
  }
  function curfewWindowText(w) {
    var wd = ["", "周一", "周二", "周三", "周四", "周五", "周六", "周日"];
    return ((w.days || []).map(function (i) { return wd[i] || "第" + i + "天"; }).join("、") || "无") +
      " " + (w.from || "20:00") + "-" + (w.to || "21:00");
  }
  function bindCompliance(body) {
    var m = body.querySelector("#set-minor");
    if (m) m.onclick = function () {
      var on = !(cfView && cfView.minor);
      CHEM.curfew.setMinor(on, function (j) {
        if (!j || !j.ok) return U.toast((j && j.msg) || "设置失败，稍后再试", "bad");
        cfView = j;                        // 用服务端回来的权威视图刷新，不拿本地猜测覆盖
        P.render();
        U.toast(on ? "🛡 青少年模式已开启" + (j.allowed ? "" : "：当前不在放行时段") : "青少年模式已关闭", on ? "good" : "");
        if (!j.allowed) CHEM.curfew.show(j);
      });
    };
    var lf = body.querySelector("#set-lowfx");
    if (lf) lf.onclick = function () {
      var on = !CHEM.shell.lowFx();
      CHEM.shell.setLowFx(on);            // setLowFx 里顺带把 DOM class 与画布分辨率切了
      P.render();
      U.toast(on ? "🔋 已开启省电模式：粒子和背景光斑都降档" : "已恢复完整特效", "good");
    };
    var up = body.querySelector("#set-upd");
    if (up) up.onclick = function () {
      var url = CHEM.game.updateUrl;
      if (!url) return U.toast("更新地址未配置，请到 TapTap 详情页下载", "bad");
      try { window.open(url, "_blank"); } catch (e) { U.toast("请从 TapTap 更新到最新版本", ""); }
    };
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
    /* H4：音量滑条与音乐开关联动。旧写法在这里无条件 setMusic(true)，
       等于"开关显示已关闭、拉一下滑条就自己响了"——开关不再是开关。
       现在的口径：开关说了算；但把音量从 0 拉起来的人明显是想听，就顺手把开关一并打开。 */
    body.querySelector("#vol-mus").oninput = function (ev) {
      var v = +ev.target.value;
      st.data.volMus = v;
      if (v > 0 && !st.data.music) {
        st.data.music = true;
        var mb = body.querySelector("#set-mus");
        if (mb) mb.textContent = "已开启";
      }
      CHEM.sfx.setMusic(!!st.data.music);       // 关着就不起乐；开着时幂等确保在播
    };
    body.querySelector("#vol-mus").onchange = function (ev) {
      run("settings", { volMus: +ev.target.value, music: !!st.data.music });   // 一起送，别让滑条把开关落下
    };
    /* 触感是本机偏好、不进存档，所以这里不走 run()：改了就地更新按钮，靠页面重绘回来会把人弹出设置页。 */
    var hap = body.querySelector("#set-hap");
    if (hap) hap.onclick = function () {
      var on = !CHEM.sfx.hapticOn();
      CHEM.sfx.setHaptic(on);
      hap.textContent = on ? "已开启" : "已关闭";
      if (on) hap.classList.add("g"); else hap.classList.remove("g");
      if (on) CHEM.sfx.buzz("success");   // 重新打开时立刻给一下：振不振得动当场就知道，不用等下一次事故
      U.toast(on ? "📳 触感已开启" : "触感已关闭", "good");
    };
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
          (d.skins.cur === sk[0] ? '<span class="tag lv1">使用中</span>' : o ? '<button class="btn-s" data-skin="' + sk[0] + '">切换</button>' : '<button class="btn-s o" data-goto="ad">📺 积分兑换</button>') + "</div>";
      }).join("") + '<p class="hint-p">皮肤只改配色令牌，不动任何玩法数值；赛博与复古在【设置·广告】用看广告攒的积分兑换。</p>'
    });
    body.querySelectorAll("[data-skin]").forEach(function (b) {
      b.onclick = function () { run("settings", { skin: b.dataset.skin }, function () { U.toast("皮肤已切换", "good"); }); };
    });
    body.querySelectorAll("[data-goto]").forEach(function (b) {
      b.onclick = function () { adWant = true; segState.settings = "ad"; P.go("settings"); };
    });
  }

  /* ================= 广告中心（取代钻石商店与充值） =================
     本页所有数字都来自服务端 ad.status 视图：广告位余额、冷却、积分与兑换目录由服务器算好，
     客户端只负责画和"播一段广告"。看广告→服务器回调→下次取帧结算，这条链路客户端说了不算。 */
  function drawAds(body, mAct) {
    var d = st.data;
    if (adWant) {
      adWant = false;
      G.call("ad.status", {}, function (r) {
        if (!r || r.ok === false) { U.toast((r && r.msg) || "广告中心暂不可用", "bad"); return; }
        adView = r;
        P.render();
      });
    }
    if (!adView) {
      body.innerHTML = card({
        t: "📺 广告中心", meta: "看视频换奖励",
        body: '<div class="ph">正在向服务器读取广告位与积分余额…</div>'
      });
      return;
    }
    var v = adView;
    var slots = v.slots || [], unlocks = v.unlocks || [];
    var html = statbar([
      ["广告积分", "⭐ " + v.points],
      ["今日余量", v.leftToday + " / " + v.dailyTotal],
      ["复活次数", v.revive + " 次", v.revive ? "hot" : ""],
      ["月卡", mAct ? "生效中" : "未开通"]
    ]);
    if (!v.enabled) {
      body.innerHTML = html + card({
        t: "📺 广告中心", meta: "暂未开放",
        body: '<div class="ph">运营侧暂时关闭了激励视频，金币与玩法产出照常，不影响继续实验。</div>'
      });
      return;
    }
    if (v.locked) {
      body.innerHTML = html + card({
        t: "🔒 还没到开放等级", meta: "Lv." + v.minLevel + " 开放",
        body: '<div class="ph">做出更多方程式就能解锁激励视频奖励。当前 Lv.' + d.level + "。</div>" +
          '<div class="row"><div class="grow"><b>先去实验台</b><small>做实验、涨图鉴，等级是最快的钥匙</small></div><button class="btn-s" data-act="bench">回实验台</button></div>'
      });
      bindEmptyActions(body);
      return;
    }
    var warn = "";
    if (!v.ready) {
      warn = card({
        t: "⚠️ 暂时不能发奖", meta: "运营配置", cls: "danger",
        body: '<div class="ph">服务器还没有配置激励视频的验签口令，看完广告不会发放奖励，所以本页的按钮先按住了。运营在后台补上口令后即可恢复。</div>'
      });
    } else if (v.devMode) {
      warn = card({
        t: "🧪 演示环境", meta: "仅本机",
        body: '<div class="ph">当前是本地演示：广告由一段倒计时动画代替，奖励走服务端的演示通道直接入账。线上环境这条通道会关闭，改由广告网络的服务器回调确认。</div>'
      });
    }
    html += warn;
    if (v.pending > 0) {
      html += card({
        t: "⏳ 待到账", meta: v.pending + " 段",
        body: '<div class="ph">有 ' + v.pending + " 段观看正在等广告网络回执，回执一到就会自动入账，不用重复点。</div>"
      });
    }
    var canPlay = v.ready;
    html += card({
      t: "📺 看视频领奖励", meta: "每日上限 " + v.dailyTotal + " 次",
      body: (slots.length ? slots.map(function (s) {
        var cd = s.cooldownMs > 0;
        return '<div class="row"><div class="grow"><b>' + U.esc(s.zh) + "</b><small>" + U.esc(s.desc) +
          " · 奖励 " + U.esc(s.rewardText) + " · 今日剩 " + s.left + " / " + s.daily + "</small>" +
          (cd ? '<small class="ad-cd" data-cd="' + s.cooldownMs + '">冷却 ' + Math.ceil(s.cooldownMs / 1000) + "s</small>" : "") +
          '</div><button class="btn-s ' + (s.ready && canPlay ? "o" : "") + '" data-ad="' + s.kind + '"' +
          (s.ready && canPlay ? "" : " disabled") + ">📺 观看</button></div>";
      }).join("") : '<div class="ph">当前没有可看的广告位。</div>') +
        '<p class="hint-p">每个广告位有每日次数与冷却；奖励在服务器确认你看完之后才入账，中途关掉不发放。</p>'
    });
    html += card({
      t: "⭐ 积分兑换", meta: "余额 ⭐" + v.points,
      body: (unlocks.length ? unlocks.map(function (u) {
        return '<div class="row"><div class="grow"><b>' + U.esc(u.zh) + "</b><small>" + U.esc(u.desc) +
          " · " + U.esc(u.rewardText) + (u.once ? " · 限一次" : "") + "</small></div>" +
          (u.done ? '<span class="tag lv1">已兑换</span>'
            : '<button class="btn-s ' + (u.affordable ? "g" : "") + '" data-ax="' + u.id + '"' + (canPlay ? "" : " disabled") + ">⭐" + u.cost + "</button>") + "</div>";
      }).join("") : '<div class="ph">运营侧还没有上架兑换项。</div>') +
        '<p class="hint-p">每看一段广告得 ' + (v.viewPoints || 1) + " 积分，积分不清零；攒够再换想要的东西。</p>"
    });
    body.innerHTML = html;
    body.querySelectorAll("[data-ad]").forEach(function (b) {
      b.onclick = function () { watchFromPanel(b.dataset.ad, b); };
    });
    body.querySelectorAll("[data-ax]").forEach(function (b) {
      b.onclick = function () {
        run("ad.exchange", { id: b.dataset.ax }, function (r) {
          if (r && r.view) { adView = r.view; P.render(); }
          U.toast("已兑换：" + ((r && r.text) || r.zh || "奖励已到账"), "gold");
        });
      };
    });
    tickAdCooldown();
  }
  /** 从广告中心点"观看"：按钮先置灰防重复点击，回来后强制刷新一次余额。 */
  function watchFromPanel(kind, btn) {
    if (btn) btn.disabled = true;
    U.watchAd(kind, function () {
      adWant = true;
      P.render();
    });
  }
  /** 冷却倒计时只在本地走秒做展示；真要点的时候服务器还会再判一次。 */
  function tickAdCooldown() {
    if (adTimer) return;
    adTimer = setInterval(function () {
      if (P.tab !== "settings" || segState.settings !== "ad") { clearInterval(adTimer); adTimer = null; return; }
      var nodes = (U.$("page-body") || document).querySelectorAll("[data-cd]");
      if (!nodes.length) { clearInterval(adTimer); adTimer = null; return; }   // 没有冷却要显示就别留着这个计时器
      var anyLeft = false;
      nodes.forEach(function (n) {
        var ms = Math.max(0, (+n.dataset.cd || 0) - 1000);
        n.dataset.cd = ms;
        n.textContent = ms > 0 ? "冷却 " + Math.ceil(ms / 1000) + "s" : "冷却结束";
        if (ms > 0) anyLeft = true;
      });
      if (!anyLeft) { clearInterval(adTimer); adTimer = null; adWant = true; P.render(); }
    }, 1000);
  }
  /** 月卡剩余天数（向上取整），未开通为 0。 */
  function monthlyLeft(d) {
    var ms = (d.monthly && d.monthly.until) || 0;
    return ms > Date.now() ? Math.ceil((ms - Date.now()) / 86400000) : 0;
  }
  /** 广告视图失效：观看/兑换/服务端到账事件之后都要重新拉一次，避免把旧余额当真的。 */
  P.adInvalidate = function () {
    adWant = true;
    if (G.ready && P.tab === "settings" && segState.settings === "ad") P.render();
  };
  /** 其他地方（如 U.watchAd 内部）已经拿到新鲜视图时，直接喂给本页，省一次往返。 */
  P.adSetView = function (v) {
    if (!v || v.ok === false) return;
    adView = v;
    adWant = false;
    if (G.ready && P.tab === "settings" && segState.settings === "ad") P.render();
  };
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
          (CHEM.shell.hasTapTap()
            ? '<div class="row"><div class="grow"><b>用 TapTap 账号一键绑定</b><small>由 TapTap 出身份，服务端验签后建档并把当前游客进度并进去；换设备时凭 TapTap 找回，比口令稳</small></div>' +
              '<button class="btn-s g" id="acc-taptap">绑定</button></div>'
            : "") +
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
  /* ---------- 举报 / 反馈（G2 的客户端那一半） ----------
     规则一条都不在这里判：类型闭合、对象是否真存在、同一对象只留一条在途、每人每日上限，
     全在服务器的 ReportService。这一层只负责"给一个能指的对象"——所以只有两类进得来：
     · listing：【市场·挂单】里那一行，ref 用「物品编号|品质」，运营能对着订单查；
     · other：页面上任何有编号的实体（物质条目、反应、题目），必须写一句发生了什么。
     举报"人"的两类（nickname／behavior）故意不做：当前版本玩家看不到任何别人的昵称或行为
     ——好友与榜单都是 NPC 表里的行，硬塞一个【举报】按钮只会收到"查不到这位玩家"。 */
  function openReportModal(kind, ref) {
    var k = kind === "listing" ? "listing" : "other";
    var refPh = function () {
      return k === "listing" ? "挂单编号，如 H2O|2" : "涉及内容的编号，如 H2O / R045";
    };
    U.modal("<h3>🚩 举报 / 反馈</h3>" +
      "<div class='ph'>这一条只有运营在后台看得到。写清对象与问题即可，不需要写密码，也不要放联系方式。</div>" +
      chips([["listing", "挂单有问题"], ["other", "内容有问题"]], k, "rk") +
      '<input class="inp stack" id="rp-ref" maxlength="64" placeholder="' + refPh() + '" value="' + U.esc(ref || "") + '">' +
      '<textarea class="inp stack" id="rp-why" maxlength="400" rows="3" placeholder="发生了什么（最多 400 字）"></textarea>' +
      "<button class='ok' id='rp-go'>提交</button><button class='ghost' data-close>取消</button>", {
        after: function (m) {
          var kindBtns = m.querySelectorAll("[data-rk]"), refIn = m.querySelector("#rp-ref");
          kindBtns.forEach(function (b) {
            b.onclick = function () {
              // 就地换选中态：重画整张弹窗会把玩家已经敲进去的编号和说明一起清掉
              k = b.dataset.rk;
              kindBtns.forEach(function (o) { o.classList.toggle("on", o === b); });
              refIn.placeholder = refPh();
            };
          });
          var go = m.querySelector("#rp-go");
          go.onclick = function () {
            var rf = refIn.value.trim(), why = m.querySelector("#rp-why").value.trim();
            if (!rf) return U.toast(k === "listing" ? "请写明是哪一张挂单" : "请写明涉及内容的编号", "bad");
            if (!why) return U.toast("请写一句发生了什么", "bad");
            go.disabled = true; go.textContent = "提交中…";   // 服务端去重按 (人, 类型, 编号)，但连点两下依然该拦在本地
            CHEM.cloud.report(k, rf, why, function (j) {
              go.disabled = false; go.textContent = "提交";
              if (!j.ok) return U.toast(j.msg || "提交失败", "bad");
              U.closeModal();
              var left = Math.max(0, (+j.dailyMax || 0) - (+j.openToday || 0));
              U.toast("已提交，今天还能举报 " + left + " 条", "good");
            });
          };
        }
      });
  }
  function drawAbout(body, d) {
    var c = claimCounts();
    body.innerHTML = card({
      t: "ℹ️ 关于", meta: "v" + appVer() + " · 在线游戏",
      body: '<div class="kv"><span>内容规模</span><b>118 元素 · ' + (CHEM.REACTIONS || []).length + " 方程式 · " + CHEM.INSTRUMENTS.length + " 仪器 · " + CHEM.ROOMS.length + " 实验室</b></div>" +
        '<div class="kv"><span>结算真源</span><b>服务器（客户端只发意图）</b></div>' +
        '<div class="kv"><span>存档</span><b>服务器云存档，revision 乐观并发</b></div>' +
        '<div class="kv"><span>我的进度</span><b>Lv.' + d.level + " · 发现 " + Object.keys(d.discovered).length + " 种 · 🪙" + d.coins + " · 💎" + d.diamonds + "</b></div>" +
        (CHEM.shell.inShell() ? '<div class="kv"><span>安装包</span><b>' + U.esc(CHEM.shell.platform()) + " · build " + (CHEM.game.build || "?") + "</b></div>" : "") +
        '<div class="row"><div class="grow"><b>用户协议 / 隐私政策</b><small>列明我们收什么（账号标识、服务器存档、少量行为埋点）、不收什么（真实姓名、位置、通讯录），以及怎么要求停止埋点</small></div>' +
        '<button class="btn-s" data-legal="terms">用户协议</button><button class="btn-s" data-legal="privacy">隐私政策</button></div>' +
        '<div class="ph left">以真实化学为底座的在线实验沙盒。数据基于公开化学常识整理，实验请勿在家中模仿。</div>'
    });
    body.innerHTML += card({
      t: "🚩 举报 / 反馈", meta: "限流",
      body: '<div class="row"><div class="grow"><b>发现问题内容</b><small>挂单、物质条目、反应式或题目有问题，留下编号和一句话；服务器记成一工单，同一问题只留一条在处理，每天有量上限</small></div><button class="btn-s" id="set-report">举报</button></div>' +
        '<div class="ph left">这里收的是游戏内容的问题。举报其他玩家暂时开不了：这一版里没有玩家之间互相看得见的内容（好友与榜单都是系统角色），等有了再说。</div>'
    });
    body.innerHTML += card({
      t: "🧹 存档", meta: "谨慎", cls: "danger",
      body: '<div class="row"><div class="grow"><b>重置存档</b><small>清空服务器上的全部进度（金币、钻石、图鉴、成就、挂单），无法恢复。当前还有 ' + c.total + " 项奖励未领</small></div>" +
        '<button class="btn-s danger" id="set-reset">重置</button></div>'
    });
    /* 绑定必须排在最后一次 innerHTML += 之后：给容器再加一次 HTML 会把整棵子树重建，
       先前挂在按钮上的 onclick 会跟着旧节点一起消失——协议按钮就是这么变成哑的。 */
    body.querySelectorAll("[data-legal]").forEach(function (b) {
      b.onclick = function () { CHEM.cloud.showLegal(b.dataset.legal); };
    });
    body.querySelector("#set-report").onclick = function () { openReportModal("other", ""); };
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
  /* 钻石商店与模拟充值已随付费面下线：原来得花钱的东西全部改到【设置·广告】用观看积分兑换，
     服务端也对 shop.buy / shop.recharge / ad.bonus 三条意图关了回绝，客户端不再有任何入口。 */

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
      var tt = body.querySelector("#acc-taptap");
      if (tt) {
        tt.onclick = function () {
          tt.disabled = true; tt.textContent = "唤起中…";
          var back = function () { tt.disabled = false; tt.textContent = "绑定"; };
          CHEM.shell.taptapTicket(function (e, ticket) {
            if (e) { back(); return U.toast(e.message || e.msg || "TapTap 授权未完成", "bad"); }
            CHEM.cloud.taptap(ticket, function (j) {
              if (!j.ok) { back(); return U.toast(j.msg || "绑定失败", "bad"); }
              // 换了身份（新 uid）就整页重进：广告视图、榜单缓存、引擎现场都是按旧账号攒的，逐个作废不如重来
              U.toast("🎮 已绑定 TapTap 账号 " + (j.user || "") + "，游客进度已并入", "gold");
              G.restart();
            });
          });
        };
      }
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
    var html = head + "<h3>📝 趣味化学题（" + U.esc(q.grade) + "·答对 🪙" + CHEM.quizReward(q.grade) + "）</h3><div class='quiz-q'>" + U.esc(q.q) + "</div>" +
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
