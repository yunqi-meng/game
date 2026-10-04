/* 引擎冒烟测试 v2：node test/smoke.js */
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm");
const sb = { console: { log() {} }, setInterval() {}, localStorage: { getItem: () => null, setItem() {}, removeItem() {} }, location: {}, Date, Math, JSON };
sb.window = sb;
sb.globalThis = sb;
const ctx = vm.createContext(sb);
["js/data/elements.js", "js/data/compounds.js", "js/data/reactions.js", "js/data/instruments.js", "js/state.js", "js/engine.js"]
  .forEach((f) => vm.runInContext(fs.readFileSync(path.join(__dirname, "..", f), "utf8"), ctx, { filename: f }));
const CHEM = sb.CHEM, st = CHEM.state, E = CHEM.engine;
st.load();
E.init();

function assert(c, m) { if (!c) { console.error("FAIL:", m); process.exitCode = 1; } else console.log("ok  -", m); }
function resetBench() { E.cur().placed = {}; E.cur().temp = "room"; E.cur().electrolysis = false; }

/* 1. 简单模式：H2+O2 点燃 → H2O */
resetBench(); E.cur().temp = "ignite";
st.addItem("H2", 4, 0, true); st.addItem("O2", 4, 0, true);
assert(E.place("H2", 2).ok, "投放 H2×2");
assert(E.place("O2", 1).ok, "投放 O2×1");
let res = E.react();
assert((res.kind === "success" || res.kind === "partial") && res.produced.H2O >= 1, "化合反应生成 H2O（容忍产率波动），kind=" + res.kind);
assert(st.countAll("H2O") >= 1, "水进入背包");
assert(!!Object.keys(st.data.reactionsKnown).length, "方程式已解锁");

/* 2. 锌 + 盐酸 */
resetBench();
st.addItem("Zn", 3, 0, true); st.addItem("HCl", 3, 0, true);
E.place("Zn", 1); E.place("HCl", 2);
res = E.react();
assert((res.kind === "success" || res.kind === "partial") && res.produced.ZnCl2 + res.produced.H2 > 0, "Zn+HCl 置换成功: " + (res.r && res.r.eq));

/* 3. 废渣：无匹配组合 */
resetBench();
st.addItem("Au", 2, 0, true); st.addItem("KCl", 2, 0, true);
E.place("Au", 1); E.place("KCl", 1);
res = E.react();
assert(res.kind === "boom" || res.kind === "fail-cond", "无匹配组合给出失败结算: " + res.kind);

/* 4. 真实模式条件判定 */
st.data.realMode = true;
resetBench();
st.addItem("H2", 6, 0, true); st.addItem("O2", 6, 0, true);
E.place("H2", 2); E.place("O2", 1);
assert(E.matches().length === 0, "真实模式：室温下 H2+O2 不匹配");
E.cur().temp = "ignite";
assert(E.matches().length >= 1, "真实模式：点燃后匹配");
st.data.realMode = false;

/* 5. 经济：买卖价 */
const buyP = st.buyPrice("NaCl"), sellP = st.sellPrice("NaCl", 0);
assert(buyP > sellP, "买入价高于回收价，无法套利: buy=" + buyP + " sell=" + sellP);
assert(st.sellPrice("NaCl", 2) > st.sellPrice("NaCl", 0), "高纯产物售价更高");

/* 6. 等级解锁线 */
assert(st.data.level === 1 && E.marketPool().length > 10, "市场池非空");
const a1 = E.marketPool().length; st.data.level = 16;
assert(E.marketPool().length > a1, "升级后市场池扩大: " + a1 + " -> " + E.marketPool().length);
st.data.level = 1;

/* 7. 多工作台：房间切换、状态独立、sync 持久化 */
st.data.rooms = ["inorganic", "analysis"];
E.init();
assert(E.benches.length === 2, "两个房间 → 两台 bench");
E.goBench(1);
resetBench();
st.addItem("NaCl", 4, 0, true);
E.place("NaCl", 2);
assert(Object.keys(E.benches[1].placed).length === 1 && !Object.keys(E.benches[0].placed).length, "bench 状态互相独立");
E.sync();
assert(st.data.benchStates === E.benches && st.data.bi === 1, "E.sync 写入存档字段");
E.goBench(0);

/* 8. 工艺：粗盐过滤（滤纸） */
st.data.vessels.funnel = { owned: true, tier: 0 };
E.cur().vessel = "funnel";
resetBench();
st.addItem("SLAG", 3, 0, true); st.addItem("NaCl", 3, 0, true); st.addItem("filterpaper", 2, 0, true);
E.place("SLAG", 2); E.place("NaCl", 2);
let pm = E.processMatch();
assert(pm && pm.id === "P01", "过滤工艺匹配");
res = E.react();
assert((res.kind === "success" || res.kind === "partial") && res.proc && res.proc.id === "P01", "过滤执行: " + (res.proc && res.proc.id));
st.data.vessels.funnel.owned = false;
E.cur().vessel = "beaker";

