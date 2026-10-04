/* 在线桥接：客户端唯一的服务端出入口。本地不再存真源——
   发意图 → POST /api/game/<intent> → 服务端整帧 {state,revision,result,events,bench,sandbox} 覆盖本地 → 重绘。
   意图请求串行排队，保证服务端看到的顺序与玩家操作一致。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var G = {};
  CHEM.game = G;

  var queue = [], running = false, pollTimer = null;
  /* 服务端会静默入库的发现（购买/提纯等）不弹提示，只有这几个意图与原客户端一致地点亮图鉴 */
  var DISCOVER_TOAST = { react: 1, sign: 1, "friend.visit": 1 };

  function el(id) { return document.getElementById(id); }

  /* ---------- 启动门（加载/鉴权遮罩） ---------- */
  function showBoot(text) {
    var r = el("boot-root");
    if (r) r.classList.remove("hidden");
    var m = el("boot-msg"); if (m) m.textContent = text || "正在连接服务器…";
    var a = el("boot-actions"); if (a) a.innerHTML = "";
  }
  function hideBoot() { var r = el("boot-root"); if (r) r.classList.add("hidden"); }
  function bootFail(msg, retry) {
    showBoot("⚠️ " + msg);
    var a = el("boot-actions");
    if (a && retry !== false) {
      a.innerHTML = '<button class="ok" id="boot-retry">重试</button>';
      el("boot-retry").onclick = function () { location.reload(); };
    }
  }

  /** 启动：无会话先进登录/注册页；带令牌（含游客令牌）才对齐内容库 → 校验会话 → 拉取权威存档 → 初始化界面。 */
  G.boot = function () {
    var C = CHEM.cloud;
    if (!C.available()) return bootFail("这是一款在线游戏：请启动后端，再用浏览器访问它给出的地址（如 http://localhost:8080）。", false);
    if (!C.logged()) return G.toGate();
    G.start();
  };

  /** 显示登录/注册页（盖住界面）；任意一条路径拿到令牌后回到 G.start()。 */
  G.toGate = function (notice) {
    hideBoot();
    if (notice && G.ready) CHEM.ui.toast(notice, "bad");
    CHEM.gate.show(function () { G.start(); }, G.ready ? null : notice);
  };

  /** 已有令牌后的正式进入流程，可重入（登录态失效、切换账号后复用）。 */
  G.start = function () {
    var C = CHEM.cloud;
    showBoot(G.ready ? "正在重新连接服务器…" : "正在连接服务器…");
    CHEM.content.load(function () {
      if (CHEM.state) CHEM.state.subMap = null;      /* 内容覆盖后重建物质查表 */
      G.session(function (j) {
        if (!j.ok) {
          if (j.code === 401) { C.clearSession(); return G.toGate("🔒 登录状态已失效，请重新登录或以游客身份进入"); }
          return bootFail(j.msg || "无法连接服务器");
        }
        G.user = j.user; G.guest = !!j.guest;
        G.pull(function (k) {
          if (!k.ok) return bootFail(k.msg || "读取存档失败");
          G.apply(k.frame, "boot");
          if (!G.ready) {
            G.ready = true;
            CHEM.panels.init(); CHEM.ui.init();
            if (CHEM.state.data.tutorial < 5) CHEM.ui.showTutorial();
            startPoll();
          } else CHEM.ui.toast("🔌 已重新连接服务器", "good");
          hideBoot();
        });
      });
    });
  };

  /** 校验当前令牌并取回身份；不再自动开游客档——游客入口只有一处，就是登录页那个按钮。 */
  G.session = function (cb) {
    CHEM.cloud.me(function (m) {
      if (m.ok) return cb({ ok: true, user: m.user, guest: !!m.guest });
      cb({ ok: false, code: m.code, msg: m.msg });
    });
  };

  G.pull = function (cb) {
    CHEM.cloud.gameState(function (j) { cb({ ok: !!j.state, msg: j.msg, frame: j }); });
  };

  /* ---------- 意图调用 ---------- */
  /** 发送意图；cb(result, frame)。网络/鉴权失败时 result={ok:false,msg:...}、frame=null。 */
  G.call = function (intent, params, cb) {
    queue.push({ i: intent, p: params, c: cb || function () {} });
    pump();
  };
  function pump() {
    if (running || !queue.length) return;
    var job = queue.shift();
    running = true;
    CHEM.cloud.game(job.i, job.p, function (j) {
      running = false;
      if (!j || !j.state) {
        job.c({ ok: false, msg: (j && j.msg) || "网络异常，进度未变动" }, null);
        if (j && j.code === 401) G.onExpired();
        return pump();
      }
      G.apply(j, job.i);
      job.c(j.result || { ok: j.ok }, j);
      pump();
    });
  }
  /** 刷新令牌也失效：清会话并回到登录/注册页（游客档若未转正，重新进入会是新档）。 */
  G.onExpired = function () {
    CHEM.cloud.clearSession();
    queue.length = 0; running = false;
    G.toGate("🔒 登录状态已失效，请重新登录或以游客身份进入");
  };

  /* ---------- 整帧落地 + 重绘 ---------- */
  G.apply = function (frame, intent) {
    var st = CHEM.state, E = CHEM.engine, U = CHEM.ui;
    var before = st.data ? st.data.discovered : null;
    st.setServer(frame.state, frame.revision);
    E.init();
    E.tempBench = frame.bench || null;
    E.sandboxActive = !!frame.sandbox;
    if (U && U.inited) {
      G.announce(frame.events);
      if (DISCOVER_TOAST[intent]) G.announceDiscoveries(before);
      G.refresh();
    }
  };
  G.refresh = function () {
    var U = CHEM.ui;
    if (!U || !U.inited || !CHEM.state.data) return;
    U.updateTop(); U.renderStage(); U.applySkin(); U.checkAchToasts();
    /* 导航角标跟着整帧走：领了奖励、买了仪器、达到等级，红点自己就掉 */
    U.updateNav();
    if (CHEM.panels && CHEM.panels.refreshIfOpen) CHEM.panels.refreshIfOpen();
  };

  /** 服务端每日刷新的回执事件 → toast（补贴/挂单结算），替代原来的本地心跳。 */
  G.announce = function (events) {
    var U = CHEM.ui, st = CHEM.state;
    (events || []).forEach(function (e) {
      if (!e || e.type !== "daily") return;
      if (e.newDay) U.toast("🌅 新的一日：实验室补贴 +🪙" + e.stipend + (st.monthlyActive() ? " +💎3" : ""), "gold");
      if (e.listingsSettled) U.toast("📦 商会有 " + e.listingsSettled + " 笔挂单已结算，去【市场-挂单】看看", "good");
    });
  };
  /** 与旧客户端一致：点亮图鉴时提示发现奖金（奖金本身由服务端入账，这里只做展示）。 */
  G.announceDiscoveries = function (before) {
    var st = CHEM.state, U = CHEM.ui;
    if (!before) return;
    Object.keys(st.data.discovered).forEach(function (id) {
      if (before[id]) return;
      var s = st.sub(id);
      if (!s || s.kind === "consumable") return;
      var bonus = s.level >= 1 ? st.discoverBonus(id, false) : 50;
      U.toast("🎉 发现新物质【" + s.zh + "】，获得发现奖金 " + bonus + " 金币！", "good");
    });
  };

  /* ---------- 挂单结算：只在后台可见且有在挂订单时轻轮询 ---------- */
  function startPoll() {
    if (pollTimer) return;
    pollTimer = setInterval(function () {
      var d = CHEM.state.data;
      if (running || document.hidden || !d || !d.listings || !d.listings.length) return;
      G.call("state", {}, function () {});
    }, 8000);
  }

  /* ---------- 账号动作后整页重进（服务端为真源，无未保存数据） ---------- */
  G.restart = function () { location.reload(); };
})();
