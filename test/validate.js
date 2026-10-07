/* 数据校验：node test/validate.js */
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm");
const root = path.join(__dirname, "..", "frontend");
const sandbox = {};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;
vm.createContext(sandbox);
["js/data/elements.js", "js/data/compounds.js", "js/data/instruments.js", "js/data/reactions.js"].forEach((f) => {
  const p = path.join(root, f);
  if (!fs.existsSync(p)) { console.error("MISSING FILE:", f); process.exit(1); }
  try { vm.runInContext(fs.readFileSync(p, "utf8"), sandbox, { filename: f }); }
  catch (e) { console.error("SYNTAX ERROR in " + f + ":", e.message); process.exit(1); }
});
const C = sandbox.window.CHEM;
const errs = [], warns = [];
const ids = new Set();
(C.ELEMENTS || []).forEach((e) => ids.add(e.id));
(C.COMPOUNDS || []).forEach((e) => ids.add(e.id));
ids.add("SLAG");
console.log("elements:", (C.ELEMENTS || []).length, " compounds:", (C.COMPOUNDS || []).length,
  " instruments:", (C.INSTRUMENTS || []).length, " reactions:", (C.REACTIONS || []).length, " dangers:", (C.DANGERS || []).length);

if ((C.ELEMENTS || []).length !== 118) errs.push("元素数量应为118，实际 " + (C.ELEMENTS || []).length);
const dupS = {};
(C.ELEMENTS || []).concat(C.COMPOUNDS || []).forEach((s) => {
  if (dupS[s.id]) errs.push("物质ID重复: " + s.id);
  dupS[s.id] = 1;
});
const REQUIRED_SUBS = ["H2", "O2", "Na", "H2O", "CO2", "CaCO3", "HCl", "NaOH", "SLAG"];
REQUIRED_SUBS.forEach((r) => { if (!dupS[r]) errs.push("缺少关键物质: " + r); });

const rid = new Set(), sig = new Set();
const VESSELS = new Set(["testtube", "beaker", "flask", "evapor", "crucible", "spoon"]);
const ratioGroups = {};
const FX = new Set(["bubble", "smoke", "flame", "glow", "precip", "dissolve", "colorchange"]);
(C.REACTIONS || []).forEach((r, i) => {
  const tag = "R#" + (r.id || i);
  if (rid.has(r.id)) errs.push(tag + " 反应ID重复"); rid.add(r.id);
  Object.keys(r.reactants || {}).forEach((k) => { if (!ids.has(k)) errs.push(tag + " 反应物不存在: " + k); });
  Object.keys(r.products || {}).forEach((k) => { if (!ids.has(k)) errs.push(tag + " 产物不存在: " + k); });
  if (r.conditions && r.conditions.catalyst && !ids.has(r.conditions.catalyst)) errs.push(tag + " 催化剂不存在: " + r.conditions.catalyst);
  (r.instrument || []).forEach((v) => { if (!VESSELS.has(v)) errs.push(tag + " 容器不存在: " + v); });
  (r.fx || []).forEach((f) => { if (!FX.has(f)) warns.push(tag + " 未知fx: " + f); });
  if (!r.eq || !r.type || !r.phenomenon) errs.push(tag + " 缺少字段 eq/type/phenomenon");
  if (typeof r.discoverLv !== "number" || r.discoverLv < 1 || r.discoverLv > 20) errs.push(tag + " discoverLv 非法");
  if (!r.products || !Object.keys(r.products).length) errs.push(tag + " 无产物");
  const temp = r.conditions && r.conditions.temp || "room";
  const cat = r.conditions && r.conditions.catalyst || "";
  const elec = !!r.conditions && !!r.conditions.electrolysis;
  const sigKey = JSON.stringify([temp, cat, elec, Object.keys(r.reactants).sort().map((k) => k + ":" + r.reactants[k]).sort()]);
  if (sig.has(sigKey)) warns.push(tag + " 反应物(含配比)+条件完全重复的真·重复反应"); sig.add(sigKey);
  const ratioKey = JSON.stringify([temp, cat, elec, Object.keys(r.reactants).sort()]);
  (ratioGroups[ratioKey] || (ratioGroups[ratioKey] = [])).push(r.id);
});
(C.DANGERS || []).forEach((d, i) => {
  Object.keys(d.reactants || {}).forEach((k) => { if (!ids.has(k)) errs.push("D#" + (d.id || i) + " 物质不存在: " + k); });
});
const warnCounts = {};
(C.REACTIONS || []).forEach((r) => { warnCounts[r.type] = (warnCounts[r.type] || 0) + 1; });
console.log("反应类型分布:", JSON.stringify(warnCounts));
const ratioVariants = Object.values(ratioGroups).filter((g) => g.length > 1);
console.log("同反应物不同配比的兄弟组（引擎按投放量精确消耗消歧）:", ratioVariants.length, JSON.stringify(ratioVariants));
/* ---------- 仪器线稿图标：js/icons.js 是图标的唯一真源 ----------
   起因是"烧杯显示成实验服"那类 emoji 错配；改成手绘图形后，漏画/画歪必须被机器拦住，
   所以这里连着判四件事：覆盖率、几何合法性（越框与液体溢出）、图形不重复、能渲染出 svg。 */
