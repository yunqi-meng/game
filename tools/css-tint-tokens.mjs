/* F7：把 color-mix() 收进一层令牌，并给旧 WebView 留出可用兜底值。
   做法：所有 color-mix(in srgb, …) 换成语义令牌 var(--mix-*)；
   :root 里给每个令牌一份**按默认皮肤实色算出来的**兜底值（老浏览器根本不认识 color-mix，
   整条声明会被丢掉，边框/阴影就直接没了），再在一个 @supports 块里用真正的 color-mix 覆盖，
   覆盖值引用 var(--blue) 这类皮肤变量 ⇒ 换皮肤时色调跟着走，兜底值只服务老引擎。
   这个脚本是**幂等**的：已经是 var(--mix-*) 的写法不会再被改。 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FILE = path.join(__dirname, '..', 'frontend', 'css', 'style.css');

// 默认皮肤（:root）的实色，兜底值由它们算出
const PAL = {
  blue: '#1e7fe0', gold: '#c9932a', red: '#e0453c', green: '#22a06b',
  bench: '#cfdcea', ink: '#1b2b3c', line: '#dce6f1', stage_top: '#f4f9ff',
};
const hex = (h) => {
  const s = h.replace('#', '');
  return [parseInt(s.slice(0, 2), 16), parseInt(s.slice(2, 4), 16), parseInt(s.slice(4, 6), 16)];
};
const key = (name) => name.replace(/-/g, '_');
const rgba = (c, a) => `rgba(${c[0]}, ${c[1]}, ${c[2]}, ${(+a).toFixed(2).replace(/\.?0+$/, '') || '0'})`;
const blend = (a, b, pct) => a.map((v, i) => Math.round(v * pct + b[i] * (1 - pct)));

let css = fs.readFileSync(FILE, 'utf8');
const re = /color-mix\(in srgb, var\(--([a-z-]+)\) (\d+)%, (?:var\(--([a-z-]+)\)|transparent)\)/g;
const seen = new Map();          // 表达式 → 令牌名
for (const m of css.matchAll(re)) {
  const [, base, pct, target] = m;
  const name = target ? `mix-${base}-${pct}-${key(target)}` : `mix-${base}-${pct}`;
  if (seen.has(m[0])) continue;
  seen.set(m[0], name);
}
if (!seen.size) { console.log('没有需要改写的 color-mix，跳过'); process.exit(0); }

// 兜底值
const fallbacks = [];
for (const [expr, name] of seen) {
  const m = /^color-mix\(in srgb, var\(--([a-z-]+)\) (\d+)%, (?:var\(--([a-z-]+)\)|transparent)\)$/.exec(expr);
  const [, base, pct, target] = m;
  const p = Number(pct) / 100;
  const bk = key(base);
  let fb;
  if (target) {
    const a = PAL[bk], b = PAL[key(target)];
    if (!a || !b) throw new Error('兜底值缺少调色板项：' + expr);
    const r = blend(hex(a), hex(b), p);
    fb = `rgb(${r[0]}, ${r[1]}, ${r[2]})`;
  } else {
    if (base === 'ic-line') {
      // --ic-line 自身就是 ink 的 76% ⇒ 再乘 42%
      fb = rgba(hex(PAL.ink), 0.76 * p);
    } else {
      const a = PAL[bk];
      if (!a) throw new Error('兜底值缺少调色板项：' + expr);
      fb = rgba(hex(a), p);
    }
  }
  fallbacks.push({ name, fb, expr });
}
fallbacks.sort((x, y) => x.name.localeCompare(y.name));

const indent = '  ';
const block = [
  '',
  '/* ---------- 1.1 派生色调（F7：兼容旧 WebView）----------',
  '   color-mix() 要 Chrome 111 / Safari 16.2，而 minSdk 24 的老机器上 WebView 可能停在更老的版本；',
  '   它一旦被丢掉，"边框没了、阴影没了、台面渐变没了"是整片一起发生的。',
  '   所以这里把它降级成一层令牌：:root 里放按默认皮肤算出来的实色兜底（老引擎只用这份），',
  '   @supports 里再用 color-mix 覆盖（现代引擎用的是皮肤变量，换皮肤色调跟着走）。',
  '   不要在这个块外写 color-mix()——test/validate.js 有一条裁判专门盯这件事。 */',
].join('\n');

const rootLines = fallbacks.map(f => `${indent}--${f.name}: ${f.fb};`);
const supLines = fallbacks.map(f => `${indent}--${f.name}: ${f.expr};`);

// 插入到 :root 块结束处
const rootEnd = css.indexOf('\n}', css.indexOf(':root {'));
if (rootEnd < 0) throw new Error('找不到 :root 块结尾');
const insert = `\n${block}\n:root {\n${rootLines.join('\n')}\n}\n\n/* 现代引擎：用 color-mix 精确跟随皮肤，覆盖上面那份按默认色算的兜底 */\n@supports (color: color-mix(in srgb, red 50%, transparent)) {\n:root {\n${supLines.join('\n')}\n}\n}\n`;
css = css.slice(0, rootEnd + 2) + insert + css.slice(rootEnd + 2);

// 表达式 → 令牌
css = css.replace(re, (full) => `var(--${seen.get(full)})`);
// 表达式出现在 @supports 覆盖块里的那份不能被换成 var（自己引用自己）
css = css.replace(/(@supports \(color: color-mix[\s\S]*?\n\}\n)/, (blk) =>
  blk.replace(/var\(--mix-[a-z0-9_-]+\)/g, (v) => {
    const n = v.slice(6, -1);
    const back = [...seen.entries()].find(([, name]) => name === n);
    return back ? back[0] : v;
  }));

fs.writeFileSync(FILE, css, 'utf8');
console.log(`已改写 ${seen.size} 个 color-mix 表达式为 ${seen.size} 个 --mix-* 令牌`);
