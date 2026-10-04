/* UI 骨架 v3（在线版）：顶栏/房间工作台/拖拽投放/安全弹窗/保险/助手精灵/模拟广告/反应回执/教程。
   所有交互 = 一个服务端意图（CHEM.game.call）；界面由 game.js 在整帧落地后统一重绘，这里只渲染回执文案。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var U = {};
  CHEM.ui = U;
  var st, E, FX, G;
  /** init 之前 game.js 会先落地一帧状态，此时不得触碰任何绑定过闭包变量的渲染函数。 */
  U.inited = false;

  U.$ = function (id) { return document.getElementById(id); };
  U.esc = function (s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); };

  U.init = function () {
    st = CHEM.state; E = CHEM.engine; FX = CHEM.fx; G = CHEM.game;
    FX.attach(U.$("fx-canvas"));
    window.addEventListener("resize", function () { FX.resize(); });
    bindTop(); bindBench(); bindNav();
    U.applySkin();
    U.updateTop(); renderStage();
    if (st.data.music) CHEM.sfx.setMusic(true);
    U.inited = true;
  };

  /* ---------- 皮肤 ---------- */
  U.applySkin = function () {
    document.body.setAttribute("data-skin", st.data.skins.cur || "default");
  };

  /* ---------- 顶栏 ---------- */
  U.updateTop = function () {
    var d = st.data;
    U.$("ui-level").textContent = d.level;
    U.$("ui-coins").textContent = d.coins;
    U.$("ui-diamonds").textContent = d.diamonds;
    U.$("ui-exp").style.width = Math.min(100, Math.round(d.exp / CHEM.LEVEL_EXP(d.level) * 100)) + "%";
    var b = U.$("btn-mode");
    b.textContent = d.realMode ? "真实模式" : "简单模式";
    b.onclick = function () {
      var to = !d.realMode;
      G.call("settings", { realMode: to }, function (r) {
        if (!r.ok) return U.toast(r.msg || "切换失败", "bad");
        U.toast(to ? "真实模式：必须满足温度/催化剂/仪器等全部条件" : "简单模式：物质正确即可反应", "gold");
      });
    };
  };
  function bindTop() {
    U.$("btn-quiz").onclick = function () { CHEM.panels.openQuiz(); };
    U.$("btn-hint").onclick = askHint;
  }

  /* ---------- 成就达成提示（纯展示：进度真源在服务端） ---------- */
  var achFired = {};
  U.checkAchToasts = function () {
    (CHEM.ACHIEVEMENTS || []).forEach(function (a) {
      if (!st.data.achClaimed[a.id] && !achFired[a.id] && st.achDone(a)) {
        achFired[a.id] = true;
        U.toast("🏆 达成成就【" + a.zh + "】，可领取 " + a.reward + " 金币", "good");
      }
    });
  };

  /* ---------- 助手精灵提示 ---------- */
  function askHint() {
    var d = st.data;
    var src = d.hints > 0 ? "助手精灵次数 ×" + d.hints : "🪙300";
    U.modal("<h3>🧚 助手精灵</h3><div class='ph'>精灵会从你尚未发现的方程式中随机揭示一条（当前消耗：" + src + "）。也可在设置页用钻石购买次数。</div>" +
      '<button class="ok" id="hint-go">请精灵提示</button><button class="ghost" data-close>先不用</button>', {
      after: function (card) {
        card.querySelector("#hint-go").onclick = function () {
          U.closeModal();
          G.call("hint", { spendCoin: true }, function (r) {
            if (!r.ok) return U.toast(r.msg || "没有可提示的方程式（或金币不足）", "bad");
            var rx = findReaction(r.rid) || {};
            U.modal("<h3>🧚 精灵的低语</h3><div class='eq'>" + U.esc(r.eq) + "</div>" +
              '<div class="ph">条件：' + U.esc(tempZh(rx.conditions && rx.conditions.temp) + condExtra(rx)) + "</div>" +
              '<div class="ph">💡 ' + U.esc(rx.tip || "把它做出来，图鉴会记下这条方程式。") + "</div>" +
              '<button class="ok" data-close>记住了</button>');
          });
        };
      }
    });
  }
  function tempZh(t) {
    return { room: "室温", heat: "加热", ignite: "点燃", highTemp: "高温" }[t || "room"] || "室温";
  }
  function condExtra(r) {
    var c = r.conditions || {}, out = [];
    if (c.catalyst) { var s = st.sub(c.catalyst); out.push("催化剂：" + (s ? s.zh : c.catalyst)); }
    if (c.electrolysis) out.push("电解");
    if (r.instrument && r.instrument.length) out.push("仪器：" + r.instrument.map(function (i) {
      var x = CHEM.INSTRUMENTS.find(function (q) { return q.id === i; }); return x ? x.zh : i;
    }).join("/"));
    return out.length ? "｜" + out.join("｜") : "";
  }
  function findReaction(rid) { return (CHEM.REACTIONS || []).find(function (x) { return x.id === rid; }); }
  function findProcess(pid) { return (CHEM.PROCESSES || []).find(function (x) { return x.id === pid; }); }
  /** 反应回执 → 展示用的内容对象（工艺条目按同名字段兼容） */
  function ofReceipt(res) {
    if (!res) return null;
    if (res.proc) return findProcess(res.proc);
    return res.rid ? findReaction(res.rid) : null;
  }

  /* ---------- 模拟激励广告 ---------- */
  U.simAd = function (title, cb) {
    if (st.data.noad) { cb && cb(); return; }
    var left = 3;
    U.modal("<h3>📺 广告</h3><div class='ph'>" + U.esc(title || "观看广告即可获得奖励") + "</div>" +
      "<div class='ad-box'>广告播放中… <b id='ad-n'>" + left + "</b>s<div class='ad-bar'><i id='ad-fill' style='width:0%'></i></div></div>" +
      '<button class="ghost" id="ad-skip" disabled>跳过（看完后可领取）</button>' +
      '<button class="ok hidden" id="ad-get">领取奖励</button>', {
      locked: true,
      after: function (card) {
        var t = setInterval(function () {
          left--;
          var n = card.querySelector("#ad-n"), f = card.querySelector("#ad-fill");
          if (!n) { clearInterval(t); return; }
          n.textContent = Math.max(0, left);
          f.style.width = ((3 - left) / 3 * 100) + "%";
          if (left <= 0) {
            clearInterval(t);
            card.querySelector("#ad-skip").classList.add("hidden");
            card.querySelector("#ad-get").classList.remove("hidden");
            card.querySelector("#ad-get").onclick = function () { U.closeModal(); cb && cb(); };
            card.querySelector("#ad-skip").onclick = function () { U.closeModal(); };
          }
        }, 1000);
        card.querySelector("#ad-skip").onclick = function () {};
      }
    });
  };
  /** 服务端记账的广告奖励：kind 0=事故慰问金 1=任务双倍 */
  U.adBonus = function (kind, cb) {
    G.call("ad.bonus", { kind: kind }, function (r) {
      if (!r.ok) return U.toast(r.msg || "奖励不可领取", "bad");
      U.toast(r.coupon ? "🎟️ 今日双倍券已到账，领取任务/成就奖励时可用" : "已领取 🪙" + r.coins, "gold");
      cb && cb();
    });
  };

  /* ---------- toast ---------- */
  U.toast = function (msg, cls) {
    var box = document.createElement("div");
    box.className = "toast " + (cls || "");
    box.textContent = msg;
    U.$("toasts").appendChild(box);
    setTimeout(function () { box.remove(); }, 2800);
  };

  /* ---------- 模态 ---------- */
  U.modal = function (html, opts) {
    opts = opts || {};
    var root = U.$("modal-root"), card = U.$("modal-card");
    card.innerHTML = html;
    root.classList.remove("hidden");
    root.onclick = function (e) {
      if (e.target === root && !opts.locked) root.classList.add("hidden");
    };
    card.querySelectorAll("[data-close]").forEach(function (b) {
      b.onclick = function () { root.classList.add("hidden"); };
    });
    if (opts.after) opts.after(card);
  };
  U.closeModal = function () { U.$("modal-root").classList.add("hidden"); };

  /* ---------- 房间 tabs + 挑战/沙盒横幅 ---------- */
  function renderRoomTabs() {
    var wrap = U.$("room-tabs");
    var d = st.data;
    if (E.tempBench) {
      wrap.innerHTML = "";
      wrap.classList.add("hidden");
      return;
    }
    wrap.classList.remove("hidden");
    var html = "";
    d.rooms.forEach(function (rid, i) {
      var r = CHEM.ROOMS.find(function (x) { return x.id === rid; }) || { zh: rid };
      html += '<button class="room-tab' + (i === E.bi ? " on" : "") + '" data-i="' + i + '">' + U.esc(r.zh) + "</button>";
    });
    wrap.innerHTML = html;
    wrap.querySelectorAll(".room-tab").forEach(function (b) {
      b.onclick = function () {
        G.call("bench.switch", { index: parseInt(b.dataset.i, 10) }, function (r) {
          if (!r.ok) U.toast(r.msg || "切换失败", "bad");
        });
      };
    });
  }
  function renderBanner() {
    var el = U.$("banner"), d = st.data;
    if (E.tempBench && d.chal && !E.sandboxActive) {
      var ch = d.chal;
      var t = st.sub(ch.target);
      el.classList.remove("hidden");
      el.innerHTML = "🎯 挑战：合成 <b>" + U.esc(t ? t.zh : ch.target) + "</b>（" + U.esc(ch.reactId) + "）｜步骤 " + ch.steps + "/" + ch.max +
        "｜奖励 🪙" + ch.reward +
        (ch.failed ? ' <button id="chal-rev">📺 复活 +2步</button>' : "") +
        ' <button id="chal-giveup">放弃</button>';
      if (ch.failed) el.querySelector("#chal-rev").onclick = function () { U.reviveChallenge(); };
      el.querySelector("#chal-giveup").onclick = function () {
        G.call("challenge.quit", {}, function () { U.toast("已放弃挑战", "bad"); });
      };
      return;
    }
    if (E.tempBench && E.sandboxActive) {
      el.classList.remove("hidden");
      el.innerHTML = '🌌 创意沙盒：物质无限、不消耗库存、没有奖励（用于自由探索与彩蛋） <button id="sb-exit">退出沙盒</button>';
      el.querySelector("#sb-exit").onclick = function () { G.call("sandbox.exit", {}); };
      return;
    }
    el.classList.add("hidden");
    el.innerHTML = "";
  }
  U.refreshBenchChrome = function () { renderRoomTabs(); renderBanner(); };

  /* ---------- 工作台 ---------- */
  function bindBench() {
    U.$("vessel-card").onclick = pickVessel;
    document.querySelectorAll(".temp-btn[data-t]").forEach(function (b) {
      b.onclick = function () {
        if (b.disabled) return;
        G.call("bench.temp", { temp: b.dataset.t }, function (r) {
          if (!r.ok) U.toast(r.msg || "该温度需要对应仪器", "bad");
        });
      };
    });
    U.$("btn-elec").onclick = function () {
      if (!E.canElectrolysis()) return U.toast("需要先购买【电解槽】（建设页）", "bad");
      G.call("bench.electrolysis", { on: !E.cur().electrolysis });
    };
    U.$("sel-mult").onchange = function () {
      var v = parseInt(this.value, 10) || 1;
      var cap = E.batchCap();
      if (v > cap) { v = cap; this.value = cap; }
      E.multiplier = v;
      renderStage();
    };
    U.$("btn-react").onclick = doReact;
    U.$("btn-clear").onclick = function () { G.call("bench.clear", {}); };
    U.$("chk-ins").onchange = function () {
      var on = this.checked;
      G.call("settings", { insured: on }, function (r) {
        if (!r.ok) return U.toast(r.msg || "设置失败", "bad");
        if (on) U.toast("已购买实验保险：下一次实验若出事故，理赔 50% 原料价值", "gold");
      });
    };
  }

  function syncTempBtns() {
    var b = E.cur();
    document.querySelectorAll(".temp-btn[data-t]").forEach(function (x) {
      var t = x.dataset.t, ok = E.canTemp(t);
      x.disabled = !ok;
      x.classList.toggle("on", b.temp === t);
    });
    var eb = U.$("btn-elec");
    eb.classList.toggle("on", !!b.electrolysis);
    eb.style.opacity = E.canElectrolysis() ? 1 : 0.4;
    var sm = U.$("sel-mult");
    if (sm) {
      var cap = E.batchCap();
      Array.prototype.forEach.call(sm.options, function (o) { o.disabled = (+o.value) > cap; });
      if ((E.multiplier || 1) > cap) { E.multiplier = cap; sm.value = cap; }
    }
  }

  /* 容器图形不在这里定义：真源是 js/icons.js（线稿 + 液体层），
     以前这里挂着一张 emoji 表，烧杯画成实验服、试管画成培养皿，量筒/容量瓶/研钵干脆没有。 */

  /* ---------- 物质架（实验台自带的投放源） ----------
     抽屉时代背包面板只有 68% 高、台面常驻可见，所以"从面板拖到台面"是主交互。
     现在每个标签都是整页，别的页面看不到台面，于是把最常用的投放源搬到实验台里：
     点一下投一份，按住拖到台面同样能投（走同一个 bench.place 意图，服务端裁决）。 */
  var QS_MAX = 16;
  function renderQuickShelf() {
    var row = U.$("qs-row"), tip = U.$("qs-tip");
    if (!row) return;
    if (U.isDragging()) return;   /* 拖拽途中被整帧刷新换掉节点会丢指针，跳过这一次 */
    var ids = [], left = 0, moreLabel = "";
    if (E.tempBench && E.sandboxActive) {
      ids = Object.keys(st.allSubs()).filter(function (id) { return id !== "SLAG"; })
        .sort(function (a, b) { return ((st.sub(a).z || 999) - (st.sub(b).z || 999)) || (st.sub(a).price - st.sub(b).price); });
      if (tip) tip.textContent = "沙盒 · 全部物质无限，点一下就投";
    } else if (E.tempBench && st.data.chal) {
      ids = Object.keys(st.data.chal.given).concat(Object.keys(st.data.chal.decoys));
      if (tip) tip.textContent = "挑战材料箱 · 数字是剩余份数";
    } else {
      var seen = {};
      ids = Object.keys(st.data.bag).map(function (k) { return k.split("|")[0]; })
        .filter(function (id) { if (seen[id]) return false; seen[id] = 1; return st.countAll(id) > 0; })
        .sort(function (a, b) { return st.countAll(b) - st.countAll(a) || (st.sub(b) || {}).price - (st.sub(a) || {}).price; });
      if (tip) tip.textContent = "点一下投一份 · 也可拖到台面";
    }
    left = ids.length - QS_MAX;
    shelfAction(ids.length, left);
    ids = ids.slice(0, QS_MAX);
    if (!ids.length) {
      row.innerHTML = '<div class="qs-none">背包是空的 —— 去【市场】采购，或先在台面上做实验合成。</div>';
      return;
    }
    var html = ids.map(function (id) {
      var s = st.sub(id) || { zh: id, color: "#90a4ae", formula: id };
      var n = E.tempBench && !E.sandboxActive ? Math.max(0, E.givenLeft(id)) : (E.sandboxActive ? -1 : st.countAll(id));
      var fg = CHEM.panels.pickTextColor(s.color);
      return '<div class="item-card" data-qs="' + U.esc(id) + '">' +
        (n < 0 ? '<i class="cnt">∞</i>' : n ? '<i class="cnt">' + n + "</i>" : "") +
        '<div class="sq" style="background:' + U.esc(s.color) + ";color:" + fg + '">' + U.esc(CHEM.panels.shortFm(s)) + "</div>" +
        '<div class="zh">' + U.esc(s.zh) + "</div></div>";
    }).join("");
    if (left > 0) {
      html += '<div class="item-card qs-more" data-qs-more="1"><div class="sq">+' + left + '</div><div class="zh">更多</div></div>';
    }
    row.innerHTML = html;
    row.querySelectorAll("[data-qs]").forEach(function (el) {
      U.bindItemDrag(el, el.dataset.qs);
    });
    var more = row.querySelector("[data-qs-more]");
    if (more) more.onclick = function () { CHEM.panels.go("bag"); };
  }

  /** 物质架右上角那个动作：空架子就带去采购，架子上摆不下就带去【物质】页看全部。
      临时工作台（挑战/沙盒）本来就是全量材料柜，不给这个入口。 */
  function shelfAction(kinds, overflow) {
    var act = U.$("qs-restock");
    if (!act) return;
    if (E.tempBench || (kinds && overflow <= 0)) { act.classList.add("hidden"); return; }
    act.classList.remove("hidden");
    if (!kinds) {
      act.textContent = "🛒 去采购";
      act.onclick = function () { CHEM.panels.go("market"); };
      return;
    }
    act.textContent = "全部 " + kinds + " 种";
    act.onclick = function () { CHEM.panels.go("bag"); };
  }

  function mixColor(placed) {
    var keys = Object.keys(placed);
    if (!keys.length) return "transparent";
    var r = 0, g = 0, b2 = 0, n = 0;
    keys.forEach(function (id) {
      var s = st.sub(id) || { color: "#90a4ae" };
      var c = s.color || "#90a4ae";
      var m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(c);
      if (!m) return;
      r += parseInt(m[1], 16); g += parseInt(m[2], 16); b2 += parseInt(m[3], 16); n++;
    });
    if (!n) return "#78909c";
    return "rgba(" + Math.round(r / n) + "," + Math.round(g / n) + "," + Math.round(b2 / n) + ",.55)";
  }

  function renderStage() {
    var b = E.cur();
    var inst = CHEM.INSTRUMENTS.find(function (i) { return i.id === b.vessel; });
    var tier = st.vesselTier(b.vessel);
    var iconBox = U.$("vessel-icon");
    var shapeId = CHEM.icon.has(inst) ? inst.id : "other";
    var liquid = (inst && inst.kind === "vessel" && Object.keys(b.placed).length) ? mixColor(b.placed) : "";
    /* 只有换容器才重画形状；形状不变时改 class/变量，液面才能跟着混合色渐变 */
    if (iconBox.dataset.shape !== shapeId) {
      iconBox.innerHTML = CHEM.icon.draw(shapeId, { label: inst ? inst.zh : "" });
      iconBox.dataset.shape = shapeId;
    }
    var svg = iconBox.firstElementChild;
    if (svg) {
      if (liquid) svg.style.setProperty("--ic-liq", liquid);
      svg.classList.toggle("wet", !!liquid);
    }
    U.$("vessel-name").textContent = (inst ? inst.zh : b.vessel) + "（点击切换）";
    U.$("vessel-tier").textContent = (CHEM.TIER_NAMES[tier] || "普通") + " · 产出" + (CHEM.QUALITY[Math.max(0, tier)] || CHEM.QUALITY[0]).zh;

    var ul = U.$("placed-list");
    ul.innerHTML = "";
    Object.keys(b.placed).forEach(function (id) {
      var s = st.sub(id) || { zh: id, color: "#999" };
      var li = document.createElement("li");
      li.innerHTML = '<i class="dot" style="background:' + U.esc(s.color) + '"></i>' +
        U.esc(s.zh) + " ×" + b.placed[id] + ' <b title="取回">✕</b>';
      li.onclick = function () { G.call("bench.takeBack", { id: id }); };
      ul.appendChild(li);
    });
    U.$("drop-hint").style.opacity = Object.keys(b.placed).length ? 0 : 1;
    renderQuickShelf();

    syncTempBtns();
    renderRoomTabs();
    renderBanner();

    /* 保险勾选：仅在存在危险组合时展示 */
    var insWrap = U.$("ins-wrap");
    var danger = E.dangerInfo();
    insWrap.classList.toggle("hidden", !danger || !!E.tempBench);
    U.$("chk-ins").checked = !!st.data.insured;

    /* 成本预览：纯本地读操作（真实结算与方程式以服务端回执为准） */
    var pv = U.$("cost-preview");
    var rs = E.matches();
    if (!Object.keys(b.placed).length) { pv.innerHTML = ""; }
    else if (danger) {
      pv.innerHTML = '<span class="warn">⚠️ 危险混放：' + U.esc(danger.msg) + "</span>";
    } else if (rs.length) {
      var c = E.costPreview(rs[0]);
      pv.innerHTML = "匹配反应：" + U.esc(rs[0].eq) + " ｜原料成本 " + c.cost + " → 产物价值 <b>" + c.worth + "</b>" +
        tagLine(rs[0]);
    } else {
      var realNoCond = (CHEM.REACTIONS || []).some(function (r) {
        return Object.keys(r.reactants).every(function (k) { return (b.placed[k] || 0) >= r.reactants[k]; })
          && E.extraPlacedAllowed(r, b.placed);
      });
      pv.innerHTML = realNoCond
        ? '<span class="warn">物质组合已知，但当前条件不满足（真实模式下需正确温度/催化剂/电解/仪器）</span>'
        : '<span class="warn">该组合没有已知反应，强行反应可能生成废渣甚至事故</span>';
    }
  }
  U.renderStage = renderStage;

  function tagLine(r) {
    var t = "";
    if (r.ionic) t += '<span class="tag ionic">离子</span>';
    if (r.rev) t += '<span class="tag rev">可逆</span>';
    if (r.thermal) t += '<span class="tag thermal">热化学</span>';
    return t ? " " + t : "";
  }

  function pickVessel() {
    var cur = E.cur().vessel;
    var html = "<h3>选择反应容器（本工作台）</h3>";
    CHEM.INSTRUMENTS.filter(function (i) { return i.kind === "vessel"; }).forEach(function (i) {
      var owned = st.ownVessel(i.id);
      var tier = st.vesselTier(i.id);
      var canBuy = st.data.level >= i.unlockLv;
      html += '<div class="row"><span class="ic-slot">' + CHEM.icon.of(i) + "</span>" +
        '<div class="grow"><b>' + U.esc(i.zh) + "</b> <small>" + U.esc(i.desc) + "</small>" +
        (owned ? "<small>品质档：" + CHEM.TIER_NAMES[tier] + (i.yieldBonus ? " · 产率+" + Math.round(i.yieldBonus * 100) + "%" : "") + (i.noHeat ? " · 不可加热！" : "") + "</small>"
          : (!canBuy ? "<small>等级 Lv." + i.unlockLv + " 解锁</small>" : "")) +
        "</div>" +
        (owned
          ? '<button class="btn-s ' + (cur === i.id ? "g" : "") + '" data-v="' + i.id + '">' + (cur === i.id ? "使用中" : "使用") + "</button>"
          : (canBuy ? '<button class="btn-s o" data-buy="' + i.id + '">🪙' + i.baseCost + " 购买</button>" : "")) +
        "</div>";
    });
    U.modal(html, {
      after: function (card) {
        card.querySelectorAll("[data-v]").forEach(function (b) {
          b.onclick = function () {
            G.call("bench.vessel", { id: b.dataset.v }, function (r) {
              if (!r.ok) return U.toast(r.msg || "无法使用该容器", "bad");
              U.closeModal();
            });
          };
        });
        card.querySelectorAll("[data-buy]").forEach(function (b) {
          b.onclick = function () {
            var id = b.dataset.buy, i = CHEM.INSTRUMENTS.find(function (x) { return x.id === id; });
            G.call("upgrade.vessel", { id: id }, function (r) {
              if (!r.ok) return U.toast(r.msg || "购买失败", "bad");
              G.call("bench.vessel", { id: id }, function () { U.closeModal(); U.toast("已购入 " + i.zh, "good"); });
            });
          };
        });
      }
    });
  }

  /* ---------- 反应执行 ---------- */
  function doReact() {
    if (st.data.tutorial === 2) G.call("tutorial.step", { to: 3 });
    var danger = E.dangerInfo();
    if (danger && !E.tempBench) return safetyModal(danger);
    runReact(danger && E.tempBench ? danger : null, null);
  }

  function safetyModal(danger) {
    U.modal("<h3>⚠️ 安全教育提示</h3><div class='ph'>" + U.esc(danger.msg) + "</div>" +
      '<div class="ph">现实中这类操作可能造成严重伤害。游戏里继续实验将有很高概率发生事故（损失原料与金币，安全设施/防护罩可降低损失）。</div>' +
      '<label class="row"><input type="checkbox" id="ins2"> 购买本次实验保险（理赔 50% 原料价值）</label>' +
      '<button class="ok" id="safety-go">我已了解风险，继续</button><button class="ghost" data-close>冷静一下，先分离它们</button>', {
      after: function (card) {
        card.querySelector("#ins2").checked = !!st.data.insured;
        card.querySelector("#safety-go").onclick = function () {
          var ins = card.querySelector("#ins2").checked;
          U.closeModal();
          runReact(danger, ins);
        };
      }
    });
  }

  function runReact(wasDanger, insuredOpt) {
    var params = { multiplier: E.multiplier || 1 };
    if (insuredOpt !== null && insuredOpt !== undefined) params.insured = !!insuredOpt;
    G.call("react", params, function (res, frame) {
      if (!frame) return U.toast(res.msg || "提交失败，请稍后重试", "bad");
      afterReact(res, wasDanger);
    });
  }

  function afterReact(res, wasDanger) {
    if (res.kind === "none") return U.toast(res.msg, "bad");
    if (res.kind === "fail-chal") {
      CHEM.sfx.play("error");
      U.showResult({
        title: "🎯 挑战失败",
        body: '<div class="gold-line">💥 干扰物质混入了反应体系，本次挑战判负（不可复活）。</div>' +
          "<div class='ph'>下次投放前留意材料箱说明：有 2 种物质是来捣乱的。</div>"
      });
      return;
    }
    if (res.kind === "fail-cond") {
      CHEM.sfx.play("error");
      U.toast(res.msg, "bad");
      var sug = findReaction(res.suggest);
      if (sug) U.toast("提示方向：" + sug.eq, "gold");
      CHEM.fx.burst(["smoke"], "#b0bec5");
      return;
    }
    if (res.kind === "boom") {
      CHEM.sfx.play("boom");
      if (CHEM.track) CHEM.track("react_boom");
      CHEM.fx.burst(["explosion"]); CHEM.fx.shake();
      U.toast(res.msg, "bad");
      U.showResult({
        title: "💥 实验事故",
        body: '<div class="ph">' + U.esc(res.msg) + "</div>" +
          "<div class='ph'>复盘：危险物质要分开存放！升级【安全设施】、常备【防护罩】与【实验保险】可大幅减少损失。</div>" +
          (st.data.chal ? "" : "<div class='ph'>想快速回本？看一段广告领取事故慰问金 🪙500。</div>") +
          (st.data.chal ? "" : '<button class="btn-s o" id="ad-boom">📺 观看广告领取</button>')
      });
      var ab = U.$("modal-card").querySelector("#ad-boom");
      if (ab) ab.onclick = function () { U.simAd("事故慰问金", function () { U.adBonus(0); }); };
      return;
    }
    var r = ofReceipt(res) || { eq: res.eq || "反应完成", phenomenon: "", type: "", fx: [] };
    CHEM.sfx.play(res.kind === "partial" ? "pour" : "success");
    if (CHEM.track) CHEM.track("react_success", { eq: r.id || res.rid, kind: res.kind });
    if (res.eqBonus && CHEM.track) CHEM.track("discover", { eq: r.id || res.rid });
    if (res.ups && CHEM.track) CHEM.track("level_up", { level: st.data.level });
    if (res.eqBonus) CHEM.sfx.play("coin");
    if (res.ups) setTimeout(function () { CHEM.sfx.play("levelup"); }, 250);
    CHEM.fx.burst((r.fx || []).concat(res.kind === "partial" ? ["explosion"] : []), precipColor(r));
    if (r.fx && r.fx.indexOf("flame") >= 0) setTimeout(function () { CHEM.fx.burst(["glow"]); }, 400);
    var prods = Object.keys(res.produced || {}).map(function (id) {
      var s = st.sub(id);
      return "<span>" + U.esc(s ? s.zh : id) + " ×" + res.produced[id] + "</span>";
    }).join("");
    var body = '<div class="eq">' + U.esc(r.eq) + "</div>" +
      '<div class="ph">现象：' + U.esc(r.phenomenon) + "（" + U.esc(r.type) + "）" + tagLine(r) + "</div>" +
      '<div class="prods">' + prods + "</div>" +
      '<div class="kv"><span>经验</span><b>+' + res.exp + (res.ups ? "（升了 " + res.ups + " 级！）" : "") + "</b></div>" +
      (res.eqBonus ? '<div class="kv"><span>首次解锁方程式奖金</span><b>+' + res.eqBonus + " 🪙</b></div>" : "") +
      (res.yieldWarn ? '<div class="ph warn-o">⚗️ 产率波动：部分原料副反应损耗，产物减半。更精密的容器/更高的安全等级可降低损耗。</div>' : "") +
      (res.kind === "partial" ? '<div class="ph warn-r">' + U.esc(res.msg) + "</div>" : "") +
      (res.proc ? '<div class="ph">🔬 实验工艺：' + U.esc(r.zh) + "（消耗品已扣除）</div>" : "") +
      (wasDanger ? '<div class="ph">你冒着风险完成了这次实验，向严谨的实验员致敬（现实中请务必遵守安全规范）。</div>' : "") +
      (!E.sandboxActive && !E.tempBench && !st.data.realMode ? '<div class="ph">简单模式已忽略反应条件；切到真实模式体验完整化学。</div>' : "");

    if (res.chal) {
      var cr = res.chal;
      if (cr.win) {
        CHEM.sfx.play("coin");
        body += '<div class="gold-line">🎯 挑战成功！奖励 🪙' + cr.reward + " 与 60 经验已到账。</div>";
        U.showResult({ title: "🎯 挑战达成", body: body });
      } else if (cr.outOfSteps) {
        CHEM.sfx.play("error");
        U.modal("<h3>🎯 步骤用尽</h3>" + body +
          '<div class="gold-line">⏱️ 挑战步骤已用完，产物照常收下。看一段广告可复活 +2 步继续挑战。</div>' +
          '<div class="btn-row"><button class="ok" id="chal-rev2">📺 复活 +2步</button><button class="ghost" data-close>知道了</button></div>',
          { after: function (card) {
            card.querySelector("#chal-rev2").onclick = function () { U.closeModal(); U.reviveChallenge(); };
          } });
      } else {
        CHEM.sfx.play("error");
        body += '<div class="gold-line">' + (cr.decoy ? "💥 混入了干扰物质，挑战立即失败（不可复活）。" : "⏱️ 挑战失败。可去【任务】页再次发起挑战。") + "</div>";
        U.showResult({ title: "🎯 挑战结束", body: body });
      }
      finishReactTutorial();
      return;
    }
    if (E.sandboxActive) body += '<div class="ph">🌌 沙盒实验：不产生奖励，尽情探索彩蛋吧。</div>';
    U.showResult({ title: res.kind === "partial" ? "⚠️ 反应成功（有惊无险）" : (res.proc ? "🔬 工艺完成" : "✨ 反应成功"), body: body });
    if (res.ups) setTimeout(function () { U.toast("🎉 等级提升至 Lv." + st.data.level + "，解锁更多内容！", "gold"); }, 600);
    finishReactTutorial();
  }
  function finishReactTutorial() {
    if (st.data.tutorial !== 3 || E.sandboxActive) return;
    G.call("tutorial.step", { to: 4 }, function (r) {
      U.tutDone("完成首次实验！额外赠送启动资金 " + (r.bonus || CHEM.TUTORIAL_COINS) + " 金币。");
    });
  }
  function precipColor(r) {
    var p = r.phenomenon || "";
    if (p.indexOf("白色") >= 0) return "#eceff1";
    if (p.indexOf("红褐色") >= 0) return "#a1552f";
    if (p.indexOf("蓝色") >= 0) return "#5c9ce6";
    if (p.indexOf("黑色") >= 0) return "#37474f";
    if (p.indexOf("黄色") >= 0) return "#f2ca4a";
    if (p.indexOf("紫色") >= 0) return "#9575cd";
    return "#e57373";
  }
  U.showResult = function (o) {
    U.modal("<h3>" + o.title + "</h3>" + o.body + '<button class="ok" data-close>收下产物</button>');
  };

  /* ---------- 拖拽 / 点击投放 ---------- */
  var drag = null;
  U.isDragging = function () { return !!drag; };
  U.bindItemDrag = function (el, id) {
    el.addEventListener("pointerdown", function (e) {
      if (el.classList.contains("locked")) return;
      e.preventDefault();
      /* 上一笔手势没走完就开了新的：先把旧节点监听从身上摘掉，
         否则 drag 会永远挂着——物质架的 isDragging 守卫会让它再也不刷新。 */
      if (drag && drag.el) unbindDrag(drag.el);
      drag = { id: id, x0: e.clientX, y0: e.clientY, moved: false, pid: e.pointerId, el: el };
      try {
        el.setPointerCapture(e.pointerId);
      } catch (err) {
        /* 捕获失败（无有效指针/老引擎）也要继续：move/up 仍然能收到元素上的事件，
           顶多少了"手指移出元素也不丢事件"的加成，不能因为抛错就把手势卡死。 */
      }
      el._onmove = function (ev) {
        if (!drag) return;
        var dx = ev.clientX - drag.x0, dy = ev.clientY - drag.y0;
        if (!drag.moved && (dx * dx + dy * dy) > 144) { drag.moved = true; showGhost(drag.id, ev); U.$("bench-stage").classList.add("hot"); }
        if (drag.moved) moveGhost(ev);
      };
      el._onup = function (ev) {
        unbindDrag(el);
        if (!drag) return;
        if (drag.moved) {
          var over = overStage(ev);
          hideGhost(); U.$("bench-stage").classList.remove("hot");
          if (over) placeId(drag.id);
        } else {
          placeId(drag.id);
        }
        drag = null;
      };
      /* 浏览器接管手势（横向滑动物质架）时会发 pointercancel：
         这时必须【不】投放，否则每滑一次架子就多一份物质。 */
      el._oncancel = function () {
        unbindDrag(el);
        hideGhost(); U.$("bench-stage").classList.remove("hot");
        drag = null;
      };
      el.addEventListener("pointermove", el._onmove);
      el.addEventListener("pointerup", el._onup);
      el.addEventListener("pointercancel", el._oncancel);
    });
  };
  function unbindDrag(el) {
    el.removeEventListener("pointermove", el._onmove);
    el.removeEventListener("pointerup", el._onup);
    el.removeEventListener("pointercancel", el._oncancel);
  }
  function placeId(id, done) {
    G.call("bench.place", { id: id, n: 1 }, function (r) {
      if (!r.ok) { CHEM.sfx.play("error"); return U.toast(r.msg, "bad"); }
      CHEM.sfx.play("place");
      if (st.data.tutorial === 1) G.call("tutorial.step", { to: 2 }, function () { U.showTutorial(); });
      if (done) done(r);
    });
  }
  /** 别的页面（背包/材料箱/沙盒柜）点卡片投放也走这一个入口。 */
  U.place = placeId;
  function showGhost(id, ev) {
    var g = U.$("drag-ghost"), s = st.sub(id);
    g.textContent = s ? (s.formula || s.zh).slice(0, 4) : id;
    g.style.background = (s && s.color) || "#607d8b";
    g.classList.remove("hidden");
    moveGhost(ev);
  }
  function moveGhost(ev) {
    var g = U.$("drag-ghost"), r = U.$("app").getBoundingClientRect();
    g.style.left = (ev.clientX - r.left) + "px";
    g.style.top = (ev.clientY - r.top) + "px";
  }
  function hideGhost() { U.$("drag-ghost").classList.add("hidden"); }
  function overStage(ev) {
    var r = U.$("bench-stage").getBoundingClientRect();
    return ev.clientX >= r.left && ev.clientX <= r.right && ev.clientY >= r.top && ev.clientY <= r.bottom;
  }

  /* ---------- 挑战 / 沙盒入口 ---------- */
  U.reviveChallenge = function () {
    var ch = st.data.chal;
    if (!ch || !ch.failed) return U.toast("当前没有可复活的挑战", "bad");
    U.simAd("📺 复活挑战", function () {
      G.call("challenge.revive", {}, function (r) {
        if (!r.ok) return U.toast(r.msg || "复活失败", "bad");
        CHEM.sfx.play("levelup");
        U.toast("复活成功：+2 步，继续挑战！", "gold");
      });
    });
  };
  U.startChallenge = function () {
    if (st.data.chal) {
      CHEM.panels.go("bench");
      return U.toast("回到挑战工作台", "gold");
    }
    G.call("challenge.start", {}, function (r) {
      if (!r.ok) return U.toast(r.msg || "暂时没有可用的挑战目标（先把等级提上去或解锁更多反应）", "bad");
      CHEM.panels.go("bench");
      var ch = st.data.chal;
      U.modal("<h3>🎯 合成挑战</h3><div class='ph'>只允许使用材料箱中的物质，在 <b>" + ch.max + "</b> 步内合成 <b>" +
        U.esc((st.sub(ch.target) || {}).zh) + "</b>。混入干扰物质会导致失败！</div>" +
        "<div class='ph'>材料箱：" + Object.keys(ch.given).map(function (id) { return U.esc(st.sub(id).zh) + "×" + ch.given[id]; }).join("、") +
        "（另有 2 种干扰物质混在其中）</div>" +
        '<button class="ok" data-close>开始实验</button>');
    });
  };
  U.startSandbox = function () {
    G.call("sandbox.enter", {}, function () {
      CHEM.panels.go("bench");
      U.toast("🌌 沙盒开启：所有物质无限供应，实验台下方物质架随便拿", "gold");
    });
  };

  /* ---------- 底部导航（每项 = 一整页） ---------- */
  function bindNav() {
    document.querySelectorAll("#bottom-nav button").forEach(function (b) {
      b.onclick = function () { CHEM.panels.go(b.dataset.tab); };
    });
    U.updateNav();
  }
  /** 角标 = "这一页现在有事可办"，计数与页内分段条同源（panels.js），两处不会各算一套。
      每次整帧落地后由 game.js 调用，所以领完奖励角标自己就掉了。 */
  U.updateNav = function () {
    var d = st && st.data;
    var P = CHEM.panels;
    document.querySelectorAll("#bottom-nav button").forEach(function (b) {
      var tag = b.querySelector(".nav-badge");
      if (!tag) return;
      if (!d || !P || !P.claimCounts) { tag.classList.add("hidden"); return; }
      var tab = b.dataset.tab, n = 0;
      if (tab === "settings") {
        /* 游客档没有口令，清掉缓存就找不回来：用一枚小点提醒转正，不占数字 */
        tag.classList.add("dot"); tag.textContent = "";
        tag.classList.toggle("hidden", !G.guest);
        return;
      }
      tag.classList.remove("dot");
      if (tab === "tasks" || tab === "codex") {
        var c = P.claimCounts();
        n = tab === "tasks" ? c.today + c.ach : c.node;
      } else if (tab === "lab") n = P.buyableCount ? P.buyableCount() : 0;
      tag.classList.toggle("hidden", n <= 0);
      tag.textContent = n > 0 ? (n > 99 ? "99+" : String(n)) : "";
    });
  };

  /* ---------- 教程 ---------- */
  var TUT = [
    { t: "欢迎来到《化学实验室：元素纪元》", b: "你是新晋研究员。底部每个图标都是<b>一整页</b>：在【实验台】下方的<b>物质架</b>上点一下，就把一份物质投进容器；凑齐后点<b>开始反应</b>，就能合成新物质、解锁图鉴并赚钱。", act: "去【物质】页看看第一瓶试剂" },
    { t: "第一步：投放物质", b: "把 <b>H₂（氢气）</b>和 <b>O₂（氧气）</b>各投 1 份：<b>点一下</b>物质卡就是投放（【物质】页或实验台物质架都行），不用回这一页，看完直接操作。" },
    { t: "第二步：点燃反应", b: "回到【实验台】，选温度 <b>点燃</b>，然后点 <b>⚗️ 开始反应</b>。" },
    { t: "第三步：收集产物", b: "反应成功后产物会自动存入背包，并解锁物质图鉴与方程式图鉴。赚到的钱可以买仪器、开实验室房间（每台房间都是独立工作台！）。" },
    { t: "出发！", b: "新手奖励已到账。去【市场】买卖原料、【建设】升级实验室，挑战关卡与合成彩蛋等着你。真实模式下需要正确满足温度/催化剂/仪器条件。" }
  ];
  U.showTutorial = function () {
    var i = st.data.tutorial;
    var root = U.$("tutorial-root");
    if (i >= 5) return root.classList.add("hidden");
    var step = TUT[i];
    /* 1~3 步是"现在就动手"：遮罩如果吃掉整屏，玩家根本点不到物质卡，
       所以这几步改成顶部悬浮的教学条，底下照常可玩。 */
    root.classList.toggle("coach", i >= 1 && i <= 3);
    U.$("tutorial-card").innerHTML = "<h3>🧑‍🔬 " + step.t + "</h3><div>" + step.b + "</div>" +
      '<button class="ok" id="tut-next">' + (i === 4 ? "开始游戏" : "明白，继续" + (step.act ? " → " + step.act : "")) + "</button>";
    root.classList.remove("hidden");
    U.$("tut-next").onclick = function () {
      if (i === 0) { CHEM.panels.go("bag"); }
      if (i === 1) { CHEM.panels.go("bench"); }
      G.call("tutorial.step", { to: i >= 3 ? 5 : i + 1 }, function () { U.showTutorial(); });
    };
  };
  U.tutDone = function (msg) {
    var root = U.$("tutorial-root");
    root.classList.add("hidden");
    root.classList.remove("coach");
    U.$("tutorial-card").innerHTML = "<h3>🎉 教程完成</h3><div>" + msg + "</div>" +
      '<button class="ok" id="tut-x">开始探索</button>';
    root.classList.remove("hidden");
    U.$("tut-x").onclick = function () { root.classList.add("hidden"); };
  };

  /* ---------- 分享 ---------- */
  U.share = function (text) {
    var payload = { title: "化学实验室：元素纪元", text: text || "我在《化学实验室：元素纪元》里发现了 " + Object.keys(st.data.discovered).length + " 种物质，来跟我一起玩化学！" };
    var done = function () { U.toast("分享口令已复制/已发起分享", "good"); };
    if (navigator.share) { navigator.share(payload).then(done, function () { copy(payload.text); done(); }); }
    else { copy(payload.text); done(); }
    function copy(t) {
      try {
        var ta = document.createElement("textarea");
        ta.value = t; document.body.appendChild(ta); ta.select();
        document.execCommand("copy"); ta.remove();
      } catch (e) {}
    }
  };
})();
