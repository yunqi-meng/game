/* 一次性补丁：新增 R138-R141、离子/可逆/热化学标记，重排 reactions.js */
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm");
const file = path.join(__dirname, "..", "js", "data", "reactions.js");
const sb = {}; sb.window = sb;
const ctx = vm.createContext(sb);
vm.runInContext(fs.readFileSync(path.join(__dirname, "..", "js", "data", "compounds.js"), "utf8"), ctx);
vm.runInContext(fs.readFileSync(file, "utf8"), ctx);
const C = sb.window.CHEM, R = C.REACTIONS;

const keys = (r) => Object.keys(r.reactants).sort().join("+");
const has = (r, ...ks) => ks.every((k) => r.reactants[k]);

/* 1. 新反应 */
const exist = new Set(R.map((r) => keys(r)));
const news = [
  { id:"R138", reactants:{"Cl2":1,"KI":2}, conditions:{temp:"room"}, products:{"KCl":2,"I2":1}, instrument:["testtube","beaker"], type:"置换反应", eq:"Cl₂ + 2KI = 2KCl + I₂", phenomenon:"溶液由无色变为棕黄色，滴加淀粉后显蓝色", fx:["colorchange"], discoverLv:7, exp:35, tip:"氧化性更强的卤素单质置换氧化性较弱的卤素。" },
  { id:"R139", reactants:{"Fe":1,"S":1}, conditions:{temp:"heat"}, products:{"FeS":1}, instrument:["crucible","testtube"], type:"化合反应", eq:"Fe + S —加热→ FeS", phenomenon:"混合物保持红热继续反应，生成黑色固体", fx:["glow","colorchange"], discoverLv:6, exp:30, tip:"铁与硫化合生成硫化亚铁而非硫化铁，硫的氧化性较弱。" },
  { id:"R140", reactants:{"FeS":1,"HCl":2}, conditions:{temp:"room"}, products:{"FeCl2":1,"H2S":1}, instrument:["testtube","flask"], type:"复分解反应", eq:"FeS + 2HCl = FeCl₂ + H₂S↑", phenomenon:"黑褐色固体溶解，放出臭鸡蛋气味气体", fx:["bubble","dissolve"], discoverLv:9, exp:40, hazard:true, dangerMsg:"硫化氢为剧毒气体！", tip:"实验室制H₂S的方法，必须在通风橱中进行。" },
  { id:"R141", reactants:{"C12H22O11":1,"H2SO4":1}, conditions:{temp:"room"}, products:{"C":12,"H2O":11}, instrument:["beaker"], type:"脱水反应", eq:"C₁₂H₂₂O₁₁ —浓硫酸→ 12C + 11H₂O", phenomenon:"生成膨松多孔的黑色‘黑蛇’并剧烈放热，杯壁发烫", fx:["smoke","colorchange","glow"], discoverLv:14, exp:90, hazard:true, dangerMsg:"浓硫酸脱水放热剧烈，有飞溅危险！", tip:"浓硫酸脱水性的经典演示——法老之蛇。" }
];
news.forEach((n) => { if (!exist.has(keys(n))) R.push(n); });

/* 2. 分类标记 */
const IONIC = {
  "HCl+NaOH": "H⁺ + OH⁻ = H₂O",
  "AgNO3+NaCl": "Ag⁺ + Cl⁻ = AgCl↓",
  "BaCl2+Na2SO4": "Ba²⁺ + SO₄²⁻ = BaSO₄↓",
  "CaCO3+HCl": "CaCO₃ + 2H⁺ = Ca²⁺ + H₂O + CO₂↑",
  "CuSO4+NaOH": "Cu²⁺ + 2OH⁻ = Cu(OH)₂↓",
  "FeCl3+NaOH": "Fe³⁺ + 3OH⁻ = Fe(OH)₃↓",
  "CO2+NaOH": "CO₂ + 2OH⁻ = CO₃²⁻ + H₂O"
};
R.forEach((r) => {
  const k = keys(r);
  if (r.conditions && r.conditions.temp === "ignite" && r.reactants.O2) r.thermal = "ΔH < 0（放热反应）";
  if ((r.reactants.N2 && r.reactants.H2) || (r.reactants.SO2 && r.reactants.O2)) r.rev = true;
  if (IONIC[k]) r.ionic = IONIC[k];
  if (has(r, "Na", "H2O") && !r.thermal) r.thermal = "ΔH < 0（剧烈放热）";
});
/* 常见离子方程式按 reactants 键名精确匹配 */
R.forEach((r) => {
  const k = keys(r);
  if ((k === "HCl+NaOH" || k === "AgNO3+NaCl" || k === "BaCl2+Na2SO4" || k === "CaCO3+HCl" || k === "CuSO4+NaOH" || k === "FeCl3+NaOH" || k === "CO2+NaOH") && !r.ionic) r.ionic = IONIC[k];
  if (r.reactants.Zn && (r.reactants.H2SO4 || r.reactants.HCl) && r.products.H2 && !r.ionic) r.ionic = "Zn + 2H⁺ = Zn²⁺ + H₂↑";
});

/* 3. 重排序列化 */
function ser(arr, name) {
  return name + " = [\n" + arr.map((e) => "  " + JSON.stringify(e)).join(",\n") + "\n];\n";
}
const out = "// 反应方程式库（配平；ionic/rev/thermal 为图鉴分类字段）\nwindow.CHEM = window.CHEM || {};\n\n" +
  ser(R, "CHEM.REACTIONS") + "\n" + ser(C.DANGERS, "CHEM.DANGERS") +
  "\n/* count: " + R.length + " reactions, " + C.DANGERS.length + " dangers */\n";
fs.writeFileSync(file, out);
console.log("patched:", R.length, "reactions;", R.filter((r) => r.ionic).length, "ionic;", R.filter((r) => r.rev).length, "rev;", R.filter((r) => r.thermal).length, "thermal");
