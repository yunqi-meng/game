/* 账号与权威游戏 API：对接 Spring Boot 后端。在线游戏：无本地真源，file:// 打开不可用。
   Base URL：默认同源；跨域部署时设置 window.CHEM_API_BASE="http://host:8080"。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var C = {};
  CHEM.cloud = C;

  var TOKEN_KEY = "chem-era-token";
  var REFRESH_KEY = "chem-era-refresh";
  var USER_KEY = "chem-era-user";

  function base() { return (window.CHEM_API_BASE || "").replace(/\/+$/, ""); }
  C.available = function () { return /^https?:/.test(location.protocol); };
  C.user = function () { return localStorage.getItem(USER_KEY); };
  C.logged = function () { return !!localStorage.getItem(TOKEN_KEY); };
  C.token = function () { return localStorage.getItem(TOKEN_KEY); };

  /* 统一响应解包：后端返回 {ok,code,data,msg}，data 的字段平铺到结果对象上。 */
  function unwrap(j) {
    if (j && typeof j === "object" && ("ok" in j) && ("data" in j || "code" in j)) {
      var d = (j.data && typeof j.data === "object" && !Array.isArray(j.data)) ? j.data : {};
      return Object.assign({}, d, { ok: j.ok !== false, code: j.code, msg: j.msg });
    }
    return j || { ok: false, msg: "服务器无响应" };
  }

  function storeTokens(o) {
    if (o && o.ok && o.token) {
      localStorage.setItem(TOKEN_KEY, o.token);
      if (o.refresh) localStorage.setItem(REFRESH_KEY, o.refresh);
      if (o.user) localStorage.setItem(USER_KEY, o.user);
    }
  }
  /** 退出/失效时清空本地会话（唯一的本地状态：令牌）。 */
  C.clearSession = function () {
    localStorage.removeItem(TOKEN_KEY); localStorage.removeItem(REFRESH_KEY); localStorage.removeItem(USER_KEY);
  };

  function raw(path, method, body, cb) {
    var opts = { method: method, headers: {} };
    if (body !== undefined) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); }
    var t = localStorage.getItem(TOKEN_KEY);
    if (t) opts.headers.Authorization = "Bearer " + t;
    fetch(base() + path, opts)
      .then(function (r) { return r.json().catch(function () { return { ok: false, msg: "响应解析失败" }; }); })
      .then(cb)
      .catch(function () { cb({ ok: false, msg: "网络错误：请确认后端已启动" }); });
  }

  /* 访问令牌过期时用刷新令牌自动续期并重试一次。 */
  function api(path, method, body, cb) {
    if (!C.available()) return cb({ ok: false, msg: "需通过 http 访问后端" });
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

  /* 后端可达性探测（供设置页展示） */
  C.ping = function (cb) { api("/api/content/version", "GET", undefined, cb); };

  /* 埋点：仅登录用户上报，失败静默（不影响游戏）。 */
  C.track = function (event, props) {
    if (!C.available() || !C.logged()) return;
    raw("/api/analytics/event", "POST", { event: event, props: props || null }, function () {});
  };
  CHEM.track = C.track;
})();