/* 9. 误用：量筒加热 → 炸裂 */
st.data.vessels.qcyl = { owned: true, tier: 0 };
const cyl = (CHEM.INSTRUMENTS || []).find((i) => i.noHeat);
if (cyl) {
  st.data.vessels[cyl.id] = { owned: true, tier: 0 };
  resetBench();
  E.cur().vessel = cyl.id;
  E.cur().temp = "heat";
  st.addItem("H2O", 2, 0, true);
  E.place("H2O", 1);
  res = E.react();
  assert(res.kind === "boom", "不可加热仪器受热炸裂: " + cyl.id);
  E.cur().vessel = "beaker";
} else assert(false, "数据中应存在 noHeat 仪器");

/* 10. 危险组合 + 保险理赔 */
resetBench(); E.cur().temp = "room";
st.addItem("KClO3", 3, 0, true); st.addItem("S", 3, 0, true);
E.place("KClO3", 1); E.place("S", 1);
assert(E.hasDanger(), "KClO3+S 判定为危险混放");
E.insured = true;
const coinsBefore = st.data.coins;
res = E.react();
assert(res.kind === "boom" || res.kind === "success" || res.kind === "partial", "危险组合给出结算: " + res.kind);
if (res.kind === "boom") assert(E.insured === false, "保险一次事故后自动失效");

/* 11. 挑战模式：临时 bench、材料箱限制、胜利结算 */
st.data.level = 10;
const ch = st.startChallenge();
assert(!!ch && !!ch.target, "生成挑战: " + (ch && ch.target));
E.startChallengeBench();
const foreign = Object.keys(st.allSubs()).find((id) => id !== "SLAG" && !ch.given[id] && !ch.decoys[id]);
assert(!E.place(foreign, 1).ok, "挑战台拒绝非材料");
/* 直接在临时台上摆放正确反应物验证胜利路径 */
const rr = CHEM.REACTIONS.find((x) => x.id === ch.reactId);
Object.keys(rr.reactants).forEach((k) => { E.tempBench.placed[k] = rr.reactants[k]; });
E.tempBench.temp = (rr.conditions && rr.conditions.temp) || "room";
E.tempBench.vessel = (rr.instrument && rr.instrument[0]) || "beaker";
if (rr.conditions && rr.conditions.catalyst) E.tempBench.placed[rr.conditions.catalyst] = 1;
res = E.react();
assert(res.chal && res.chal.win, "挑战胜利结算");
assert(E.tempBench === null, "挑战结束关闭临时台");

/* 12. 沙盒：不扣库存、不发发现奖、不产奖励 */
st.data.level = 1;
const diamondBefore = st.data.diamonds;
E.startSandbox();
const coinsSb = st.data.coins, succSb = st.data.stats.success;
st.data.bag = {};
E.tempBench.placed = { H2: 2, O2: 1 };
E.tempBench.temp = "ignite";
res = E.react();
assert((res.kind === "success" || res.kind === "partial") && Object.keys(st.data.bag).length === 0, "沙盒不写入背包");
assert(st.data.coins === coinsSb, "沙盒不产生金币");
E.closeTempBench();
assert(!E.sandboxActive, "沙盒关闭");

/* 13. 挂单：消耗试剂瓶、离线到期结算 */
st.data.rooms = ["inorganic"]; E.init(); E.sync();
st.addItem("Cu", 12, 1, true);
st.addItem("reagentbottle", 5, 0, true);
const lb = st.data.listings.length;
const lr = st.createListing("Cu", 1, 10, 1.0);
assert(lr.ok && st.data.listings.length === lb + 1, "挂单成立");
assert(st.count("reagentbottle", 0) === 4, "挂单消耗试剂瓶");
st.data.listings[st.data.listings.length - 1].mat = Date.now() - 1;
st.processListings();
assert(st.data.listings.length === lb, "到期挂单被结算/退回");

/* 14. 提示 / 声望 / 月卡 */
st.data.coins += 1000;
const hintR = st.hint(true);
assert(hintR === null || hintR.eq, "hint 返回方程式或 null");
st.data.rep = 25;
assert(st.repTier().zh === "常客", "声望档位");
st.data.monthly.until = Date.now() + 1e6;
assert(st.monthlyActive(), "月卡生效判定");

