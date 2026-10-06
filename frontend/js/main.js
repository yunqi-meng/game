/* 启动入口 v3（在线版）：真源在服务器，这里只做数据自检 + 清掉历史离线缓存 + 打开启动门。 */
(function () {
  "use strict";

  /** 早期版本注册过 PWA Service Worker，在线版必须彻底卸载，否则玩家会一直看到旧缓存。 */
  function purgeOfflineShell() {
    if (!("serviceWorker" in navigator)) return;
    navigator.serviceWorker.getRegistrations().then(function (rs) {
      rs.forEach(function (r) { r.unregister(); });
    }).catch(function () {});
    if (!window.caches) return;
    caches.keys().then(function (ks) {
      ks.forEach(function (k) { caches.delete(k); });
    }).catch(function () {});
  }

  function banner(text) {
    var w = document.createElement("div");
    w.className = "sys-banner";
    w.textContent = text;
    document.body.appendChild(w);
  }

  window.addEventListener("DOMContentLoaded", function () {
    var miss = [];
    if (!CHEM.ELEMENTS || CHEM.ELEMENTS.length < 100) miss.push("元素库");
    if (!CHEM.COMPOUNDS || !CHEM.COMPOUNDS.length) miss.push("化合物库");
    if (!CHEM.REACTIONS || !CHEM.REACTIONS.length) miss.push("反应库");
    if (!CHEM.ROOMS || !CHEM.ROOMS.length) miss.push("房间库");
    if (miss.length) banner(miss.join("、") + " 未加载，请检查 js/data 内容文件或刷新页面");
    purgeOfflineShell();
    CHEM.shell.applyLowFx();
    /* 壳里先从应用私有目录把令牌捞回 localStorage（异步），再进启动流程。
       不在壳内时 hydrate 直接回调，浏览器路径一步不加。 */
    CHEM.shell.store.hydrate(function () { CHEM.game.boot(); });
  });
})();
