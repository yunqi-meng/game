/* test/intent-coverage.js —— 意图全集 ↔ e2e 实际打过的意图（H7）
 *
 * 要防的那件事：`POST /api/game/{intent}` 是这座服务端唯一的玩法入口，`GameService.dispatch` 的
 * switch 就是它的**协议面**。加一条意图（或改一条的语义）而不给它一条 e2e，回归就出现了一个
 * "从没被真请求碰过"的分支——症状不是报错，是"这条分支坏在只有单测看得见的位置上"：
 * 单测拿的是注入的确定性快照与内存存档，走不到 HTTP 绑定、幂等层、每日刷新与写回那一段。
 * 反过来也一样危险：e2e 少了一个 `act`，没人知道，直到某个玩家按下那个按钮。
 *
 * 所以这里不抄第三份名单，两端各自从源码读出来再对账（同 admin-lock / admin-paging 的形状）：
 *   ① 全集：`GameService.java` 里 **`switch (intent)` 那一个** switch 体的 `case "…":` 标签。
 *      只读那一个 switch 是必须的——`applySettings`/`setLabLevel` 里也有 `switch (key)` 与
 *      `case "storage":`，把它们当意图就凭空多出三个"意图"。注释先剥：javadoc 里的
 *      `case "state"` 是解释不是协议。`default:` 不是意图。
 *   ② 已打过的：`test/e2e-api.sh` 里的三种真实调用形状——
 *      a. 意图助手 `act "<intent>" …`（助手名不写死：凡函数体里出现 `/api/game/$1` 的都算，
 *         于是 `act2`／后面新增的 `act7` 自动在列，改天重命名也不会把对账变成"漏判"）；
 *      b. 直接 curl 字面量 `"$BASE/api/game/<intent>"`（有些段落为了换 token 不走助手）；
 *      c. 数据驱动的 `"intent|payload"` 表（脚本用一条 for 循环把"已下线意图必须被回绝"三兄弟
 *         一起打掉的写法——`${OLD%%|*}` 就是意图名，所以它确实被打过，只是不是字面量参数）。
 *
 * 剩下的缺口只有两种出路，都在这个文件里写死：要么补进 e2e，要么进 `WHITELIST` 并给出
 * **从服务端源码读出来的**理由（缺一条理由就红）。白名单里出现了其实已被覆盖的意图也算红——
 * 白名单只该容纳"端到端真的做不到"，不该变成第二份待办清单。
 *
 * 退出码：0 通过 / 1 有问题（问题逐条打到 stderr）。依赖：node（只读文件，不连后端）。
 */
"use strict";
const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const P = (...s) => path.join(ROOT, ...s);

const JAVA = P("server", "src", "main", "java", "com", "chemera", "server", "game", "GameService.java");
const E2E = P("test", "e2e-api.sh");

/**
 * 确实不该／无法端到端走的意图。每条的理由都必须能在服务端源码里指着说，否则就该去补 e2e。
 */
const WHITELIST = {
  // EconomyService.rollMarketExtras：`if (!g.market.specials.isEmpty()) return;` 且
  // `int nBlk = g.level >= 11 ? 2 : 0`。行情每天只滚一次，而滚那一滚发生在 actOnce 的 rollDaily
  // 里、早于本帧的意图结算——新号第一帧就把 specials 填满了，之后无论怎么升级，当天都不会再
  // 出现第二批行；唯一能重滚的 reset 又会把等级打回 1（GameService.resetSave → GameState.fresh），
  // 于是"等级≥11 且行情为空"这两个条件在同一帧里永远凑不到一起。正向覆盖只能等跨天。
  "market.black": "黑市行只在每日行情首滚时按 Lv.11+ 生成（rollMarketExtras 里 specials 非空即 return、nBlk 需 level>=11），而那一滚排在意图结算之前；当天升满级也不会再滚第二次，reset 又把等级清回 1，两个条件同帧凑不出来",
  // GameService 的 challenge.revive 要求 `g.chal != null && g.chal.failed`，而 failed 只有一个产地：
  // GameEngine.react 的挑战结算块里 `else if (ch.steps >= ch.max) ch.failed = true`——必须是
  // "成功但不产目标物"的反应累计满 max(=4) 步。挑战材料是服务端随机下发的（given 只有目标方程式
  // 的反应物 ×1~2 + 两种干扰物各 2），干扰物一放就整局作废（decoy → g.chal=null），部分反应物
  // 又凑不出第二条能成的方程式，所以"四次成功且不产目标"无法在随机下发下稳定复现。
  // 复活次数本身已由 e2e 正向拿到（ad.devGrant → settle → ad.revive+1，那是真状态位），
  // 差的只是 failed 那一半。
  "challenge.revive": "需要同一挑战内连做 4 次「成功但不产目标物」的反应才有 failed（GameEngine.react 挑战结算块）；挑战材料由服务端随机下发且干扰物一放即整局作废，正向态无法稳定复现（复活次数已由 ad.devGrant 正向覆盖）",
};

