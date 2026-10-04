/* tools/icon-preview.mjs —— 把 js/icons.js 里的仪器线稿栅格化到终端，逐个人眼确认形状。
   图标是几何图形而不是 emoji，光看代码猜不出"像不像烧杯"，所以提供这个离线预览：
     node tools/icon-preview.mjs            # 全部（含液体填充）
     node tools/icon-preview.mjs beaker     # 只看某几个 id
   画面上：# = 轮廓线 · + = 细节（刻度/支管等） · ~ = 液体填充
   几何判定（越框、液体溢出）复用 test/icon-geom.js —— 和回归用的是同一份裁判。 */
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";
import { flatten, toPoly, auditShape } from "../test/icon-geom.js";

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");

/* 与 test/validate.js 同一套沙箱技巧：让 window 就是 global 本身，
   这样模块里 window.CHEM = {} 之后，裸 CHEM 标识符才找得到。 */
function load(rel) {
  const box = {};
  box.window = box;
  box.globalThis = box;
  box.self = box;
  box.console = console;
  vm.createContext(box);
  vm.runInContext(fs.readFileSync(path.join(root, rel), "utf8"), box, { filename: rel });
  return box.window.CHEM;
}
const ICON = load("frontend/js/icons.js").icon;
const SHAPES = ICON.SHAPES;

const VB = ICON.VB || 48;
const COLS = 48, ROWS = 24;              // 终端字符约 2:1 高宽比，纵向压一半
const X = (x) => Math.max(0, Math.min(COLS - 1, Math.round((x / VB) * (COLS - 1))));
const Y = (y) => Math.max(0, Math.min(ROWS - 1, Math.round((y / VB) * (ROWS - 1))));

function draw(name, zh) {
  const g = Array.from({ length: ROWS }, () => Array(COLS).fill(" "));
  const put = (px, py, ch, over) => {
    if (py < 0 || py >= ROWS || px < 0 || px >= COLS) return;
    if (over || g[py][px] === " ") g[py][px] = ch;
  };
  const stroke = (poly, ch) => {
    for (let k = 1; k < poly.length; k++) {
      const [x1, y1] = poly[k - 1], [x2, y2] = poly[k];
      const a = X(x1), b = Y(y1), c = X(x2), d = Y(y2);
      const n = Math.max(Math.abs(c - a), Math.abs(d - b), 1);
      for (let m = 0; m <= n; m++) put(Math.round(a + ((c - a) * m) / n), Math.round(b + ((d - b) * m) / n), ch, true);
    }
  };
  const fill = (poly) => {
    /* 在连续的 viewBox 坐标上做扫描线相交，再落到字符栅格：
       曲线被细分成几十个小线段，任何"端点正好落在本行就跳过"的偷懒写法都会把交点漏光。 */
    for (let row = 0; row < ROWS; row++) {
      const yv = ((row + 0.5) / ROWS) * VB;
      const xs = [];
      for (let k = 1; k < poly.length; k++) {
        const [x1, y1] = poly[k - 1], [x2, y2] = poly[k];
        if (y1 === y2) continue;
        if (yv >= Math.min(y1, y2) && yv < Math.max(y1, y2)) {
          const t = (yv - y1) / (y2 - y1);
          xs.push(X(x1 + t * (x2 - x1)));
        }
      }
      xs.sort((p, q) => p - q);
      for (let k = 0; k + 1 < xs.length; k += 2) for (let xx = Math.ceil(xs[k]); xx <= Math.floor(xs[k + 1]); xx++) put(xx, row, "~");
    }
  };
  const s = SHAPES[name];
  for (const p of s.l || []) fill(toPoly(p) || []);
  for (const p of s.t || []) stroke(toPoly(p) || [], "+");
  for (const p of s.o || []) stroke(toPoly(p) || [], "#");
  const bad = auditShape(name, s, VB);
  return `${(zh || name).padEnd(6)} ${name}${bad.length ? "   ⚠ " + bad.join("；") : ""}\n` +
    g.map((r) => "  │" + r.join("") + "│").join("\n");
}

const instrs = load("frontend/js/data/instruments.js").INSTRUMENTS;
const ZH = Object.fromEntries(instrs.map((i) => [i.id, i.zh]));
ZH.other = "兜底";

const want = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(SHAPES);
let problems = 0;
console.log(want.map((k) => {
  if (!SHAPES[k]) { problems++; return k + "  → 没有这个图形"; }
  if (auditShape(k, SHAPES[k], VB).length) problems++;
  return draw(k, ZH[k]);
}).join("\n\n"));

const missing = instrs.filter((i) => !SHAPES[i.id]).map((i) => i.id + "/" + i.zh);
const noLiquid = instrs.filter((i) => i.kind === "vessel" && SHAPES[i.id] && !SHAPES[i.id].l).map((i) => i.id);
console.log("\n没有专属图形的仪器：" + (missing.length ? missing.join(", ") : "（无，全部覆盖）"));
console.log("容器却没有液体层的仪器：" + (noLiquid.length ? noLiquid.join(", ") : "（无）"));
console.log("图形总数：" + Object.keys(SHAPES).length + " · 本轮问题图形：" + problems);
process.exit(problems ? 1 : 0);
