/* test/admin-paging.js —— 后台分页列表的两端对账（H6-3）
 *
 * 要防的那件事：`/admin/api/moderation/reports` 这类列表端点回的是**裸数组**，面板拿不到总数，
 * 只好用本页长度编一个（`d.length < size ? 偏移 + d.length : 页码 * size + 1`）。那个数字在满页时
 * 永远说"还有下一页"，翻过去是空的；在最后一页又多报一条。这一轮服务端统一回 `{rows,total}`，
 * 总数由 `COUNT(*)` 回答，于是债换成了跨语言的一对：
 *
 *   Java 那边宣布分页的端点，Vue 这边每一个调用点都必须按 `rows/total` 读；
 *   反过来，面板上每一个分页组件绑定的总数，必须是某个服务端给的 total。
 *
 * 这类账 grep 判不动——"少接了一个端点"在文本上没有错字可搜，Java 单测看不见 `.vue`，
 * 而 e2e 只证明"真库上这个接口是对的"，证明不了面板读的是那两个键。所以这里对账，
 * 受分页的端点从 Java 源码里**读出来**（返回类型是 `ApiResponse<Page<…>>` 的 @GetMapping），
 * 本文件不抄一份端点清单。
 *
 * 顺带钉住两件同形的事：① 页长不能超过服务端认的上限（`Page.MAX_SIZE`，从 Java 读），
 * 否则面板填个 500 以为自己一次搬完，实际只拿到 200 行却按 500 算页数；
 * ② 那句"用本页长度编总数"的写法不许在面板里复活。
 *
 * 只管这三条：SQL 层面的 count 与 page 是否同源由 MapperQueryHygieneTest 判，
 * 真库上逐页走一遍由 test/e2e-api.sh 判。
 *
 * 退出码：0 通过 / 1 有问题。依赖：node（只读文件）。
 */
"use strict";
const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..");
const P = (...s) => path.join(ROOT, ...s);
const ADMIN_SRC = P("admin", "src");
const PAGE_JAVA = P("server", "src", "main", "java", "com", "chemera", "server", "common", "Page.java");
const CONTROLLER_DIR = P("server", "src", "main", "java", "com", "chemera", "server", "controller", "admin");

/** 面板那侧一眼就能看出"总数是编的"两种写法：拿本页长度补一个总数。 */
const FABRICATED = [
  { re: /\.length\s*<\s*size\b/, why: "用本页长度判\"还有没有下一页\"，再据此编一个总数" },
  { re: /\*\s*size\s*\+\s*1\b/, why: "满页时把总数写成 页码×每页+1（永远说还有下一页）" },
];

function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, " ").replace(/^[ \t]*\/\/.*$/gm, " ")
            .replace(/<!--[\s\S]*?-->/g, " ");
}

/** 遍历 admin/src 下的 .vue / .js（面板的全部源码，新加视图自动进来，不用登记）。 */
function panelFiles(dir = ADMIN_SRC, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const f = path.join(dir, e.name);
    if (e.isDirectory()) panelFiles(f, out);
    else if (/\.vue$|\.js$/.test(e.name)) out.push(f);
  }
  return out;
}

/**
 * 从 Java 控制器里读出"哪些 GET 回的是分页形状"。
 * 判据是返回类型写了 `ApiResponse<Page<`——这一个构造点就是这一轮的合同。
 */
function pagedEndpoints(javaFiles) {
  const out = [];
  for (const f of javaFiles) {
    const src = stripComments(fs.readFileSync(f, "utf8"));
    const cls = /@RequestMapping\(\s*"([^"]+)"\s*\)/.exec(src);
    if (!cls) continue;
    const base = cls[1];
    const re = /@GetMapping\b(?:\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\))?/g;
    let m;
    while ((m = re.exec(src))) {
      const tail = src.slice(m.index + m[0].length, m.index + m[0].length + 700);
      // 只看签名：方法体以 { 开头、语句以 ; 收尾，越过这条线读到的都是下一个方法的返回类型
      const stop = firstOf(tail, ["{", ";"]);
      const sig = stop < 0 ? tail : tail.slice(0, stop);
      if (!sig.includes("ApiResponse<Page<")) continue;
      const names = sig.match(/([A-Za-z]\w*)\s*\(/g) || [];
      out.push({
        file: path.basename(f),
        method: names.length ? names[names.length - 1].replace(/\s*\($/, "") : "?",
        // 面板的 baseURL 是 /admin/api，所以那边写的是去掉这段前缀的路径
        path: (base + (m[1] || "")).replace(/^\/admin\/api/, ""),
      });
    }
  }
  return out;
}