const ICON = (() => {
  const f = "js/icons.js";
  const p = path.join(root, f);
  if (!fs.existsSync(p)) { errs.push("缺少 " + f); return null; }
  try { vm.runInContext(fs.readFileSync(p, "utf8"), sandbox, { filename: f }); }
  catch (e) { errs.push("SYNTAX ERROR in " + f + ": " + e.message); return null; }
  const ic = sandbox.window.CHEM && sandbox.window.CHEM.icon;
  if (!ic || !ic.SHAPES) { errs.push("js/icons.js 未导出 CHEM.icon.SHAPES"); return null; }
  return ic;
})();
if (ICON) {
  const geom = require("./icon-geom.js");
  const SH = ICON.SHAPES, VB = ICON.VB || 48;
  if (!SH.other) errs.push("icons.js 缺少兜底图形 other");
  (C.INSTRUMENTS || []).forEach((i) => {
    if (!SH[i.id]) return errs.push("仪器缺少专属图标: " + i.id + "/" + i.zh);
    if (i.kind === "vessel" && !SH[i.id].l) errs.push("容器没有液体层(装了东西也看不出来): " + i.id);
    geom.auditShape(i.id, SH[i.id], VB).forEach((m) => errs.push("图标几何不合格: " + m));
    const svg = ICON.of(i, { liq: "#42a5f5" });
    const wantWet = !!SH[i.id].l;   /* 天平/铁架台这类不盛液体的设备，本来就不该出现 wet */
    if (!/^<svg class="v-ic/.test(svg) || svg.indexOf("v-o") < 0) errs.push("图标渲染异常: " + i.id);
    if (wantWet !== (svg.indexOf(" wet") > 0)) errs.push("液体层显示状态异常: " + i.id);
  });
  Object.keys(SH).forEach((k) => geom.auditShape(k, SH[k], VB).forEach((m) => errs.push("图标几何不合格: " + m)));
  const sig = {};
  Object.keys(SH).forEach((k) => {
    const key = JSON.stringify(SH[k]);
    (sig[key] || (sig[key] = [])).push(k);
  });
  Object.values(sig).forEach((ks) => { if (ks.length > 1) errs.push("两个仪器共用同一张图形，玩家分不出: " + ks.join(",")); });
  /* 颜色来自后台可编辑的物质数据，进 style 前必须被过滤 */
  const dirty = ICON.draw("beaker", { liq: 'red; background:url(//evil)' });
  if (/evil|;/.test(dirty)) errs.push("图标未过滤非法颜色，存在 CSS 注入: " + dirty.slice(0, 60));
  console.log("instrument icons:", Object.keys(SH).length, "shapes /", (C.INSTRUMENTS || []).length, "instruments");
}

/* F7：CSS 兼容兜底裁判（color-mix / inset / dvh 都必须带老写法），见 test/css-guard.js */
(() => {
  const f = "css/style.css";
  const p = path.join(root, f);
  if (!fs.existsSync(p)) return errs.push("缺少 " + f);
  const guard = require("./css-guard.js");
  const found = guard.audit(fs.readFileSync(p, "utf8"));
  found.forEach((e) => errs.push(f + " " + e));
  console.log("css fallback guard:", found.length ? found.length + " 处缺兜底" : "ok");
})();

/* G4：结算参数的三方对齐（前端基线 == Java 兜底 == V12 种子）+ 成就 cond 词汇表单一来源。
   见 test/config-parity.js：这一层不连数据库，所以后端没起也能拦住"展示与判定分叉"。 */
(() => {
  const parity = require("./config-parity.js");
  const r = parity.audit();
  r.errs.forEach((e) => errs.push("config-parity: " + e));
  (r.info || []).forEach((i) => console.log("config parity:", i));
})();

/* H1：两端引擎共享的黄金向量表（test/golden/run.js + vectors.json）。
   上面那节管的是"配置常数别分叉"，这一节管的是"拿这些常数算出来的结果别分叉"：
   同一批台面在 vm 里跑客户端的 engine.js/state.js，读数钉进 vectors.json，
   第 3 层的 GoldenVectorsTest 再用同一份表跑服务端结算。
   这一层还顺手判三件事：夹具（data/*.js ↔ content-bundle.json）逐条对齐、
   向量字段有没有钉满、以及 GoldenVectorsTest 还在不在读这张表——
   有人删掉那半边，这张表就退化成只测前端，所以让它在这里红。 */
(() => {
  const golden = require("./golden/run.js");
  const r = golden.audit();
  r.errs.forEach((e) => errs.push("golden: " + e));
  (r.info || []).forEach((i) => console.log("golden vectors:", i));
})();

/* H3：整页重绘与重复计算的收口（见 test/render-budget.js）。
   图鉴 214 张卡、方程 143 行、材料柜 213 项——这类账在低端 WebView 上是首屏固定开销，
   而它坏了不会报错，只是"卡一下"，所以只能拿静态裁判钉住：分页切不切得动、
   按帧记忆在不在、物质查表的失效点绑的是内容版本还是 revision。
   这里判仓库里那两份，e2e 用同一个裁判判服务端下发的那两份——少一边，改在仓库忘了出包就漏过去了。 */
(() => {
  const rb = require("./render-budget.js");
  [["panels", "js/panels.js"], ["state", "js/state.js"]].forEach(([mode, rel]) => {
    const f = path.join(root, rel);
    if (!fs.existsSync(f)) return errs.push("render-budget: 缺少 " + rel);
    const r = rb.judge(mode, fs.readFileSync(f, "utf8"));
    r.errs.forEach((e) => errs.push("render-budget(" + mode + "): " + e));
    if (!r.errs.length) console.log("render budget (" + rel + "):", r.info);
  });
})();

/* H4：音频点火时机与触感（见 test/audio-haptics.js）。
   在线版每条音效都是服务端结算回来才响，那一次 new AudioContext 落在手势外——
   WebView 里它一起就是 suspended，resume() 又被自动播放策略拒掉，整局静音且界面上没有任何异常。
   这类"顺序错了/条件错了"的账 grep 判不了，所以裁判把触感那段切出来喂假 navigator 真跑：
   该振的三处各振多久、没这个 API／调用抛错／页面不在前台三层容错、静音时振不振、
   滑条会不会绕过音乐开关。判仓库里那两份，e2e 用同一个裁判判服务端下发的那两份。 */
(() => {
  const ah = require("./audio-haptics.js");
  [["sfx", "js/sfx.js"], ["panels", "js/panels.js"]].forEach(([mode, rel]) => {
    const f = path.join(root, rel);
    if (!fs.existsSync(f)) return errs.push("audio-haptics: 缺少 " + rel);
    const r = ah.judge(mode, fs.readFileSync(f, "utf8"));
    r.errs.forEach((e) => errs.push("audio-haptics(" + mode + "): " + e));
    if (!r.errs.length) console.log("audio haptics (" + rel + "):", r.info);
  });
  // 三个结果时刻之外不许有第二个 vibrate 入口，否则本机开关与特性检测就被人绕开
  const dir = path.join(root, "js");
  fs.readdirSync(dir).filter((x) => x.endsWith(".js") && x !== "sfx.js").forEach((x) => {
    const s = fs.readFileSync(path.join(dir, x), "utf8");
    if (/navigator\.vibrate|\bvibrate\(/.test(s)) errs.push("audio-haptics: " + x + " 里直接调了 vibrate（振动入口只该在 sfx.buzz）");
  });
})();

/* H6：后台【运营配置】的描述符对账（见 test/admin-spec.js）。
   上一轮的债是"改了 admin/src 忘了出包"，由 admin-dist-check 拿构建产物拦；这一轮同一类债换了头：
   面板自己抄了一份「键名 → 编辑器形态」和一份「scope → 颜色」，后端加了「已退役」那一档之后
   那两份表没人跟，于是"改它什么都不发生"的退役键显示得和"改它不影响结算"一样温和。
   现在两份表都删了，颜色与控件由 ConfigSpec 的 tone/form 下发，于是账变成"Java 那三个集合与
   Vue 那些分支会不会分叉"——跨语言、而且"少了哪个分支"根本没有文本可搜，grep 判不动，只能对账。
   这一节只读两份源码（不连后端）；第 4 层用同一个裁判判线上真的下发的那些 form/tone。 */
(() => {
  const as = require("./admin-spec.js");
  if (!fs.existsSync(as.JAVA)) return errs.push("admin-spec: 找不到 ConfigSpec.java，对账没有依据");
  if (!fs.existsSync(as.VUE)) return errs.push("admin-spec: 找不到 admin/src/views/Config.vue，对账没有依据");
  const r = as.judgeJava(fs.readFileSync(as.JAVA, "utf8"), fs.readFileSync(as.VUE, "utf8"));
  r.errs.forEach((e) => errs.push("admin-spec: " + e));
  if (!r.errs.length) console.log("admin config spec:", r.info);
})();

/* H6：后台写入乐观锁的两端对账（见 test/admin-lock.js）。
   这一轮的债是跨语言的一对：服务端把 /content/item、/config 这类行写入改成了条件写
   （版本号就是那一行的 updatedAt，对不上回真 409），面板负责把它读出来、带回去。
   "少了一处调用没带版本号"在文本上没有任何错字可搜，Java 单测又看不见 .vue，所以拿两端源码对账：
   受守卫的写入口从 Java 的注解与方法体里读出来（这里不抄端点清单），admin/src 的每个调用点都要命中。
   顺带钉住客户端的分流依据——按 409 走「载入最新」，不按文案走（文案会改字），且两个面板共用一个出口。 */
(() => {
  const lock = require("./admin-lock.js");
  const r = lock.judgeFiles();
  r.errs.forEach((e) => errs.push(e));
  if (!r.errs.length) (r.info || []).forEach((i) => console.log("admin-lock:", i));
})();

/* H6：分页与总数的两端对账（见 test/admin-paging.js）。
   上一轮的面板在自己算总数：`d.length < size ? ... : page*size + 1`——服务端返的是裸 List，
   它根本没有总数可用，于是"共 41 条"其实是"这一页有几条"，翻页翻到第 2 页数字就开始骗人。
   现在服务端一律回 {rows,total}（Page 是唯一构造点），债换了形状：
   "某个端点改了返回形状、某个 .vue 还按老样子读"在两端各自都是合法代码，grep 判不动，只能对账。
   这一节从 Java 的签名里读出哪些 GET 是分页端点（不抄清单），再要求 admin/src 的每个调用点
   读 .rows、带 size/off、至少有一处读 .total，且el-pagination 绑的那个变量确实来自 .total。 */
(() => {
  const pg = require("./admin-paging.js");
  const r = pg.judgeFiles();
  r.errs.forEach((e) => errs.push(e));
  if (!r.errs.length) (r.info || []).forEach((i) => console.log("admin-paging:", i));
})();

/* H7：意图覆盖面的两端对账（见 test/intent-coverage.js）。
   这个游戏的玩法面就是 POST /api/game/{intent} 一个口子：Java 的 switch(intent) 加分支，
   前端多一个按钮，回归脚本若没跟着发那一枚意图，那条分支就永远只在脑测里跑过。
   "少测了一个意图"在两份文件里都是合法代码，grep 判不动，只能对账：
   全量意图从 GameService.java 的 switch(intent) 里读（不抄清单），已发意图从 e2e-api.sh 的
   请求构造里读（含 act/act2 这类 helper 与 data-driven 表），差集必须为空——
   除非那条意图在当前协议下真的正向日做不到，那要进 WHITELIST 并写一句理由，
   否则不许假绿；WHITELIST 里的理由失效（意图已被测到或已从 Java 消失）同样报错。 */
(() => {
  const ic = require("./intent-coverage.js");
  const r = ic.judgeFiles();
  r.errs.forEach((e) => errs.push(e));
  if (!r.errs.length) (r.info || []).forEach((i) => console.log("intent coverage:", i));
})();

/* H5：无障碍与可达性的静态裁判（对比度 / 命中区 / ARIA 结构 / 物质底色），见 test/a11y.js。
   这类问题截图看不出来、评审也容易划过：色值差一档、按钮矮 15px，代价却是某类玩家用不了。
   样式在 token 层判，所以三套皮肤一次判完；底色是内容数据，直接把沙箱里那份调色板递过去，
   顺带判"前端改过的色有没有同步进 Flyway 迁移"——不需要浏览器也不需要后端。 */
(() => {
  const cp = path.join(root, "css/style.css"), hp = path.join(root, "index.html");
  if (!fs.existsSync(cp)) return errs.push("缺少 css/style.css");
  if (!fs.existsSync(hp)) return errs.push("缺少 index.html");
  const a11y = require("./a11y.js");
  const r = a11y.audit(fs.readFileSync(cp, "utf8"), fs.readFileSync(hp, "utf8"),
    (C.ELEMENTS || []).concat(C.COMPOUNDS || []));
  r.errs.forEach((e) => errs.push(e));
  r.info.forEach((i) => console.log("a11y:", i));
})();

/* H2：请求超时与"回调恰好一次"（见 test/cloud-timeout.js）。这一条要等人造的定时器落地，
   所以放在最后并让汇总阶段异步等它——其余各节都是同步的，照旧先跑完。 */
const cloudTimeout = require("./cloud-timeout.js");
function report() {
  warns.forEach((w) => console.warn("WARN:", w));
  errs.forEach((e) => console.error("ERROR:", e));
  console.log(errs.length ? "FAIL: " + errs.length + " errors" : "PASS ✔");
  process.exit(errs.length ? 1 : 0);
}
Promise.resolve()
  .then(() => cloudTimeout.run())
  .then((extra) => { (extra || []).forEach((m) => errs.push("cloud-timeout: " + m)); report(); },
        (e) => { errs.push("cloud-timeout 崩了: " + ((e && e.stack) || e)); report(); });
