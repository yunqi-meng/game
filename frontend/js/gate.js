/* 登录 / 注册页（鉴权门）。未登录时的唯一入口：登录、注册、游客模式三条路径
   都要拿到服务端令牌后才交给 onReady() 进游戏；本地除令牌外不存任何进度。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var Gate = {};
  CHEM.gate = Gate;

  var LAST_KEY = "chem-era-last-user";
  /* 与后端 AuthService.USER 保持同一规则，避免玩家提交后才收到提示 */
  var NAME = /^[\w一-龥]{2,24}$/;
  var mode = "login", busy = false, ready = null;

  function el(id) { return document.getElementById(id); }
  function cloud() { return CHEM.cloud; }

  function err(msg) {
    var box = el("gate-err");
    box.textContent = msg || "";
    box.classList[ msg ? "remove" : "add"]("hidden");
  }
  function label() { return mode === "login" ? "登录并进入实验室" : "注册并进入实验室"; }
  function setBusy(on) {
    busy = on;
    el("gate-form").classList[on ? "add" : "remove"]("gate-busy");
    el("gate-guest").disabled = on;
    el("gate-go").textContent = on ? "请稍候…" : label();
  }
  function paint() {
    var login = mode === "login";
    el("gate-tabs").querySelectorAll("button").forEach(function (b) {
      b.classList[b.dataset.m === mode ? "add" : "remove"]("on");
    });
    el("gate-nrow").classList[login ? "add" : "remove"]("hidden");
    el("gate-forgot").classList[login ? "remove" : "add"]("hidden");
    el("gate-help").classList.add("hidden");
    el("gate-forgot").textContent = "忘记密码？";
    el("gate-p").setAttribute("autocomplete", login ? "current-password" : "new-password");
    el("gate-tip").textContent = login
      ? "账号存档在服务器：换设备用同一账号登录即可继续。"
      : "注册即创建服务器账号；若之前用游客试玩过，请在游戏内【设置-账号】转正，进度会并入这个账号。";
    el("gate-go").textContent = label();
    err("");
  }

  function finish(j, what) {
    setBusy(false);
    if (!j || !j.ok || !j.token) return err((j && j.msg) || (what + "失败：请确认后端已启动"));
    var u = el("gate-u").value.trim();
    if (u) localStorage.setItem(LAST_KEY, u);
    Gate.hide();
    ready();
  }

  function submit() {
    if (busy) return;
    var u = el("gate-u").value.trim(), p = el("gate-p").value, n = el("gate-n").value.trim();
    if (!u) return err("请输入用户名");
    if (!NAME.test(u)) return err("用户名需 2-24 位，仅限中文、字母、数字或下划线");
    if (p.length < 6) return err("密码至少 6 位");
    setBusy(true);
    if (mode === "login") return cloud().login(u, p, function (j) { finish(j, "登录"); });
    cloud().register(u, p, n, function (j) { finish(j, "注册"); });
  }

  /** @param onReady 拿到令牌后进游戏的回调（game.js 传入 G.start）；notice 是要显示在表单上的原因（如登录态失效） */
  Gate.show = function (onReady, notice) {
    ready = onReady || function () {};
    if (!el("gate-root")) return ready();
    mode = "login";
    var last = localStorage.getItem(LAST_KEY) || "";
    el("gate-u").value = last;
    el("gate-p").value = "";
    el("gate-n").value = "";
    el("gate-welcome").textContent = last ? "欢迎回来，" + last : "";
    el("gate-root").classList.remove("hidden");
    paint();
    err(notice || "");
    status();
    setTimeout(function () { el(last ? "gate-p" : "gate-u").focus(); }, 120);
  };
  Gate.hide = function () { var r = el("gate-root"); if (r) r.classList.add("hidden"); };

  /** 服务器可达性指示：失败也让玩家继续试，错误会在提交时具体报出。 */
  function status() {
    var dot = el("gate-dot"), txt = el("gate-server");
    dot.className = "dot"; txt.textContent = "正在检测服务器…";
    cloud().ping(function (j) {
      var up = !!(j && j.ok);
      dot.className = "dot " + (up ? "up" : "down");
      txt.textContent = up ? "服务器在线 · 进度实时保存" : "连不上服务器：请先启动后端（http://localhost:8080）";
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    var form = el("gate-form");
    if (!form) return;
    form.addEventListener("submit", function (e) { e.preventDefault(); submit(); });
    el("gate-tabs").onclick = function (e) {
      var b = e.target.closest("button[data-m]");
      if (!b || busy) return;
      mode = b.dataset.m;
      paint();
    };
    el("gate-eye").onclick = function () {
      var p = el("gate-p"), shown = p.getAttribute("type") === "text";
      p.setAttribute("type", shown ? "password" : "text");
      el("gate-eye").textContent = shown ? "👁" : "🙈";
    };
    /* 没有邮件服务可自助验证身份，所以这里只给找回路径，不假装能重置 */
    el("gate-forgot").onclick = function () {
      var h = el("gate-help"), on = h.classList.toggle("hidden") === false;
      el("gate-forgot").textContent = on ? "收起" : "忘记密码？";
    };
    el("gate-guest").onclick = function () {
      if (busy) return;
      setBusy(true);
      el("gate-guest").textContent = "正在开启游客档…";
      cloud().guest(function (j) {
        el("gate-guest").textContent = "🎈 以游客身份进入";
        if (!j || !j.ok || !j.token) { setBusy(false); return err((j && j.msg) || "游客档创建失败：请确认后端已启动"); }
        localStorage.removeItem(LAST_KEY);
        setBusy(false);
        Gate.hide();
        ready();
      });
    };
    ["gate-u", "gate-p", "gate-n"].forEach(function (id) {
      el(id).addEventListener("input", function () { err(""); });
    });
  });
})();
