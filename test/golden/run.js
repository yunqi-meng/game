/* 共享黄金向量表（H1）：一张 vectors.json 同时钉住"前端的预测"和"服务端的结算"。
 *
 * 为什么需要这一层：客户端不再写存档，但它**预测**结果，而预测是玩家看得见的——
 * 台面上放了什么、会走哪条方程式、原料值多少、批量能到几倍、有没有危险，
 * 这五件事在 engine.js / state.js 里有一份实现，在 GameEngine.java / EconomyService.java 里有一份权威实现。
 * 两份写法漂移的后果不是崩溃，是"界面写着 A 式、落账是 B 式"和"预览报价是假的"，
 * 这类问题人工回归根本扫不到（要同时改两端数值才会露出来）。
 *
 * 现在两端在同一批电路上重放同一张表：
 *   第 1 层（这里）：vm 载 data/*.js + engine.js + state.js，按向量摆好台面，跑客户端那一半字段；
 *   第 3 层（GoldenVectorsTest）：同一份 JSON 用 Snapshots.base() 起服务端快照，跑同一批字段 + react 结算。
 * 任何一侧动数值（改配比、改价格、改 pick 排序、改产率、改事故参数）都会有一层红。
 *
 * 用法：
 *   node test/golden/run.js             只跑前端那一半（validate.js 已经这么调）
 *   node test/golden/run.js --dump      把前端算出来的字段写回 vectors.json（有意改数值之后重新对表）
 *   node test/golden/run.js --merge     把 GoldenVectorsTest 以 -Dgolden.dump=true 产出的 java-dump.json 并进 expect.react
 *   bundle 从 stdin 进 node test/golden/check-deployed.js
 *                                       第 4 层（e2e）用：把玩家实际取到的那份内容再对一遍（判据就是这里的 bundleParity）
 * --dump / --merge 会改写本仓库文件，所以只在"这次数值改动是有意的"时候用，改完必须看 diff。
 */
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm");

const ROOT = path.join(__dirname, "..", "..");
const VFILE = path.join(__dirname, "vectors.json");
const BFILE = path.join(ROOT, "server", "src", "test", "resources", "content-bundle.json");
const JFILE = path.join(ROOT, "server", "src", "test", "java", "com", "chemera", "server", "game", "GoldenVectorsTest.java");
const ENG = path.join(ROOT, "frontend", "js", "engine.js");
const GAME = path.join(ROOT, "server", "src", "main", "java", "com", "chemera", "server", "game", "GameEngine.java");

/* 两端都算得出的字段：向量必须逐条钉住，少钉一条等于这条电路没人守（audit 里会红）。 */
const SHARED = ["matchIds", "pickId", "eq", "fx", "danger", "dangerId", "batchCap", "maxMultiplier",
  "repTier", "prices", "bonus"];
/* 只有客户端算的字段：cost 是"原料成本 → 产物价值"那行预览，服务端没有对应结算路径，
   但它依赖的物质价格与 pick 都已经在共判字段里钉住，所以这一条钉的是展示算法本身（含兜底价）。 */
const CLIENT_ONLY = ["cost"];

/* ---------- 客户端沙箱：和数据校验同一套装载方式 ---------- */
function loadClient() {
  const sb = { console };
  sb.window = sb; sb.globalThis = sb;
  vm.createContext(sb);
  ["js/data/elements.js", "js/data/compounds.js", "js/data/instruments.js", "js/data/reactions.js",
    "js/engine.js", "js/state.js"].forEach((f) => {
      const p = path.join(ROOT, "frontend", f);
      if (!fs.existsSync(p)) throw new Error("缺少 " + f);
      vm.runInContext(fs.readFileSync(p, "utf8"), sb, { filename: f });
    });
  if (!sb.window.CHEM || !sb.window.CHEM.engine || !sb.window.CHEM.state) {
    throw new Error("engine.js / state.js 没有挂出 CHEM.engine / CHEM.state");
  }
  return sb.window.CHEM;
}

