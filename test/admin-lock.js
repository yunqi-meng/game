/* test/admin-lock.js —— 后台写入乐观锁的两端对账（H6-2）
 *
 * 要防的那件事：`/admin/api/content/item` 这类行写入以前是无条件覆盖，两个运营先后打开同一条
 * 反应，后提交的那个静默盖掉前一个，而两边界面都显示"已保存"。这一轮服务端改成了条件写
 * （版本号就是那一行的 `updated_at`，对不上回真 409），面板负责**把它读出来、带回去**。
 *
 * 于是债换成了跨语言的一对：Java 那边守卫了哪几个写入口，Vue 这边就得在每一次调用里带
 * `expect`。这类账 grep 判不动——"少了一处调用没带版本号"在文本上没有任何错字可搜，
 * 而 Java 单测看不见 `.vue`，前端的构建产物又只证明"源码是这样就是这样"。所以这里对账：
 *
 *   受守卫的写入口从 Java 源码**读出来**（注解 + 方法体里有没有 Expect.parse），
 *   这里不抄一份端点清单；面板那侧遍历 admin/src 全部调用点。
 *
 * 同时钉住客户端的分流依据：冲突按 **HTTP 409** 走「载入最新」，不按文案走（文案会改字），
 * 而且只有一个 `conflict.js` 负责那个弹窗——两个面板各写一份确认框，早晚一个带"载入最新"、
 * 另一个只剩红字。
 *
 * 只管这两条：服务端的条件写本身（0 行不写不留档、回体带新号）由 ContentRevisionServiceTest
 * 与 AdminOptimisticLockHttpTest 判，真库上的六种形状由 test/e2e-api.sh 判。
 *
 * 退出码：0 通过 / 1 有问题（问题逐条打到 stderr）。依赖：node（只读文件）。
 */
"use strict";
const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const P = (...s) => path.join(ROOT, ...s);

// 只有这两个控制器是"一行一份内容"的覆盖写面（H6 方案 ②点名的范围）。
const CONTROLLERS = [
  P("server", "src", "main", "java", "com", "chemera", "server", "controller", "admin", "AdminContentController.java"),
  P("server", "src", "main", "java", "com", "chemera", "server", "controller", "admin", "AdminConfigController.java"),
];
const API_JS = P("admin", "src", "api.js");
const CONFLICT_JS = P("admin", "src", "conflict.js");
const ADMIN_SRC = P("admin", "src");

/* ---------------- 读源码的小工具 ---------------- */

/**
 * 注释里的端点名、expect 是解释不是调用；先剥掉再找调用点。
 * 剥成等长的空格：下面 Java 那侧要在"去注释"和"去字符串"两份副本之间对同一个下标取值。
 */
