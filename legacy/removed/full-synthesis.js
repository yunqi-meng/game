/* 全量合成回归：驱动引擎逐条验证所有反应/工艺能否按其声明条件正常合成。
   node test/full-synthesis.js */
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm");
const root = path.join(__dirname, "..");
const sb = { console: { log() {}, warn() {}, error() {} }, setInterval() {},
  localStorage: { getItem: () => null, setItem() {}, removeItem() {} }, location: {}, Date, Math, JSON };
sb.window = sb; sb.globalThis = sb;
const ctx = vm.createContext(sb);
["js/data/elements.js", "js/data/compounds.js", "js/data/reactions.js", "js/data/instruments.js", "js/state.js", "js/engine.js"]
  .forEach((f) => vm.runInContext(fs.readFileSync(path.join(root, f), "utf8"), ctx, { filename: f }));
const CHEM = sb.CHEM, st = CHEM.state, E = CHEM.engine;
// 决定性：让所有 Math.random() 概率分支走“成功/无事故/无产率损耗”路径
sb.Math.random = () => 0.999;

st.load(); E.init();
st.data.realMode = true;               // 真实模式：条件必须满足，逐条验证更严格
st.data.level = 20;                    // 放开等级（matches 不卡等级，但成就/解锁副作用无碍）
st.data.lab.safety = 5;                // 拉满安全，配合 random 高值消除事故
["lamp", "blowtorch", "electrolyzer", "centrifuge", "phmeter", "thermometer", "stand", "dropper", "spatula"].forEach((k) => (st.data.equipment[k] = true));

const instr = (id) => (CHEM.INSTRUMENTS || []).find((x) => x.id === id);
const sigOf = (r) => JSON.stringify([Object.keys(r.reactants || {}).sort(), (r.conditions && r.conditions.temp) || "room",
  (r.conditions && r.conditions.catalyst) || "", !!(r.conditions && r.conditions.electrolysis)]);

function setupBench(r) {
  const b = E.cur();
  b.placed = {}; b.temp = (r.conditions && r.conditions.temp) || "room";
  b.electrolysis = !!(r.conditions && r.conditions.electrolysis);
  // 选择容器：优先满足 r.instrument，且避开“不可加热 yet 需要升温”的冲突
  let vessel = "beaker";
  if (r.instrument && r.instrument.length) {
    const needHeat = b.temp !== "room";
    vessel = r.instrument.find((v) => !(instr(v) && instr(v).noHeat && needHeat)) || r.instrument[0];
  }
  b.vessel = vessel;
  Object.keys(r.reactants).forEach((k) => (b.placed[k] = r.reactants[k]));
  const cat = r.conditions && r.conditions.catalyst;
  if (cat) b.placed[cat] = Math.max(b.placed[cat] || 0, 1);
  return b;
}

function diag(r, b) {
  const c = r.conditions || {};
  const miss = [];
  if ((c.temp || "room") !== b.temp) miss.push("temp " + b.temp + "≠" + (c.temp || "room"));
  if (!!c.electrolysis !== !!b.electrolysis) miss.push("electrolysis");
  if (c.catalyst && !(b.placed[c.catalyst] > 0)) miss.push("catalyst " + c.catalyst);
  if (r.instrument && r.instrument.length && r.instrument.indexOf(b.vessel) === -1) miss.push("vessel " + b.vessel + "∉" + r.instrument.join("/"));
  return miss.join(", ") || "无匹配（反应物未落入任何 matches）";
}

const results = { total: 0, intended: 0, sibling: 0, sibs: [], fail: [] };
CHEM.REACTIONS.forEach((r) => {
  results.total++;
  const b = setupBench(r);
  const matched = E.matches().some((m) => m.id === r.id);
  const res = E.react();
  const okKind = (res.kind === "success" || res.kind === "partial") && res.produced && Object.keys(res.produced).length;
  if (okKind && res.r && res.r.id === r.id) { results.intended++; return; }
  if (okKind && res.r && sigOf(res.r) === sigOf(r)) { results.sibling++; results.sibs.push(r.id + "→" + res.r.id); return; } // 同签名兄弟反应胜出，组合仍可合成
  if (okKind && matched) { results.sibling++; results.sibs.push(r.id + "→" + (res.r && res.r.id)); return; }                   // 命中且合成，解析到别的组合
  results.fail.push({ id: r.id, eq: r.eq, kind: res.kind, rid: res.r && res.r.id, matched, why: matched ? "匹配但结算异常:" + res.kind : diag(r, b) });
});

// 工艺（过滤/蒸馏/升华）
const proc = { total: 0, ok: 0, fail: [] };
(CHEM.PROCESSES || []).forEach((p) => {
  proc.total++;
  const b = E.cur(); b.placed = {}; b.temp = p.temp || "room"; b.electrolysis = false; b.vessel = p.vessel;
  if (p.needEquip) st.data.equipment[p.needEquip] = true;
  if (p.consume) st.addItem(p.consume, 3, 0, true);
  Object.keys(p.reactants).forEach((k) => (b.placed[k] = p.reactants[k]));
  const res = E.react();
  if ((res.kind === "success" || res.kind === "partial") && res.proc && res.proc.id === p.id) proc.ok++;
  else proc.fail.push({ id: p.id, eq: p.eq, kind: res.kind, pid: res.proc && res.proc.id });
});

// 元素完整性
const el = CHEM.ELEMENTS || [];
const elBad = el.filter((e) => !e.id || !e.zh || typeof e.z !== "number");

console.log("== 元素 ==", el.length, "个；字段异常", elBad.length, elBad.slice(0, 5).map((e) => e.id).join(","));
console.log("== 反应 ==", results.total, "条");
console.log("   按预期命中:", results.intended, " 同签名兄弟/组合命中:", results.sibling, results.sibs.length ? "(" + results.sibs.join(", ") + ")" : "");
console.log("   失败:", results.fail.length);
results.fail.forEach((f) => console.log("   ✗ " + f.id + " [" + f.kind + "] " + (f.eq || "").slice(0, 30) + " → " + f.why));
console.log("== 工艺 ==", proc.total, "条；成功", proc.ok, "失败", proc.fail.length, JSON.stringify(proc.fail));
const pass = results.fail.length === 0 && proc.fail.length === 0 && elBad.length === 0;
console.log(pass ? "FULL-SYNTHESIS PASS ✔" : "FULL-SYNTHESIS FAIL");
process.exit(pass ? 0 : 1);