/** 把向量里的现场摆到客户端：存档走唯一的写入口 setServer，台面走 engine.benches。 */
function setupClient(C, v) {
  const s = v.seed, m = v.mode || {}, b = v.bench || {};
  C.state.setServer({
    coins: s.coins, diamonds: s.diamonds || 0, exp: s.exp || 0, level: s.level, rep: s.rep || 0,
    realMode: !!s.realMode, packs: { el: !!s.packsEl },
    bag: s.bag || {}, discovered: s.discovered || {},
    reactionsKnown: s.reactionsKnown || {}, firstBonusTaken: s.firstBonusTaken || {},
    vessels: s.vessels || {}, equipment: s.equipment || {},
    lab: Object.assign({ storage: 0, safety: 0, bench: 0 }, s.lab),
    rooms: [s.room || "inorganic"], bi: 0,
    benchStates: [Object.assign({ vessel: "beaker", placed: {}, temp: "room", electrolysis: false }, b)],
    market: { date: s.marketDate || "", drift: s.drift || {} },
    insured: !!m.insured,
  }, 1);
  C.engine.init();
  /* 上一条向量的临时台必须显式清掉：engine.init() 只重建常驻台数组，不碰 tempBench / sandboxActive，
     而 E.cur() 优先返回 tempBench——漏了这一句，沙盒向量之后的每条都在重放沙盒台面，读数全是假的。 */
  C.engine.tempBench = null;
  C.engine.sandboxActive = false;
  C.engine.multiplier = m.multiplier || 1;
  if (m.sandbox) { C.engine.tempBench = C.engine.benches[0]; C.engine.sandboxActive = true; }
  return C.engine;
}

/** 客户端这一半能算出来的全部读数。字段名与 vectors.json 的 expect 一一对应。 */
function observeClient(C, v) {
  const E = C.engine, S = C.state;
  const rs = E.matches();
  const pk = E.pick();
  const di = E.dangerInfo();
  const probe = (v.probe) || {};
  return {
    matchIds: rs.map((r) => r.id),
    pickId: pk ? pk.id : null,
    eq: pk ? pk.eq : null,
    fx: pk ? (pk.fx || []) : [],
    danger: !!di,
    dangerId: di ? di.id : null,
    batchCap: E.batchCap(),
    maxMultiplier: pk ? E.maxMultiplier(pk) : null,
    cost: pk ? E.costPreview(pk) : null,
    repTier: S.repTier(),
    prices: (probe.prices || []).map((p) => ({ id: p.id, q: p.q || 0,
      buy: S.buyPrice(p.id), sell: S.sellPrice(p.id, p.q || 0) })),
    bonus: (probe.bonus || []).map((p) => ({ id: p.id,
      plain: S.discoverBonus(p.id, false), forReaction: S.discoverBonus(p.id, true) })),
  };
}

/* ---------- 比较：数字留浮点余量，对象按排序后的键比，数组按顺序比 ---------- */
function norm(x) {
  if (Array.isArray(x)) return x.map(norm);
  if (x && typeof x === "object") return Object.keys(x).sort().map((k) => [k, norm(x[k])]);
  return x;
}
function same(a, b) {
  if (typeof a === "number" || typeof b === "number") {
    if (a == null || b == null) return a === b;
    return Math.abs(Number(a) - Number(b)) < 1e-9;
  }
  return JSON.stringify(norm(a)) === JSON.stringify(norm(b));
}
function diff(what, want, got, errs) {
  if (!same(want, got)) {
    errs.push(what + "：期望 " + JSON.stringify(want) + "，客户端算出 " + JSON.stringify(got));
  }
}

/* ---------- 内容对齐：Java 那侧读的是 content-bundle.json，客户端读的是 data/*.js。
   两者一旦分叉，"同一张表"就变成各测各的内容，向量红得莫名其妙。这里逐条比回来。
   同一套判据跑两次：夹具一次（第 1 层，本地就有，保证"仓库里两端一致"），
   线上下发的那份一次（第 4 层 e2e 调 check-deployed.js，保证"玩家手里那份也在同一张表上"）。
   label 只出现在报错文案里，用来分辨红在哪一侧。 ---------- */