function firstOf(s, needles) {
  let best = -1;
  for (const n of needles) {
    const i = s.indexOf(n);
    if (i >= 0 && (best < 0 || i < best)) best = i;
  }
  return best;
}

/* ---------------- 主判据 ---------------- */

function judge(panelSrc, javaSrc, maxSize) {
  const errs = [], info = [];
  const files = Object.keys(panelSrc);

  // ① 那句假总数不许复活
  for (const f of files) {
    for (const { re, why } of FABRICATED) {
      if (re.test(panelSrc[f])) errs.push(`${f} 里又出现了"用本页长度编总数"：${why}`);
    }
  }

  // ② Java 宣布分页的每个端点，面板调用点必须按 rows/total 读
  const endpoints = javaSrc.endpoints;
  let wired = 0;
  for (const ep of endpoints) {
    const hits = files.filter((f) => panelSrc[f].includes(`http.get("${ep.path}"`));
    if (!hits.length) { info.push(`分页端点 ${ep.path} 面板没调用（死逻辑，H6-6 的范围）`); continue; }
    for (const f of hits) {
      const src = panelSrc[f];
      let from = 0, sawTotal = false, sites = 0;
      while ((from = src.indexOf(`http.get("${ep.path}"`, from)) >= 0) {
        sites++;
        const win = src.slice(from, from + 400);
        if (!win.includes(".rows")) errs.push(`${f} 调 ${ep.path} 之后没读 .rows：这个端点回的是 {rows,total}，直接把返回值当数组用会一片空白`);
        if (win.includes(".total")) sawTotal = true;
        if (!/\bparams\s*:/.test(win) || !/\bsize\b/.test(win) || !/\boff\b/.test(win))
          errs.push(`${f} 调 ${ep.path} 没带 size/off：整个列表会一次搬回来，分页组件形同装饰`);
        from += 10;
      }
      // 同一个端点可能有几个调用点（列表那一处、"这个键在不在库里"那种定点查），
      // 只有喂分页组件的那处需要总数，所以按"这一页里至少有一处读了 .total"判，不逼每一处都读。
      if (!sawTotal) errs.push(`${f} 调 ${ep.path} 的 ${sites} 处里没有一个读 .total：分页组件的总数只能来自服务端那句 COUNT(*)`);
      wired += sites;
    }
  }

  // ③ 每个分页组件绑的总数，得是这一轮服务端给的那个 total
  for (const f of files) {
    const src = panelSrc[f];
    const re = /<el-pagination\b[^>]*?:total="([\w.]+)"([^>]*?)\/?>/gs;
    let m, seen = 0;
    while ((m = re.exec(src))) {
      seen++;
      const [, bound, rest] = m;
      if (!/:\s*page-size\s*=/.test(rest)) errs.push(`${f} 的 el-pagination 没绑 :page-size：页数会按默认 10 条算，和服务端给的一页条数对不上`);
      if (!/@current-change\s*=/.test(rest)) errs.push(`${f} 的 el-pagination 没接 @current-change：页码点了不动，翻页是装饰`);
      // 绑的那个名字必须在这一页里被服务端给的 total 喂过（不是本地 length 算出来的）
      const name = bound.split(".").pop();
      if (!new RegExp(name + "\\.value\\s*=\\s*[^;]*\.total").test(src)
          && !new RegExp("\\b" + name + "\\s*[:=]\\s*[^;,]*\.total").test(src))
        errs.push(`${f} 的分页组件把 :total 绑到 ${bound}，而它不是从响应体的 .total 来的`);
    }
    if (/<el-pagination\b/.test(src) && !seen)
      errs.push(`${f} 有 el-pagination 却没绑 :total——没总数的分页组件只能靠猜`);
  }

  // ④ 页长不许超过服务端认的上限：面板按 500 算页数、库里只给 200 行，就是第二个假页数
  for (const f of files) {
    const src = panelSrc[f];
    const re = /\bsize\s*[:=]\s*(\d+)/g;
    let m;
    while ((m = re.exec(src))) {
      const n = Number(m[1]);
      if (n > maxSize) errs.push(`${f} 里的页长 ${n} 超过服务端上限 Page.MAX_SIZE=${maxSize}：拿回来的行数会比按它算的页数少`);
    }
  }

  info.push(`分页端点 ${endpoints.length} 个 / 面板调用点 ${wired} 处 / MAX_SIZE=${maxSize}`);
  return { errs, info };
}

/* ---------------- 反证：这两把尺子得能咬动 ---------------- */

/**
 * 静态对账最容易犯的错，是写成一座永远绿的桥：扫描器认不出任何端点（于是"每个分页端点都要接"
 * 空转）、或者把隔壁那个非分页端点也认成分页（于是全仓库都在报错，下一步就是有人把这条判据删掉）。
 * 两种失效都在这里喂一次假源码。
 */
