/* sw.js —— 已废弃：本作改为纯在线游戏，进度真源在服务器，不再做离线壳缓存。
   本文件只为卸载历史版本注册过的 Service Worker：清空全部旧缓存并自我注销。
   index.html 不再注册它；老客户端在下次 SW 更新检查时取到这里即自动清理干净。 */
self.addEventListener("install", function () { self.skipWaiting(); });
self.addEventListener("activate", function (e) {
  e.waitUntil(
    caches.keys()
      .then(function (keys) { return Promise.all(keys.map(function (k) { return caches.delete(k); })); })
      .then(function () { return self.registration.unregister(); })
      .then(function () { return self.clients.matchAll(); })
      .then(function (cs) { cs.forEach(function (c) { c.navigate(c.url); }); })
  );
});