function bundleParity(C, bundle, label, errs) {
  const drop = (o) => {
    const x = Object.assign({}, o); delete x.__type; return x;
  };
  const key = (o) => JSON.stringify(norm(drop(o)));
  /* 红的时候必须点名是哪一列：整条记录的 JSON 截到 120 字，真正被改的那个字段常常正好在截断之后，
     于是"知道漂了、不知道漂在哪"，只能两个文件并排打开一行行看——回归文案的价值就在这一步。 */
  const cols = (a, b) => {
    const x = drop(a), y = drop(b), ks = {};
    Object.keys(x).concat(Object.keys(y)).forEach((k) => { ks[k] = 1; });
    return Object.keys(ks).filter((k) => JSON.stringify(norm(x[k])) !== JSON.stringify(norm(y[k]))).join(",");
  };

  const br = (bundle.content || {}).reaction || [], jr = C.REACTIONS || [];
  if (br.length !== jr.length) errs.push(label + "反应数不一致：data/reactions.js " + jr.length + " / " + label + " " + br.length);
  const jmap = {}; jr.forEach((r) => { jmap[r.id] = r; });
  br.forEach((r) => {
    const j = jmap[r.id];
    if (!j) return errs.push(label + "里多出一条客户端没有的反应：" + r.id + "（data/reactions.js 落后于 " + label + "）");
    if (key(j) === key(r)) return;
    const c = cols(j, r), first = c.split(",")[0];
    errs.push("同一反应两端字段不同：" + r.id + " 差的列是 " + c + "（" + first + "：客户端 " +
      JSON.stringify(norm(drop(j)[first])) + " / " + label + " " + JSON.stringify(norm(drop(r)[first])) + "）");
  });
  const brIds = {}; br.forEach((r) => { brIds[r.id] = 1; });
  jr.forEach((r) => { if (!brIds[r.id]) errs.push("客户端有、" + label + "里没有的反应：" + r.id); });
  const js = {}; C.ELEMENTS.concat(C.COMPOUNDS).forEach((s) => { js[s.id] = s; });
  ((bundle.content || {}).element || []).concat((bundle.content || {}).compound || []).forEach((s) => {
    const j = js[s.id];
    if (!j) return;
    if (j.price !== s.price) errs.push("同一物质两端价格不同：" + s.id + " 客户端 " + j.price + " / " + label + " " + s.price);
    if (j.level !== undefined && s.level !== undefined && j.level !== s.level) {
      errs.push("同一物质两端等级不同：" + s.id + " 客户端 " + j.level + " / " + label + " " + s.level);
    }
  });
  const jd = {}; (C.DANGERS || []).forEach((d) => { jd[d.id] = d; });
  ((bundle.content || {}).danger || []).forEach((d) => {
    const j = jd[d.id];
    if (!j) return errs.push(label + "里有客户端没有的危险组合：" + d.id);
    if (key(j) !== key(d)) errs.push("危险组合两端不同：" + d.id + " 差的列是 " + cols(j, d));
  });
  const jp = {}; (C.PROCESSES || []).forEach((p) => { jp[p.id] = p; });
  ((bundle.content || {}).process || []).forEach((p) => {
    const j = jp[p.id];
    if (!j) return errs.push(label + "里有客户端没有的工艺：" + p.id);
    if (key(j) !== key(p)) errs.push("工艺两端不同：" + p.id + " 差的列是 " + cols(j, p));
  });
  /* 仪器行也要比：批量上限与产率都看容器与设备的等级/造价，向量里 batchCap、maxMultiplier
     两条读数是踩着这份表算出来的，后台把某个 baseCost 或 kind 改一下，两边就算的不是同一台设备了。 */
  const ji = {}; (C.INSTRUMENTS || []).forEach((x) => { ji[x.id] = x; });
  const bi = (bundle.content || {}).instrument || [];
  if (bi.length !== (C.INSTRUMENTS || []).length) {
    errs.push("仪器条数两端不同：data/instruments.js " + (C.INSTRUMENTS || []).length + " / " + label + " " + bi.length);
  }
  bi.forEach((x) => {
    const j = ji[x.id];
    if (!j) return errs.push(label + "里有客户端没有的仪器：" + x.id);
    if (key(j) !== key(x)) errs.push("仪器两端不同：" + x.id + " 差的列是 " + cols(j, x));
  });
  const biIds = {}; bi.forEach((x) => { biIds[x.id] = 1; });
  (C.INSTRUMENTS || []).forEach((x) => { if (!biIds[x.id]) errs.push("客户端有、" + label + "里没有的仪器：" + x.id); });

  /* 两端各写一份的配置常数：bundle 的 config 与前端基线（G4 的三方对齐只管 app_config，
     这里管的是"向量表算出来的钱"依赖的那几个系数）。 */
  const cfg = bundle.config || {};
  const num = (name, fe, be) => {
    if (be == null) return;
    if (!same(fe, be)) errs.push("结算系数两端不同：" + name + " 客户端 " + fe + " / " + label + " " + be);
  };
  num("buy_rate", C.BUY_RATE, cfg.buy_rate);
  num("sell_rate", C.SELL_RATE, cfg.sell_rate);
  num("first_sell_bonus", C.FIRST_SELL_BONUS, cfg.first_sell_bonus);
  num("start_coins", C.START_COINS, cfg.start_coins);
  num("bench_max_lines", C.BENCH_MAX_LINES, cfg.bench_max_lines == null ? 6 : cfg.bench_max_lines);
  if (cfg.quality) cfg.quality.forEach((q) => {
    const fe = (C.QUALITY || []).find((x) => x.q === q.q);
    if (!fe) return errs.push("客户端缺少品质档 q=" + q.q);
    num("quality_mult#" + q.q, fe.mult, q.mult);
  });
  if (cfg.discover_bonus) Object.keys(cfg.discover_bonus).forEach((k) => {
    const fe = (C.DISCOVER_BONUS || {})[k];
    if (!fe) return errs.push("客户端缺少发现奖金档位 " + k);
    if (fe[0] !== cfg.discover_bonus[k][0] || fe[1] !== cfg.discover_bonus[k][1]) {
      errs.push("发现奖金区间两端不同：" + k);
    }
  });
}

