// 把 frontend/js/data/*.js（classic script 挂 window.CHEM）导出为 Flyway 种子 SQL。
// 用法： node tools/export-seed.mjs
// 产物： server/src/main/resources/db/migration/V2__seed_content.sql
//        server/src/main/resources/db/migration/V3__seed_config.sql
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const DATA_DIR = path.join(ROOT, "frontend", "js", "data");
const OUT_DIR = path.join(ROOT, "server", "src", "main", "resources", "db", "migration");

// 1) 在 vm 沙箱里加载数据文件（window 即全局对象，使裸 CHEM 可解析）
const ctx = vm.createContext({ console });
ctx.window = ctx;
ctx.self = ctx;
for (const f of ["elements.js", "compounds.js", "reactions.js", "instruments.js"]) {
  const code = fs.readFileSync(path.join(DATA_DIR, f), "utf8");
  vm.runInContext(code, ctx, { filename: f });
}
const C = ctx.CHEM;

// 2) 分类：有 .id 的实体数组 -> content_item；无 id 的数组/标量 -> app_config
const ENTITY_TYPES = [
  ["ELEMENTS", "element"],
  ["COMPOUNDS", "compound"],
  ["REACTIONS", "reaction"],
  ["INSTRUMENTS", "instrument"],
  ["PROCESSES", "process"],
  ["DANGERS", "danger"],
  ["ROOMS", "room"],
  ["CONSUMABLES", "consumable"],
  ["NPCS", "npc"],
  ["DSHOP", "shop"],
  ["ACHIEVEMENTS", "achievement"],
  ["DAILY_TASKS", "task"],
  ["QUIZZES", "quiz"], // 无 id，下面合成 item_id
];
const CONFIG_KEYS = [
  "RECHARGE", "QUALITY", "MILESTONES", "TIER_NAMES", "TIER_UP_COST",
  "LAB_UPGRADES", "DISCOVER_BONUS", "START_COINS", "TUTORIAL_COINS",
  "QUIZ_REWARD", "SELL_RATE", "BUY_RATE", "FIRST_SELL_BONUS",
];

const sqlStr = (s) => "'" + String(s).replace(/\\/g, "\\\\").replace(/'/g, "''") + "'";
const jsonOf = (o) => sqlStr(JSON.stringify(o));
const nameOf = (o, i) => o.zh || o.q || o.name || o.id || ("#" + i);

function entityId(o, type, i) {
  if (type === "quiz") return "q" + String(i + 1).padStart(4, "0");
  if (typeof o.id === "string") return o.id;
  return type + "_" + i;
}

// 3) 生成 content_item
const rows = [];
const counts = {};
for (const [global, type] of ENTITY_TYPES) {
  const arr = C[global] || [];
  counts[type] = arr.length;
  arr.forEach((o, i) => {
    const id = entityId(o, type, i);
    const name = nameOf(o, i);
    const payload = Object.assign({}, o);
    if (type === "quiz") payload.id = id;
    payload.__type = type;
    rows.push(
      `(${sqlStr(type)}, ${sqlStr(id)}, ${sqlStr(name)}, ${i}, 1, ${jsonOf(payload)}, 'seed')`
    );
  });
}

const contentSql = [
  "-- 自动生成，勿手改。来源 tools/export-seed.mjs",
  "SET NAMES utf8mb4;",
  "INSERT INTO content_item (content_type, item_id, name, sort, enabled, data, updated_by) VALUES",
  rows.join(",\n") + ";",
  "",
].join("\n");

// 4) 生成 app_config（运营/静态参数）
const cfgRows = [];
for (const key of CONFIG_KEYS) {
  if (!(key in C)) continue;
  cfgRows.push(
    `(${sqlStr(key.toLowerCase())}, ${jsonOf(C[key])}, ${sqlStr(classify(key))}, ${sqlStr(key)}, 'seed')`
  );
}
function classify(k) {
  if (["SELL_RATE", "BUY_RATE", "FIRST_SELL_BONUS", "START_COINS", "DISCOVER_BONUS"].includes(k)) return "economy";
  if (["QUIZ_REWARD", "QUALITY", "MILESTONES", "TIER_NAMES", "TIER_UP_COST", "LAB_UPGRADES"].includes(k)) return "progression";
  return "shop";
}
const configSql = [
  "-- 自动生成，勿手改。来源 tools/export-seed.mjs",
  "SET NAMES utf8mb4;",
  "INSERT INTO app_config (cfg_key, cfg_value, category, remark, updated_by) VALUES",
  cfgRows.join(",\n") + ";",
  "",
].join("\n");

fs.mkdirSync(OUT_DIR, { recursive: true });
fs.writeFileSync(path.join(OUT_DIR, "V2__seed_content.sql"), contentSql, "utf8");
fs.writeFileSync(path.join(OUT_DIR, "V3__seed_config.sql"), configSql, "utf8");

console.log("content_item 行数:", rows.length);
console.log("按类型:", JSON.stringify(counts));
console.log("app_config 键:", cfgRows.length, "→", CONFIG_KEYS.filter((k) => k in C).join(","));
console.log("写出:", OUT_DIR);
