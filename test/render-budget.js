/* test/render-budget.js —— H3 的裁判：整页重绘与重复计算有没有真的收口。
 *
 * 为什么不用 shell grep 判（用法）
 *   升级方案里这一项的回归写的是"e2e 断服务端下发的 panels.js 里图鉴／市场路径含 slice／'更多'，
 *   并断 subMap 的失效点绑的是 content 版本而不是 revision"。字在不在，grep 说得上；
 *   但"切出来的是不是一页、点更多接不接得上、作废会不会误伤别的列表"只有真跑一遍才知道。
 *   而且本机 grep 在 C.UTF-8 下匹配不了中文与 4 字节字符（e2e 里为此留过注释），
 *   中文文案的锚点放到 node 里判才不会假绿。所以这里读 stdin 那份**随包下发**的代码，
 *   把分页辅助函数切出来 new Function 跑一遍行为，再补几条静态判据。
 *
 * 用法（都从 stdin 进，退出码 0 通过 / 1 红 / 2 读不到料）
 *   curl -s $BASE/js/panels.js | node test/render-budget.js panels
 *   curl -s $BASE/js/state.js  | node test/render-budget.js state
 * 判据失败时逐行打印原因，第一行带 BAD· 前缀，e2e 那句 ok/no 直接把整行原样报出来。
 */
"use strict";

const fs = require("fs");

function say(lines, code) {
  (Array.isArray(lines) ? lines : [lines]).forEach(function (l) { console.log(l); });
  process.exit(code);
}

/* ---------- panels 那份：分页 + 记忆表 ---------- */

/** 把 `var PAGE_N` 到 `P.pageStats` 之间那段切出来求值：它不碰 DOM，只用到 U.esc 与 P.render。 */
function loadPaging(src) {
  const i = src.indexOf("var PAGE_N"), j = src.indexOf("P.pageStats");
  if (i < 0 || j < 0 || j <= i) return null;
  return new Function("U", "P", src.slice(i, j) +
    ";return{PAGE_N:PAGE_N,slice:pageSlice,reset:resetPages,card:moreCard,row:moreRow," +
    "keys:function(){return Object.keys(pageOpen)}," +
    "bump:function(k){pageOpen[k]=(pageOpen[k]||0)+1}};"
  )({ esc: function (s) { return String(s); } }, { render: function () {} });
}

/** 行为判据：切页、续展、按前缀作废、溢出提示文案。 */
function pagingBehavior(api, errs) {
  const N = api.PAGE_N;
  if (!(N >= 8 && N <= 60)) errs.push("每页 " + N + " 项：太少是白切，太多等于没分页");
  const a = [];
  for (let k = 0; k < N * 2 + 6; k++) a.push("x" + k);
  const p = api.slice("codex:known", a);
  if (p.list.length !== N) errs.push("首屏应只画 " + N + " 项，实际 " + p.list.length + "（等于整页重绘）");
  if (p.left !== N + 6) errs.push("溢出数应为 " + (N + 6) + "，实际 " + p.left);
  api.bump("codex:known");
  const p2 = api.slice("codex:known", a);
  if (p2.list.length !== N * 2) errs.push("点一次更多应续到 " + N * 2 + " 项，实际 " + p2.list.length);
  if (p2.left !== 6) errs.push("续展后溢出数应为 6，实际 " + p2.left);
  api.reset("codex");
  if (api.slice("codex:known", a).list.length !== N) errs.push("作废后没退回首屏");
  api.bump("codex:known"); api.bump("bag:grid");
  api.reset("bag");
  if (api.keys().indexOf("bag:grid") >= 0) errs.push("resetPages(前缀) 没按前缀作废");
  if (api.keys().indexOf("codex:known") < 0) errs.push("resetPages(前缀) 误伤了别的列表（换搜索词会把别的页一起收起）");
  api.reset();
  if (api.keys().length) errs.push("resetPages() 没清干净");
  if (api.card("codex:locked", 7).indexOf("+7") < 0) errs.push("网格溢出卡没报还剩几项");
  if (api.card("codex:locked", 7).indexOf("data-more") < 0) errs.push("网格溢出卡没有 data-more，bindMore 绑不到");
  /* 溢出卡是 div：不补 role/tabindex/可读名字，键盘与读屏玩家走到这一步就断了（H5 判的正是这类） */
  if (!/role="button"/.test(api.card("codex:locked", 7))) errs.push("溢出卡缺 role=button（div 当按钮用，键盘按不到）");
  if (!/tabindex="0"/.test(api.card("codex:locked", 7))) errs.push("溢出卡缺 tabindex，Tab 键跳不过去");
  if (!/aria-label/.test(api.card("codex:locked", 7))) errs.push("溢出卡没有可读的 aria-label");
  if (api.row("eq:got", 9).indexOf("更多") < 0) errs.push("行式溢出条没有更多入口");
}