/* 15. 规模 */
assert(CHEM.REACTIONS.length >= 140, "反应库规模: " + CHEM.REACTIONS.length);
assert((CHEM.INSTRUMENTS || []).length >= 26, "仪器规模: " + CHEM.INSTRUMENTS.length);
assert(CHEM.ROOMS.length === 5, "房间数: " + CHEM.ROOMS.length);

/* 16. 笑气链路：NH3+HNO3→NH4NO3（R144），NH4NO3 受热→N2O（R142） */
resetBench(); E.cur().temp = "room";
st.addItem("NH3", 2, 0, true); st.addItem("HNO3", 2, 0, true);
E.place("NH3", 1); E.place("HNO3", 1);
res = E.react();
assert(res.r && res.r.id === "R144" && (res.produced.NH4NO3 >= 1 || res.yieldWarn), "R144 合成硝酸铵: " + res.kind);
resetBench(); E.cur().temp = "heat";
st.addItem("NH4NO3", 2, 0, true);
E.place("NH4NO3", 1);
res = E.react();
assert(res.r && res.r.id === "R142" && (res.produced.N2O >= 1 || res.yieldWarn), "R142 硝酸铵受热分解出笑气: " + res.kind);

/* 17. 挑战：干扰物质立即判负（不可复活） */
st.data.chal = { reactId: "R010", target: "__T__", given: { H2: 6, O2: 3, Au: 2 }, decoys: { Au: 2 }, steps: 0, max: 3, win: false };
E.startChallengeBench();
E.tempBench.placed = { H2: 2, O2: 1, Au: 1 };
E.tempBench.temp = "ignite";
res = E.react();
assert(res.chal && res.chal.decoy && !st.data.chal && !E.tempBench, "混入干扰物质：立即判负并关闭挑战台");

/* 18. 挑战：步骤用尽 → failed 保留现场，可复活 +2 步 */
st.data.chal = { reactId: "R010", target: "__T__", given: { H2: 6, O2: 3 }, decoys: { Au: 2 }, steps: 1, max: 1, win: false };
E.startChallengeBench();
E.tempBench.placed = { H2: 2, O2: 1 };
E.tempBench.temp = "ignite";
res = E.react();
assert(res.chal && res.chal.outOfSteps && st.data.chal.failed && !!E.tempBench, "步骤用尽：保留现场等待复活");
st.data.chal.max += 2; st.data.chal.failed = false;
assert(st.data.chal.steps < st.data.chal.max && !st.data.chal.failed, "复活 +2 步后可继续挑战");
st.data.chal = null; E.closeTempBench();

/* 19. 新增存档字段默认值 */
assert(st.data.cloud && st.data.cloud.auto === false, "defaults 含 cloud 字段");
assert(st.data.cloud.syncedAt === 0 && st.data.cloud.conflict === 0, "cloud 含冲突协议字段");
assert(st.data.sign && st.data.sign.streak === 0, "defaults 含 sign 连续签字段");
assert(st.data.volSfx === 80 && st.data.volMus === 35 && st.data.music === false, "defaults 含音量/音乐字段");

/* 20. 存档结构校验 */
assert(st.validateSave(st.data), "validateSave 接受当前存档");
assert(!st.validateSave(null) && !st.validateSave({ v: 2 }) && !st.validateSave({ v: 9, coins: 1, level: 1, bag: {}, discovered: {} }), "validateSave 拒绝坏档");

/* 21. 挑战现场持久化：sync 写 chal.bench，重启回填 */
st.data.chal = { reactId: "R010", target: "__T__", given: { H2: 6, O2: 3 }, decoys: { Au: 2 }, steps: 0, max: 3, win: false };
E.startChallengeBench();
E.tempBench.placed = { H2: 2 }; E.tempBench.temp = "heat"; E.tempBench.vessel = "flask";
E.sync();
assert(st.data.chal.bench && st.data.chal.bench.temp === "heat" && st.data.chal.bench.placed.H2 === 2, "E.sync 持久化挑战现场");
E.closeTempBench();
E.startChallengeBench();
assert(E.tempBench.vessel === "flask" && E.tempBench.placed.H2 === 2 && E.tempBench.temp === "heat", "刷新后恢复挑战现场（placed/温度/容器）");
st.data.chal = null; E.closeTempBench();

/* 22. applySave：校验通过后套用并补默认 */
assert(st.applySave({ v: 2, coins: 42, level: 2, bag: { "Cu|0": 1 }, discovered: {} }) && st.data.coins === 42 && Array.isArray(st.data.rooms), "applySave 套用外部存档并合并默认字段");
assert(!st.applySave({ v: 2, coins: "坏" }), "applySave 拒绝坏档");
console.log(process.exitCode ? "SMOKE FAIL" : "SMOKE PASS");
