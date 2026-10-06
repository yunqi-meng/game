/* 激励视频播放桥接：把"看广告"这件事实现在一处。
   真机（TapADN / Dirichlet SSP）走 Capacitor 原生插件 window.ChemeraAd；
   浏览器/本机回归没有 SDK，只能放一段明确标注的演示动画——注意它拿奖励要靠服务端 dev-mode 自证通道，
   线上（dev-mode=false）这条演示路径不会发出任何奖励，别把它当成"可以刷广告"。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var A = {};
  CHEM.ad = A;

  /** 原生广告插件是否可用（APK 里由 Capacitor 注入）。 */
  A.native = function () {
    if (!window.CHEM.cloud || !CHEM.cloud.available()) return false;
    var C = window.Capacitor;
    if (C && C.Plugins && C.Plugins.ChemeraAd && typeof C.Plugins.ChemeraAd.showRewardVideo === "function") return true;
    return !!(window.ChemeraAd && typeof window.ChemeraAd.showRewardVideo === "function");
  };
  A.plugin = function () {
    var C = window.Capacitor;
    if (C && C.Plugins && C.Plugins.ChemeraAd) return C.Plugins.ChemeraAd;
    return window.ChemeraAd || null;
  };

  /* ---------------- 隐私同意（只影响壳，浏览器一条都不走） ----------------
     审核 5.8 与个保法要的是"同意之前不初始化任何第三方 SDK"，而这条判定必须落在原生：
     JS 里的一个布尔变量可以在控制台里被改掉，原生 SharedPreferences 里的同意记录不会。
     所以前端的职责只有一件——问一次、把玩家的选择交给原生，然后按原生回的状态决定放不放广告。 */

  /** cb(st)：{native, consented, hasProvider, mediaConfigured}。浏览器里 native=false，一切照旧。 */
  A.consentStatus = function (cb) {
    var p = CHEM.shell.inShell() ? A.plugin() : null;
    if (!p || typeof p.status !== "function") return cb({ native: false, consented: true });
    var req = p.status({});
    if (req && typeof req.then === "function") {
      req.then(function (r) { cb(norm(r)); }, function () { cb({ native: true, consented: false, broken: true }); });
      return;
    }
    cb(norm(req));
    function norm(r) {
      r = r || {};
      return {
        native: true,
        /* fail-closed：读不到同意就按"没同意"处理。
           写成 r.consented !== false 的话，插件版本对不上、字段改名、status() 回一个空对象
           都会变成"已同意"，而那正是审核要盯的那件事——同意之前初始化了第三方 SDK。
           代价只是老玩家可能被多问一次，比合规风险便宜得多。 */
        consented: r.consented === true,
        hasSdk: !!(r.hasProvider || r.bundled),
        mediaConfigured: r.mediaConfigured !== false,
        agreedAt: r.agreedAt || 0,
        revokedAt: r.revokedAt || 0
      };
    }
  };

  /** 把"同意"这件事交给原生记账（记账之后壳里才允许初始化 SDK）。 */
  A.agreeConsent = function (cb) {
    var p = CHEM.shell.inShell() ? A.plugin() : null;
    if (!p || typeof p.consent !== "function") return cb(true);
    var req = p.consent({ agree: true });
    if (req && typeof req.then === "function") {
      req.then(function () { cb(true); }, function () { cb(false); });
      return;
    }
    cb(true);
  };

  /**
   * 撤回同意（【设置-隐私】用）。撤回之后原生不再初始化、不再调起任何第三方 SDK，
   * 广告位会明确进入"暂不可用"；游戏本身照玩，因为本作除了广告没有别的第三方采集。
   */
  A.revokeConsent = function (cb) {
    var p = CHEM.shell.inShell() ? A.plugin() : null;
    if (!p || typeof p.consent !== "function") return cb(false);
    var req = p.consent({ agree: false });
    if (req && typeof req.then === "function") {
      req.then(function () { cb(true); }, function () { cb(false); });
      return;
    }
    cb(true);
  };

  /**
   * 首次进登录页时的那一次征求同意。文案与【设置-关于】里的政策正文同源（cloud.js 的 legal 表），
   * 不在这里重抄一遍条款：两处各写一份政策，迟早有一处是过期的。
   *
   * <p>必须给"不同意"一条路：只留一个"同意并进入"的按钮，在审核眼里等于强迫同意。
   * 这条路的代价是玩家退出游戏（本作纯在线，没有网络就没有可保存的进度），
   * 但它必须是**按得动的按钮**，而不是"按返回键退出"这种要玩家自己猜的说明。
   */
  A.askConsent = function (onAnswer, onRefuse) {
    var U = CHEM.ui, C = CHEM.cloud;
    if (!U || !C) return onAnswer(false);
    U.modal("<h3>开始之前，请先同意</h3><div class='ph'>本作需要联网保存进度，激励视频由第三方广告网络提供。"
      + "同意下列政策后，安卓壳才会初始化对应的 SDK；在此之前不会采集、也不会上传任何设备标识。</div>"
      + "<div class='consent-links'>"
      + "<button class='ghost' data-legal='user' type='button'>📜 用户协议</button>"
      + "<button class='ghost' data-legal='privacy' type='button'>🔒 隐私政策</button>"
      + "</div>"
      + "<button class='ok' id='consent-ok' type='button'>同意并进入实验室</button>"
      + "<button class='ghost' id='consent-no' type='button'>不同意并退出</button>"
      + "<div class='consent-note'>同意后仍可在【设置-隐私】里随时撤回。</div>", {
      // locked：点遮罩不关。这是一次必须给出答案的询问，不是一次可以顺手划掉的通知。
      locked: true,
      after: function (card) {
        card.querySelectorAll("[data-legal]").forEach(function (b) {
          b.onclick = function () { C.showLegal(b.dataset.legal); };
        });
        var ok = card.querySelector("#consent-ok");
        if (ok) ok.onclick = function () {
          A.agreeConsent(function (agreed) {
            if (!agreed) { U.toast("记录同意状态失败：本次不会初始化任何 SDK", "bad"); return; }
            U.closeModal();
            onAnswer(true);
          });
        };
        var no = card.querySelector("#consent-no");
        if (no) no.onclick = function () {
          U.closeModal();
          (onRefuse || function () { if (CHEM.shell.exitApp) CHEM.shell.exitApp(); })();
        };
      }
    });
  };

  /**
   * 播一段激励视频。onResult({ok, finished, code, msg})：ok 只表示"播放流程走完了"，
   * 是否真发奖励由服务端验签/结算决定，客户端的完成回调不作任何凭据。
   */
  A.play = function (opt, onResult) {
    var done = onResult || function () {};
    if (A.native()) {
      var p = A.plugin();
      var req = p.showRewardVideo({
        spaceId: opt.spaceId || "",
        // extra 必须原样带回服务端：回调就是靠这个随机串找回这次观看
        extra: opt.ticket,
        rewardName: opt.zh || "",
        rewardAmount: opt.amount || 0,
        userId: String(opt.uid || ""),
        transId: opt.ticket
      });
      if (req && typeof req.then === "function") {
        req.then(function (r) { done({ ok: true, finished: !r || r.finished !== false, code: (r && r.code) || 0 }); })
          .catch(function (e) { done({ ok: false, msg: (e && (e.message || e.code)) || "广告加载失败，稍后再试" }); });
      } else {
        done({ ok: true, finished: true });               // 插件用回调风格时由回调触发，这里按已播完处理
      }
      return;
    }
    A.demo(opt, done);
  };

  /** 演示播放器：3 秒倒计时，明确标注不是真实广告。 */
  A.demo = function (opt, done) {
    var U = CHEM.ui;
    var left = 3;
    U.modal("<h3>📺 激励视频（演示）</h3><div class='ph'>" + U.esc(opt.zh || "看完即可领取") +
      "·当前是浏览器预览，接入 TapADN 后这里放真实广告</div>" +
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
            var get = card.querySelector("#ad-get");
            get.classList.remove("hidden");
            get.onclick = function () { U.closeModal(); done({ ok: true, finished: true }); };
            card.querySelector("#ad-skip").onclick = function () { U.closeModal(); done({ ok: false, msg: "已跳过，未发奖励" }); };
          }
        }, 1000);
        card.querySelector("#ad-skip").onclick = function () {};
      }
    });
  };

  /* 等待服务器回调到账：真机上 SSV 是广告网络的服务器打过来的，客户端播完时往往还没到，
     所以轮询几次 ad.status；每次取帧服务端都会先 settle，到账即停。
     cb(ok, 本次到账明细, 最后一次视图)——视图顺手带回给广告中心，省一次往返。

     awaitRun 是这条递归的代号（H2）：它不在 game.js 的 stopTimers() 覆盖范围内，
     而它每 1.2 秒就发一次 ad.status——玩家中途退出登录、或者又去看第二条广告时，
     旧的递归必须闭嘴，否则它带着上一个身份的令牌继续取帧，还会把两条观看的奖励混成一次回报。
     cancelAwait() 只把代号推高一格并掐掉待触发的那一次 setTimeout：已经在飞的请求不追，
     它的回包会因为代号不符被丢弃，而服务端侧的幂等与带号写回兜着，不会因此丢奖励。

     被顶替／被取消的这一路仍然要给调用方一次 finish(false)：看广告的玩家有权知道"这一笔先按未到账处理"，
     而调用方（ui.js 的复活、panels.js 的双倍券）都是靠这个回调把按钮和状态放开的——静默丢弃等于把 UI 卡住。
     told 闩保证每个调用方恰好收到一次答复，包括"先到账后被取消"这种交错。 */
  var POLL_TRIES = 5, POLL_MS = 1200;
  var awaitRun = 0, awaitTimer = null;
  A.cancelAwait = function () {
    awaitRun++;
    if (awaitTimer) { clearTimeout(awaitTimer); awaitTimer = null; }
  };
  A.awaitReward = function (ticket, cb) {
    var run = ++awaitRun, tries = 0, last = null, told = false;
    function finish(ok, hit) {
      if (told) return;
      told = true;
      cb(!!ok, hit || null, last);
    }
    (function step() {
      if (run !== awaitRun) return finish(false);           // 被后来者或 cancelAwait 顶掉：不再发请求，但给个交代
      CHEM.game.call("ad.status", {}, function (r) {
        if (run !== awaitRun) return finish(false);         // 回调可能在 awaitReward 之后才回来，两处都要认
        if (r && r.ok !== false) last = r;
        var hit = findGrant(r, ticket);
        if (hit) return finish(true, hit);
        if (++tries >= POLL_TRIES) return finish(false);
        awaitTimer = setTimeout(step, POLL_MS);
      });
    })();
  };

  /** 在视图的 granted 明细里认出这次观看：服务端把签发时的 ticket 一起回传，匹配不会认错。 */
  function findGrant(view, ticket) {
    var list = (view && view.granted) || [];
    for (var i = 0; i < list.length; i++) if (list[i].ticket === ticket) return list[i];
    return null;
  }
})();
