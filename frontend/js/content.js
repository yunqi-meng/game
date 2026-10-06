/* 内容远端化：从后端拉取 content bundle 覆盖内嵌 CHEM 数据，按 version 缓存到 localStorage 以省去重复下载。
   内嵌 js/data/*.js 只是首帧基线：后端不可达时游戏无法开始（见 main.js / game.js 的启动门），缓存仅用于加速。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var K = "chem-era-content-cache";

  // content_type -> CHEM 全局数组
  var CONTENT_MAP = {
    element: "ELEMENTS", compound: "COMPOUNDS", reaction: "REACTIONS", instrument: "INSTRUMENTS",
    process: "PROCESSES", danger: "DANGERS", room: "ROOMS", consumable: "CONSUMABLES",
    npc: "NPCS", shop: "DSHOP", achievement: "ACHIEVEMENTS", task: "DAILY_TASKS", quiz: "QUIZZES"
  };
  // app_config key -> CHEM 全局常量
  // G4 之后 level_exp / bench_max_lines / accident / quiz_grade_mult 也从写死的 Java 常数搬进了这一张表：
  // 客户端拿它们只用于"显示同一个数"（经验条、答题奖励、保险理赔比例），判定仍在服务端，
  // 但展示若继续写死，运营改完配置就会出现"进度条说还要 180、服务器按别的数升"的分裂。
  var CONFIG_MAP = {
    recharge: "RECHARGE", quality: "QUALITY", milestones: "MILESTONES", tier_names: "TIER_NAMES",
    tier_up_cost: "TIER_UP_COST", lab_upgrades: "LAB_UPGRADES", discover_bonus: "DISCOVER_BONUS",
    start_coins: "START_COINS", tutorial_coins: "TUTORIAL_COINS", quiz_reward: "QUIZ_REWARD",
    sell_rate: "SELL_RATE", buy_rate: "BUY_RATE", first_sell_bonus: "FIRST_SELL_BONUS",
    level_exp: "LEVEL_EXP_CFG", bench_max_lines: "BENCH_MAX_LINES",
    accident: "ACCIDENT", quiz_grade_mult: "QUIZ_GRADE_MULT"
  };

  function base() { return (window.CHEM_API_BASE || "").replace(/\/+$/, ""); }
  function available() { return /^https?:/.test(location.protocol); }

  function applyBundle(bundle) {
    if (!bundle) return false;
    var c = bundle.content || {}, g = 0;
    Object.keys(CONTENT_MAP).forEach(function (type) {
      var arr = c[type];
      if (Array.isArray(arr) && arr.length) { CHEM[CONTENT_MAP[type]] = arr; g++; }
    });
    var cfg = bundle.config || {};
    Object.keys(CONFIG_MAP).forEach(function (key) {
      if (key in cfg) CHEM[CONFIG_MAP[key]] = cfg[key];
    });
    try { localStorage.setItem(K, JSON.stringify({ version: bundle.version, bundle: bundle })); } catch (e) {}
    return g > 0;
  }

  function loadFromCache() {
    try {
      var o = JSON.parse(localStorage.getItem(K) || "null");
      if (o && o.bundle) return o;
    } catch (e) {}
    return null;
  }

  var CT = {};
  CHEM.content = CT;
  CT.isRemote = false;
  /** 内容只用来"画"：结算/引擎全在服务端，所以客户端拿旧一版多渲染一秒不会算错任何东西。 */
  CT.version = 0;
  /** 后台换版成功后回调（game.js 挂：重建物质查表 + 重绘当前页 + 提示一次）。 */
  CT.onRefresh = null;
  CT.isNewer = function (live) {
    return !!live && String(CT.version) !== String(live) && String(loadFromCache().version || "") !== String(live);
  };
  CT.load = function (cb) {
    cb = cb || function () {};
    if (!available()) return cb({ ok: false, reason: "offline" });
    var cached = loadFromCache();
    if (cached && cached.bundle) {
      // C3 的关键一步：命中缓存就当场进游戏，不等网络。
      // 老实现是"先问版本号、再决定要不要下"——那一次探测对一个什么都没变的玩家来说是纯等待，
      // 而它排在启动链最前面，弱网时就是白屏上最扎眼的那一秒。版本对不上的情况交给后台换版。
      CT.isRemote = applyBundle(cached.bundle);
      CT.version = cached.version;
      revalidate(cached);
      cb({ ok: true, fromCache: true, version: cached.version });
      return;
    }
    // 首装/清过数据：没有可先画的一份，只能等真下载；也别先问版本号（那次探测唯一用途是"能不能复用本地"）
    fetchBundle(null, cb);
  };

  /** 后台对版本：只在确实换版时再下一份，下完换数据并通知一次。任何失败都静默——玩家正在玩，不打扰。 */
  function revalidate(cached) {
    if (revalidating) return;
    revalidating = true;
    getJson(base() + "/api/content/version", function (e, j) {
      if (e) { revalidating = false; return; }
      var live = (j && j.data && j.data.version) || (j && j.version) || 0;
      revalidating = false;
      if (!CT.isNewer(live)) return;
      fetchBundle(cached, function (res) {
        if (!res || !res.ok) return;
        if (typeof CT.onRefresh === "function") CT.onRefresh(live, cached.version);
      });
    });
  }
  var revalidating = false;

  /* 带期限的 GET：H2 那一套超时口径从 cloud.js 借过来，不另起一份。
     没有它时（比如被单独拿来做回归、只加载本文件）退回裸 fetch，行为与从前一致。
     为什么这里也要管：首装／清过数据的玩家走的是 fetchBundle(null, cb)，
     那一次请求挂在弱网里就等于 cb 永远不来，G.start 的 afterBoth 永远凑不齐第二个 true，
     启动遮罩就转圈转到天荒地老——这是启动链上唯一一个"没有回音就没有任何失败页"的位置。 */
  function getJson(url, cb) {
    if (CHEM.cloud && CHEM.cloud.timedFetch) return CHEM.cloud.timedFetch(url, { method: "GET", headers: {} }, cb);
    fetch(url).then(function (r) { return r.json(); }).then(function (j) { cb(null, j); }, function () { cb({ net: true }); });
  }

  function fetchBundle(cached, cb) {
    getJson(base() + "/api/content/bundle", function (e, b) {
      if (e) {
        if (cached) applyBundle(cached.bundle);
        cb({ ok: false, reason: "fetch-failed", timeout: !!e.timeout });
        return;
      }
      CT.isRemote = applyBundle(b);
      CT.version = (b && b.version) || 0;
      cb({ ok: true, fromCache: false, version: b && b.version });
    });
  }
})();
