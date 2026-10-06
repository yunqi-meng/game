/* 构建期配置注入点（必须在 cloud.js 之前加载）。
   浏览器/本机开发：这个文件什么都不做，请求走同源，也就是 http://localhost:8080。
   安卓壳打包：android/ 里的 gradle 任务会用真实域名覆写本文件（见 android/app-config.gradle），
   内容变成 window.CHEM_API_BASE = "https://api.<已备案域名>"。

   为什么要专门留一个文件而不是靠壳注入 JS：Capacitor 把 webDir 整目录拷进 assets/public，
   覆写这一份就能随包生效，不需要改 index.html，也不需要 WebView 在首屏之前抢先执行脚本。
   也正因为它是"会被覆写的文件"，这里绝不写任何密钥——API 地址不是秘密，
   真正敏感的口令只走服务端环境变量（见 server/.env）。 */
(function () {
  "use strict";
  // 同源。APK 里这一行会被 gradle 替换成绝对地址；android-check.sh 会检查它不是 localhost。
  window.CHEM_API_BASE = "";
})();
