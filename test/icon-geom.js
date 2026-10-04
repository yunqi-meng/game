/* 仪器线稿（js/icons.js）的几何工具。
   为什么单独一个文件：test/validate.js（回归）和 tools/icon-preview.mjs（终端预览）都必须用同一套
   判定，否则会出现"预览看着没问题、回归也没报错，但浏览器里液体画出器壁"。这里就是那份共同裁判。
   坐标系：所有图元画在 0..VB 的方形视图框里（VB=48）。 */
"use strict";

/* SVG path → 折线点集。支持 M/L/H/V/Q/T/Z 及其小写（相对）形式。
   icons.js 里刻意只用这几种命令：命令越少，能被离线解析的图形就越少惊喜。 */
function flatten(d) {
  const pts = [];
  let x = 0, y = 0, sx = 0, sy = 0, cmd = "", i = 0, lastCtrl = null;
  const num = () => {
    const m = /-?\d*\.?\d+/.exec(d.slice(i));
    if (!m) return 0;
    i += m[0].length;
    while (i < d.length && /[\s,]/.test(d[i])) i++;
    return parseFloat(m[0]);
  };
  const quad = (cx, cy, nx, ny) => {
    for (let k = 1; k <= 24; k++) {
      const t = k / 24, u = 1 - t;
      pts.push([u * u * x + 2 * u * t * cx + t * t * nx, u * u * y + 2 * u * t * cy + t * t * ny]);
    }
    lastCtrl = [cx, cy]; x = nx; y = ny;
  };
  while (i < d.length) {
    if (/[a-zA-Z]/.test(d[i])) { cmd = d[i]; i++; }
    const rel = cmd === cmd.toLowerCase();
    const C = (v, base) => (rel ? base + v : v);
    const K = cmd.toUpperCase();
    if (K === "M") { x = C(num(), x); y = C(num(), y); sx = x; sy = y; pts.push([x, y]); cmd = rel ? "l" : "L"; lastCtrl = null; }
    else if (K === "L") { const nx = C(num(), x), ny = C(num(), y); pts.push([x, y], [nx, ny]); x = nx; y = ny; lastCtrl = null; }
    else if (K === "H") { x = C(num(), x); pts.push([x, y]); lastCtrl = null; }
    else if (K === "V") { y = C(num(), y); pts.push([x, y]); lastCtrl = null; }
    else if (K === "Q") { quad(C(num(), x), C(num(), y), C(num(), x), C(num(), y)); }
    else if (K === "T") {   /* 镜像上一个二次控制点（只在紧接 Q/T 之后有意义） */
      quad(lastCtrl ? 2 * x - lastCtrl[0] : x, lastCtrl ? 2 * y - lastCtrl[1] : y, C(num(), x), C(num(), y));
    }
    else if (K === "Z") { pts.push([x, y], [sx, sy]); x = sx; y = sy; lastCtrl = null; }
    else { i++; continue; }
    while (i < d.length && /[\s,]/.test(d[i])) i++;
  }
  return pts;
}

/** 一个图元 → 折线点集；类型不认识就返回 null（draw() 会静默丢掉它，所以必须当错误报出来）。 */
function toPoly(p) {
  if (!Array.isArray(p)) return null;
  if (p[0] === "path" && typeof p[1] === "string") return flatten(p[1]);
  if (p[0] === "circle") {
    const out = [];
    for (let a = 0; a < 360; a += 4) out.push([p[1] + p[3] * Math.cos(a * Math.PI / 180), p[2] + p[3] * Math.sin(a * Math.PI / 180)]);
    return out;
  }
  if (p[0] === "rect") return [[p[1], p[2]], [p[1] + p[3], p[2]], [p[1] + p[3], p[2] + p[4]], [p[1], p[2] + p[4]]];
  return null;
}
const POLYS = (list) => (list || []).map((p) => ({ p, pts: toPoly(p) }));
function bbox(list) {
  let x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9, n = 0;
  for (const { pts } of POLYS(list)) {
    if (!pts) continue;
    for (const [x, y] of pts) { n++; x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); }
  }
  return n ? { x0, x1, y0, y1, n } : null;
}

/**
 * 图形体检，返回问题描述数组（空数组 = 合格）。
 * 三条规则各自对应一种"玩家能直接看出来"的事故：
 *   越框 → 图标被裁掉一半；未知图元 → 图标上少一根线；液体跑出器壁 → 正是这次要修的"液体在烧杯外面"。
 */
function auditShape(name, s, VB) {
  const bad = [];
  if (!s || !Array.isArray(s.o) || !s.o.length) return [name + " 没有轮廓层"];
  ["o", "t", "l"].forEach((k) => {
    (s[k] || []).forEach((p) => {
      const pts = toPoly(p);
      if (!pts) return bad.push(name + "." + k + " 图元类型无法解析: " + JSON.stringify(p).slice(0, 40));
      if (!pts.length) return bad.push(name + "." + k + " 展开后是空折线（路径写错？）");
      for (const [x, y] of pts) {
        if (x < -0.6 || x > VB + 0.6 || y < -0.6 || y > VB + 0.6) {
          bad.push(name + "." + k + " 越出视图框 (" + x.toFixed(1) + "," + y.toFixed(1) + ")");
          break;
        }
      }
    });
  });
  if (s.l && s.o) {
    const L = bbox(s.l), O = bbox(s.o);
    if (L && O) {
      if (L.x0 < O.x0 - 0.8 || L.x1 > O.x1 + 0.8)
        bad.push(name + " 液体横向超出器壁 x:" + L.x0.toFixed(1) + "~" + L.x1.toFixed(1) + " vs " + O.x0.toFixed(1) + "~" + O.x1.toFixed(1));
      if (L.y1 > O.y1 + 1.5) bad.push(name + " 液体沉到轮廓之下 y:" + L.y1.toFixed(1) + ">" + O.y1.toFixed(1));
    }
  }
  return bad;
}

module.exports = { flatten, toPoly, bbox, auditShape };