/** 第 1 层用的夹具那一遍：缺夹具等于 Java 那半没有内容可读，直接红。 */
function fixtureParity(C, errs) {
  if (!fs.existsSync(BFILE)) return errs.push("缺少 " + BFILE + "（Java 那半没有夹具，向量表钉不住服务端）");
  bundleParity(C, JSON.parse(fs.readFileSync(BFILE, "utf8")), "夹具", errs);
}

/** 未知物质的估价：两端各写在自己的源文件里，只能靠正则对账（跑不起 Java 也能拦住）。 */
function unknownPriceParity(errs) {
  const js = fs.readFileSync(ENG, "utf8").match(/E\.UNKNOWN_PRICE\s*=\s*(\d+)/);
  const jav = fs.readFileSync(GAME, "utf8").match(/UNKNOWN_PRICE\s*=\s*(\d+)/);
  if (!js) return errs.push("engine.js 里找不到 E.UNKNOWN_PRICE（H1 的兜底价被改名了，请同步改这里）");
  if (!jav) return errs.push("GameEngine.java 里找不到 UNKNOWN_PRICE（同上）");
  if (js[1] !== jav[1]) errs.push("未知物质估价两端不同：客户端 " + js[1] + " / 服务端 " + jav[1]);
  return Number(js[1]);
}

