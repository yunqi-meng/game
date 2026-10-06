/* 在线桥接：客户端唯一的服务端出入口。本地不再存真源——
   发意图 → POST /api/game/<intent> → 服务端整帧 {state,revision,result,events,bench,sandbox} 覆盖本地 → 重绘。
   意图请求串行排队，保证服务端看到的顺序与玩家操作一致。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var G = {};
  CHEM.game = G;

  var queue = [], running = false, pollTimer = null;
  /* F2/H2 的两个号：
     seq   —— 会话内单调的意图编号，贴在每一笔出发的身上，服务端按 (uid, sid, seq) 认重复交付。
              换一次登录它从 1 重新数，所以服务端那边必须带 sid（已带）。
     epoch —— 身份纪元。onExpired／退登／重启页面时 +1；回调落地前先比一次，
              不等就说明这一帧属于上一个账号，整帧丢掉（H2：切号后旧请求的迟到回包会画脏界面）。 */
  var seq = 0, epoch = 0;
  /* 服务端会静默入库的发现（购买/提纯等）不弹提示，只有这几个意图与原客户端一致地点亮图鉴 */
  var DISCOVER_TOAST = { react: 1, sign: 1, "friend.visit": 1 };

  function el(id) { return document.getElementById(id); }
  function bootVisible() { var r = el("boot-root"); return !!r && !r.classList.contains("hidden"); }

  /* ---------- 启动门（加载/鉴权遮罩） ---------- */
  function showBoot(text) {
    var r = el("boot-root");
    if (r) r.classList.remove("hidden");
    // 新一轮尝试开始就不该留着上一轮的定时器：否则两次 G.boot() 会在同一个时刻并发跑
    stopBootRetry();
    var m = el("boot-msg"); if (m) m.textContent = text || "正在连接服务器…";
    var a = el("boot-actions"); if (a) a.innerHTML = "";
    var d = el("boot-detail"); if (d) { d.classList.add("hidden"); d.textContent = ""; }
  }
  function hideBoot() {
    var r = el("boot-root"); if (r) r.classList.add("hidden");
    // 进得去就说明这条重试路走完了：次数归零，下次再断网从 1.5 秒重新起算
    resetBootRetry();
  }

  /* ---------- 启动失败的自动重试（C4：原来只有"整页重载"这一根救命稻草） ---------- */
  /* 手机上最常见的不是"坏了"，是"这一刻没信号"：地铁里进游戏失败，玩家要做的事本来是
     把手机放下、过两站再打开——旧实现却让他盯着一个静态报错页自己数时间。现在按
     1.5s→3s→6s→12s→24s→30s 自己重试，并把"第几次、还剩几秒"写在遮罩上：等待要有形状，
     玩家才知道系统没死。服务端活着但数据库连不上（state="db"）是运营侧故障，
     重试再多也只是同一句报错，只给两轮就停手等按钮。 */
  var BOOT_MAX = 6;
  var br = { timer: null, tick: null, tries: 0, left: 0, cap: BOOT_MAX };
  function stopBootRetry() {
    if (br.timer) { clearTimeout(br.timer); br.timer = null; }
    if (br.tick) { clearInterval(br.tick); br.tick = null; }
    br.left = 0;
  }
  function resetBootRetry() { stopBootRetry(); br.tries = 0; }
  function scheduleBootRetry(state) {
    br.cap = state === "db" ? 2 : BOOT_MAX;
    if (br.timer || br.tries >= br.cap) return;
    var delay = Math.min(30000, 1500 * Math.pow(2, br.tries));
    br.tries++;
    br.left = Math.ceil(delay / 1000);
    br.timer = setTimeout(function () {
      br.timer = null;
      if (br.tick) { clearInterval(br.tick); br.tick = null; }
      // 玩家可能已经点了别的出路（重新登录/退出游戏），不能从背后把链路重启一遍
      if (!bootVisible()) return;
      G.boot();
    }, delay);
    if (br.tick) clearInterval(br.tick);
    br.tick = setInterval(function () {
      br.left = Math.max(0, br.left - 1);
      var b = el("boot-auto");
      if (b) b.textContent = br.left > 0 ? br.left + " 秒后自动重试（第 " + br.tries + "/" + br.cap + " 次）" : "正在重试…";
    }, 1000);
    autoLine();
  }
  /** 倒计时只补一行小字，不重建遮罩：重建会把已经画好的错误详情擦掉。 */
  function autoLine() {
    var a = el("boot-actions"); if (!a) return;
    var b = el("boot-auto");
    if (!b) {
      b = document.createElement("p");
      b.className = "boot-auto"; b.id = "boot-auto";
      a.appendChild(b);
    }
    b.textContent = br.left + " 秒后自动重试（第 " + br.tries + "/" + br.cap + " 次）";
  }

  /**
   * 启动失败页三态：结论来自 /api/healthz，而不是把 msg 原样贴上去让玩家猜。
   * reach=false → 根本没连上（网络断了／后端没起／壳里没配地址）；
   * reach=true 且 ok=false → 进程活着但数据库连不上，这是运营侧故障，与玩家账号无关；
   * 两边都正常却仍然进不去 → 会话或内容的问题，给"重新登录"这条出路。
   * 探活是异步的，回来时遮罩可能已经被别的路径收起来了，所以每一处都先确认还露着才画。
   */
  function bootFail(msg, retry) {
    showBoot("⚠️ " + msg);
    if (retry === false) return actions([{ id: "boot-recheck", cls: "ok", text: "重新检测" }]);
    CHEM.cloud.health(paintFail);
  }
  function paintFail(h) {
    if (!bootVisible()) return;
    var state = !h || !h.reach ? "net" : (h.ok ? "client" : "db");
    var d = el("boot-detail");
    if (d) {
      d.className = "boot-detail " + state;
      d.textContent = state === "net"
        ? (CHEM.shell.inShell()
            ? "手机连不上游戏服务器。这款游戏的结算全在服务器上，没有离线模式：请检查网络，或稍后再试。"
            : "连不上后端。本机开发时请先启动服务，再用它给出的地址访问（如 http://localhost:8080）。")
        : state === "db"
          ? "服务器进程在运行，但它连不上数据库" + (h.dbError ? "（" + h.dbError + "）" : "") +
            "。这是运营侧的故障，不是你账号的问题：你的存档还在库里，恢复后回来接着玩。"
          : "服务器和数据库都正常，那这一步多半卡在登录状态或内容版本上——重试或重新登录通常就能进。";
      d.classList.remove("hidden");
    }
    var list = [{ id: "boot-retry", cls: "ok", text: "立刻重试" }];
    if (state === "client") list.push({ id: "boot-relogin", cls: "ghost", text: "重新登录" });
    list.push({ id: "boot-recheck", cls: "ghost", text: "再看一次状态" });
    actions(list);
    // 报错页画完才开始计时：先给玩家两秒读清楚发生了什么，再自己动
    scheduleBootRetry(state);
  }
  function actions(list) {
    var a = el("boot-actions");
    if (!a) return;
    a.innerHTML = list.map(function (x) {
      return '<button class="' + x.cls + '" id="' + x.id + '">' + x.text + "</button>";
    }).join("");
    list.forEach(function (x) {
      var b = el(x.id);
      if (b) b.onclick = function () {
        if (x.id === "boot-relogin") { CHEM.cloud.clearSession(); return G.toGate(); }
        if (x.id === "boot-recheck") return bootFail(el("boot-msg").textContent.replace(/^⚠️\s*/, "") || "启动失败");
        // 手动重试走 G.boot() 而不是整页重载：链路本身可重入，重载只会把内容库缓存与已建好的 DOM 一起丢掉
        resetBootRetry();
        G.boot();
      };
    });
  }

  /**
   * 版本门：只在壳里判（H5 没有"包版本"这个概念，它永远跟着服务端发的那份 JS 走）。
   * 低于 minBuild 硬拒——旧客户端配新协议只会打出一堆无解错误，不如挡在门外；
   * 低于 latestBuild 只在游戏里给一次提示，不打断。
   * 问到版本失败就放行：能不能玩该由游戏服务器决定，不该由"版本查询这次没通"决定。
   */
  G.versionGate = function (cb) {
    if (!CHEM.shell.inShell()) return cb({ allow: true });
    CHEM.shell.appBuild(function (e, build) {
      if (!build) return cb({ allow: true });
      CHEM.cloud.appVersion(function (j) {
        if (!j || !j.ok) return cb({ allow: true });
        var min = Number(j.minBuild) || 0, latest = Number(j.latestBuild) || 0;
        if (build < min) return cb({ allow: false, force: true, build: build, min: min, note: j.note || "", url: j.url || "" });
        cb({ allow: true, build: build, outdated: latest > 0 && build < latest, latest: latest, note: j.note || "", url: j.url || "" });
      });
    });
  };
  /** 强制升级页：给出版本号与更新入口，不假装"重试"能解决问题。 */
  G.forceUpdate = function (info) {
    showBoot("📦 需要更新到新版本");
    var d = el("boot-detail");
    if (d) {
      d.className = "boot-detail force";
      d.textContent = "当前包版本 " + info.build + "，本服最低要求 " + info.min + "。" +
        (info.note ? info.note : "旧版本的服务端结算协议已停用，继续玩只会拿到错误结果。");
      d.classList.remove("hidden");
    }
    actions([{ id: "boot-upd", cls: "ok", text: "去更新" }, { id: "boot-close", cls: "ghost", text: "退出游戏" }]);
    el("boot-upd").onclick = function () {
      if (info.url) { try { window.open(info.url, "_blank"); } catch (e) {} }
      else CHEM.ui.toast("更新地址未配置，请到 TapTap 详情页下载新版本", "bad");
    };
    el("boot-close").onclick = function () { if (!CHEM.shell.exitApp()) location.reload(); };
  };

  /* ---------- 顶栏网络状态（C4） ---------- */
  /* 只在请求真的拖久了或失败时才露脸：一路顺畅时顶栏不该有东西晃玩家眼睛。 */
  var inflight = 0, slowTimer = null, chipHide = null;
  function netStart() {
    inflight++;
    // 系统已经报断网就别再等 900 毫秒：直接说清楚，省得玩家以为是自己手慢
    if (!navigator.onLine) { G.offline = true; chip("网络已断开", "bad", 0); return; }
    if (inflight === 1 && !slowTimer) slowTimer = setTimeout(function () { chip("重连中…", "wait", 0); }, 900);
  }
  function netDone(ok) {
    inflight = Math.max(0, inflight - 1);
    if (inflight === 0 && slowTimer) { clearTimeout(slowTimer); slowTimer = null; }
    if (ok) { if (inflight === 0) { G.offline = false; chip(null); } return; }
    if (!navigator.onLine) { G.offline = true; chip("网络已断开", "bad", 0); return; }
    chip("网络异常", "bad", 5000);
  }
  function chip(text, cls, ttl) {
    var c = el("net-chip");
    if (!c) return;
    if (chipHide) { clearTimeout(chipHide); chipHide = null; }
    if (!text) { if (slowTimer) { clearTimeout(slowTimer); slowTimer = null; } c.className = "net-chip hidden"; return; }
    c.textContent = text;
    c.className = "net-chip " + cls;
    if (ttl) chipHide = setTimeout(function () { chip(null); }, ttl);
  }

  /** 启动：无会话先进登录/注册页；带令牌（含游客令牌）才对齐内容库 → 校验会话 → 拉取权威存档 → 初始化界面。 */
  G.boot = function () {
    var C = CHEM.cloud;
    if (!C.available()) return bootFail("这是一款在线游戏：请启动后端，再用浏览器访问它给出的地址（如 http://localhost:8080）。", false);
    // 壳里最常见的一种"打不开"：构建时没注入 API 地址，请求全落到壳自己的 https://localhost
    if (CHEM.shell.inShell() && !CHEM.shell.apiBase())
      return bootFail("安装包里没有配置服务器地址（构建时未注入 API 域名）。", false);
    // 版本门放在登录判断之前：旧包的登录链路本身就可能是坏的，先让他把包装上
    G.versionGate(function (v) {
      if (!v.allow) return G.forceUpdate(v);
      G.build = v.build || 0;
      G.updateHint = v.outdated ? "v" + v.build + " → v" + v.latest : "";
      G.updateUrl = v.url || "";
      if (!CHEM.cloud.logged()) return G.toGate();
      G.start();
    });
  };

  /** 显示登录/注册页（盖住界面）；任意一条路径拿到令牌后回到 G.start()。 */
  G.toGate = function (notice) {
    // 站到登录门前，就意味着当前这个身份要么没有、要么作废了：把它留下的自发来源一起掐掉（H2）
    stopForNewIdentity();
    hideBoot();
    if (notice && G.ready) CHEM.ui.toast(notice, "bad");
    CHEM.gate.show(function () { G.start(); }, G.ready ? null : notice);
  };

  /**
   * 已有令牌后的正式进入流程，可重入（登录态失效、切换账号后复用）。
   *
   * <p>C2：内容库与会话校验互不依赖——一个拿 118 条物质表，一个问"这枚令牌还认不认"。
   * 以前串成 content.load → me → state 三级，等于每次进游戏都白等一个来回；
   * 壳里冷启动弱网时那一个来回就是两三秒的白屏。现在两件并发做，都回来才拉权威存档
   * （存档要等内容，因为 apply() 里就得用物质表；不等到 401 才放弃内容请求，免得半张表落地）。
   */
  G.start = function () {
    var C = CHEM.cloud;
    // 启动链路不走意图队列，所以它要自己认一次纪元（H2）：见 afterBoth 开头
    var ep = epoch;
    showBoot(G.ready ? "正在重新连接服务器…" : "正在连接服务器…");
    bindOnce();
    var got = { content: false, session: false }, sess = null;
    function afterBoth() {
      if (!got.content || !got.session) return;
      if (ep !== epoch) return;             // 这一轮启动属于上一个身份：别替它画界面，也别报"失败"
      var j = sess;
      if (!j.ok) {
        if (j.code === 401) { C.clearSession(); return G.toGate("🔒 登录状态已失效，请重新登录或以游客身份进入"); }
        return bootFail(j.msg || "无法连接服务器");
      }
      G.user = j.user; G.guest = !!j.guest;
      G.pull(function (k) {
        if (ep !== epoch) return;             // 取存档是第二个来回，中间也可能换了身份：同样只认自己那一轮
        if (!k.ok) {
          // 青少年时段到了：这不是故障，画等待页而不是报错页
          if (k.frame && CHEM.curfew.handle(k.frame)) return hideBoot();
          return bootFail(k.msg || "读取存档失败");
        }
        G.apply(k.frame, "boot");
        // 轮询放在 if 外面：stopForNewIdentity() 停过它，重新登录这一路 G.ready 已经是 true，
        // 只在首帧里 arm 一次就等于"换过一次账号之后挂单永远不再自动结算"。startPoll 自己幂等。
        startPoll();
        if (!G.ready) {
          G.ready = true;
          CHEM.panels.init(); CHEM.ui.init();
          if (CHEM.state.data.tutorial < 5) CHEM.ui.showTutorial();
          announceUpdate();
        } else CHEM.ui.toast("🔌 已重新连接服务器", "good");
        hideBoot();
      });
    }
    CHEM.content.load(function () {
      if (CHEM.state) CHEM.state.subMap = null;      /* 内容覆盖后重建物质查表 */
      got.content = true; afterBoth();
    });
    G.session(function (j) { sess = j; got.session = true; afterBoth(); });
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
  /* ---------- 连点防护 ----------
   * 点一下就把这个按钮锁住，直到它那条意图的整帧落地（20 秒兜底超时防回调丢了把按钮焊死）。
   *
   * 为什么锁在 G.call 而不是各面板的 run()：panels 的 run() 和 ui.js 的一堆直调最终都汇到这里，
   * 一处就覆盖市场买卖、领取、升级、实验台全部按钮；反过来在每个 onclick 里手写禁用，
   * 新页面一定会漏。记"这一下点的是谁"用捕获阶段监听，它先于任何 onclick 执行，
   * 所以不必给每个调用点多加一个参数。
   *
   * 说清楚它不管什么：这不是并发安全，也不负责"同一次点击被链路送来说两次"——那是 F2 的 seq。
   * 玩家真想连买两次（第二次点在第一下落地之后）本来就该买两次，所以服务端对不带 seq 的旧包
   * 与对两条不同 seq 的意图都照单执行，e2e 里"连发两次确实扣两次"那条钉的就是这个语义；
   * 这一层只管"手抖一下多出一次购买"。
   */
  var pendingLock = null;
  document.addEventListener("click", function (e) {
    var b = e && e.target && e.target.closest ? e.target.closest("button") : null;
    pendingLock = b && !b.disabled ? { el: b, at: Date.now() } : null;
  }, true);
  function takeLock() {
    var p = pendingLock;
    pendingLock = null;
    if (!p || Date.now() - p.at > 1000) return null;   // 只认"这一下"，不认上次留下的
    return p.el.isConnected ? p.el : null;
  }

  /**
   * 发送意图；cb(result, frame)。网络/鉴权失败时 result={ok:false,msg:...}、frame=null。
   * job 上带 n：读意图自动重放时记录已试次数（C4 的退避靠它）。
   * job 上带 s：这一笔的幂等编号，发几次都是同一个（见 pump 里的说明）。
   */
  G.call = function (intent, params, cb) {
    var el = takeLock();
    var locked = false;
    if (el) {
      el.disabled = true;                    // 先禁：后面排队的那次点击就没有按钮可点了
      el.classList.add("busy");
      locked = true;
      setTimeout(unlock, 20000);             // 兜底：回调没来也不许把按钮焊死
    }
    function unlock() {
      if (!locked) return;
      locked = false;
      if (el.isConnected) { el.disabled = false; el.classList.remove("busy"); }
    }
    var f = cb || function () {};
    queue.push({ i: intent, p: params, n: 0, s: ++seq, e: epoch,
      c: function () { unlock(); f.apply(null, arguments); } });
    pump();
  };

  /**
   * 只有"读"能被自动重放。
   *
   * <p>写意图失败分两种：服务端明确回了拒绝（有 code / 有帧），和根本没回音（弱网、请求超时）。
   * 后者最要命——服务端可能已经把这次合成算完了，只是回复没回来。这时自动重放等于让同一句
   * "点火"执行两遍，玩家的产物和金币都会被吃掉一份。所以写意图一律不重放，改成拉一次权威帧：
   * 到底做没做成由服务端那一帧说了算，本地不猜。
   */
  var READONLY = { state: 1, "ad.status": 1, leaderboard: 1 };
  var RETRY_MAX = 4;                       // 1.2s → 2.4s → 4.8s → 9.6s，之后把决定权交回玩家
  var retryTimer = null, resyncTimer = null, resyncTries = 0;
  function backoffMs(tries) { return Math.min(30000, 1200 * Math.pow(2, tries)); }
  function stopTimers() {
    if (retryTimer) { clearTimeout(retryTimer); retryTimer = null; }
    if (resyncTimer) { clearTimeout(resyncTimer); resyncTimer = null; }
    resyncTries = 0;
  }
  /** 挂单轻轮询：它自己会调 G.call，所以停不停它决定了"换身份之后还有没有请求在飞"。 */
  function stopPoll() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  }

  /**
   * 身份要换了（登录失效／退登／重进页面）：把上一账号留下的所有自发来源一起掐掉。
   *
   * <p>H2 的核心一条：{@code stopTimers()} 只管退避与对齐两个定时器，挂单轮询（{@code startPoll}）
   * 和广告等待那条递归（{@code ads.js} 的 awaitReward）都不在它覆盖范围内。于是"退出登录后
   * 8 秒一次的 state 请求"会带着上一个账号的令牌残影继续跑，回包还会把那个账号的整帧画进来——
   * 玩家看到的是自己已经退出了，界面却还在替别人刷新金币。
   *
   * <p>刻意不叫"stopAllTimers"，也不挂到 offline/online 那条路上：掉网时轮询要留着，
   * 复网那一刻它就是最早的一次对齐；只有身份变了才需要连它一起停。
   */
  function stopForNewIdentity() {
    epoch++;                            // 先推纪元：任何在飞的回包落地前都会被 pump 认出来丢掉
    stopTimers();
    stopPoll();
    if (CHEM.ads && CHEM.ads.cancelAwait) CHEM.ads.cancelAwait();
    // 防沉迷等待页那 1 秒一跳也要停：它每跳一次都可能问一次 /api/curfew/status，
    // 而那张页面上写的窗口属于上一个身份——人都回到登录门了，不该还在替他数还有几分钟放开。
    if (CHEM.curfew && CHEM.curfew.hide) CHEM.curfew.hide();
  }

  /** 写意图没回音：按退避拉一帧对齐服务端真相，同一时刻只排一次，成功即停。 */
  function scheduleResync() {
    if (resyncTimer) return;
    if (resyncTries >= RETRY_MAX) { resyncTries = 0; chip("网络异常", "bad", 6000); return; }
    chip("重连中…（" + (++resyncTries) + "/" + RETRY_MAX + "）", "wait", 0);
    resyncTimer = setTimeout(function () {
      resyncTimer = null;
      G.call("state", {}, function (r, frame) {
        if (!frame) return scheduleResync();      // 还没连上：下一轮退避由 resyncTries 继续推高
        resyncTries = 0;
        CHEM.ui.toast("🔌 已与服务端对齐", "good");
      });
    }, backoffMs(resyncTries - 1));
  }

  function pump() {
    if (running || !queue.length) return;
    var job = queue.shift();
    running = true;
    netStart();
    /* seq 在这一刻才贴上去，而不是在 G.call 里改 job.p：
       ① 幂等键认的是"这一笔操作"，读意图退避重发时 job 会原样回到队首，重发必须带同一个号，
          服务端才认得出那是同一笔的第二次交付（换号等于自己把幂等窗口作废）；
       ② 参数对象是调用方（panels／ui）传进来的，就地写一个 seq 会把玩家的表格对象弄脏，
          同一个 params 第二次提交时还会带上上一笔的号。
       所以每次出发拷一份。 */
    CHEM.cloud.game(job.i, Object.assign({}, job.p, { seq: job.s }), function (j) {
      /* 纪元不符 = 这一帧属于上一个账号（退登／令牌失效之后才回来的迟到回包）。
         整帧丢掉，不落地也不回调：apply 会把上一个账号的金币、背包、图鉴画在当前界面上，
         而 pump 的 running 已经由 onExpired 归零，这里再动它就是给新会话抢闸门。
         代价是那个按钮的锁只能等 20 秒兜底自己松开——而它本来就该松开：这一笔已经不是他点的了。 */
      if (job.e !== epoch) return;
      running = false;
      netDone(!!(j && j.state));
      if (!j || !j.state) {
        /* 被防沉迷挡下：画等待页。这条分支必须排在通用失败之前，
           否则玩家看到的是"网络异常，进度未变动"——明明是时段限制，却像我们的服务器坏了。 */
        if (j && j.tag === "CURFEW") {
          CHEM.curfew.handle(j);
          job.c({ ok: false, msg: j.msg, tag: j.tag }, null);
          return pump();
        }
        /* 没 code 也没帧 = 这次请求根本没得到答复，才谈得上重放；有 code 是服务端明确拒绝，重试只会再拒一次。 */
        var silent = !j || (j.code === undefined && !j.tag);
        if (silent && READONLY[j.i] && job.n < RETRY_MAX) {
          job.n++;
          chip("重连中…（" + job.n + "/" + RETRY_MAX + "）", "wait", 0);
          queue.unshift(job);
          if (retryTimer) clearTimeout(retryTimer);
          retryTimer = setTimeout(function () { retryTimer = null; pump(); }, backoffMs(job.n - 1));
          return;
        }
        job.c({ ok: false, msg: (j && j.msg) || "网络异常，进度未变动" }, null);
        if (j && j.code === 401) G.onExpired();
        else if (silent && !READONLY[j.i]) scheduleResync();   // 写没落进去？拉一帧问服务端
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
    // 换了身份，上一轮的退避、对齐、挂单轮询和广告等待递归都不该再自己发请求（H2）
    stopForNewIdentity();
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

  /** 服务端事件 → toast：每日刷新（补贴/挂单结算）与激励视频到账，替代原来的本地心跳。 */
  G.announce = function (events) {
    var U = CHEM.ui, st = CHEM.state;
    (events || []).forEach(function (e) {
      if (!e) return;
      if (e.type === "ad") {
        /* 到账明细由服务端在结算那一刻算好（text 就是"🪙500"这种），客户端不重算一遍 */
        var list = (e.granted || []);
        if (!list.length) return;
        var sum = list.map(function (x) { return x.text; }).filter(Boolean).join("、");
        U.toast("🎁 广告奖励已到账：" + sum, "gold");
        if (CHEM.panels && CHEM.panels.adInvalidate) CHEM.panels.adInvalidate();
        return;
      }
      if (e.type !== "daily") return;
      if (e.newDay) {
        U.toast("🌅 新的一日：实验室补贴 +🪙" + e.stipend + (st.monthlyActive() ? " +💎3" : ""), "gold");
        // 跨日了，青少年模式的时段视图必须重新问一次（昨晚被挡的人今天可能已经放开）
        if (CHEM.panels && CHEM.panels.curfewInvalidate) CHEM.panels.curfewInvalidate();
      }
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

  /* ---------- 壳里才需要的接线：返回键 / 建议更新 ---------- */
  var bound = false, lastBack = 0, updateTold = false;
  function bindOnce() {
    if (bound) return;
    bound = true;
    var c = el("net-chip");
    // 芯片可点：卡住的玩家最想要的就是"我现在就试一次"，而不是等 8 秒轮询
    if (c) c.onclick = function () { if (!running) G.call("state", {}, function () {}); };
    CHEM.shell.onBack = back;
    bindNetwork();
    bindContentRefresh();
  }

  /**
   * 后台换版的落地（C3）：启动不等内容，所以内容可能在玩家已经玩起来之后才换完。
   * 这一刻要做的不是刷新页面——玩家正在实验台中间，刷新等于把他手里的现场抹掉——
   * 而是重建物质查表、重绘当前页，并只提示一次。
   */
  function bindContentRefresh() {
    CHEM.content.onRefresh = function (live, from) {
      /* 走 state 的作废入口，而不是手写 subMap = null：那张表还记着"建表时用的内容版本"，
         只清表不清版本，下一次 allSubs 会以为手上这张就是新版内容（H3 把失效点从存档帧搬到内容版本，
         漏掉这一句就是"换了版却还在画旧物质"）。 */
      if (CHEM.state && CHEM.state.invalidateSubs) CHEM.state.invalidateSubs();
      if (CHEM.panels && CHEM.panels.invalidateCounts) CHEM.panels.invalidateCounts();
      if (!G.ready) return;                       // 还没进游戏：G.start 会用新那份画首屏，不用打扰
      if (CHEM.panels && CHEM.panels.render) CHEM.panels.render();
      if (CHEM.ui && CHEM.ui.toast) CHEM.ui.toast("🆕 内容已更新到 v" + live + "（原 v" + from + "）", "good");
    };
  }

  /**
   * 断网/复网监听：系统的 online/offline 比"请求失败回来"快得多，也更准——
   * 手机上掉网络时请求往往根本回不来，只能干等超时。复网后主动补一次 G.start()，
   * 玩家不用自己刷新页面；拿回存档前芯片一直挂着"正在重新连接"，不给他假象。
   */
  function bindNetwork() {
    window.addEventListener("offline", function () {
      G.offline = true;
      stopTimers();                       // 明摆着没网，就别让退避定时器继续对着空气发请求
      chip("网络已断开", "bad", 0);
    });
    window.addEventListener("online", function () {
      var was = G.offline;
      G.offline = false;
      stopTimers();                       // 复网这一刻就是最早的一次重试，退避从头算
      if (!was) { chip(null); return; }
      if (!G.ready) return;                       // 还没进过游戏，boot 链路自己会走
      chip("正在重新连接…", "wait", 0);
      G.start();                                  // 成功会吐司"已重新连接服务器"，失败自会报出来
    });
  }

  /**
   * 安卓返回键：一次只退一层，退无可退才双击退出。
   *
   * <p>计划表里"回实验台"排在"复位搜索/分段"前面，那样第二步永远轮不到执行（人都离开这页了），
   * 所以这里把复位提到离开前面：返回键的语义是"撤销我在这台设备上最后一步导航"，
   * 输入过的搜索词和切过的分段就是最后一步。防沉迷等待页刻意不消化返回——那时退出才是对的答案。
   */
  function back() {
    var U = CHEM.ui, P = CHEM.panels;
    if (CHEM.curfew.open()) return false;
    var modal = el("modal-root");
    if (U && U.inited && modal && !modal.classList.contains("hidden")) { U.closeModal(); return true; }
    var tut = el("tutorial-root");
    if (tut && !tut.classList.contains("hidden")) { tut.classList.add("hidden"); return true; }
    if (P && P.backStep && P.backStep()) return true;
    if (P && P.tab !== "bench") { P.go("bench"); return true; }
    var now = Date.now();
    if (now - lastBack < 2000) { if (CHEM.shell.exitApp()) return true; }
    lastBack = now;
    if (U && U.toast) U.toast("再按一次退出游戏", "");
    return true;
  }

  /** 建议更新只在进游戏时提一次：低于 latestBuild 不是故障，不值得每帧唠叨。 */
  function announceUpdate() {
    if (updateTold || !G.updateHint) return;
    updateTold = true;
    CHEM.ui.toast("📦 有新版本可用：" + G.updateHint, "good");
  }

  /* ---------- 账号动作后整页重进（服务端为真源，无未保存数据） ---------- */
  G.restart = function () {
    // reload 是异步的：在这之前先把轮询与广告递归停掉，别让"退出登录"到页面真正换掉之间那一段
    // 还替上一个账号发意图（退登走的就是这一条：panels 里 clearSession() + G.restart()）
    stopForNewIdentity();
    location.reload();                     // 有意重载：G.restart 换身份整页重进，不是"失败了就刷一下试试"
  };
})();