function selfCheck() {
  const errs = [];
  const java = { endpoints: [{ file: "Y.java", method: "list", path: "/y" }] };
  const good = {
    "a/V.vue": `<template><el-pagination :total="total" :page-size="size" @current-change="onPage" /></template>
      <script>const d = await http.get("/y", { params: { size, off } }); rows.value = d.rows; total.value = d.total;</script>`,
  };
  if (judge(good, java, 200).errs.length)
    errs.push("反证失败：正确写法被判红（" + judge(good, java, 200).errs.join(" / ") + "），这条对账会把人推向「删掉判据」");

  const bad = {
    "a/V.vue": `<script>const d = await http.get("/y", { params: { size, off } });
      rows.value = d; total.value = d.length < size ? (page - 1) * size + d.length : page * size + 1;</script>`,
  };
  const r = judge(bad, java, 200);
  if (!r.errs.length) errs.push("反证失败：把返回值当数组、还用本页长度编总数，判据居然没红");

  // 扫描器：一个 List 端点 + 一个 Page 端点，只该认出后面那个
  const synth = `
    @RequestMapping("/admin/api/y")
    public class Y {
      @GetMapping("/types") public ApiResponse<List<String>> types() { return ApiResponse.ok(List.of()); }
      @GetMapping public ApiResponse<Page<Map<String, Object>>> list(@RequestParam String q) {
        return ApiResponse.ok(new Page<>(List.of(), 0L));
      }
    }`;
  const tmp = path.join(ROOT, "test", ".admin-paging-selfcheck-tmp");
  try {
    fs.mkdirSync(path.dirname(tmp), { recursive: true });
    fs.writeFileSync(tmp, synth);
    const found = pagedEndpoints([tmp]).map((e) => e.path);
    if (found.length !== 1 || found[0] !== "/y")
      errs.push("反证失败：分页端点扫描认错或漏认（实得 " + JSON.stringify(found) + "）——它把方法体里下一个方法的返回类型也算进来了");
    if (pagedEndpoints([tmp])[0] && pagedEndpoints([tmp])[0].method !== "list")
      errs.push("反证失败：扫到了分页端点却没认出方法名，红字将无法点名");
  } finally {
    try { fs.rmSync(tmp, { force: true }); } catch (e) { /* 清不掉不影响判据 */ }
  }
  return errs;
}

/* ---------------- 落地：从真实文件读数 ---------------- */

function judgeFiles() {
  const self = selfCheck();
  if (self.length) return { errs: self, info: [] };
  if (!fs.existsSync(PAGE_JAVA)) return { errs: ["找不到 common/Page.java：这一轮的合同没有出处"], info: [] };
  const javaPage = stripComments(fs.readFileSync(PAGE_JAVA, "utf8"));
  const mm = /MAX_SIZE\s*=\s*(\d+)/.exec(javaPage);
  if (!mm) return { errs: ["Page.java 里没有 MAX_SIZE 这个常数，页长上限那条判据失去依据"], info: [] };
  const maxSize = Number(mm[1]);
  // 形状本身也钉一下：面板读的就是这两个键名，改名要两边一起改
  if (!/record\s+Page\s*<\s*T\s*>\s*\(\s*List<T>\s+rows\s*,\s*long\s+total\s*\)/.test(javaPage))
    return { errs: ["Page 的字段不再是 (rows, total)：面板那侧读的两个键名要跟着改，别让这条判据先绿"], info: [] };

  if (!fs.existsSync(CONTROLLER_DIR)) return { errs: ["找不到后台控制器目录"], info: [] };
  const controllers = fs.readdirSync(CONTROLLER_DIR)
    .filter((f) => /^Admin\w*Controller\.java$/.test(f))
    .map((f) => path.join(CONTROLLER_DIR, f));
  const endpoints = pagedEndpoints(controllers);
  if (endpoints.length < 6) return { errs: [`只从后台控制器里认出 ${endpoints.length} 个分页端点，扫描大概失效了`], info: [] };

  const panelSrc = {};
  for (const f of panelFiles()) panelSrc[path.relative(ROOT, f).replace(/\\/g, "/")] = stripComments(fs.readFileSync(f, "utf8"));
  return judge(panelSrc, { endpoints }, maxSize);
}

module.exports = { judge, judgeFiles, pagedEndpoints, panelFiles, PAGE_JAVA, CONTROLLER_DIR };

if (require.main === module) {
  const r = judgeFiles();
  (r.info || []).forEach((i) => console.log("admin-paging:", i));
  r.errs.forEach((e) => console.error("ERROR:", e));
  process.exit(r.errs.length ? 1 : 0);
}
