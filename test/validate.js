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

warns.forEach((w) => console.warn("WARN:", w));
errs.forEach((e) => console.error("ERROR:", e));
console.log(errs.length ? "FAIL: " + errs.length + " errors" : "PASS ✔");
process.exit(errs.length ? 1 : 0);
