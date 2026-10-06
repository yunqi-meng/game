/* test/admin-spec.js —— 后台【运营配置】的描述符对账（H6-1）
 *
 * 要防的那件事很具体：`Config.vue` 曾经自己抄了两张表——一张"scope 中文 → 标签颜色"，
 * 一张"键名 → 编辑器形态 + 步进精度"。后端把付费面下线、给 recharge 加了「已退役」这一档说明之后，
 * 那两张表没人记得跟着改，于是"改了什么都不发生、而且曾经是要发钱的键"一路显示成和「仅前端」
 * 一样的温和灰色。本地跑起来永远看得见、评审看不见，因为它少的不是错字，是**一整行**。
 *
 * 现在颜色和编辑器都由后端 ConfigSpec 下发（tone / form），前端只剩一份 token → 控件的映射。
 * 于是真正的债换了一种形状：**Java 那边的集合与 Vue 这边的分支会不会分叉**。
 * 这类账 grep 判不动（跨语言、且"少了哪个"根本没有文本可搜），所以拿构建产物与源码对账：
 *
 *   第 1 层（validate.js）  ConfigSpec.java 里每个键的 form/tone  ↔  Config.vue 的分支与 TONE 映射
 *   第 4 层（e2e-api.sh）   线上 GET /admin/api/config/spec 真返回的那些 form/tone  ↔  同一份 Vue 源码
 *
 * 同一个裁判两种喂法：只看仓库字节的那一层拦"改源码忘了改面板"，看下发字节的那一层拦
 * "跑着的后端不是这份源码"。两边都只准比对，不准自己再列一份 token 清单——
 * 所以 FORMS 与 TONE_* 都是从 Java 源码里**读出来**的，不是这里抄的。
 *
 * 退出码：0 通过 / 1 有问题（问题逐条打到 stderr）。依赖：node（只读文件）。
 */
"use strict";
const fs = require("fs");
const path = require("path");

const VUE = path.join(__dirname, "..", "admin", "src", "views", "Config.vue");
const JAVA = path.join(__dirname, "..", "server", "src", "main", "java",
  "com", "chemera", "server", "game", "ConfigSpec.java");

