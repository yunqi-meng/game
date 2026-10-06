/* 壳层桥接（Capacitor / 原生插件）：一处回答"我现在是不是跑在 APK 壳里、壳给了我什么"。
   设计原则：H5 浏览器里这些能力全部缺席，每一条都要有明确的服务端兜底或降级路径，
   绝不允许"没有原生插件就白屏"。反过来说，凡是影响结算的判断都不在这里做——
   这里只负责"把设备告诉我们的事转达上去"，采信与否由服务端决定。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var S = {};
  CHEM.shell = S;

  function cap() { return window.Capacitor || null; }

  /** 是否运行在 Capacitor 壳里（APK / IPA）。浏览器、以及 file:// 直开都返回 false。 */
  S.inShell = function () {
    var C = cap();
    return !!(C && (C.isNativePlatform || C.isCapacitor) &&
      (C.isNativePlatform ? C.isNativePlatform() : true));
  };
  S.platform = function () {
    var C = cap();
    if (!C || !S.inShell()) return "web";
    return C.getPlatform ? C.getPlatform() : "unknown";
  };

  /** 取原生插件：Capacitor 的注册表优先，其次全局变量（自定义插件两种挂法都在野外出现过）。 */
  S.plugin = function (name) {
    var C = cap();
    if (C && C.Plugins && C.Plugins[name]) return C.Plugins[name];
    return window[name] || null;
  };
  S.has = function (name, method) {
    var p = S.plugin(name);
    return !!(p && typeof p[method] === "function");
  };

  /** 把插件的 Promise/回调两种返回形状统一成 callback(err, data)。 */
  function settle(req, cb) {
    if (req && typeof req.then === "function") {
      req.then(function (d) { cb(null, d); }, function (e) { cb(e || new Error("插件调用失败")); });
      return;
    }
    cb(null, req);
  }

  /* ---------------- API 地址 ---------------- */

  /**
   * 后端基址。三级来源，优先级从高到低：
   *   1) 构建期注入的 window.CHEM_API_BASE（壳里由 js/app-config.js 给出，必须是绝对地址）；
   *   2) 壳内没配 → 空串并打一次警告：这时同源请求会打到 https://localhost，注定失败，
   *      与其让玩家看到莫名的"网络错误"，不如在启动页把配置问题说清楚；
   *   3) 浏览器 → 同源（空串）。
   */
  var warned = false;
  S.apiBase = function () {
    var raw = window.CHEM_API_BASE || "";
    var base = String(raw).replace(/\/+$/, "");
    if (S.inShell() && !/^https?:/.test(base)) {
      if (!warned) {
        warned = true;
        console.warn("[chemera] 壳内未配置有效 CHEM_API_BASE（现在是「" + raw + "」），"
          + "请求会落到壳自己的 https://localhost 上。请在打包前生成 js/app-config.js。");
      }
      return "";
    }
    return base;
  };

  /* ---------------- 令牌存放 ---------------- */

  /**
   * 令牌存储：壳里用 Capacitor Preferences（应用私有目录，跟随 APK 的沙箱），浏览器用 localStorage。
   *
   * <p>为什么不是"只用 Preferences"：H5 端根本没有这个插件；为什么不是"只用 localStorage"：
   * WebView 的 localStorage 会跟着"清除浏览数据"一起没，而玩家理解的"清除浏览器缓存"绝不该等于删号登录态。
   *
   * <p>实现是**同步读、异步镜像**：localStorage 仍然是运行期唯一同步来源（cloud.js 全程同步取值，
   * 改成异步会把每个调用点都拖成回调），Preferences 只在启动时 hydrate 一次、写入时尽力而为地跟上。
   * 镜像失败不影响游戏——最坏情况是下次冷启回到登录页，而不是奖励算错。
   */
  var STORE_GROUP = "chemera-auth";
  var PREFS;
  /**
   * @capacitor/preferences 的插件实例。它没有 Cordova 那套 configure({group})，
   * 数据本来就落在该应用私有的 SharedPreferences 文件里，隔离是免费的；
   * 但有些野外版本仍暴露 configure，所以按能力探测，不做无谓的强求。
   */
  function prefs() {
    // 只在"真的拿到了插件"时才缓存。插件是 Capacitor 在 WebView 初始化时挂上来的，
    // 理论上早于任何脚本；但一旦某次调用发生在它就绪之前（或那台设备上的挂法不同），
    // 把 null 缓存下来就等于整场会话静默关掉令牌镜像——那是这个安全垫唯一会被无声失效的方式。
    if (PREFS) return PREFS;
    var p = S.plugin("Preferences");
    PREFS = p && typeof p.configure === "function" ? p.configure({ group: STORE_GROUP }) : p;
    return PREFS;
  }

  S.store = {
    /** 壳内取回私有目录里的令牌并写回 localStorage；没有插件就直接结束。 */
    hydrate: function (done) {
      var p = prefs();
      if (!p || typeof p.keys !== "function") return done();
      settle(p.keys(), function (err, r) {
        // keys() 的形状只有 @capacitor/preferences 文档那一种：{keys:[...]}。
        // 兼容读一下 value 是历史包袱：早期自定义插件都按 {value} 回，写成 r.keys || r.value
        // 一行就把两种壳都吃下，而不是赌打包用的是哪一个。
        var list = (r && (r.keys || r.value)) || [];
        if (err || !list.length) return done();
        var pending = list.length;
        list.forEach(function (k) {
          settle(p.get({ key: k, defaultValue: "" }), function (e2, v) {
            try {
              if (!e2 && v && v.value) localStorage.setItem(k, v.value);
            } catch (ignore) {}
            if (--pending === 0) done();
          });
        });
      });
    },
    set: function (k, v) {
      var p = prefs(); if (!p || typeof p.set !== "function") return;
      try { p.set({ key: k, value: String(v) }); }
      catch (e) { /* 镜像失败不影响本次会话 */ }
    },
    /** 清会话时把壳内那份也删掉：否则玩家在登录页点了"退出"，重启后又自动登回去。 */
    clear: function (keys) {
      var p = prefs(); if (!p || typeof p.remove !== "function") return;
      (keys || []).forEach(function (k) {
        try { p.remove({ key: k }); } catch (e) {}
      });
    }
  };

  /* ---------------- TapTap 登录票据 ---------------- */

  /**
   * 向壳里的 TapTap 插件要一张登录票据，转成 callback(err, ticketJson)。
   * 浏览器里没有这个插件：调用方据此把入口隐藏，而不是报错。
   *
   * <p>票据只是"我去 TapTap 换过身份"的凭据，服务端会拿它去 open.tapapis.cn 验签，
   * 客户端在这里说什么都不是身份——所以这条链路允许壳随便传，风险由服务端兜。
   */
  S.taptapTicket = function (cb) {
    var p = S.plugin("ChemeraLogin");
    if (!p || typeof p.getTapTapTicket !== "function") return cb({ code: "unavailable" });
    settle(p.getTapTapTicket({}), function (err, r) {
      if (err) return cb(err);
      var ticket = r && (r.ticket !== undefined ? r.ticket : r.value);
      if (!ticket) return cb({ code: "empty", msg: (r && r.msg) || "未获得 TapTap 授权" });
      cb(null, typeof ticket === "string" ? ticket : JSON.stringify(ticket));
    });
  };
  S.hasTapTap = function () { return S.has("ChemeraLogin", "getTapTapTicket"); };

  /**
   * TapTap 入口"真的"能用吗：插件注册了不等于 SDK 在这个包里。
   *
   * <p>Capacitor 会把 ChemeraLogin 的 JS 代理注入进来（哪怕 app/libs 下没有 TapSDK 的 AAR），
   * 只看 hasTapTap() 就会摆出一个点了必然失败的按钮。所以这里异步问一次原生 status()，
   * 拿不到答案按"不可用"处理——少一个入口比给一个坏入口好。
   */
  S.tapReady = function (cb) {
    if (!S.hasTapTap()) return cb(false);
    var p = S.plugin("ChemeraLogin");
    if (!p || typeof p.status !== "function") return cb(true);
    settle(p.status({}), function (err, r) { cb(!err && !!r && r.hasProvider !== false); });
  };

  /* ---------------- 版本号 ---------------- */

  /** 壳内的 versionCode；浏览器返回 null（H5 没有"包版本"这个概念，版本门对它不适用）。 */
  S.appBuild = function (cb) {
    var p = S.plugin("ChemeraInfo");
    if (!p || typeof p.getBuild !== "function") return cb(null, null);
    settle(p.getBuild(), function (err, r) {
      if (err) return cb(err);
      var n = r && (r.build !== undefined ? r.build : r.value);
      cb(null, typeof n === "number" ? n : null);
    });
  };

  /* ---------------- 原生分享 ---------------- */

  /**
   * 分享一条战绩。三条路径按可用性降级：原生分享面板 → Web Share → 复制剪贴板。
   * 返回 callback(via)：via 是给 UI 的话术依据（"已调起系统分享"/"已复制到剪贴板"）。
   */
  S.share = function (title, text, cb) {
    var done = cb || function () {};
    var p = S.plugin("Share");
    if (p && typeof p.share === "function") {
      settle(p.share({ title: title, text: text }), function (err) {
        if (err) return fallback();
        done("native");
      });
      return;
    }
    fallback();
    function fallback() {
      if (navigator.share) {
        navigator.share({ title: title, text: text }).then(function () { done("web"); },
          function () { copy(); });
        return;
      }
      copy();
    }
    function copy() {
      var t = text;
      function okVia() { done("copy"); }
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(t).then(okVia, function () { legacy(okVia); });
        return;
      }
      legacy(okVia);
    }
    function legacy(ok) {
      try {
        var ta = document.createElement("textarea");
        ta.value = text; ta.setAttribute("readonly", ""); ta.style.position = "fixed"; ta.style.opacity = "0";
        document.body.appendChild(ta); ta.select(); document.execCommand("copy"); document.body.removeChild(ta);
      } catch (e) { /* 复制失败就当已经分享过：这条路径本来只是兜底 */ }
      ok();
    }
  };

  /* ---------------- 低端机降档 ---------------- */

  /**
   * 是否按低端机处理（粒子上限减半、关闭背景光斑）。
   *
   * <p>为什么允许玩家显式覆盖：真机千差万别，deviceMemory/hardwareConcurrency 在 WebView 里
   * 经常拿不到或拿错，玩家自己点【设置-省电模式】才是权威信号；这两个信号只做默认值。
   */
  var LOW_KEY = "chem-era-lowfx";
  S.lowFx = function () {
    var forced = localStorage.getItem(LOW_KEY);
    if (forced === "1") return true;
    if (forced === "0") return false;
    var cores = navigator.hardwareConcurrency || 8;
    var mem = navigator.deviceMemory || 8;
    // 壳里首屏由原生 WebView 渲染，再叠一层广告，比浏览器更容易掉帧，所以阈值放宽一档
    var floor = S.inShell() ? 6 : 4;
    return cores <= floor || mem <= 3;
  };
  S.setLowFx = function (on) {
    localStorage.setItem(LOW_KEY, on ? "1" : "0");
    S.applyLowFx();
  };
  /**
   * 把降档落到界面上：`html.chem-era-lowfx` 让 CSS 关掉背景光斑与长过渡动画，
   * fx 画布退回 1 倍分辨率、粒子减半。启动时和玩家切开关时各调一次。
   */
  S.applyLowFx = function () {
    var on = S.lowFx();
    if (document.documentElement) document.documentElement.classList.toggle("chem-era-lowfx", on);
    if (CHEM.fx && CHEM.fx.applyLowFx) CHEM.fx.applyLowFx();
    return on;
  };

  /* ---------------- 返回键 / 退出 ---------------- */

  /**
   * 安卓返回键的处理链由这里挂进去：原生在 onBackPressed 时调用 window.ChemeraBack()，
   * JS 返回 true 表示"这次返回我消化了"（关弹窗、关教程、回实验台），壳就不再执行系统默认行为。
   * 浏览器里没有这条链路（也不该抢用户的后退键），所以只在壳内注册。
   */
  S.onBack = null;
  window.ChemeraBack = function () { return S.onBack ? S.onBack() === true : false; };

  /** 请求壳退出应用；不在壳里返回 false，由调用方决定兜底（刷新／提示玩家手动关闭）。 */
  S.exitApp = function () {
    var p = S.plugin("ChemeraInfo");
    if (!p || typeof p.exit !== "function") return false;
    try { p.exit(); return true; } catch (e) { return false; }
  };
})();
