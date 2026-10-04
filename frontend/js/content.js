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
  var CONFIG_MAP = {
    recharge: "RECHARGE", quality: "QUALITY", milestones: "MILESTONES", tier_names: "TIER_NAMES",
    tier_up_cost: "TIER_UP_COST", lab_upgrades: "LAB_UPGRADES", discover_bonus: "DISCOVER_BONUS",
    start_coins: "START_COINS", tutorial_coins: "TUTORIAL_COINS", quiz_reward: "QUIZ_REWARD",
    sell_rate: "SELL_RATE", buy_rate: "BUY_RATE", first_sell_bonus: "FIRST_SELL_BONUS"
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
  CT.version = function () { var o = loadFromCache(); return o ? o.version : 0; };

  CT.load = function (cb) {
    cb = cb || function () {};
    if (!available()) return cb({ ok: false, reason: "offline" });
    var cached = loadFromCache();
    fetch(base() + "/api/content/version").then(function (r) { return r.json(); }).then(function (j) {
      var live = (j && j.data && j.data.version) || (j && j.version) || 0;
      if (cached && String(cached.version) === String(live)) {
        CT.isRemote = applyBundle(cached.bundle); cb({ ok: true, fromCache: true, version: live }); return;
      }
      fetch(base() + "/api/content/bundle").then(function (r) { return r.json(); }).then(function (b) {
        CT.isRemote = applyBundle(b); cb({ ok: true, fromCache: false, version: b && b.version });
      }).catch(function () {
        if (cached) applyBundle(cached.bundle);
        cb({ ok: false, reason: "fetch-failed" });
      });
    }).catch(function () {
      if (cached) applyBundle(cached.bundle);
      cb({ ok: false, reason: "unreachable" });
    });
  };
})();