/** Config.vue 模板里能接住的编辑器形态：`kind === 'ad'` 那些分支（含 json 兜底与 currentValue 里的比较）。 */
function vueBranches(src) {
  const out = new Set();
  const re = /\bkind(?:\.value)?\s*===\s*["'](\w+)["']/g;
  let m;
  while ((m = re.exec(src))) out.add(m[1]);
  return out;
}

/** 兜底那条 `v-else` 必须是 JSON 文本框：没有它，未知形态不是"退回文本编辑"，而是整块空白。 */
function vueHasJsonFallback(src) {
  return /<el-input\s+v-else[^>]*jsonText/.test(src);
}

/** Config.vue 里 token → 控件 的那一份映射的键（这是唯一允许存在的一份）。 */
function vueToneBlock(src) { return /const TONE\s*=\s*\{([\s\S]*?)\n\};/.exec(src); }

function vueToneKeys(src) {
  const blk = vueToneBlock(src);
  if (!blk) return null;
  const out = new Set();
  const re = /^\s*(\w+)\s*:\s*\{/gm;
  let m;
  while ((m = re.exec(blk[1]))) out.add(m[1]);
  return out;
}

/**
 * TONE 里标了 `dim: true` 的档——「已退役」不能只靠一行文案说：上一轮它漂掉之后，
 * recharge 与「仅前端」在面板上长得一模一样，运营只能靠记住键名来区分。所以颜色映射里
 * 那一档必须真有一条样式规则在用它，否则下发 tone 只是数据，看不出来。
 */
function vueToneDimKeys(src) {
  const blk = vueToneBlock(src);
  if (!blk) return null;
  const out = new Set();
  const re = /^\s*(\w+)\s*:\s*\{[^}]*\bdim\s*:\s*true/gm;
  let m;
  while ((m = re.exec(blk[1]))) out.add(m[1]);
  return out;
}

/** dim 那一档有没有真的落到界面：模板上绑了 class、样式里有一条点得着的规则。 */
function dimWired(vueSrc, where, errs) {
  const dims = vueToneDimKeys(vueSrc);
  if (!dims) return;
  if (!dims.size) {
    errs.push(where + "：TONE 里没有任何一档带 dim，已退役的键与「仅前端」在面板上会完全同色同形——风险档就该看得见，不该只写在文案里");
    return;
  }
  if (!/:class="\{\s*dim:\s*tone\(/.test(vueSrc))
    errs.push(where + "：TONE 给「" + [...dims].join("、") + "」标了 dim，但模板里没有 :class=\"{ dim: tone(…) }\"，那一档划不掉");
  if (!/\.dim\s*\{[^}]*\}/.test(vueSrc))
    errs.push(where + "：面板绑了 dim 类却没有 .dim 样式规则，运营看到的还是一张普通标签");
}

/** `new ConfigSpec("key", "名称", SCOPE, "form",` → [{key, scope, form}]（scope 那列是 Java 常量名）。 */
function javaSpecs(src) {
  const out = [];
  const re = /new ConfigSpec\(\s*"(\w+)"\s*,\s*"[^"]*"\s*,\s*([A-Z][A-Z_0-9]*)\s*,\s*"([^"]*)"/g;
  let m;
  while ((m = re.exec(src))) out.push({ key: m[1], scope: m[2], form: m[3] });
  return out;
}

/** ConfigSpec.FORMS 里声明的形态全集。 */
function javaFormUniverse(src) {
  const blk = /FORMS\s*=\s*Set\.of\(([^)]*)\)/.exec(src);
  if (!blk) return null;
  const out = new Set();
  const re = /"(\w+)"/g;
  let m;
  while ((m = re.exec(blk[1]))) out.add(m[1]);
  return out;
}

/** ConfigSpec.TONE_* 常量的取值（面板那份映射的键必须与它们一致）。 */
function javaToneTokens(src) {
  const out = new Set();
  const re = /\bTONE_[A-Z]+\s*=\s*"(\w+)"/g;
  let m;
  while ((m = re.exec(src))) out.add(m[1]);
  return out;
}

/** `public static final String NAME = "值"` 的常量表（scope 中文与 TONE token 都从这里读，不在这里抄）。 */
function javaConstStrings(src) {
  const out = {};
  const re = /public static final String ([A-Z][A-Z_0-9]*)\s*=\s*"([^"]*)"/g;
  let m;
  while ((m = re.exec(src))) out[m[1]] = m[2];
  return out;
}

/**
 * `toneOf` 那段 switch 的对照表，键是 scope 的**中文原文**：
 * "哪一档是已退役" 由 Java 的推导说，裁判只认它给出的那一档，自己不认识任何常量名。
 */
function javaScopeTones(src) {
  const blk = /String toneOf\(String scope\)\s*\{[\s\S]*?switch\s*\(scope\)\s*\{([\s\S]*?)\n\s*\}\s*;/.exec(src);
  if (!blk) return null;
  const consts = javaConstStrings(src);
  const out = {};
  const re = /case\s+((?:[A-Z][A-Z_0-9]*\s*,?\s*)+)\s*->\s*(TONE_[A-Z]+)\s*;/g;
  let m;
  while ((m = re.exec(blk[1]))) {
    const token = consts[m[2]];
    if (!token) continue;
    m[1].split(",").map((x) => x.trim()).filter(Boolean).forEach((name) => {
      if (consts[name]) out[consts[name]] = token;
    });
  }
  return Object.keys(out).length ? out : null;
}

function formKind(form) { return String(form || "json").split(":")[0]; }

/** ConfigSpec 里 scope 常量的中文原文（面板一旦按这几个字面量判断颜色，就是在本地重写后端那份映射）。 */
function javaScopeLabels(src) {
  const out = [];
  const re = /public static final String [A-Z_]+\s*=\s*"([^"]*[一-龥][^"]*)"/g;
  let m;
  while ((m = re.exec(src))) out.push(m[1]);
  return out;
}

function esc(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); }

/**
 * 面板里以中文 scope 原文做字面量判断——上一轮那张 SCOPE_TYPE 就是这个形状。
 * 光看"有没有读 .tone"判不住：一处改回本地判断、另一处还留着读 .tone，文件里两个都有。
 */
function scopeLiterals(vueSrc, labels) {
  const src = stripComments(vueSrc);
  return labels.filter((l) => new RegExp("[\"']" + esc(l) + "[\"']").test(src));
}

/** 两边共有的一段：形态集合必须与面板分支严丝合缝，而且面板里不许再出现键名清单。 */
function crossCheck(where, forms, vueSrc, errs) {
  const branches = vueBranches(vueSrc);
  if (!vueHasJsonFallback(vueSrc))
    errs.push(where + "：Config.vue 的 v-else 兜底不再是 JSON 文本框，未知编辑器形态会渲染成空白而不是退回文本编辑");
  // 后端用了面板没有的形态 → 该键安静地退回 JSON，运营看到的和文档说的不是一种控件
  forms.forEach((f) => { if (!branches.has(f)) errs.push(where + "：后端下发的编辑器形态「" + f + "」在 Config.vue 没有分支，那个键会退回 JSON 文本框"); });
  // 面板留了没人用的分支 → 死代码（H6 的"死逻辑"就是这个形状：看着像功能，永远不会被触发）
  branches.forEach((b) => { if (!forms.has(b)) errs.push(where + "：Config.vue 有「" + b + "」编辑器分支，但没有任何配置键用它（要么补键、要么删分支）"); });

  // 第二份清单的三个记号：出现过就说明有人又开始抄后端的话
  if (/\bKIND\s*=\s*\{/.test(vueSrc)) errs.push(where + "：Config.vue 又出现了一份「键名 → 编辑器」表（KIND），它该由 ConfigSpec.form 下发");
  if (/SCOPE_TYPE\s*=|function\s+scopeAlert/.test(vueSrc)) errs.push(where + "：Config.vue 又出现了一份「scope → 颜色」表，它该由 ConfigSpec.tone 下发");
  if (!/\?\.form/.test(vueSrc) || !/\?\.tone/.test(vueSrc))
    errs.push(where + "：Config.vue 没有从描述符里读 .form / .tone，那颜色与编辑器一定是本地判断的");
  dimWired(vueSrc, where, errs);
  return branches;
}

/**
 * "第二份清单"的两种形状：
 * ① 直接拿 cfgKey 做分支（那就是在面板里重写一遍"这个键该长什么样"）；
 * ② 一张以配置键名为字面量键的表（原来那张 KIND）。阈值取 3 个键：
 *     `/config/compliance/schema` 的返回本身就是按键名组织的，读它两处是正常的，
 *     连到三处就说明这里开始存答案而不是存引用了。
 *
 * <p>注释先剥掉：面板里那些 `<!-- curfew: {enabled,zone,...} -->` 是给读代码的人讲这个键长什么样，
 * 不是判断，留着会把每一条文档都判成抄表。
 */
function stripComments(src) {
  return src.replace(/<!--[\s\S]*?-->/g, "").replace(/\/\*[\s\S]*?\*\//g, "")
            .replace(/^\s*\/\/.*$/gm, "");
}

function quotedKeys(vueSrc, keys) {
  const src = stripComments(vueSrc);
  const bad = new Set();
  const cmp = /\bcfgKey\s*[=!]==?\s*["'](\w+)["']/g;
  let m;
  while ((m = cmp.exec(src))) bad.add(m[1]);
  const table = new RegExp("^\\s*(" + keys.join("|") + ")\\s*:\\s*[\\[{]", "gm");
  const tblHits = [];
  while ((m = table.exec(src))) tblHits.push(m[1]);
  if (tblHits.length >= 3) tblHits.forEach((k) => bad.add(k));
  return [...bad];
}

function judgeJava(javaSrc, vueSrc) {
  const errs = [];
  const universe = javaFormUniverse(javaSrc);
  const tones = javaToneTokens(javaSrc);
  const specs = javaSpecs(javaSrc);
  if (!universe) return { errs: ["ConfigSpec.java 里找不到 FORMS 集合，交叉对账没有依据"], info: [] };
  if (!tones || !tones.size) return { errs: ["ConfigSpec.java 里找不到 TONE_* 常量"], info: [] };
  if (!specs.length) return { errs: ["ConfigSpec.java 里一条说明都没解析出来（构造器形状变了？），这条对账已经什么都不检查"], info: [] };
  if (specs.length < 10) errs.push("ConfigSpec.java 只解析出 " + specs.length + " 条说明，比预期少得多：解析正则没跟上构造器形状的话，这条对账会安静地漏判");

  const forms = new Set(specs.map((s) => formKind(s.form)));
  forms.forEach((f) => { if (!universe.has(f)) errs.push("ConfigSpec：" + specs.filter(s => formKind(s.form) === f).map(s => s.key).join("、") + " 用了 FORMS 之外的编辑器形态「" + f + "」"); });
  universe.forEach((u) => { if (!forms.has(u)) errs.push("ConfigSpec.FORMS 声明了「" + u + "」却没有任何键在用：要么补键，要么把这条形态和面板分支一起删掉"); });

  crossCheck("ConfigSpec", forms, vueSrc, errs);

  const toneKeys = vueToneKeys(vueSrc);
  if (!toneKeys) errs.push("Config.vue：找不到 TONE 映射，颜色不知道从哪来（多半是又被就地改成了 if 判断）");
  else {
    tones.forEach((t) => { if (!toneKeys.has(t)) errs.push("Config.vue：后端有风险档「" + t + "」，TONE 映射里没有它，那一档会安静地用兜底色"); });
    toneKeys.forEach((k) => { if (!tones.has(k)) errs.push("Config.vue：TONE 里有「" + k + "」，但 ConfigSpec 从来没有这一档（后端加一档时请同时把 scope 映射补上，别留孤例）"); });
  }

  // 「已退役」那一档到底划不划掉：哪一档代表退役由 Java 的 toneOf 说，这里只认它的答案
  const scopeTones = javaScopeTones(javaSrc);
  if (!scopeTones) errs.push("ConfigSpec.java：读不出 toneOf 的 scope→tone 对照（switch 形状变了？），那这条退役档对账已经什么都不检查");
  else {
    const retiredTier = [...new Set(Object.entries(scopeTones).filter(([label]) => label.includes("退役")).map(([, t]) => t))];
    if (!retiredTier.length) errs.push("ConfigSpec.java：toneOf 里没有哪一档对应「已退役」的 scope，面板无从把它和普通键区分开");
    const dims = vueToneDimKeys(vueSrc);
    if (dims && dims.size) {
      retiredTier.forEach((t) => { if (!dims.has(t)) errs.push("Config.vue：后端把「已退役」放在风险档「" + t + "」，可面板的 dim 只标在 " + [...dims].join("、") + " 上，那一行和普通键长得一样"); });
      [...dims].forEach((d) => { if (!retiredTier.includes(d)) errs.push("Config.vue：TONE 给「" + d + "」标了 dim（划掉），但后端并不认为这一档已退役——划掉结算键会让人以为它不再生效"); });
    }
  }

  // 每个 number 形态都得带可解析的步进与精度：面板只做 Number()，NaN 会让数字框悄悄不接受输入
  specs.forEach((s) => {
    const parts = String(s.form).split(":");
    if (parts[0] !== "number") return;
    const nums = parts.slice(1).map(Number);
    if (parts.length !== 3 || nums.some((n) => !Number.isFinite(n)))
      errs.push("ConfigSpec：" + s.key + " 的 number 形态要写成 number:步进:小数位，现在是「" + s.form + "」");
  });

  const leaked = quotedKeys(vueSrc, specs.map((s) => s.key));
  if (leaked.length) errs.push("Config.vue：又在按配置键名本身做判断/抄表：" + leaked.join("、") + "（要区分控件请看下发的 form，别让面板留第二份键名清单）");
  const scopeCopy = scopeLiterals(vueSrc, javaScopeLabels(javaSrc));
  if (scopeCopy.length) errs.push("Config.vue：把生效范围的中文原文当判断条件写进了面板（" + scopeCopy.join("、") + "），颜色就该只看下发的 tone——这正是上一轮漂掉的那张表");

  return {
    errs,
    info: [specs.length + " 个配置键的编辑器形态与风险档都能在面板里落地（" + forms.size + " 种形态 / " + tones.size + " 档色阶）"]
  };
}

/** 线上下发的那份：{data:[{key,scope,tone,form}]}（ApiResponse 包着）。 */
function judgeServed(payloadText, vueSrc) {
  const errs = [];
  let rows;
  try {
    const o = JSON.parse(payloadText);
    rows = Array.isArray(o) ? o : o.data;
  } catch (e) { /* 落到下面的空表判据 */ }
  if (!Array.isArray(rows) || !rows.length)
    return { errs: ["/admin/api/config/spec 没有返回说明数组（先确认后台登录态与端点还在）"], info: [] };

  const missing = rows.filter((r) => !r.form || !r.tone).map((r) => r.key);
  if (missing.length) errs.push("下发缺 form/tone：" + missing.join("、"));

  // 同一个 scope 只能有一种颜色；反过来说颜色相同没关系（部分生效与前后端共用都是"改前读说明"）
  const byScope = {};
  rows.forEach((r) => {
    if (!r.tone) return;
    if (byScope[r.scope] && byScope[r.scope] !== r.tone)
      errs.push("下发：scope「" + r.scope + "」在 " + r.key + " 上给了 " + r.tone + "，别处给的是 " + byScope[r.scope]);
    byScope[r.scope] = r.tone;
  });
  const retired = rows.find((r) => r.key === "recharge");
  const ui = rows.find((r) => r.scope === "仅前端");
  if (retired && ui && retired.tone === ui.tone)
    errs.push("下发：已退役的 recharge 与「仅前端」同色（" + retired.tone + "），那运营就分不出「改它没用」和「改它不影响结算」");
  const dims = vueToneDimKeys(vueSrc);
  rows.filter((r) => /退役/.test(String(r.scope || "")) && r.tone)
      .forEach((r) => { if (dims && dims.size && !dims.has(r.tone))
        errs.push("下发：" + r.key + " 的 scope 是「" + r.scope + "」却给了风险档「" + r.tone + "」，而面板的 dim 只标在 " + [...dims].join("、") + " 上——这一行看不出来它已退役"); });

  const forms = new Set(rows.filter((r) => r.form).map((r) => formKind(r.form)));
  crossCheck("/config/spec", forms, vueSrc, errs);
  const toneKeys = vueToneKeys(vueSrc);
  if (toneKeys) rows.forEach((r) => {
    if (r.tone && !toneKeys.has(r.tone) && r.tone !== undefined)
      errs.push("/config/spec：" + r.key + " 的风险档「" + r.tone + "」在 Config.vue 的 TONE 映射里没有");
  });

  return {
    errs,
    info: ["随包后台与线上同源：" + rows.length + " 个键的 form/tone 都有落地分支"]
  };
}

function main() {
  const mode = process.argv[2] || "java";
  let r;
  try {
    const vue = fs.readFileSync(VUE, "utf8");
    if (mode === "served") {
      r = judgeServed(fs.readFileSync(0, "utf8"), vue);
    } else {
      r = judgeJava(fs.readFileSync(JAVA, "utf8"), vue);
    }
  } catch (e) {
    console.error("admin-spec 跑不动：" + ((e && e.message) || e));
    process.exit(1);
  }
  r.errs.forEach((e) => console.error("✗ " + e));
  if (!r.errs.length) (r.info || []).forEach((i) => console.log(i));
  process.exit(r.errs.length ? 1 : 0);
}

module.exports = { judgeJava, judgeServed, vueBranches, vueToneKeys, vueToneDimKeys, javaSpecs, javaFormUniverse, javaToneTokens, javaScopeTones, formKind, VUE, JAVA };

if (require.main === module) main();
