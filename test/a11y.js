/* H5 静态裁判：对比度 + 命中区 + 可达性结构 + 物质底色。node 里跑，不需要后端，也不需要浏览器。
 *
 * 为什么值得单独立一个文件：这四类问题的代价都是"某个玩家完全用不了"，
 * 而它们在代码评审里恰恰是最难看出来的一类——色值差一档、按钮矮 15px，
 * 截图对比都发现不了。样式在 token 层判一次算清三套皮肤，数据在 js/data 层判一次算清 214 个底色。
 *
 * 口径用 WCAG 2.1 的相对亮度与对比度公式（https://www.w3.org/TR/WCAG21/#contrast-minimum）：
 * 小号正文与非图标控件按 AA 的 4.5:1；图标级的大字（≥24px 或 ≥18.66px 粗体）按 3:1。
 * 这里一律按 4.5 收，因为项目里最小字号是 10px 的 .ctl-lbl，没有一处能享受放宽。
 */
"use strict";
const fs = require("fs"), path = require("path");
const root = path.join(__dirname, "..", "frontend");

/* ---------- 1. 对比度 ---------- */
function lum(hex) {
  const h = hex.replace("#", "");
  const s = h.length === 3 ? h.split("").map((c) => c + c).join("") : h;
  const c = [0, 2, 4].map((i) => parseInt(s.substr(i, 2), 16) / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}
function ratio(a, b) {
  let x = lum(a), y = lum(b);
  if (x < y) { const t = x; x = y; y = t; }
  return (x + 0.05) / (y + 0.05);
}

/** 解析一段 CSS 正文里所有 `--token: #hex` 定义。 */
function parseTokens(body) {
  const out = {};
  const r = /(--[a-z0-9-]+)\s*:\s*(#[0-9a-fA-F]{3,8})\s*;/g;
  let k;
  while ((k = r.exec(body))) if (!(k[1] in out)) out[k[1]] = k[2];   // 同一块里不该重名，重名取第一条
  return out;
}

/** 块里某个渐变令牌的色标列表；找不到就返回 null，交给下一层（:root）继续找。 */
function stops(block, name) {
  if (!block) return null;
  const m = block.match(new RegExp(name + "\\s*:\\s*linear-gradient\\([^)]*\\)"));
  if (!m) return null;
  const hs = m[0].match(/#[0-9a-fA-F]{3,8}/g) || [];
  return hs.length ? hs : null;
}

/**
 * 每套皮肤要判的"文字 / 底"配对。
 *
 * 注意这里成对写死，不是"把所有 --x 和所有 --y 两两组合"：后者会报一堆
 * 项目里根本不存在的组合（--ink 压在 --soft-red 上），第一次跑就红十几条，
 * 然后这条裁判就会被当成噪音划掉——那是最坏的结果。
 */
const TEXT_PAIRS = [
  ["正文", "--ink", "--bg"], ["正文（卡上）", "--ink", "--card"],
  ["次级文字", "--sub", "--bg"], ["次级文字（卡上）", "--sub", "--card"],
  ["小号文字", "--muted", "--bg"], ["小号文字（卡上）", "--muted", "--card"],
  ["徽底符号", "--badge-fg", "--badge-bg"],
  ["选中段上的数字", "--pip-fg", "--pip-bg"],
  /* 顶栏信息片：以前写的是 rgba(255,255,255,.15) 这种半透明白，压在渐变上算不出混合值，
     裁判只能放过——而它恰好是这一屏上唯一会滑到 4.5 以下的地方（实测 4.43，hover 3.54）。
     改成实色 token 之后这条配对才判得动，所以它和正文同级进表。 */
  ["顶栏信息片", "--topbar-fg", "--topbar-pill"],
  ["顶栏信息片（悬停）", "--topbar-fg", "--topbar-pill-hover"],
  ["顶栏信息片上的弱化字", "--topbar-dim", "--topbar-pill"],
  ["启动/防沉迷板上的字", "--solid-fg", "--plate"],
  ["启动/防沉迷板上的弱化字", "--solid-dim", "--plate"],
  ["板上幽灵按钮的字", "--solid-fg", "--plate-deep"],
  ["板上说明条的字", "--solid-dim", "--plate-deep"],
];
const SOFT_KEYS = ["blue", "green", "orange", "red", "purple", "teal", "pink", "gold", "gray"];
/* 实心品牌块上的字：.btn-s / 角标 / 系统横幅 / .temp-btn.on 都是这一组。
   --blue/--green/… 本身不管：它们是描边、粒子、液体的装饰色，不需要读。 */
const SOLIDS = ["--solid-blue", "--solid-green", "--solid-orange", "--solid-red", "--solid-purple"];
/* 渐变面 × 压在上面的字。渐变按"最亮的一档"保守判，因为字会横穿整条渐变。 */
const GRADS = [
  ["--g-brand", ["--solid-fg", "--solid-dim"]],
  ["--g-primary", ["--solid-fg", "--solid-dim"]],
  ["--g-topbar", ["--topbar-fg", "--topbar-dim"]],
  ["--g-gold", ["--gold-fg"]],
];
/* 皮肤只许改令牌，所以每套替代皮肤必须把这一组全部覆盖掉：
   少一条不会报错（CSS 会退回 :root 的值），但那一处就会穿着默认皮的衣服出现在赛博/复古屏上。 */
const REQUIRED_IN_SKIN = ["--solid-fg", "--solid-dim", "--solid-blue", "--solid-green", "--solid-orange",
  "--solid-red", "--solid-purple", "--topbar-fg", "--topbar-dim", "--topbar-pill", "--topbar-pill-hover",
  "--plate", "--plate-deep", "--pip-bg", "--pip-fg", "--muted", "--badge-bg"];
/* 渐变令牌不是 #hex，parseTokens 收不到，只能按 stops() 单独查一遍：
   皮肤少写一条渐变同样会静默退回 :root 的蓝色，赛博皮一整排按钮就会变成"默认皮蓝"。 */
const REQUIRED_GRADS = ["--g-brand", "--g-primary", "--g-topbar"];

function contrastAudit(css, errs, info) {
  const rootBlock = css.match(/:root\s*\{[\s\S]*?\n\}/) ? css.match(/:root\s*\{[\s\S]*?\n\}/)[0] : null;
  if (!rootBlock) errs.push("H5: 找不到 :root token 块，裁判读不到色值");
  const skins = [
    ["默认", rootBlock],
    ["赛博", (css.match(/body\[data-skin="cyber"\]\s*\{[\s\S]*?\n\}/) || [])[0]],
    ["复古", (css.match(/body\[data-skin="retro"\]\s*\{[\s\S]*?\n\}/) || [])[0]],
  ];
  const worst = { v: 99, who: "" };
  let pairs = 0;
  skins.forEach(([name, block]) => {
    if (!block) return errs.push("H5: 找不到 " + name + " 皮肤的 token 块，裁判读不到色值");
    /* 皮肤块优先，查不到再回 :root —— 和 CSS 自己的层叠顺序一致 */
    const own = parseTokens(block);
    const t = Object.assign({}, rootBlock ? parseTokens(rootBlock) : {}, name === "默认" ? {} : own);
    if (name !== "默认") {
      REQUIRED_IN_SKIN.forEach((k) => {
        if (!(k in own)) errs.push("H5: " + name + " 皮肤没有覆盖 " + k + "（会退回默认皮的色值，那一处视觉跑偏）");
      });
      REQUIRED_GRADS.forEach((k) => {
        if (!stops(block, k)) errs.push("H5: " + name + " 皮肤没有覆盖 " + k + "（会退回默认皮的渐变，按钮/顶栏整片跑色）");
      });
    }
    const get = (k) => t[k];
    TEXT_PAIRS.forEach(([role, fg, bg]) => {
      const f = get(fg), b = get(bg);
      if (!f || !b) return errs.push("H5: " + name + " 皮肤缺 token " + (!f ? fg : bg) + "，" + role + " 没法判");
      const r = ratio(f, b); pairs++;
      if (r < 4.5) errs.push("H5: " + name + " " + role + " " + fg + " 在 " + bg + " 上只有 " + r.toFixed(2) + ":1（AA 要 4.5:1）");
      if (r < worst.v) { worst.v = r; worst.who = name + " " + role; }
    });
    SOFT_KEYS.forEach((k) => {
      const f = get("--fg-" + k), b = get("--soft-" + k);
      if (!f || !b) return errs.push("H5: " + name + " 皮肤缺 --fg-" + k + " 或 --soft-" + k);
      const r = ratio(f, b); pairs++;
      if (r < 4.5) errs.push("H5: " + name + " 标签 .tag." + k + " 的 --fg-" + k + " 在 --soft-" + k + " 上只有 " + r.toFixed(2) + ":1");
      if (r < worst.v) { worst.v = r; worst.who = name + " 标签 " + k; }
    });
    const fg = get("--solid-fg");
    SOLIDS.forEach((k) => {
      const b = get(k);
      if (!b || !fg) return errs.push("H5: " + name + " 皮肤缺 " + k + " 或 --solid-fg");
      const r = ratio(fg, b); pairs++;
      if (r < 4.5) errs.push("H5: " + name + " " + k + " 实心块上的 --solid-fg 只有 " + r.toFixed(2) + ":1（.btn-s/角标/横幅都用这组）");
      if (r < worst.v) { worst.v = r; worst.who = name + " " + k; }
    });
    GRADS.forEach(([g, fgs]) => {
      const hs = stops(block, g) || (block === rootBlock ? null : stops(rootBlock, g));
      if (!hs) return errs.push("H5: " + name + " 皮肤没有 " + g + " 渐变，压在上面的一块没底可判");
      fgs.forEach((fk) => {
        const f = get(fk);
        if (!f) return errs.push("H5: " + name + " 皮肤缺 " + fk);
        let lo = { r: 99, hex: "" };
        hs.forEach((h) => { const r = ratio(f, h); if (r < lo.r) lo = { r, hex: h }; });
        pairs++;
        if (lo.r < 4.5) errs.push("H5: " + name + " " + fk + " 压在 " + g + " 上，最亮的色标 " + lo.hex + " 只有 " + lo.r.toFixed(2) + ":1");
        if (lo.r < worst.v) { worst.v = lo.r; worst.who = name + " " + fk + "/" + g; }
      });
    });
  });
  info.push("contrast: 三套皮肤 " + pairs + " 组配对（正文/标签/实心块/四条渐变），最紧的一对是 " +
    worst.who + " = " + worst.v.toFixed(2) + ":1");
}

/* 半透明白板：底是"渐变 + 一层白纱"，混合值取决于这块板压在哪一段上，token 层算不出来。
   这一类恰恰是最容易悄悄下门槛的写法（实测顶栏的 .pill 4.43、hover 3.54，
   启动按钮 4.38，复古皮说明条 4.76），所以一律要求换成实色 token。
   名单里只留两处真正的装饰：经验条与广告进度条的底槽——它们上面没有字。 */
const FROSTED_OK = [".expbar", ".ad-bar"];
function plateAudit(css, errs, info) {
  const rules = css.match(/[^{}]*\{[^{}]*\}/g) || [];
  let flat = 0, banned = 0;
  rules.forEach((r) => {
    const head = r.slice(0, r.indexOf("{")).trim().replace(/\s+/g, " ");
    const body = r.slice(r.indexOf("{"));
    if (!/(^|[;{\s])background\s*:\s*rgba\(255,\s*255,\s*255,\s*[\d.]+\)/.test(body)) return;
    flat++;
    if (/::(before|after)/.test(head)) return;         // 覆盖层伪元素：不参与文字对比度
    if (FROSTED_OK.some((s) => head.split(/[,\s]+/).some((p) => p === s || p.endsWith(s)))) return;
    banned++;
    errs.push("H5: " + head + " 用半透明白当底却带着字——混合值看它压在哪条渐变上，裁判算不出对比度。"
      + " 请改成实色 token（顶栏用 --topbar-pill，渐变面上用 --plate / --plate-deep）。");
  });
  if (banned === 0) info.push("frosted plates: 平铺的 rgba(255,255,255,…) 只剩 " + flat + " 处装饰底槽，带字的板全是实色 token");
}

/* ---------- 2. 命中区 ---------- */
const TAP_CLASSES = [".pill", ".room-tab", ".temp-btn", ".seg", ".tabs button", ".btn-s",
  ".chips button", ".gate-forgot", ".gate-guest"];
const TAP_MIN_PX = 44;

function tapAudit(css, errs, info) {
  const token = css.match(/--tap-min\s*:\s*(\d+(?:\.\d+)?)px/);
  if (!token) return errs.push("H5: 没有 --tap-min token，命中区兜底整块失效");
  const px = parseFloat(token[1]);
  if (px < TAP_MIN_PX) errs.push("H5: --tap-min 只有 " + px + "px，低于 " + TAP_MIN_PX + "px 的下限");
  TAP_CLASSES.forEach((sel) => {
    const esc = sel.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    // 逐条规则扫，不按文件位置切：兜底块就写在最后，用"最后一条规则"判会漏掉媒体查询里
    // 把高度改回去的写法，而那种写法恰恰是最容易悄悄复辟的那一种。
    const rules = css.match(new RegExp("[^{}]*\\{[^{}]*\\}", "g")) || [];
    let lifted = 0, tooSmall = 0;
    rules.forEach((r) => {
      const head = r.slice(0, r.indexOf("{"));
      if (!new RegExp("(^|[\\s,])" + esc + "([\\s,{:.]|$)").test(head)) return;
      const body = r.slice(r.indexOf("{"));
      if (/min-height\s*:\s*var\(--tap-min\)/.test(body)) lifted++;
      const hard = body.match(/min-height\s*:\s*(\d+(?:\.\d+)?)px/);
      if (hard && parseFloat(hard[1]) < 24) tooSmall++;   // 24px 是 WCAG 2.5.8 的绝对底线
    });
    if (!lifted) errs.push("H5: " + sel + " 没有 min-height: var(--tap-min) 兜底（原来只有 27~31px）");
    if (tooSmall) errs.push("H5: " + sel + " 有 " + tooSmall + " 处把命中区写回 24px 以下的固定值");
  });
  info.push("tap targets: " + TAP_CLASSES.length + " 类已抬到 --tap-min=" + px + "px");
}

/* ---------- 3. token 用法与结构 ---------- */
function structureAudit(css, html, errs, info) {
  /* 前缀里的 - 或字母必须排除，否则 border-color / background-color / accent-color
     会被当成文字色误报（第一版就在 .ghost:hover 的 border-color 上红了一条）。 */
  const TEXT_COLOR = "(?:^|[^-\\w])color\\s*:\\s*";
  // --faint 只剩装饰用途：谁再拿它当文字色，等于把 2.05:1 的灰蓝放回正文
  const badFaint = (css.match(new RegExp(TEXT_COLOR + "var\\(--faint\\)", "gm")) || []).length;
  if (badFaint) errs.push("H5: 有 " + badFaint + " 处把 --faint 当文字色（它只做圆点/描边，正文请用 --muted）");
  // 装饰档同理：--blue/--green/… 是描边与粒子的颜色，压白字只有 1.67~4.06:1，
  // 要读就用 --fg-*（浅底深字）或 --solid-*+--solid-fg（色块白字），不许直接当字色。
  const rawBrand = (css.match(new RegExp(TEXT_COLOR +
    "var\\(--(?:blue|green|orange|red|teal|purple|pink|gold)\\)", "gm")) || []);
  if (rawBrand.length) errs.push("H5: 有 " + rawBrand.length + " 处直接用装饰色当文字色，请改 --fg-* / --solid-*：" +
    rawBrand.map((s) => s.trim()).join(" | "));
  if (!/\.sr-only\b/.test(css)) errs.push("H5: 缺 .sr-only —— aria 说明文字不能用 display:none 藏，那对读屏也是藏的");

  // 禁缩放：低视力玩家唯一的手段就是双指放大，禁掉等于把人挡在门外
  const vp = (html.match(/<meta[^>]*name="viewport"[^>]*>/) || [""])[0];
  if (!vp) errs.push("H5: 找不到 viewport meta");
  if (/user-scalable\s*=\s*no|maximum-scale\s*=\s*1\b/.test(vp)) errs.push("H5: viewport 仍禁用系统缩放: " + vp);

  [["#toasts", /<div id="toasts"[^>]*aria-live="polite"/],
   ["#modal-root", /<div id="modal-root"[^>]*aria-modal="true"/],
   ["#bottom-nav", /<nav id="bottom-nav"[^>]*aria-label/],
   ["#fx-canvas", /<canvas id="fx-canvas"[^>]*aria-hidden="true"/],
   ["#vessel-card", /<div id="vessel-card"[^>]*role="button"[^>]*tabindex="0"/],
   ["#cost-preview", /<div id="cost-preview"[^>]*aria-live="polite"/],
   ["#boot-msg", /<div id="boot-msg"[^>]*aria-live="polite"/]
  ].forEach(([who, re]) => { if (!re.test(html)) errs.push("H5: index.html 的 " + who + " 少了无障碍属性（期望 " + re + "）"); });

  // 只有 emoji 的按钮必须有 aria-label，否则读屏念出来是"未标注按钮"
  const iconOnly = html.match(/<button[^>]*>\s*[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}]\s*<\/button>/gu) || [];
  iconOnly.forEach((b) => { if (!/aria-label/.test(b)) errs.push("H5: 纯 emoji 按钮没有 aria-label: " + b.slice(0, 60)); });
  info.push("structure: 7 个关键区带 role/aria-live，纯 emoji 按钮 " + iconOnly.length + " 个全部有名，" +
    "文字色里没有一处直接用装饰色（--faint/" + rawBrand.length + "）");
}

/* ---------- 4. 物质调色板 ----------
 *
 * 元素/化合物卡上的符号是"底色由数据决定、字色现场挑"（js/panels.js pickTextColor），
 * 所以它不归皮肤管：三套皮肤都同一条底色。判法也比上面宽松一档——只要"白或墨里挑得出的
 * 那一个"能过 4.5:1 就算合格，因为挑色那一步本来就会挑最好的。
 *
 * 为什么要单独判：底色是内容数据，改它的人多半在想"这块晶体该是什么颜色"，
 * 不会想到 WCAG。而 L 落在 0.18333~0.22112 之间的那一段是死区——配白要求底 L ≤ 0.18333，
 * 配墨（#0f1b26，L=0.010249）要求 L ≥ 0.22112，掉进去就两头都不达标，挪几个字节的色号才出得来。
 * V13 修掉的硅、铀、胆矾、氢氧化铜正是这一类。
 *
 * 顺带判一件事：改色必须同时改迁移。内容包的真源在 content_item，后台一改色就以库为准
 * 覆盖前端默认值，只动 js/data 等于"只修好了没联网的那一侧"。这里拿 V2 的种子色做基准，
 * 前端与种子不同的那一档，必须能在后续某个迁移里找到同一个色号。
 * （这是绊线不是证明：万一那串十六进制在别的迁移里巧合出现过，会漏判。
 *   但"改了前端忘了改库"这一类占绝大多数，绊线就够本了。） */
const SQ_WHITE = "#ffffff";
const SQ_INK = "#0f1b26";
const MIG_DIR = path.join(__dirname, "..", "server", "src", "main", "resources", "db", "migration");

/** V2 种子：物质 id → 种进去的底色。 */
function seedColors(dir) {
  const p = path.join(dir, "V2__seed_content.sql");
  if (!fs.existsSync(p)) return null;
  const out = {};
  const re = /^\('(?:element|compound)',\s*'([^']+)',[^\n]*?"color":"(#[0-9a-fA-F]{6})"/gm;
  let m;
  while ((m = re.exec(fs.readFileSync(p, "utf8")))) if (!(m[1] in out)) out[m[1]] = m[2].toLowerCase();
  return out;
}

/** V2 之后所有迁移里出现过的十六进制字面量，当作"运营/迁移已经认过的色号"集合。 */
function laterHexes(dir) {
  const set = new Set();
  fs.readdirSync(dir).filter((f) => /^V\d+__.*\.sql$/.test(f) && !/^V2__/.test(f))
    .forEach((f) => (fs.readFileSync(path.join(dir, f), "utf8").match(/#[0-9a-fA-F]{6}\b/g) || [])
      .forEach((h) => set.add(h.toLowerCase())));
  return set;
}

function paletteAudit(palette, errs, info) {
  if (!Array.isArray(palette) || !palette.length) {
    return errs.push("H5: 没拿到物质调色板（validate 应当把 js/data 的结果传进来），底色整块没判");
  }
  const seed = seedColors(MIG_DIR);
  if (!seed) errs.push("H5: 读不到 " + MIG_DIR + "/V2__seed_content.sql，改色是否同步到库没法判");
  const hexes = seed ? laterHexes(MIG_DIR) : new Set();
  const worst = { v: 99, who: "" };
  const dead = [], unsynced = [], malformed = [];
  palette.forEach((s) => {
    if (!s || !s.id) return;
    const c = (s.color || "").toLowerCase();
    if (!/^#[0-9a-f]{6}$/.test(c)) return malformed.push(s.id + "(" + JSON.stringify(s.color) + ")");
    const r = Math.max(ratio(SQ_WHITE, c), ratio(SQ_INK, c));
    if (r < worst.v) { worst.v = r; worst.who = s.id; }
    if (r < 4.5) dead.push(s.id + " " + c + " 最好只有 " + r.toFixed(2) + ":1");
    if (seed && seed[s.id] && seed[s.id] !== c && !hexes.has(c)) {
      unsynced.push(s.id + " 前端 " + c + " / 种子 " + seed[s.id]);
    }
  });
  if (malformed.length) errs.push("H5: " + malformed.length + " 个物质没有合法的 6 位十六进制底色（符号卡会退回白字）：" + malformed.slice(0, 8).join("、"));
  if (dead.length) errs.push("H5: " + dead.length + " 个底色落在白/墨双不达标区（AA 要 4.5:1），只能挪色号——这一类不是挑色挑错了：" + dead.slice(0, 8).join("；"));
  if (unsynced.length) errs.push("H5: " + unsynced.length + " 个物质在前端改了底色却没有对应迁移（后台一改色就被库覆盖回去）：" + unsynced.slice(0, 8).join("；"));
  info.push("substance palette: " + palette.length + " 个底色全数可配白/墨读到，最紧的是 " +
    worst.who + " = " + worst.v.toFixed(2) + ":1；改色与迁移" + (seed ? "已对齐" : "未判"));
}

function audit(cssText, htmlText, palette) {
  const errs = [], info = [];
  contrastAudit(cssText, errs, info);
  plateAudit(cssText, errs, info);
  tapAudit(cssText, errs, info);
  structureAudit(cssText, htmlText, errs, info);
  paletteAudit(palette, errs, info);
  return { errs, info };
}

module.exports = { audit, ratio, readPalette };

/** 独立跑时自己去读 js/data，口径和 validate.js 的 vm 沙箱一致。 */
function readPalette() {
  const vm = require("vm");
  const sb = {}; sb.window = sb; sb.globalThis = sb;
  vm.createContext(sb);
  ["js/data/elements.js", "js/data/compounds.js"].forEach((f) => {
    const p = path.join(root, f);
    if (!fs.existsSync(p)) return null;
    vm.runInContext(fs.readFileSync(p, "utf8"), sb, { filename: f });
  });
  const C = (sb.window.CHEM) || {};
  return (C.ELEMENTS || []).concat(C.COMPOUNDS || []);
}

if (require.main === module) {
  const r = audit(fs.readFileSync(path.join(root, "css/style.css"), "utf8"),
    fs.readFileSync(path.join(root, "index.html"), "utf8"), readPalette());
  r.info.forEach((i) => console.log("  " + i));
  r.errs.forEach((e) => console.error("  " + e));
  console.log(r.errs.length ? "a11y: FAIL " + r.errs.length : "a11y: ok");
  process.exit(r.errs.length ? 1 : 0);
}
