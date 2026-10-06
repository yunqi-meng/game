/* 青少年模式（防沉迷时段）的运行期表现层：把服务端给的闸门状态画成一个明确的等待页。
   这里没有任何"许可"语义——放行与否只在服务端 GameService.act 的第一行判过一次，
   本文件读到的 allowed / nextOpenAt 全部来自服务端，客户端改成本机时间也不会多得一分钟。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var K = {};
  CHEM.curfew = K;

  var WD = ["", "周一", "周二", "周三", "周四", "周五", "周六", "周日"];
  var timer = null, offset = 0, checking = false;

  function el(id) { return document.getElementById(id); }
  function view() { return K.last; }

  /** 服务器时钟校准：每次拿到状态都重新对一次表，倒计时以服务器时间为准。 */
  function sync(v) {
    if (v && typeof v.serverNow === "number") offset = v.serverNow - Date.now();
  }
  K.serverNow = function () { return Date.now() + offset; };

  /** 拉一次权威状态（只用于展示）。cb(view|null)：接口不通时给 null，调用方不该因此卡住玩家。 */
  K.refresh = function (cb) {
    if (!CHEM.cloud || !CHEM.cloud.available()) return cb(null);
    CHEM.cloud.curfewStatus(function (j) {
      if (!j || !j.ok) return cb(null);            // 字段由 cloud 平铺：allowed/serverNow/nextOpenAt 都在顶层
      K.last = j; sync(j); cb(j);
    });
  };

  /**
   * 意图请求被闸门挡下时由 game.js 调用：响应里带 tag=CURFEW 才算数。
   * 返回值表示"这条错误我处理过了吗"——处理过就不再弹普通错误 toast，免得两个提示叠着说同一件事。
   */
  K.handle = function (j) {
    if (!j || j.tag !== "CURFEW") return false;
    // 被拒的响应里没有整帧，只有文案与标签；窗口细节再问一次 status（服务端那边缓存了 minor，往返很轻）
    K.refresh(function (v) { K.show(v ? v : { enforced: true, minor: true, allowed: false, hint: j.msg }); });
    return true;
  };

  /* ---------------- 等待页 ---------------- */

  K.show = function (v) {
    var root = el("curfew-root");
    if (!root) return;
    K.last = v || {}; sync(K.last);
    root.classList.remove("hidden");
    render();
    if (!timer) timer = setInterval(tick, 1000);
  };
  K.hide = function () {
    var root = el("curfew-root"); if (root) root.classList.add("hidden");
    if (timer) { clearInterval(timer); timer = null; }
  };
  K.open = function () { return !!el("curfew-root") && !el("curfew-root").classList.contains("hidden"); };

  function windowText(w) {
    if (!w) return "";
    var days = (w.days || []).map(function (d) { return WD[d] || "第" + d + "天"; }).join("、");
    return (days ? days + " · " : "") + (w.from || "20:00") + "-" + (w.to || "21:00");
  }
  function left(ms) {
    if (ms <= 0) return "0 秒";
    var s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
    var out = [];
    if (h) out.push(h + " 小时");
    if (m) out.push(m + " 分");
    if (!h) out.push((s % 60) + " 秒");
    return out.join(" ");
  }

  function render() {
    var v = view(), box = el("curfew-card");
    if (!box) return;
    var soon = v.nextOpenAt ? v.nextOpenAt - K.serverNow() : null;
    box.innerHTML =
      '<div class="cf-emoji">🌙</div>' +
      '<h3>休息时间到</h3>' +
      '<p class="cf-hint">' + CHEM.ui.esc(v.hint || "未成年人仅在周五、周六、周日及法定节假日的 20:00-21:00 可游玩") + '</p>' +
      (soon !== null && soon > 0
        ? '<div class="cf-count">距下次可玩还有</div><div class="cf-clock" id="cf-left">' + CHEM.ui.esc(left(soon)) + '</div>'
        : soon !== null
          ? '<div class="cf-count">放行时段已到，正在重新连接…</div>'
          : '<div class="cf-count">当前配置没有放行时段，请联系客服</div>') +
      (v.window ? '<p class="cf-win">放行窗口：' + CHEM.ui.esc(windowText(v.window)) + '（按时区 ' + CHEM.ui.esc(v.window.zone || "Asia/Shanghai") + ' 计）</p>' : '') +
      '<p class="cf-why">这段时间里进度不会丢：服务器上的存档停在最后一次结算，时间到了回来接着做实验。</p>' +
      '<button class="ok" id="cf-ok">我知道了</button>' +
      (v.minor ? '<button class="ghost" id="cf-off">家长解除：关闭青少年模式</button>' : "") +
      '<button class="ghost" id="cf-out">退出游戏</button>' +
      (CHEM.shell.inShell() ? '' : '<button class="ghost" id="cf-recheck">我改过时间了，再问一次服务器</button>');
    el("cf-ok").onclick = function () { K.hide(); };
    /* 为什么等待页上也给关闭：本作没有实名认证接口可用，"是否未成年人"只有两个来源——
       玩家自己开启、或运营在后台标记，两边写的都是同一列，服务端分不出是谁开的。
       既然开启是自助的，关闭就必须同样自助，否则误开的玩家只能干等到下一个放行时段；
       代价是家长管不住会自己点关闭的孩子——这一点在方案里作为已知边界记录，
       真要硬约束得等实名认证或本地 PIN，不属于这一轮。 */
    var off = el("cf-off");
    if (off) off.onclick = function () {
      CHEM.ui.modal("<h3>关闭青少年模式？</h3><div class='ph'>关闭后不再限制可玩时段，游戏内所有玩法立即恢复。" +
        "这是家长监护工具，不是账号处罚——随时可以再打开。</div>" +
        '<button class="ok danger" id="cf-off-go">确认关闭</button><button class="ghost" data-close>再想想</button>', {
        after: function (card) {
          card.querySelector("#cf-off-go").onclick = function () {
            CHEM.ui.closeModal();
            K.setMinorOff(function (j) {
              if (!j || !j.ok) return CHEM.ui.toast((j && j.msg) || "关闭失败，稍后再试", "bad");
              K.hide();
              CHEM.ui.toast("🔓 青少年模式已关闭，正在恢复游戏", "good");
              if (CHEM.panels && CHEM.panels.curfewInvalidate) CHEM.panels.curfewInvalidate();
              CHEM.game.start();
            });
          };
        }
      });
    };
    el("cf-out").onclick = function () {
      if (CHEM.shell.inShell() && CHEM.shell.has("ChemeraInfo", "exit")) {
        CHEM.shell.plugin("ChemeraInfo").exit();
        return;
      }
      try { window.close(); } catch (e) {}
      location.reload();
    };
    var rc = el("cf-recheck");
    if (rc) rc.onclick = function () {
      if (checking) return;
      checking = true; rc.textContent = "正在询问服务器…";
      K.refresh(function (v2) {
        checking = false;
        if (v2 && v2.allowed) { K.hide(); CHEM.game.start(); return; }
        rc.textContent = "服务器说还得再等：" + (v2 && v2.nextOpenAt ? left(v2.nextOpenAt - K.serverNow()) : "暂无放行时段");
        render();
      });
    };
  }

  function tick() {
    var v = view();
    if (!v || !v.nextOpenAt) return;
    var leftMs = v.nextOpenAt - K.serverNow();
    var n = el("cf-left");
    if (n) n.textContent = left(leftMs);
    if (leftMs > 60000) return;                    // 还远，不用反复问服务器
    if (checking) return;
    checking = true;
    K.refresh(function (v2) {
      checking = false;
      if (v2 && v2.allowed) {
        K.hide();
        CHEM.ui.toast("🌅 放行时段到了，可以继续实验了", "good");
        CHEM.game.start();                          // 重新对齐一次权威帧，避免拿旧内存态去发意图
      }
    });
  }

  /* ---------------- 玩家自助开关（设置页用） ---------------- */

  /** 开/关青少年模式：写的是服务端那一列，回来的是权威视图，所以点完直接按视图刷新界面。 */
  K.setMinor = function (on, cb) {
    CHEM.cloud.setMinor(on, function (j) {
      if (j && j.ok) {
        K.last = j; sync(j);
        if (!j.allowed) K.show(j);
      }
      cb && cb(j);
    });
  };

  /** 家长解除（等待页上那一个）：关掉之后必须自己把页收起，不能再走"不放行就弹页"那条逻辑。 */
  K.setMinorOff = function (cb) {
    CHEM.cloud.setMinor(false, function (j) {
      if (j && j.ok) { K.last = j; sync(j); K.hide(); }
      cb && cb(j);
    });
  };
})();
