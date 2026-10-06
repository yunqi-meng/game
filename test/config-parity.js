/* 结算参数三方对齐（G4-5 的回归层）：node test/config-parity.js
 *
 * 为什么单独有这一层：G4 把升级曲线、单容器物质种数、事故概率与赔付、答题年级倍率、成就判定
 * 从 Java 字面量搬进了 app_config，于是仓库里同一件事剩下**三份写法**——
 *   1) 服务端读不到配置时的兜底：Content.java（Accident.DEFAULT / expNeeded / benchMaxLines / GRADE_DEFAULT）
 *   2) 迁移种下去的值：V12__data_driven_rules.sql
 *   3) 后端还没下发内容包时的前端基线：frontend/js/data/instruments.js（含 engine.js 的台位钳位）
 * 三份本来都来自同一批写死的数字，"迁移当天没有任何玩家结算变化"就靠它们逐字相同。
 * 以前的局面是只有 Java 那侧有单测钉着（EngineConfigValidatorTest），前端改个默认值没人拦——
 * 展示层一旦和服务端判定分叉，玩家看到的是"经验条说还差 30，服务端却已经升级"这类说不清的错。
 * 这一层不启动后端、不连数据库，直接读三份源文件对账，所以它在 ci.sh 第 1 层（validate.js）就能跑。
 *
 * 它同时钉住"只有一份词汇表"：成就的 metric 名必须出自 AchievementRule.Metric，
 * 前端 cond 解释器（state.js 的 ACH_NUM）必须覆盖其中所有数值式指标、且不自创指标。
 */
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm");

const ROOT = path.join(__dirname, "..");
const FE_DATA = "frontend/js/data/instruments.js";
const FE_ENGINE = "frontend/js/engine.js";
const FE_STATE = "frontend/js/state.js";
const JAVA_CONTENT = "server/src/main/java/com/chemera/server/game/Content.java";
const JAVA_RULE = "server/src/main/java/com/chemera/server/game/AchievementRule.java";
const V12 = "server/src/main/resources/db/migration/V12__data_driven_rules.sql";

const ACC_KEYS = ["hit_base", "hit_floor", "safety_step", "danger_base", "danger_floor", "danger_step",
  "loss_base", "loss_step", "loss_ratio", "repair_base", "repair_exp_mult", "repair_exp_floor",
  "protect_mult", "insured_refund"];

const eq = (a, b) => Math.abs(Number(a) - Number(b)) < 1e-12;
const sorted = (o) => Object.keys(o).sort().join(",");

function read(rel, errs) {
  const p = path.join(ROOT, rel);
  if (!fs.existsSync(p)) { errs.push("缺少文件 " + rel); return null; }
  return fs.readFileSync(p, "utf8");
}
/** 抓一个必填的正则组：抓不到就是"源文件形状变了，这条对齐检查失效了"，同样算红。 */
function grab(re, src, what, errs) {
  const m = re.exec(src);
  if (!m) errs.push("解析失败：" + what + "（对账脚本没跟上源文件的写法，请同步改 test/config-parity.js）");
  return m ? m[1] : null;
}
function grabAll(re, src) { const out = []; let m; while ((m = re.exec(src))) out.push(m); return out; }

/* ---------- 前端基线：和 validate.js 一样把数据文件跑进 vm，读到的是真要用的那份对象 ---------- */
function loadCHEM(errs) {
  const src = read(FE_DATA, errs);
  if (src == null) return null;
  const sb = { console };
  sb.window = sb; sb.globalThis = sb;
  vm.createContext(sb);
  try { vm.runInContext(src, sb, { filename: FE_DATA }); }
  catch (e) { errs.push("SYNTAX ERROR in " + FE_DATA + ": " + e.message); return null; }
  const C = sb.window.CHEM;
  if (!C) { errs.push(FE_DATA + " 未挂出 window.CHEM"); return null; }
  return C;
}

