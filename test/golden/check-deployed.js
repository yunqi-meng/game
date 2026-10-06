/* test/golden/check-deployed.js —— H1 的第三处真源核对：玩家手里那份内容也得在同一张表上。
 *
 * 前两层用的都是仓库里的东西：第 1 层比 data/*.js ↔ content-bundle.json（夹具），
 * 第 3 层（GoldenVectorsTest）拿同一份夹具跑服务端结算。可是真正在跑的那份内容在 MySQL 里：
 * 后台改一个价格、跑一次迁移，content_version 就往前走一格，上面两层照样全绿，
 * 而玩家的预测与落账读的是线上这一份。这一段补的就是这条边。
 *
 * 判法：把 /api/content/bundle 的返回当 bundle，走和第 1 层一模一样的判据（run.js 的 bundleParity），
 * 所以两侧不会各写一套比较逻辑然后互相"看起来对得上"。
 * 版本号只报不判：改简介、调 sort 都会 bump 版本，那种改动碰不到任何一条读数；
 * 真正被向量依赖的字段（反应 / 危险 / 工艺 / 仪器 / 物质价格与等级 / 结算系数）对不上才红，
 * 红的时候点名是哪一条哪一列，出路写在提示里。
 *
 * 用法（test/e2e-api.sh 第 4 层这么调）：
 *   curl -s "$BASE/api/content/bundle" | node test/golden/check-deployed.js
 * 退出码：0 对齐 / 1 漂移 / 2 输入读不了（后端没起或响应不是内容包）。
 * stdout 一行结论（漂移时每个问题再一行），给 e2e 直接塞进 ok/no 的文案里。
 */
"use strict";
const fs = require("fs"), path = require("path");
const { loadClient, bundleParity } = require("./run.js");

const VFILE = path.join(__dirname, "vectors.json");

function say(msg, code) {
  console.log(msg);
  process.exit(code);
}

let raw;
try {
  raw = process.argv[2] ? fs.readFileSync(process.argv[2], "utf8") : fs.readFileSync(0, "utf8");
} catch (e) {
  say("读不到 bundle（用法：curl -s $BASE/api/content/bundle | node test/golden/check-deployed.js）：" + e.message, 2);
}
let bundle;
try {
  bundle = JSON.parse(raw);
} catch (e) {
  say("响应不是 JSON，多半是没起后端或被网关换成了错误页：" + e.message + "；开头：" + raw.slice(0, 60), 2);
}
if (!bundle || !bundle.content) say("响应里没有 content 字段，不像内容包：" + Object.keys(bundle || {}).join(","), 2);

let vectors;
try {
  vectors = JSON.parse(fs.readFileSync(VFILE, "utf8"));
} catch (e) {
  say("读不了 vectors.json：" + e.message, 2);
}
const pinned = {};
vectors.forEach((v) => { pinned[v.contentVersion] = (pinned[v.contentVersion] || 0) + 1; });
const pinnedKeys = Object.keys(pinned);

let C;
try {
  C = loadClient();
} catch (e) {
  say("前端引擎装载失败：" + e.message, 2);
}
const errs = [];
bundleParity(C, bundle, "线上内容", errs);

const note = "线上 content_version=" + bundle.version + "，向量表钉在 " +
  (pinnedKeys.length === 1 ? pinnedKeys[0] : pinnedKeys.join("/")) +
  "（版本不同不算红：只有向量真正读的那几列动了才红）";
if (errs.length) {
  errs.slice(0, 12).forEach((m) => console.log("  ! " + m));
  say("线上内容与 data/*.js 及向量表对不上（" + errs.length + " 处，" + note + "）。出路二选一：" +
    "①这次改动是有意的 → 重新导夹具并跑 node test/golden/run.js --dump + 服务端 --merge 重对表；" +
    "②改动是误操作 → 后台把那一行改回去（或回滚版本）。", 1);
}
  say("线上下发内容与夹具、前端数据逐列一致（反应 " + ((bundle.content.reaction || []).length) +
  " / 物质 " + ((bundle.content.element || []).length + (bundle.content.compound || []).length) +
  " / 危险 " + ((bundle.content.danger || []).length) + " / 仪器 " + ((bundle.content.instrument || []).length) +
  " 条）；" + note, 0);