function stripComments(src) {
  const blank = (s) => s.replace(/[^\n]/g, " ");
  return src.replace(/\/\*[\s\S]*?\*\//g, blank)
            .replace(/^[ \t]*\/\/.*$/gm, blank)
            .replace(/<!--[\s\S]*?-->/g, blank);
}

/** 把字符串字面量的内容抹平（保留引号），用来安全地数花括号：文案里的 `{}` 不该参与配对。 */
function blankStrings(src) {
  let out = "", i = 0, q = null;
  while (i < src.length) {
    const c = src[i];
    if (q) {
      if (c === "\\") { out += "  "; i += 2; continue; }
      if (c === q) { q = null; out += c; } else out += " ";
      i++; continue;
    }
    if (c === '"' || c === "'" || c === "`") { q = c; out += c; i++; continue; }
    out += c; i++;
  }
  return out;
}

/** 从 openIdx 的那个 '(' 或 '{' 起取到配对闭合为止的原文（含两端），引号里的括号不算。 */
function sliceBalanced(src, openIdx) {
  const want = src[openIdx];
  if (want !== "(" && want !== "{") return null;
  const close = want === "(" ? ")" : "}";
  let depth = 0, i = openIdx, q = null;
  for (; i < src.length; i++) {
    const c = src[i];
    if (q) {
      if (c === "\\") { i++; continue; }
      if (c === q) q = null;
      continue;
    }
    if (c === '"' || c === "'" || c === "`") { q = c; continue; }
    if (c === want) depth++;
    else if (c === close) { depth--; if (depth === 0) return src.slice(openIdx, i + 1); }
  }
  return null;
}

function lineOf(src, idx) {
  return src.slice(0, idx).split("\n").length;
}

/* ---------------- Java：受守卫的写入口是从这里读出来的 ---------------- */

/**
 * → [{ verb, path, guarded, body }]，path 已去掉 baseURL 那段（面板写的是 "/content/item"）。
 *
 * 判据是"这个方法体里调没调 Expect.parse"，不是方法名——将来加一个 `@PutMapping("/batch")`
 * 却忘了条件写，它就以未守卫的身份出现在下面的对账里；反过来把它接上条件写，这里会自动开始
 * 要求面板带版本号，不需要谁来改这份裁判。
 */
function writeEndpoints(javaSrc) {
  const base = /@RequestMapping\("([^"]+)"\)/.exec(javaSrc);
  if (!base) return null;
  const clean = stripComments(javaSrc);          // 去注释、保留字符串（注解里的路径要读）
  const skeleton = blankStrings(clean);           // 再抹平字符串，花括号才敢拿来配对
  const cut = base[1].replace(/^\/admin\/api/, "");
  const out = [];
  const re = /@(Put|Post|Delete)Mapping\b(\s*\(\s*"[^"]*"\s*\))?/g;
  let m;
  while ((m = re.exec(clean))) {
    const sub = m[2] ? (/(\s*\(\s*)"([^"]*)"/.exec(m[2]) || [, "", ""])[2] : "";
    const brace = skeleton.indexOf("{", re.lastIndex);
    if (brace < 0) continue;
    const body = sliceBalanced(skeleton, brace);
    if (!body) continue;
    // 同一段的"带字符串"版本：Map 的键名写在字面量里，抹平之后就读不到 updatedAt 这种契约了
    const bodyText = clean.slice(brace, brace + body.length);
    const sig = skeleton.slice(re.lastIndex, brace).replace(/\s+/g, " ").trim();
    out.push({
      verb: m[1].toUpperCase(),
      path: (cut + sub) || "/",
      guarded: /Expect\.parse\(/.test(body),
      // 这一条写的是"某一行"（参数里有 type / id / key），还是全局动作（publish 只顶版本号）
      rowScoped: /@RequestParam[^)]*\b(type|id|key)\b/.test(sig),
      returnsVoid: /ApiResponse\s*<\s*Void\s*>/.test(sig),
      body: bodyText,
      signature: sig.slice(0, 200),
    });
  }
  return out;
}

/* ---------------- Vue：每一个调用点都算 ---------------- */

/** 递归列出 admin/src 下所有 .vue / .js（对账要覆盖"第三个面板也来写这一行"的情况）。 */
function adminFiles(dir) {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...adminFiles(p));
    else if (/\.(vue|js)$/.test(e.name)) out.push(p);
  }
  return out;
}