/** 向量必须真的被两端消费：字段没钉满、或 Java 那半压根没读这张表，都算这一层失败。 */
function auditCoverage(vectors, errs) {
  if (vectors.length < 10) errs.push("向量表只剩 " + vectors.length + " 条，少于 10 条就等于放弃了这层回归");
  const seen = {};
  vectors.forEach((v) => {
    if (!v.id) return errs.push("有一条向量没有 id");
    if (seen[v.id]) errs.push("向量 id 重复：" + v.id);
    seen[v.id] = 1;
    if (!v.why) errs.push("向量 " + v.id + " 没写 why：一年后没人知道这条钉的是什么");
    const e = v.expect || {};
    SHARED.concat(CLIENT_ONLY).forEach((f) => {
      if (!(f in e)) errs.push("向量 " + v.id + " 没钉 expect." + f + "（客户端算得出来的字段，漏钉等于没守）");
    });
    if (!e.react) errs.push("向量 " + v.id + " 没钉 expect.react（服务端那一半没人比对）");
  });
  if (!fs.existsSync(JFILE)) errs.push("缺少 GoldenVectorsTest.java：这张表只有前端在读，服务端侧已经失守");
  else {
    const src = fs.readFileSync(JFILE, "utf8");
    if (!/@ParameterizedTest/.test(src)) errs.push("GoldenVectorsTest.java 没有 @ParameterizedTest（向量没被逐条跑）");
    if (!/vectors\.json/.test(src)) errs.push("GoldenVectorsTest.java 没有读 vectors.json");
  }
}

function runVectors(C, vectors) {
  const errs = [];
  vectors.forEach((v) => {
    const tag = "向量 " + v.id + "：";
    try {
      setupClient(C, v);
      const got = observeClient(C, v);
      const e = v.expect || {};
      diff(tag + "matchIds", e.matchIds, got.matchIds, errs);
      diff(tag + "pickId", e.pickId, got.pickId, errs);
      diff(tag + "eq", e.eq, got.eq, errs);
      diff(tag + "fx", e.fx, got.fx, errs);
      diff(tag + "danger", e.danger, got.danger, errs);
      diff(tag + "dangerId", e.dangerId, got.dangerId, errs);
      diff(tag + "batchCap", e.batchCap, got.batchCap, errs);
      diff(tag + "maxMultiplier", e.maxMultiplier, got.maxMultiplier, errs);
      diff(tag + "cost", e.cost, got.cost, errs);
      diff(tag + "repTier", e.repTier, got.repTier, errs);
      (e.prices || []).forEach((want, i) => {
        const g = got.prices[i] || {};
        ["buy", "sell"].forEach((k) => diff(tag + k + "(" + want.id + ")", want[k], g[k], errs));
      });
      (e.bonus || []).forEach((want, i) => {
        const g = got.bonus[i] || {};
        ["plain", "forReaction"].forEach((k) => diff(tag + k + "(" + want.id + ")", want[k], g[k], errs));
      });
    } catch (err) {
      errs.push(tag + "客户端跑挂了：" + ((err && err.message) || err));
    }
  });
  return errs;
}

/**
 * 自检：故意把客户端改坏一处，向量表必须"红在该红的那条上"。
 *
 * 这层最大的风险不是算错，是<b>钉了个寂寞</b>——某条电路压根没有向量经过它，于是改坏了也全绿，
 * 而"全绿"会让人以为两端对齐有回归守着。所以每次 audit 都拿几路篡改试一遍，每路都指定必须红在哪条
 * 向量上：指不到就报自检失败。这几路正好是 H1 这轮真正改过的五条电路（选式、价格、买价系数、
 * 批量上限、危险判定），以后哪条被向量化了，就往这里加一路。
 */
function selfCheck(vectors, errs) {
  const tampers = [
    ["E.pick 退回 matches()[0]（H1 之前的写法）", (C) => {
      C.engine.pick = function () { var rs = C.engine.matches(); return rs.length ? rs[0] : null; };
    }, "ratio-siblings-glucose"],
    ["NaCl 的价格 +1", (C) => {
      const s = (C.COMPOUNDS || []).find((x) => x.id === "NaCl");
      if (!s) return "内容表里找不到 NaCl，这条自检前提就没了";
      s.price += 1; return null;
    }, "neutralize-beaker"],
    ["买价系数 BUY_RATE 改成 1.25", (C) => { C.BUY_RATE = 1.25; return null; }, "neutralize-beaker"],
    ["batchCap 写死 10（忽略工作台等级与容器加成）", (C) => { C.engine.batchCap = function () { return 10; }; },
      "batch-cap-bench3"],
    ["dangerInfo 恒返回 null（危险混放不再预警）", (C) => { C.engine.dangerInfo = function () { return null; }; },
      "danger-boom-kclo3-s"],
  ];
  tampers.forEach(([name, apply, wantId]) => {
    let C;
    try { C = loadClient(); } catch (e) { return errs.push("自检装载客户端失败：" + e.message); }
    const why = apply(C);
    if (why) return errs.push("自检 " + name + " 无法执行：" + why);
    const found = runVectors(C, vectors);
    const hit = found.some((m) => m.indexOf("向量 " + wantId + "：") === 0);
    if (!hit) {
      errs.push("自检失败：把 " + name + " 改坏之后，向量 " + wantId +
        " 仍然全绿——这条电路没有被任何向量真正钉住，这层回归是假的");
    }
  });
}