/* ---------- 服务端兜底 ---------- */
function javaAccident(src, errs) {
  const head = grab(/record Accident\(([\s\S]*?)\)\s*\{/, src, "Content.java 的 record Accident 字段表", errs);
  const def = grab(/static final Accident DEFAULT = new Accident\(([\s\S]*?)\);/, src, "Content.java 的 Accident.DEFAULT", errs);
  if (!head || !def) return null;
  const names = head.split(",").map((s) => s.trim()).filter(Boolean).map((s) => s.split(/\s+/).pop());
  const vals = def.split(",").map((s) => s.trim().replace(/L$/, ""));
  if (names.length !== vals.length) { errs.push("Accident.DEFAULT 的实参数(" + vals.length + ")与字段数(" + names.length + ")不等"); return null; }
  const o = {};
  names.forEach((n, i) => { o[n] = Number(vals[i]); });
  return o;
}
function javaNumbers(src, errs) {
  const get = (re, what) => { const v = grab(re, src, what, errs); return v == null ? null : Number(v); };
  return {
    expBase: get(/base\(\)\s*==\s*null\s*\?\s*(\d+)/, "expNeeded 的 base 兜底"),
    expCoef: get(/coef\(\)\s*==\s*null\s*\?\s*(\d+)/, "expNeeded 的 coef 兜底"),
    bench: get(/bench_max_lines\s*==\s*null\s*\?\s*(\d+)/, "benchMaxLines 的兜底"),
    benchCap: get(/Math\.min\((\d+), n\)/, "benchMaxLines 的上钳位"),
    quizReward: get(/quiz_reward\s*==\s*null\s*\?\s*(\d+)/, "quizReward 的兜底"),
  };
}
function javaGradeMult(src, errs) {
  const body = grab(/GRADE_DEFAULT =\s*Map\.of\(([^;]*)\);/, src, "Content.Config 的 GRADE_DEFAULT", errs);
  if (!body) return null;
  const o = {};
  grabAll(/"([^"]+)",\s*([\d.]+)/g, body).forEach((m) => { o[m[1]] = Number(m[2]); });
  return o;
}
function javaMetrics(src, errs) {
  const rows = grabAll(/^ {8}([A-Za-z]\w*)\("[^"]*", Kind\.(\w+)/gm, src);
  if (rows.length < 10) errs.push("解析失败：AchievementRule.Metric 词表（只找到 " + rows.length + " 条）");
  const num = [], member = [];
  rows.forEach((m) => (m[2] === "NUM" ? num : member).push(m[1]));
  return { all: rows.map((m) => m[1]), num, member };
}

/* ---------- V12 种子 ---------- */
function sqlConfig(src, key, errs) {
  const raw = grab(new RegExp("\\('" + key + "',\\s*'([^']*)'"), src, "V12 的 " + key + " 种子行", errs);
  if (raw == null) return null;
  try { return JSON.parse(raw.replace(/""/g, '"')); }
  catch (e) { errs.push("V12 的 " + key + " 不是合法 JSON: " + e.message); return null; }
}
function sqlBackfill(src, errs) {
  const rows = grabAll(/WHEN\s+'(\w+)'\s+THEN\s+'(\{[^']*\})'/g, src);
  if (rows.length < 10) errs.push("解析失败：V12 的成就条件回填（只找到 " + rows.length + " 条）");
  const o = {};
  rows.forEach((m) => {
    try { o[m[1]] = JSON.parse(m[2]); } catch (e) { errs.push("V12 回填 " + m[1] + " 不是合法 JSON"); }
  });
  return o;
}

/* ---------- 比较 ---------- */
function cmpNums(label, a, b, bName, errs) {
  if (!a || !b) return;
  const ka = Object.keys(a), kb = Object.keys(b);
  if (sorted(a) !== sorted(b)) { errs.push(label + " 的键集与 " + bName + " 不一致：[" + ka.join(",") + "] vs [" + kb.join(",") + "]"); return; }
  ka.forEach((k) => { if (!eq(a[k], b[k])) errs.push(label + "." + k + " 与 " + bName + " 不一致：前端/本侧 " + a[k] + " vs " + bName + " " + b[k]); });
}
function sameCond(x, y) {
  if (!x || !y) return false;
  if (x.metric !== y.metric) return false;
  if (x.metric === "discoveredSubstance" || x.metric === "knownReaction") return x.subject === y.subject && x.value == null;
  return String(x.op || "ge") === String(y.op || "ge") && Number(x.value) === Number(y.value) && x.subject == null;
}