/** 静态判据：哪些列表走了分页、作废点齐不齐、记忆表有没有被绕开。 */
function pagingWiring(src, errs) {
  const n = (re) => (src.match(re) || []).length;
  const called = n(/pageSlice\("/g);
  if (called < 6) errs.push("只有 " + called + " 处调用 pageSlice：图鉴 4 张网格/清单 + 采购 + 出售 + 背包 + 材料柜都该在");
  if (n(/bindMore\(body\)/g) < 4) errs.push("bindMore 接线少于 4 处，有页面的\"更多\"点了不会续展");
  if (n(/resetPages/g) < 5) errs.push("resetPages 出现不足 5 次：换页/换分段/换分类/改搜索词都该作废展开");
  if (!/resetPages\(key\)/.test(src)) errs.push("bindSearch 里没有 resetPages(key)：换关键词还留着上一轮的展开，等于白分");
  /* 分页 key 的前缀必须是搜索框那把 key，否则"结果集变了"与"作废展开"对不上号 */
  const qKeys = (src.match(/var qState = \{([^}]*)\}/) || [, ""])[1]
    .split(",").map(function (s) { return (s.match(/^\s*([A-Za-z_$][\w$]*)/) || [])[1]; }).filter(Boolean);
  const prefixes = {};
  let m;
  const pk = /pageSlice\("([^":]+):/g;
  while ((m = pk.exec(src))) prefixes[m[1]] = 1;
  Object.keys(prefixes).forEach(function (p) {
    if (qKeys.indexOf(p) < 0) errs.push("分页前缀 " + p + " 不是搜索框的 key（换词时没人替它作废展开）");
  });
  /* 老的全量重绘不该再留在随包代码里：这三处正是升级方案点名的图鉴/方程/材料柜 */
  [/known\.map\(codexCard\)/, /locked\.map\(codexCard\)/, /\bmiss\.map\(function/]
    .forEach(function (re) { if (re.test(src)) errs.push("整表 map 还在（" + re + "），那一页仍是全量重绘"); });
  /* 重复计算：角标这类"一帧内被取 4~6 遍"的答案必须过记忆表，且判据是存档对象身份而非 revision */
  if (!/function claimCounts\(\)\s*\{\s*return memoGet\(/.test(src)) errs.push("claimCounts 没走 memoGet，一次导航仍算 4~6 遍");
  if (!/countsFrame !== st\.data/.test(src)) errs.push("记忆表的帧判据不是存档对象身份（换帧不重算就是拿旧数字发奖励）");
  if (!/P\.invalidateCounts = invalidateCounts/.test(src)) errs.push("没导出 invalidateCounts，后台换版清不掉记忆");
  if (!/buyableCount\(\)\s*\{\s*return memoGet\(/.test(src)) errs.push("建设页红点 buyableCount 没走记忆表");
  /* 安卓返回键把"展开过的长列表"当作第一层要收回的状态，否则一次返回直接跳回实验台 */
  if (!/if \(anyPageOpen\(keys\)\)/.test(src)) errs.push("backStep 没先收回展开的列表（返回键该就近退一层）");
  /* 用到的辅助函数必须真有定义：ReferenceError 只在跑到那一行才炸，而"字在不在"这种 grep 锚点
     恰好放过"调了 anyPageOpen 却忘了写函数"——本轮真踩过一次，所以把它写成判据。 */
  ["pageSlice", "resetPages", "moreCard", "moreRow", "bindMore", "anyPageOpen",
   "memoGet", "invalidateCounts", "computeClaimCounts", "computeBuyable"].forEach(function (fn) {
    const all = (src.match(new RegExp(fn + "\\s*\\(", "g")) || []).length;
    const defs = (src.match(new RegExp("function\\s+" + fn + "\\s*\\(", "g")) || []).length;
    if (all > 0 && defs === 0) errs.push("调用了 " + fn + "() 但没有定义（跑到的那一行才 ReferenceError）");
  });
  return { called: called };
}

/* ---------- state 那份：物质查表的失效点 ---------- */
function stateJudgment(src, errs) {
  const a = src.indexOf("S.setServer = function");
  const b = src.indexOf("物质查询");
  if (a < 0 || b < 0) { errs.push("找不到 setServer 或物质查询段，判不了"); return; }
  const body = src.slice(a, b);
  if (/subMap\s*=\s*null/.test(body)) errs.push("setServer 仍每帧置空 subMap：~270 个对象跟着重建，帧一落地就卡一下");
  const cache = src.slice(b, src.indexOf("S.sub = function") > b ? src.indexOf("S.sub = function") : src.length);
  if (!/CHEM\.content/.test(cache)) errs.push("查表缓存的失效判据没绑内容版本");
  if (/S\.revision/.test(cache)) errs.push("失效判据里出现 S.revision——后台换版不动 revision，旧表会被一直留着");
  if (!/S\.subVersion === S\.contentVersion\(\)/.test(cache)) errs.push("allSubs 没在返回前对内容版本，换版后仍会拿旧表");
  if (!/S\.invalidateSubs = function/.test(cache)) errs.push("少了 invalidateSubs 这个显式作废入口（换版只能各处手写 subMap = null）");
}

function main() {
  const mode = process.argv[2];
  let src = "";
  try { src = fs.readFileSync(0, "utf8"); } catch (e) { say("BAD · 读不到 stdin（用法：curl -s $BASE/js/xx.js | node test/render-budget.js " + mode + "）", 2); }
  const r = judge(mode, src);
  if (r.errs.length) return say(["BAD · 渲染收口没做到位"].concat(r.errs.map(function (e) { return "    · " + e; })), 1);
  say("OK · " + r.info, 0);
}

/** 判一份源码：返回 { errs, info }，不打印也不退出——第 1 层（validate.js）与 e2e 都调这一个。 */
function judge(mode, src) {
  if (src.length < 200) return { errs: ["喂进来的代码只有 " + src.length + " 字节，不像是 js/panels.js 或 js/state.js（后端没起？路径 404？）"], info: "" };
  const errs = [];
  let info = "";
  if (mode === "panels") {
    const api = loadPaging(src);
    if (!api) errs.push("切不出分页辅助函数（var PAGE_N … P.pageStats 那一段没随包下发，或已被改名）");
    else pagingBehavior(api, errs);
    const w = pagingWiring(src, errs);
    info = w.called + " 处长列表走分页";
  } else if (mode === "state") {
    stateJudgment(src, errs);
    info = "查表只在内容换版时重建";
  } else {
    return { errs: ["未知模式 " + mode + "（可用：panels / state）"], info: "" };
  }
  return { errs: errs, info: info };
}

module.exports = { judge: judge, loadPaging: loadPaging, pagingBehavior: pagingBehavior, pagingWiring: pagingWiring, stateJudgment: stateJudgment };

if (require.main === module) main();
