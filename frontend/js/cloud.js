/* 账号与权威游戏 API：对接 Spring Boot 后端。在线游戏：无本地真源，file:// 打开不可用。
   Base URL：默认同源；跨域部署或 APK 壳内由 js/app-config.js 设置 window.CHEM_API_BASE="https://host"，
   解析规则集中在 js/shell.js（壳内没配就等于配错，宁可警告也不要让玩家对着 https://localhost 猜）。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var C = {};
  CHEM.cloud = C;

  var TOKEN_KEY = "chem-era-token";
  var REFRESH_KEY = "chem-era-refresh";
  var USER_KEY = "chem-era-user";
  var KEYS = [TOKEN_KEY, REFRESH_KEY, USER_KEY];

  function base() { return CHEM.shell ? CHEM.shell.apiBase() : (window.CHEM_API_BASE || "").replace(/\/+$/, ""); }
  C.available = function () {
    // 壳里的 WebView 协议是 https://localhost（或 http），所以协议判断之外再认一次"是不是壳"
    if (CHEM.shell && CHEM.shell.inShell()) return true;
    return /^https?:/.test(location.protocol);
  };
  C.user = function () { return localStorage.getItem(USER_KEY); };
  C.logged = function () { return !!localStorage.getItem(TOKEN_KEY); };
  C.token = function () { return localStorage.getItem(TOKEN_KEY); };

  /* 令牌读写只走这一层：localStorage 是同步真源（cloud.js 全程同步取值），
     壳内的 Preferences 是它的镜像，用于扛住"清了浏览器数据但没清应用数据"这类误伤。 */
  function put(k, v) {
    localStorage.setItem(k, v);
    if (CHEM.shell) CHEM.shell.store.set(k, v);
  }
  function dropAll() {
    KEYS.forEach(function (k) { localStorage.removeItem(k); });
    if (CHEM.shell) CHEM.shell.store.clear(KEYS);
  }
  C.keys = KEYS;

  /* 统一响应解包：后端返回 {ok,code,data,msg}，data 的字段平铺到结果对象上。
     tag 是机器可读标签（如 CURFEW=被防沉迷时段挡下），客户端据此分流，不去匹配会被改词的文案。 */
  function unwrap(j) {
    if (j && typeof j === "object" && ("ok" in j) && ("data" in j || "code" in j)) {
      var d = (j.data && typeof j.data === "object" && !Array.isArray(j.data)) ? j.data : {};
      var out = Object.assign({}, d, { ok: j.ok !== false, code: j.code, msg: j.msg });
      if (j.tag) out.tag = j.tag;
      return out;
    }
    return j || { ok: false, msg: "服务器无响应" };
  }

  function storeTokens(o) {
    if (o && o.ok && o.token) {
      put(TOKEN_KEY, o.token);
      if (o.refresh) put(REFRESH_KEY, o.refresh);
      if (o.user) put(USER_KEY, o.user);
    }
  }
  /** 退出/失效时清空本地会话（唯一的本地状态：令牌）。 */
  C.clearSession = function () { dropAll(); };

  /**
   * 带期限的一次请求（H2）：cb(err, json)；err 为 null 就是拿到了 JSON 正文（业务失败另算），
   * 正文解析不出来时 json 是 null。
   *
   * <p>为什么必须有：弱网上 fetch 可以挂着几十秒既不 resolve 也不 reject，而意图队列是串行的
   * （{@code game.js} 的 pump 一次只放行一个）——头一个挂住，后面全停，玩家看到的是"点了没反应"，
   * 而 {@code game.js} 里那套退避／拉帧对齐根本不会被触发，因为它等的是"请求失败回来"。
   * 期限由这一层自己给：到点主动放弃，回调一条 {@code err.timeout=true}，让上层照旧分流
   * （读意图退避重发、写意图拉一帧问服务端）。
   *
   * <p>回调恰好一次：靠 {@code fire} 上的闩。三个来源都可能落地——超时、正文读回来、请求被 reject
   * （abort 之后那个 reject 一定会来，它是迟到者）——只有第一个说话算数。
   * 早先那种 {@code .then(cb).catch(cb)} 的链式写法在 cb 自己抛错时会被 catch 再叫一次，
   * 一次请求点两下购买，正是这一层要消灭的东西。
   *
   * <p>先读体再松手：响应头到了而 chunk 卡住同样是"没回音"，只掐 headers 等于没做。
   *
   * <p>没有 AbortController 的老 WebView（iOS 11／Android 4.x 那一档）原样发请求，
   * 行为与 H2 之前完全一致：宁可没有超时，也不许因为探测不到 API 就没有网络。
   */
  C.timeoutMs = 12000;                 // 可变：回归脚本把它调小，不然一条用例要真等 12 秒
  function timedFetch(url, opts, cb) {
    var done = false;
    function fire(err, json) { if (done) return; done = true; cb(err, json); }
    if (typeof AbortController !== "function") {
      fetch(url, opts).then(function (r) { return r.json().catch(function () { return null; }); })
        .then(function (j) { fire(null, j); }, function () { fire({ net: true }); });
      return;
    }
    var ac = new AbortController();
    var t = setTimeout(function () {
      try { ac.abort(); } catch (e) { /* 少数实现 abort 会抛；闩已经落下，报的是超时 */ }
      fire({ timeout: true });
    }, Math.max(50, C.timeoutMs | 0));
    fetch(url, Object.assign({}, opts || {}, { signal: ac.signal })).then(function (r) {
      return r.json().catch(function () { return null; });
    }).then(function (j) { clearTimeout(t); fire(null, j); }, function () { clearTimeout(t); fire({ net: true }); });
  }
  /* 内容库（js/content.js）共用这一个期限：两条路都可能在启动链上挂住不回，
     分开两套超时口径只会漂移成"一个有超时一个没有"。 */
  C.timedFetch = timedFetch;

  function raw(path, method, body, cb) {
    var opts = { method: method, headers: {} };
    if (body !== undefined) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); }
    var t = localStorage.getItem(TOKEN_KEY);
    if (t) opts.headers.Authorization = "Bearer " + t;
    timedFetch(base() + path, opts, function (e, j) {
      if (e) {
        /* 超时与连不上分开说：前者是"我们放弃了这一次"，玩家要知道进度到底动没动。
           刻意不带 code 也不带 tag——game.js 靠这两样缺席判定"这次请求根本没得到答复"，
           才会走退避重发／拉帧对齐那条静默路径。 */
        cb(e.timeout
          ? { ok: false, timeout: true, msg: "服务器太久没回应（" + Math.round(C.timeoutMs / 1000) + " 秒），进度未变动" }
          : { ok: false, msg: "网络错误：请确认后端已启动" });
        return;
      }
      cb(j === null || j === undefined ? { ok: false, msg: "响应解析失败" } : j);
    });
  }

  /* 访问令牌过期时用刷新令牌自动续期并重试一次。 */
  function api(path, method, body, cb) {
    // 走到这里只剩一种情况：既不是 http(s) 页面也不在壳里（比如直接双击 index.html）。
    // 文案对壳内玩家也得成立，所以不再提"http 访问后端"这种只有开发者听得懂的话。
    if (!C.available()) return cb({ ok: false, msg: "网络连接失败，请检查网络后重试" });
    raw(path, method, body, function (j0) {
      var j = unwrap(j0);
      if ((!j.ok) && (j.code === 401 || j.ok === false && /过期|无效/.test(j.msg || "")) && localStorage.getItem(REFRESH_KEY)) {
        raw("/api/auth/refresh", "POST", { refresh: localStorage.getItem(REFRESH_KEY) }, function (rj) {
          var r = unwrap(rj);
          if (r.ok && r.token) { storeTokens(r); raw(path, method, body, function (jj) { cb(unwrap(jj)); }); }
          else { C.clearSession(); cb(j); }
        });
        return;
      }
      storeTokens(j);
      cb(j);
    });
  }

  /* ---------- 账号 ---------- */
  /** 注册新账号（昵称可空，服务端回退成用户名）。 */
  C.register = function (u, p, n, cb) {
    if (cb === undefined && typeof n === "function") { cb = n; n = null; }
    api("/api/auth/register", "POST", { user: u, pass: p, nickname: n || null }, cb);
  };
  C.login = function (u, p, cb) { api("/api/auth/login", "POST", { user: u, pass: p }, cb); };
  C.guest = function (cb) { api("/api/auth/guest", "POST", {}, cb); };
  /** 游客转正：带当前游客令牌注册，服务端把游客进度并入新账号。 */
  C.upgrade = function (u, p, n, cb) {
    if (cb === undefined && typeof n === "function") { cb = n; n = null; }
    api("/api/auth/upgrade", "POST", { user: u, pass: p, nickname: n || null }, cb);
  };
  C.logout = function (cb) {
    api("/api/auth/logout", "POST", { refresh: localStorage.getItem(REFRESH_KEY) }, function (j) {
      C.clearSession(); cb && cb(j);
    });
  };
  C.changePass = function (oldP, newP, cb) { api("/api/auth/pass", "POST", { old: oldP, "new": newP }, cb); };
  C.deleteAccount = function (pass, cb) {
    api("/api/auth/delete", "POST", { pass: pass }, function (j) {
      if (j.ok) C.clearSession();
      cb(j);
    });
  };
  C.me = function (cb) { api("/api/me", "GET", undefined, cb); };

  /* ---------- 权威游戏意图 ---------- */
  C.game = function (intent, params, cb) { api("/api/game/" + intent, "POST", params || {}, cb); };
  C.gameState = function (cb) { api("/api/game/state", "GET", undefined, cb); };

  /** 重置进度：服务端删档后重新拉取首帧（由 game.js 传入回调）。 */
  C.wipeNotice = function () { return "进度保存在服务器：重置即清空服务器存档。"; };

  /* ---------- 合规与运行状态 ---------- */
  /**
   * 青少年模式的权威视图（只读）。刻意只当展示用：真正的拦截在 GameService 每一行的时段判定里，
   * 把这里的返回改成 allowed:true 也拿不到一个奖励。提供它只是为了让玩家看见"还要等多久"。
   */
  C.curfewStatus = function (cb) { api("/api/curfew/status", "GET", undefined, cb); };
  /** 玩家自助开关青少年模式；返回的是服务端作废缓存后重查的视图，不是回显玩家点的那个值。 */
  C.setMinor = function (on, cb) { api("/api/me/minor", "POST", { on: !!on }, cb); };
  /**
   * 举报 / 反馈（G2 的客户端那一半）。
   *
   * <p>只发 kind + ref + reason：判定全在 {@code ReportService}（类型闭合、对象是否真存在、
   * 同一对象只留一条在途、每人每日上限），这里不复制规则，只做"空值就别发出去"这一件事——
   * 服务端对空 ref 的回绝文案和空理由的回绝文案不同，让玩家先看见自己少填了哪一格。
   *
   * <p>刻意没有 target 参数：那两类（举报昵称／举报行为）要一个真实存在的 uid，
   * 而当前版本玩家看不到任何别人的昵称或行为（好友与榜单是 NPC），传谁都只会拿到"查不到这位玩家"。
   *
   * @param cb 成功时拿到 {ok:true, kind, openToday, dailyMax}——服务端给的是"今天还能提几条"，不是内部 id
   */
  C.report = function (kind, ref, reason, cb) {
    api("/api/report", "POST", { kind: kind, ref: ref, reason: reason }, cb);
  };
  /**
   * TapTap 登录：票据由安卓壳里的原生 SDK 提供，服务端验签通过才发本服令牌。
   * 带着游客令牌来（拦截器按可选上下文读），就把试玩进度并进正式账号——这是"一键转正"。
   */
  C.taptap = function (ticket, cb) { api("/api/auth/taptap", "POST", { ticket: ticket }, cb); };
  /** 版本门：匿名可调（旧包进不来时也得能拿到升级说明）。 */
  C.appVersion = function (cb) { api("/api/app/version", "GET", undefined, cb); };

  /**
   * 运维探活。这个接口的返回不是 {ok,code,data} 信封，不能走 api() 的统一解包，也不带令牌——
   * 它是启动失败页唯一的事实来源：先分清"连都没连上"和"服务活着但库断了"，才谈得上给人话。
   * 回调：{ok, reach, db, ad, taptap, uptimeSec, msg}
   */
  C.health = function (cb) {
    if (!C.available()) return cb({ ok: false, reach: false, msg: "未在可联网环境打开" });
    timedFetch(base() + "/api/healthz", { method: "GET", headers: {} }, function (e, j) {
      // 探活也走同一个期限：这一条是启动失败页唯一的事实来源，挂着不回就等于让玩家对着转圈看
      if (e && e.timeout) return cb({ ok: false, reach: false, timeout: true, msg: "服务器太久没回应" });
      if (e) return cb({ ok: false, reach: false, msg: "连不上服务器" });
      if (!j || typeof j !== "object") return cb({ ok: false, reach: true, msg: "服务应答异常" });
      cb({
        ok: !!j.ok, reach: true, db: !!j.db, dbError: j.dbError || "",
        ad: j.ad || null, taptap: j.taptap || null, uptimeSec: j.uptimeSec
      });
    });
  };

  /* ---------- 协议文案 ----------
     上架要求游戏内看得到的两份文书就写在这里（壳里的首次弹窗用同一份文案，不另起一套）。
     只列本作真实存在的东西：账号标识、服务器存档、行为埋点、激励视频广告、青少年时段限制。
     没做的不写（无内购计费、不采集通讯录/位置/相机）。 */
  C.legal = {
    updated: "2026-10-05",
    terms: {
      title: "用户协议",
      items: [
        "本作是免费的在线沙盒游戏：进度、资产与图鉴全部存在服务器上，服务端是唯一的真源。断网时不可玩，重置进度即清空服务器存档。",
        "登录方式有三种：TapTap 账号（安卓包内）、账号密码、以及游客试玩。游客档可随时一键绑定为正式账号，进度并入不丢失；未转正的游客档在清除应用数据后无法找回。",
        "游戏内所有需要投入的解锁项均通过观看激励视频换取积分获得，不设任何充值入口、不展示人民币标价，也不会向你收取费用。",
        "请通过正当方式游玩。使用外挂、篡改协议或刷广告回调取得的进度，服务端会拒绝结算，运营有权回滚或封禁该账号。",
        "游戏内容为虚拟化学实验，元素、反应与危险标签均为教学演绎，请勿在现实中模仿操作任何危险品。"
      ]
    },
    privacy: {
      title: "隐私政策",
      items: [
        "我们收集什么：账号标识（用户名/昵称，或 TapTap 的匿名开放标识）、游戏存档（金币、钻石、图鉴、房间、挂单等玩法数据）、以及有限的行为埋点（打开页面、点击、完成实验这类事件名与少量参数，用于看板和留存统计）。",
        "我们不收集：真实姓名、身份证号、手机号、通讯录、位置、相机与麦克风内容。本作没有实名认证接口，未成年人身份来自玩家自助开启青少年模式或运营标记。",
        "数据存储在哪里：本作的后端服务器。存档不写入第三方云服务，不用于广告定向画像。",
        "广告：激励视频由第三方广告网络（TapADN / Dirichlet SSP）提供播放，奖励的到账判定走我们服务端与广告平台的服务端验签。广告 SDK 可能使用其自身的设备标识，具体见其隐私条款；未同意本政策前，安卓壳不会初始化任何 SDK。",
        "未成年人保护：开启青少年模式后，服务端按国家规定的可玩时段（周五六日及法定节假日 20:00–21:00）拦截玩法请求，时段外只保留查看类页面。玩家可随时在游戏内【设置】自行关闭该模式。",
        "你可以做什么：在游戏内【设置】随时关闭青少年模式；可以要求停止收集行为数据（联系我们或在反馈渠道说明即可，服务端有对应开关，关停后看板不再增长）；也可以注销账号，注销即刻删除服务器存档且不可恢复。"
      ]
    }
  };
  /** 把协议渲染成 modal 正文（登录页与设置页共用一处文案）。 */
  C.showLegal = function (which) {
    var U = CHEM.ui, d = C.legal[which];
    if (!U || !U.modal || !d) return;
    U.modal("<h3>" + d.title + "</h3><div class='ph'>最近更新：" + C.legal.updated + "</div>" +
      "<div class='legal'>" + d.items.map(function (t) { return "<p>" + U.esc(t) + "</p>"; }).join("") + "</div>");
  };

  /* 后端可达性探测（供设置页展示） */
  C.ping = function (cb) { api("/api/content/version", "GET", undefined, cb); };

  /* 埋点：仅登录用户上报，失败静默（不影响游戏）。 */
  C.track = function (event, props) {
    if (!C.available() || !C.logged()) return;
    raw("/api/analytics/event", "POST", { event: event, props: props || null }, function () {});
  };
  CHEM.track = C.track;
})();