function audit() {
  const errs = [], info = [];
  let vectors;
  try {
    vectors = JSON.parse(fs.readFileSync(VFILE, "utf8"));
  } catch (e) { return { errs: ["读不了 vectors.json：" + e.message], info }; }
  if (!Array.isArray(vectors)) return { errs: ["vectors.json 必须是数组"], info };

  let C;
  try { C = loadClient(); } catch (e) { return { errs: ["前端引擎装载失败：" + e.message], info }; }
  const price = unknownPriceParity(errs);
  fixtureParity(C, errs);
  auditCoverage(vectors, errs);
  errs.push.apply(errs, runVectors(C, vectors));
  selfCheck(vectors, errs);
  info.push("golden vectors: " + vectors.length + " 条，共判字段 " + SHARED.length + " 个 + 客户端单判 " +
    CLIENT_ONLY.length + " 个，夹具（data/*.js ↔ content-bundle.json）逐条对齐，未知物质估价 " + price +
    "，篡改自检 5 路");
  return { errs, info };
}

/* ---------- 改写模式：--dump 用客户端读数填 expect 的共判字段 ---------- */
function dump() {
  const C = loadClient();
  const vectors = JSON.parse(fs.readFileSync(VFILE, "utf8"));
  vectors.forEach((v) => {
    setupClient(C, v);
    const got = observeClient(C, v);
    v.expect = Object.assign({}, v.expect, {
      matchIds: got.matchIds, pickId: got.pickId, eq: got.eq, fx: got.fx,
      danger: got.danger, dangerId: got.dangerId, batchCap: got.batchCap,
      maxMultiplier: got.maxMultiplier, cost: got.cost, repTier: got.repTier,
      prices: got.prices, bonus: got.bonus,
    });
  });
  fs.writeFileSync(VFILE, JSON.stringify(vectors, null, 2) + "\n", "utf8");
  console.log("已把客户端读数写回 " + VFILE + "（" + vectors.length + " 条）；react 字段请用 --merge 并进来。");
}

/* ---------- --merge：把 java-dump.json 里的 react 段并进 expect ---------- */
function merge() {
  const dj = path.join(__dirname, "java-dump.json");
  if (!fs.existsSync(dj)) return console.error("没有 " + dj + "：先在 server/ 跑 mvn -Dgolden.dump=true -Dtest=GoldenVectorsTest test");
  const map = JSON.parse(fs.readFileSync(dj, "utf8"));
  const vectors = JSON.parse(fs.readFileSync(VFILE, "utf8"));
  let n = 0;
  vectors.forEach((v) => {
    if (!Object.prototype.hasOwnProperty.call(map, v.id)) return console.error("  ! java-dump 里没有 " + v.id);
    v.expect = Object.assign({}, v.expect, { react: map[v.id] }); n++;
  });
  fs.writeFileSync(VFILE, JSON.stringify(vectors, null, 2) + "\n", "utf8");
  console.log("并入 " + n + " 条 react；确认 diff 后删掉 java-dump.json。");
}

module.exports = { audit, loadClient, observeClient, setupClient, bundleParity, SHARED, CLIENT_ONLY };

if (require.main === module) {
  if (process.argv[2] === "--dump") dump();
  else if (process.argv[2] === "--merge") merge();
  else {
    const r = audit();
    r.info.forEach((i) => console.log("golden:", i));
    r.errs.forEach((e) => console.error("ERROR:", e));
    process.exit(r.errs.length ? 1 : 0);
  }
}