function audit() {
  const errs = [], info = [];
  const C = loadCHEM(errs);
  const content = read(JAVA_CONTENT, errs), rule = read(JAVA_RULE, errs);
  const sql = read(V12, errs), engineSrc = read(FE_ENGINE, errs), stateSrc = read(FE_STATE, errs);
  if (!C || content == null || rule == null || sql == null || engineSrc == null || stateSrc == null)
    return { errs: errs.concat("源文件缺失，本次对齐检查未跑完"), info };

  const jAcc = javaAccident(content, errs);
  const jNum = javaNumbers(content, errs);
  const jGrade = javaGradeMult(content, errs);
  const vocab = javaMetrics(rule, errs);
  const sAcc = sqlConfig(sql, "accident", errs);
  const sLevel = sqlConfig(sql, "level_exp", errs);
  const sGrade = sqlConfig(sql, "quiz_grade_mult", errs);
  const sBench = sqlConfig(sql, "bench_max_lines", errs);
  const back = sqlBackfill(sql, errs);

  /* 1. 事故：三份必须是同一串数字（14 个键逐个比） */
  const feAcc = C.ACCIDENT;
  if (!feAcc) errs.push("前端缺少 CHEM.ACCIDENT 基线");
  else {
    if (sorted(feAcc) !== ACC_KEYS.slice().sort().join(",")) errs.push("CHEM.ACCIDENT 的键与 Accident 记录字段对不上：" + sorted(feAcc));
    cmpNums("CHEM.ACCIDENT", feAcc, jAcc, "Content.Accident.DEFAULT", errs);
    cmpNums("Content.Accident.DEFAULT", jAcc, sAcc, "V12 种子", errs);
    if (feAcc && !eq(feAcc.insured_refund, (C.insurancePct ? parseFloat(C.insurancePct()) / 100 : NaN)))
      errs.push("CHEM.insurancePct() 与 accident.insured_refund 不符：" + C.insurancePct + " vs " + feAcc.insured_refund);
  }

  /* 2. 升级曲线：兜底、种子、前端基线三处一致，且前端那条函数算出来的数与服务端公式逐档相等 */
  if (!C.LEVEL_EXP_CFG) errs.push("前端缺少 CHEM.LEVEL_EXP_CFG 基线");
  else if (jNum.expBase != null && jNum.expCoef != null) {
    if (!eq(C.LEVEL_EXP_CFG.base, jNum.expBase)) errs.push("CHEM.LEVEL_EXP_CFG.base " + C.LEVEL_EXP_CFG.base + " != expNeeded 兜底 " + jNum.expBase);
    if (!eq(C.LEVEL_EXP_CFG.coef, jNum.expCoef)) errs.push("CHEM.LEVEL_EXP_CFG.coef " + C.LEVEL_EXP_CFG.coef + " != expNeeded 兜底 " + jNum.expCoef);
    if (sLevel && (!eq(sLevel.base, jNum.expBase) || !eq(sLevel.coef, jNum.expCoef)))
      errs.push("V12 的 level_exp 种子 " + JSON.stringify(sLevel) + " != 服务端兜底 base=" + jNum.expBase + ",coef=" + jNum.expCoef);
  }
  if (typeof C.LEVEL_EXP === "function" && jNum.expBase != null) {
    for (let lv = 1; lv <= 30; lv++) {
      const want = Math.max(1, jNum.expBase + jNum.expCoef * lv * lv);
      if (C.LEVEL_EXP(lv) !== want) { errs.push("CHEM.LEVEL_EXP(" + lv + ")=" + C.LEVEL_EXP(lv) + "，服务端 expNeeded 是 " + want + "（经验条会和服务端判定分叉）"); break; }
    }
  } else errs.push("前端缺少 CHEM.LEVEL_EXP 函数");

  /* 3. 单容器物质种数：默认值与上钳位都要一致，否则"提示还能放 6 种、服务端收到第 7 种才拒" */
  if (jNum.bench != null && !eq(C.BENCH_MAX_LINES, jNum.bench)) errs.push("CHEM.BENCH_MAX_LINES " + C.BENCH_MAX_LINES + " != benchMaxLines 兜底 " + jNum.bench);
  if (sBench != null && !eq(Number(sBench), jNum.bench)) errs.push("V12 的 bench_max_lines 种子 " + sBench + " != 服务端兜底 " + jNum.bench);
  const feMax = /E\.maxLines = function \(\) \{([\s\S]*?)\};/.exec(engineSrc);
  if (!feMax) errs.push("解析失败：engine.js 的 E.maxLines（前端还在写死台位数？）");
  else {
    const cap = Number(grab(/Math\.min\((\d+)/, feMax[1], "engine.js 的台位上钳位", errs));
    const dft = Number(grab(/:\s*(\d+)\s*;?\s*$/, feMax[1], "engine.js 的台位兜底值", errs));
    if (cap !== jNum.benchCap) errs.push("engine.js 上钳位 " + cap + " != 服务端 " + jNum.benchCap);
    if (dft !== jNum.bench) errs.push("engine.js 兜底 " + dft + " != 服务端兜底 " + jNum.bench);
    if (/var MAX_LINES\s*=|E\.MAX_LINES\s*=/.test(engineSrc)) errs.push("engine.js 里又出现了写死的 MAX_LINES 常量");
  }

  /* 4. 答题倍率与基础奖励：表一致 + 前端 quizReward 用的算法与服务端同（基础值×倍率后取整） */
  cmpNums("CHEM.QUIZ_GRADE_MULT", C.QUIZ_GRADE_MULT, jGrade, "Content.Config.GRADE_DEFAULT", errs);
  cmpNums("Content.Config.GRADE_DEFAULT", jGrade, sGrade, "V12 种子", errs);
  if (jNum.quizReward != null && !eq(C.QUIZ_REWARD, jNum.quizReward)) errs.push("CHEM.QUIZ_REWARD " + C.QUIZ_REWARD + " != quizReward 兜底 " + jNum.quizReward);
  if (typeof C.quizReward === "function" && jGrade && sGrade) {
    Object.keys(jGrade).forEach((g) => {
      const want = Math.round(C.QUIZ_REWARD * sGrade[g]);
      if (C.quizReward(g) !== want) errs.push("CHEM.quizReward(" + g + ")=" + C.quizReward(g) + "，按 V12 倍率该是 " + want);
    });
  } else errs.push("前端缺少 CHEM.quizReward 函数");

  /* 5. 成就：每行都得有条件、条件里的指标引擎念得出来、且与 V12 回填逐字相同 */
  const ach = C.ACHIEVEMENTS || [];
  if (!ach.length) errs.push("前端缺少 CHEM.ACHIEVEMENTS");
  if (vocab && vocab.all.length) {
    const known = new Set(vocab.all);
    ach.forEach((a) => {
      if (!a.cond || !a.cond.metric) return errs.push("成就基线缺少 cond（无条件的行在两端都永远判未达成）: " + a.id);
      if (!known.has(a.cond.metric)) errs.push("成就 " + a.id + " 的 metric \"" + a.cond.metric + "\" 不在 AchievementRule.Metric 词表里");
      if (!back[a.id]) return errs.push("V12 没有回填成就 " + a.id + " 的条件（bundle 下发后前端会把这条基线换成带 cond 的行，但对不上就说明两边有一边漏改）");
      if (!sameCond(a.cond, back[a.id]))
        errs.push("前端 " + a.id + " 的 cond " + JSON.stringify(a.cond) + " != V12 回填 " + JSON.stringify(back[a.id]));
    });
    Object.keys(back).forEach((id) => { if (!ach.some((a) => a.id === id)) errs.push("V12 回填了 " + id + "，但前端基线里没有这行成就"); });
    info.push("achievements: " + ach.length + " 行带 cond / 回填 " + Object.keys(back).length + " 条 / 词表 " + vocab.all.length + " 个指标");
  }

  /* 6. 前端 cond 解释器只能念服务端那套词汇，既不缺项也不自创 */
  if (vocab) {
    const block = /var ACH_NUM = \{([\s\S]*?)\n {2}\};/.exec(stateSrc);
    if (!block) errs.push("解析失败：state.js 的 ACH_NUM（客户端已退回按 id 硬判成就？）");
    else {
      const keys = grabAll(/^ {4}([A-Za-z]\w*):/gm, block[1]).map((m) => m[1]);
      const set = new Set(keys);
      vocab.num.forEach((m) => { if (!set.has(m)) errs.push("客户端读不出指标 " + m + "（面板点亮进度会与后端判定分叉）"); });
      keys.forEach((k) => { if (!vocab.all.includes(k)) errs.push("客户端自创了服务端没有的指标 " + k); });
      if (/S\.achDone[\s\S]{0,400}case\s+"a/.test(stateSrc)) errs.push("state.js 的 S.achDone 里又出现了按成就 id 硬判的 switch");
    }
    vocab.member.forEach((m) => {
      if (stateSrc.indexOf('c.metric === "' + m + '"') < 0) errs.push("客户端未处理成员式指标 " + m);
    });
    /* 比较符的语义也钉一下形状：把 ge 写成 gt 会让"累计 100 次"这类成就提前/延后点亮，
       而这种偏差只有玩家看得到、单测看不到，所以宁可拦一次写法改动让人来复核。 */
    [["ge", "v >= c.value"], ["gt", "v > c.value"], ["le", "v <= c.value"], ["eq", "v === c.value"]]
      .forEach(([op, body]) => {
        const re = new RegExp('case "' + op + '": return ' + body.replace(/[<>!=]/g, "\\$&") + ";");
        if (!re.test(stateSrc))
          errs.push('state.js 的 op "' + op + '" 判定写法变了（应为 return ' + body + "），与服务端 AchievementRule.Op 需重新对齐");
      });
  }

  /* 7. 界面文案不许再留第二个答案：理赔比例一律读 CHEM.insurancePct()，
        index.html 里那个初始值只当"JS 还没跑到"的占位，必须等于配置里的比例。 */
  ["frontend/js/ui.js", "frontend/js/panels.js"].forEach((f) => {
    const s = read(f, errs);
    if (s == null) return;
    const bad = grabAll(/理赔\s*50\s*%|理赔'\s*\+\s*50/g, s);
    if (bad.length) errs.push(f + " 仍把理赔比例写死成 50%（应读 CHEM.insurancePct()）：" + bad.length + " 处");
  });
  const html = read("frontend/index.html", errs);
  if (html != null) {
    const pct = grab(/data-ins-pct[^>]*>\s*(\d+)\s*%/ , html, "index.html 保险文案的初始比例", errs);
    if (pct != null && feAcc && Number(pct) !== Math.round(feAcc.insured_refund * 100))
      errs.push("index.html 的初始理赔比例 " + pct + "% 与 accident.insured_refund " + feAcc.insured_refund + " 不符");
  }

  info.push("accident/level_exp/bench/quiz 三方对齐；V12 种子 == 服务端兜底 == 前端基线");
  return { errs, info };
}

module.exports = { audit: audit };

if (require.main === module) {
  const r = audit();
  (r.info || []).forEach((i) => console.log("parity:", i));
  (r.errs || []).forEach((e) => console.error("ERROR:", e));
  console.log(r.errs.length ? "FAIL: config-parity " + r.errs.length + " errors" : "PASS ✔ config-parity");
  process.exit(r.errs.length ? 1 : 0);
}
