/* F7 裁判：CSS 里"只有新引擎认识"的写法必须带兜底，否则 Android 8 那批旧 WebView 上
   整条声明会被丢掉——不是"少个阴影"，而是边框、渐变、满屏高度一起没，表现为一整屏塌陷。
   这里静态审三件事：
     1) color-mix() 只许出现在 @supports (color: color-mix…) 覆盖块里，块外一律用 var(--mix-*) 令牌，
        而且每个用到的令牌都要在 :root 有一份实色兜底；
     2) 不写裸 inset:（Chrome 87 以下不认），改回 top/right/bottom/left 四条；
     3) 用了 dvh / min() / max() 的声明，前面必须有一条同属性的老写法兜底。
   node test/validate.js 会调用它；test/android-check.sh 用同一份规则审包内的 CSS。 */
"use strict";

/** 去掉注释：注释里提"color-mix 会掉"这类历史说明是允许的，代码里再出现才要拦。 */
function stripComments(css) {
  return css.replace(/\/\*[\s\S]*?\*\//g, "");
}

/** @supports (color: color-mix…) 那一块的字符区间，审的时候跳过它。 */
function supportsRanges(css) {
  const out = [];
  const re = /@supports[^{]*color-mix[^{]*\{/g;
  let m;
  while ((m = re.exec(css))) {
    let i = m.index + m[0].length, depth = 1;
    while (i < css.length && depth > 0) {
      const ch = css[i];
      if (ch === "{") depth++;
      else if (ch === "}") depth--;
      i++;
    }
    out.push([m.index, i]);
  }
  return out;
}

const inRange = (ranges, pos) => ranges.some(([a, b]) => pos >= a && pos < b);

function audit(cssRaw) {
  const errs = [];
  const css = stripComments(cssRaw);
  const ranges = supportsRanges(css);

  /* 1) color-mix 只许在 @supports 覆盖块里 */
  const mixRe = /color-mix\(/g;
  let m;
  while ((m = mixRe.exec(css))) {
    if (inRange(ranges, m.index)) continue;
    const line = css.slice(0, m.index).split("\n").length;
    errs.push(`color-mix() 直接用在样式里（第 ${line} 行）：旧 WebView 会把整条声明丢掉，请改成语义令牌 var(--mix-*)，并在 :root 备好实色兜底`);
  }

  /* 令牌定义与使用要闭合：用了没定义 ⇒ 该属性按初始值渲染，玩家看到的是"边框没了" */
  const defined = new Set();
  const defRe = /(--mix-[a-z0-9_-]+)\s*:/g;
  while ((m = defRe.exec(css))) defined.add(m[1]);
  const useRe = /var\((--mix-[a-z0-9_-]+)\)/g;
  const used = new Set();
  while ((m = useRe.exec(css))) used.add(m[1]);
  used.forEach((t) => { if (!defined.has(t)) errs.push(`引用了未定义的色调令牌 var(${t})`); });
  defined.forEach((t) => { if (!used.has(t)) errs.push(`色调令牌 ${t} 定义了却没人用（要么删掉，要么说明为什么留着）`); });

  /* 2) 裸 inset: */
  const insetRe = /(?:^|[;{\s])inset\s*:/g;
  while ((m = insetRe.exec(css))) {
    const line = css.slice(0, m.index).split("\n").length;
    errs.push(`裸 inset: 声明（第 ${line} 行）：Chrome 87 以下不认，请写 top/right/bottom/left 四条`);
  }

  /* 3) dvh / min() / max() 的兜底：同一个声明块里，现代写法前面必须有一条同属性的老写法。
     老引擎认不下 value 就把整条声明丢掉，丢掉的那条如果前面没有兜底，属性会掉回初始值
     （height:100dvh 掉回 auto ⇒ #app 高度归零 ⇒ 整屏空白，这就是"低端机白屏"的成因）。 */
  const MODERN = /\d(?:dvh|lvh|svh|dvw)\b|(?<![a-z-])(?:min|max|clamp)\(/;
  const blockRe = /\{([^{}]*)\}/g;
  let b;
  while ((b = blockRe.exec(css))) {
    const body = b[1];
    const decls = body.split(";").map((d) => /^\s*([a-z-]+)\s*:\s*(.*)$/s.exec(d)).filter(Boolean);
    const seenProps = new Set();
    for (const d of decls) {
      const prop = d[1], value = d[2];
      if (MODERN.test(value)) {
        if (!seenProps.has(prop)) {
          const line = css.slice(0, b.index + body.indexOf(value)).split("\n").length;
          errs.push(`${prop}: ${value.trim().slice(0, 48)}（约第 ${line} 行）用了 dvh/min()/max()，前面没有老写法兜底 ⇒ 旧引擎整条声明作废`);
        }
      }
      seenProps.add(prop);
    }
  }
  return errs;
}

module.exports = { audit, stripComments };

if (require.main === module) {
  const fs = require("fs"), path = require("path");
  const f = process.argv[2] || path.join(__dirname, "..", "frontend", "css", "style.css");
  const errs = audit(fs.readFileSync(f, "utf8"));
  errs.forEach((e) => console.error("CSS:", e));
  console.log(errs.length ? `CSS 兜底裁判 FAIL：${errs.length} 处（${f}）` : `CSS 兜底裁判 OK：${path.basename(f)}`);
  process.exit(errs.length ? 1 : 0);
}
