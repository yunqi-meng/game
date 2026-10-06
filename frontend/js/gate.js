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
    var tt = el("gate-taptap"); if (tt) tt.disabled = on;
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
      ? (CHEM.shell.hasTapTap() ? "TapTap 账号即正式存档：换设备重新登录即可继续，游客试玩进度可在游戏内一键绑定。" : "账号存档在服务器：换设备用同一账号登录即可继续。")
      : "注册即创建服务器账号；若之前用游客试玩过，请在游戏内【设置-账号】转正，进度会并入这个账号。";
    el("gate-go").textContent = label();
    err("");
  }

  function finish(j, what) {
    setBusy(false);
    var tt = el("gate-taptap"); if (tt) tt.textContent = "🎮 用 TapTap 账号登录";
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
    // 会话失效时可能正压着防沉迷等待页：先收起来，登录页要能看清
    if (CHEM.curfew.open()) CHEM.curfew.hide();
    mode = "login";
    var last = localStorage.getItem(LAST_KEY) || "";
    el("gate-u").value = last;
    el("gate-p").value = "";
    el("gate-n").value = "";
    el("gate-welcome").textContent = last ? "欢迎回来，" + last : "";
    el("gate-root").classList.remove("hidden");
    paint();
    // 壳外没有 TapTap SDK，按钮直接不出现：摆一个必然失败的入口比少一个入口更糟
    var tt = el("gate-taptap");
    if (tt) {
      tt.textContent = "🎮 用 TapTap 账号登录";
      tt.classList[CHEM.shell.hasTapTap() ? "remove" : "add"]("hidden");
      // 插件在 ≠ TapSDK 在：壳里没内置 AAR 时 status() 会回 hasProvider=false，这里再把按钮收掉
      if (CHEM.shell.hasTapTap()) {
        CHEM.shell.tapReady(function (ok) {
          var b = el("gate-taptap");
          if (b) b.classList[ok ? "remove" : "add"]("hidden");
        });
      }
    }
    err(notice || "");
    status();
    askConsentIfNeeded();
    // H5：原来这里无条件聚焦，而且是"见过这个人就聚焦密码框"。两件事都得改：
    //   ① 触屏上进页面就弹软键盘，键盘正好盖住【以游客身份进入】与底部条款——
    //      第一次来的人最需要的两个出口，被一个他没要求的自动动作挡住了。
    //   ② 读屏软件（TalkBack）会把焦点抢过去，玩家正在往下浏览就被打断。
    // 所以只在 pointer:fine（鼠标/键盘设备）上聚焦，而且聚焦的一直是用户名。
    if (window.matchMedia && window.matchMedia("(pointer: fine)").matches) {
      setTimeout(function () { el("gate-u").focus(); }, 120);
    }
  };

  /**
   * 壳内首次运行先取得隐私同意（审核 5.8 / 个保法：同意之前不初始化任何第三方 SDK）。
   *
   * <p>为什么在登录页问，而不是让原生弹一个系统对话框：政策正文只有一份，就在 cloud.js 的 legal 表里，
   * 玩家要能当场读——原生对话框里贴一段手抄的条款，两处迟早对不上。
   *
   * <p>为什么问的是原生而不是记在 localStorage：同意状态必须是客户端改不掉的记录，
   * 否则"我改了个 JS 变量就能让广告 SDK 在我同意之前起来"这种话就成立了。
   *
   * <p>读不到状态时**照问**（fail-closed）：以前这里是"问不出来就当已经同意过"，
   * 于是插件版本对不上、status() 字段改名、原生抛异常这三种情况都会让 SDK 在没同意记录的情况下起来，
   * 而那正是审核要查的那件事。多问一次的代价只是老玩家多点一下按钮。
   */
  function askConsentIfNeeded() {
    if (!CHEM.shell.inShell() || !CHEM.ad) return;
    CHEM.ad.consentStatus(function (st) {
      if (st && st.consented === true) return;
      CHEM.ad.askConsent(function () { /* 同意后由原生记账，并允许初始化 SDK */ });
    });
  }
  Gate.hide = function () { var r = el("gate-root"); if (r) r.classList.add("hidden"); };

  /**
   * 服务器可达性指示：一次 /api/healthz 探活，把"连不上""服务活着但库断了""一切正常"分开说。
   * 失败也允许玩家继续试（错误会在提交时具体报出），这一行只负责让他知道自己不是一个人出问题。
   */
  function status() {
    var dot = el("gate-dot"), txt = el("gate-server");
    if (!dot || !txt) return;
    dot.className = "dot"; txt.textContent = "正在检测服务器…";
    cloud().health(function (h) {
      if (!h || !h.reach) {
        dot.className = "dot down";
        txt.textContent = CHEM.shell.inShell()
          ? "连不上服务器：请检查手机网络后重试"
          : "连不上服务器：请先启动后端（http://localhost:8080）";
        return;
      }
      if (!h.ok) {
        dot.className = "dot down";
        txt.textContent = "服务器在运行，但数据库连不上（多半正在维护）：稍后再试，进度不会丢";
        return;
      }
      dot.className = "dot up";
      txt.textContent = "服务器在线 · 进度实时保存" + opsNote(h);
    });
  }
  /* 运营口径的告警只在开发机上说：壳里 location 是 https://localhost，拿它判环境会误伤，
     而正式域名下把"广告没配好"这类内部状态摊给玩家看更是错上加错。 */
  function opsNote(h) {
    if (CHEM.shell.inShell()) return "";
    if (!/^(localhost|127\.|\[?::1\]?$|0\.0\.0\.0)/.test(location.hostname)) return "";
    var out = [];
    if (h.ad && h.ad.ready === false) out.push(h.ad.devMode ? "奖励走演示通道" : "广告密钥未配置（看广告不会到账）");
    if (h.taptap && h.taptap.ready === false) out.push("TapTap 登录未就绪（" + (h.taptap.mode || "unconfigured") + "）");
    return out.length ? " · ⚠ " + out.join(" · ") : "";
  }

  /** TapTap 登录：票据由壳里的原生 SDK 提供，本服只负责把它交给服务端验签换令牌。 */
  function taptap() {
    if (busy) return;
    setBusy(true);
    el("gate-taptap").textContent = "正在唤起 TapTap 授权…";
    CHEM.shell.taptapTicket(function (e, ticket) {
      if (e) {
        setBusy(false);
        el("gate-taptap").textContent = "🎮 用 TapTap 账号登录";
        // 这个包压根没带 TapSDK：按钮留着只会让人反复点，直接收掉并说明退路
        if (e.code === "NO_SDK" || e.code === "unavailable") {
          el("gate-taptap").classList.add("hidden");
          return err("这个安装包未内置 TapTap 组件：请用账号密码或游客进入");
        }
        // 原生在同意之前不会 init TapSDK，所以这里拿到的 NO_CONSENT 不是失败，而是"还没问过"：
        // 补问一次，同意完直接重试授权，别让玩家的点击变成一句死提示。
        if (e.code === "NO_CONSENT") {
          return CHEM.ad.askConsent(function (ok) {
            if (!ok) return err("需要先同意隐私政策与用户协议才能用 TapTap 登录");
            setBusy(true);
            el("gate-taptap").textContent = "正在唤起 TapTap 授权…";
            CHEM.shell.taptapTicket(function (e2, t2) {
              setBusy(false);
              el("gate-taptap").textContent = "🎮 用 TapTap 账号登录";
              if (e2) return err(e2.message || "TapTap 授权未完成：可先用账号密码或游客进入");
              cloud().taptap(t2, function (j) { finish(j, "TapTap 登录"); });
            });
          });
        }
        return err(e.message || (e && e.code === "empty" ? e.msg : "") || "TapTap 授权未完成：可先用账号密码或游客进入");
      }
      cloud().taptap(ticket, function (j) { finish(j, "TapTap 登录"); });
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
    var tt = el("gate-taptap");
    if (tt) tt.onclick = taptap;
    /* 协议入口：上架要求"游戏内可读到政策"，登录页是第一处，注册完之前就得看得到 */
    document.querySelectorAll(".gate-legal [data-legal]").forEach(function (b) {
      b.onclick = function () { cloud().showLegal(b.dataset.legal); };
    });
    ["gate-u", "gate-p", "gate-n"].forEach(function (id) {
      el(id).addEventListener("input", function () { err(""); });
    });
  });
})();