/* ---------------- 读源码的小工具（与 admin-lock 同一口径） ---------------- */

/** 注释里的 case 名、act 调用是解释不是协议；先剥成等长空格再找。 */
function blankComments(src) {
  const blank = (s) => s.replace(/[^\n]/g, " ");
  return src.replace(/\/\*[\s\S]*?\*\//g, blank)
            .replace(/^[ \t]*\/\/.*$/gm, blank);
}

/**
 * shell 侧只剥整行注释（首非空字符是 #）。
 * 不做行尾注释：这个脚本大量用 `-d '{...}'` 与 URL，行里出现 # 是数据不是注释，
 * 一刀切下去会把 `ref":"e2elisting|$$"` 这类字面量咬掉，对账就变成漏判。
 */
function stripShellLineComments(src) {
  return src.split("\n").map((l) => (/^\s*#/.test(l) ? "" : l)).join("\n");
}

/** 从 openIdx 的那个 '{' 起取到配对闭合为止的原文（含两端），引号里的括号不算。 */
function sliceBalanced(src, openIdx) {
  let depth = 0, i = openIdx, q = null;
  for (; i < src.length; i++) {
    const c = src[i];
    if (q) {
      if (c === "\\") { i++; continue; }
      if (c === q) q = null;
      continue;
    }
    if (c === '"' || c === "'") { q = c; continue; }
    if (c === "{") depth++;
    else if (c === "}") { depth--; if (depth === 0) return src.slice(openIdx, i + 1); }
  }
  return null;
}

/* ---------------- Java：意图全集是从 switch (intent) 里读出来的 ---------------- */

function allIntents(javaSrc) {
  const clean = blankComments(javaSrc);
  const head = /\bswitch\s*\(\s*intent\s*\)/.exec(clean);
  if (!head) return null;                       // 分发不再是 switch(intent)：对账失去依据，直接红
  const brace = clean.indexOf("{", head.index + head[0].length);
  if (brace < 0) return null;
  const body = sliceBalanced(clean, brace);
  if (body === null) return null;
  const out = [];
  const re = /\bcase\s+"([A-Za-z][A-Za-z0-9._-]*)"\s*:/g;
  let m;
  while ((m = re.exec(body))) if (out.indexOf(m[1]) < 0) out.push(m[1]);
  return out;
}

/* ---------------- e2e：真的打出去的那些 ---------------- */

/** 意图助手名不写死：函数体里出现 /api/game/$1 的就是一个发意图的壳。 */
function intentHelpers(shellSrc) {
  const names = new Set();
  const re = /([A-Za-z_][A-Za-z0-9_]*)\s*\(\)\s*\{[^}\n]*\/api\/game\/\$1/g;
  let m;
  while ((m = re.exec(shellSrc))) names.add(m[1]);
  return [...names];
}

function calledIntents(shellSrc, known) {
  const src = stripShellLineComments(shellSrc);
  const hit = new Set();
  const knownSet = new Set(known);
  const take = (id) => { if (knownSet.has(id)) hit.add(id); };

  intentHelpers(src).forEach((h) => {
    const re = new RegExp("\\b" + h + "\\s+\"([A-Za-z][A-Za-z0-9._-]*)\"", "g");
    let m;
    while ((m = re.exec(src))) take(m[1]);
  });
  // 直接 curl 的端点字面量："$BASE/api/game/bench.place"
  let m;
  const reUrl = /\/api\/game\/([A-Za-z][A-Za-z0-9._-]*)"/g;
  while ((m = reUrl.exec(src))) take(m[1]);
  // 数据驱动表 "intent|payload"（${OLD%%|*} 即意图名）
  const rePair = /"([A-Za-z][A-Za-z0-9._-]*)\|/g;
  while ((m = rePair.exec(src))) take(m[1]);

  return hit;
}

/* ---------------- 对账 ---------------- */

function judge(javaSrc, e2eSrc) {
  const errs = [];
  const info = [];

  const known = allIntents(javaSrc);
  if (!known)
    return { errs: ["intent-coverage：GameService.java 里读不出 `switch (intent)` 的 case 标签——" +
      "分发形状变了（改成 Map 分派／枚举？），这条对账已经什么都不检查，请先把它对准新的真源"], info: [] };
  if (!known.length)
    return { errs: ["intent-coverage：switch (intent) 里一个 case 都没有，意图全集是空的"], info: [] };

  const called = calledIntents(e2eSrc, known);
  const missing = known.filter((i) => !called.has(i));
  const unknownWhitelist = Object.keys(WHITELIST).filter((i) => known.indexOf(i) < 0);
  unknownWhitelist.forEach((i) => errs.push("intent-coverage：白名单里的 " + i +
    " 已经不是服务端认识的意图了（switch 里没这条 case）——它要么被改名要么被删掉，白名单得跟着走"));
  const stale = Object.keys(WHITELIST).filter((i) => called.has(i));
  stale.forEach((i) => errs.push("intent-coverage：白名单里的 " + i +
    " 其实已经被 e2e 打过了——白名单只放「端到端真做不到」的，放一条已覆盖的等于把它变成第二份待办清单"));
  const noReason = Object.keys(WHITELIST).filter((i) => !String(WHITELIST[i] || "").trim());
  noReason.forEach((i) => errs.push("intent-coverage：白名单项 " + i + " 没写理由"));

  missing.forEach((i) => {
    if (WHITELIST[i]) info.push("白名单 " + i + "：" + WHITELIST[i]);
    else errs.push("intent-coverage：" + i + " 在服务端的 switch (intent) 里是一条真意图，" +
      "但 e2e-api.sh 从没把它打出去（act／直接 curl／intent|payload 表三种形状都没命中）" +
      "——要么补一段正向覆盖，要么写清它为什么端到端做不到再进 WHITELIST");
  });

  const covered = known.length - missing.length;
  info.unshift(covered + "/" + known.length + " 条意图被 e2e 打过（覆盖率 " +
    Math.round((covered / known.length) * 1000) / 10 + "%），未覆盖 " + missing.length +
    " 条全部在白名单内：" + (missing.length ? missing.join(", ") : "无缺口"));
  if (missing.length) info.push("未覆盖清单（逐条）：" + missing.map((i) => i + " [白名单]").join("、"));
  return { errs, info };
}

function judgeFiles() {
  if (!fs.existsSync(JAVA)) return { errs: ["intent-coverage：找不到 " + path.relative(ROOT, JAVA)], info: [] };
  if (!fs.existsSync(E2E)) return { errs: ["intent-coverage：找不到 " + path.relative(ROOT, E2E)], info: [] };
  try {
    return judge(fs.readFileSync(JAVA, "utf8"), fs.readFileSync(E2E, "utf8"));
  } catch (e) {
    return { errs: ["intent-coverage 跑不动：" + ((e && e.message) || e)], info: [] };
  }
}

function main() {
  const r = judgeFiles();
  r.errs.forEach((e) => console.error("✗ " + e));
  if (!r.errs.length) (r.info || []).forEach((i) => console.log(i));
  process.exit(r.errs.length ? 1 : 0);
}

module.exports = { judge, judgeFiles, allIntents, calledIntents, intentHelpers, JAVA, E2E, WHITELIST };

if (require.main === module) main();