/** `http.put("/content/item", …)` 这类写调用：verb + 首参路径 + 整段调用原文。 */
function httpWrites(src) {
  const clean = stripComments(src);
  const out = [];
  const re = /\bhttp\.(put|post|delete)\s*\(/g;
  let m;
  while ((m = re.exec(clean))) {
    const open = m.index + m[0].length - 1;      // 就是刚匹配到的那个 '('
    const call = sliceBalanced(clean, open);
    if (!call) continue;
    // call 是连同那个 '(' 一起截下来的
    const first = /^\(\s*(["'`])([^"'`]*)\1/.exec(call);
    if (!first) continue; // 路径是变量拼出来的：静态对账看不见，宁可漏判也不猜
    out.push({ verb: m[1].toUpperCase(), path: first[2], call, at: lineOf(clean, m.index) });
  }
  return out;
}

/* ---------------- 对账 ---------------- */

function judge(controllers, apiJs, conflictJs, views) {
  const errs = [];
  const info = [];

  const endpoints = [];
  controllers.forEach((src, i) => {
    const eps = writeEndpoints(src);
    if (!eps) return errs.push("admin-lock：" + path.basename(CONTROLLERS[i]) +
      " 读不出 @RequestMapping(\"/admin/api/…\")，端点归属没有依据（base 写法变了？这条对账已经什么都不检查）");
    endpoints.push(...eps);
  });
  const guarded = endpoints.filter((e) => e.guarded);
  if (!endpoints.length)
    return { errs: ["admin-lock：两个控制器一个写入口都没解析出来，这条对账已经什么都不检查"], info: [] };
  if (guarded.length < 5)
    errs.push("admin-lock：只有 " + guarded.length + " 个写入口带版本守卫（Expect.parse），比预期少：" +
      "行写入少一扇门，那个入口就还是「两个人互相覆盖而谁都没提示」");

  // 版本号对上之后，回体必须给出写完那一行的新号——否则同一个对话框里第二次保存会撞上自己。
  // 删除那一档例外：行已经不在了，没有号可回（signature 里的泛型返回类型说得很清楚）。
  guarded.forEach((e) => {
    if (e.returnsVoid) return; // 删除：行已经不在了，没有号可回
    if (!/stamped\(|updatedAt/.test(e.body))
      errs.push("admin-lock：" + e.verb + " " + e.path + " 收了 expect 却没把新的 updatedAt 带回前端，" +
        "同一个弹窗第二次保存会被自己的第一次保存挡在门外");
  });

  // 面板侧：每一个命中受守卫端点的调用都必须带 expect
  let hits = 0;
  const guardFiles = new Set();
  views.forEach(({ file, src }) => {
    httpWrites(src).forEach((w) => {
      const ep = guarded.find((e) => e.verb === w.verb && e.path === w.path);
      if (!ep) {
        // 命中了这两个控制器的行写入、那边却没有条件写：有人把锁摘了，前端不该跟着沉默。
        // 只点名"写某一行"的入口（参数里有 type/id/key）——publish 那种顶全局版本号的动作本来就没有行可锁。
        const loose = endpoints.find((e) => e.verb === w.verb && e.path === w.path && !e.guarded && e.rowScoped);
        if (loose)
          errs.push("admin-lock：" + path.relative(ROOT, file) + ":" + w.at + " 写的 " + w.verb + " " + w.path +
            " 在服务端不是条件写了（方法体里没有 Expect.parse），乐观锁在这条路上断了");
        return;
      }
      hits++;
      guardFiles.add(file);
      if (!/\bexpect\b\s*:/.test(w.call))
        errs.push("admin-lock：" + path.relative(ROOT, file) + ":" + w.at + " 调 " + w.verb + " " + w.path +
          " 没带 expect——面板不回传版本号，服务端的条件写等于没有防线（这一行的 updatedAt 就在列表里）");
      else if (!/updatedAt|\bstamp\b/.test(w.call))
        errs.push("admin-lock：" + path.relative(ROOT, file) + ":" + w.at + " 的 expect 不是从那一行的 updatedAt（或打开对话框时存下的 stamp）取的，" +
          "版本号一旦是就地现读来的别的东西，它保的就不是「我打开时看到的那一版」");
    });
  });
  if (guarded.length && !hits)
    errs.push("admin-lock：面板里没有一处调用命中受守卫的写入口（路径写法变了？那这条对账已经什么都不检查）");

  // 带版本号的面板必须真的处理 409，而且共用同一个出口
  const confRel = path.relative(ROOT, CONFLICT_JS);
  guardFiles.forEach((file) => {
    const src = stripComments(fs.readFileSync(file, "utf8"));
    if (!/from\s+["'][^"']*conflict["']/.test(src))
      errs.push("admin-lock：" + path.relative(ROOT, file) + " 写了受守卫的行却没引 ../conflict，那个「载入最新」的出口早晚只在一个面板里有");
    if (!/updatedAt/.test(src))
      errs.push("admin-lock：" + path.relative(ROOT, file) + " 从不读 updatedAt：版本号没有来源，expect 只能送一个猜的值");
  });
  if (guardFiles.size > 1) {
    const own = views.filter(({ file, src }) =>
      guardFiles.has(file) && /ElMessageBox\s*\.\s*(confirm|alert)\s*\(/.test(stripComments(src)) &&
      !/from\s+["'][^"']*conflict["']/.test(src));
    own.forEach(({ file }) => errs.push("admin-lock：" + path.relative(ROOT, file) +
      " 自己写了一份冲突确认框（该用 " + confRel + "）：两份弹窗文案与按钮，两个面板就会给出两种出路"));
  }

  // 分流依据是状态码，不是文案。注释里提一句"409"不算：要看的是那个判断函数真的读 status。
  const apiClean = stripComments(apiJs);
  const isConf = /function\s+isConflict[\s\S]{0,400}?\n\}/.exec(apiClean);
  if (!isConf) errs.push("admin-lock：api.js 里没有 isConflict 了——面板按什么认冲突？");
  else if (!/\bstatus\b/.test(isConf[0]) || !/409/.test(isConf[0]))
    errs.push("admin-lock：api.js 的 isConflict 不是按 response.status === 409 判的（" +
      isConf[0].replace(/\s+/g, " ").slice(0, 90) + "）。按 msg 匹配的话改一个字就把这条防线拆成一行飘过去的红字");
  if (/\bstatus\b\s*===?\s*40[0-8]\b/.test(apiClean))
    errs.push("admin-lock：api.js 把冲突认成了别的 4xx");
  if (!/export\s+(async\s+)?function\s+askReloadOnConflict/.test(stripComments(conflictJs)))
    errs.push("admin-lock：conflict.js 里没有 askReloadOnConflict——面板拿到 409 之后那句「载入最新」没有落地处");
  // 面板里不许再按文案认冲突（那是同一件事的第二份判断，早晚和 api.js 那份分叉）
  views.forEach(({ file, src }) => {
    if (!guardFiles.has(file)) return;
    if (/\.msg[^;\n]*?(includes|indexOf|startsWith)\s*\([^)]*(冲突|已被|改过)/.test(stripComments(src)))
      errs.push("admin-lock：" + path.relative(ROOT, file) + " 在按 msg 文案认冲突（该用 isConflict/askReloadOnConflict）");
  });

  info.push(guarded.length + " 个受守卫写入口 / 面板 " + hits + " 处调用全部带版本号，冲突按 409 分流到同一个出口");
  return { errs, info };
}

function readAll() {
  const controllers = CONTROLLERS.map((f) => {
    if (!fs.existsSync(f)) throw new Error("找不到 " + f);
    return fs.readFileSync(f, "utf8");
  });
  const views = adminFiles(ADMIN_SRC).map((f) => ({ file: f, src: fs.readFileSync(f, "utf8") }));
  return {
    controllers,
    apiJs: fs.readFileSync(API_JS, "utf8"),
    conflictJs: fs.readFileSync(CONFLICT_JS, "utf8"),
    views,
  };
}

/** 给 validate.js 用：自己去读那几份源码，返回 {errs, info}。 */
function judgeFiles() {
  const missing = [API_JS, CONFLICT_JS, ADMIN_SRC].concat(CONTROLLERS).filter((f) => !fs.existsSync(f));
  if (missing.length)
    return { errs: missing.map((f) => "admin-lock：找不到 " + path.relative(ROOT, f) + "，对账没有依据"), info: [] };
  const a = readAll();
  return judge(a.controllers, a.apiJs, a.conflictJs, a.views);
}

function main() {
  let r;
  try {
    r = judgeFiles();
  } catch (e) {
    console.error("admin-lock 跑不动：" + ((e && e.message) || e));
    process.exit(1);
  }
  r.errs.forEach((e) => console.error("✗ " + e));
  if (!r.errs.length) (r.info || []).forEach((i) => console.log(i));
  process.exit(r.errs.length ? 1 : 0);
}

module.exports = { judge, judgeFiles, writeEndpoints, httpWrites, adminFiles, CONTROLLERS, API_JS, CONFLICT_JS, ADMIN_SRC };

if (require.main === module) main();
