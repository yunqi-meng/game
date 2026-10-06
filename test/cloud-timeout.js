/* H2 裁判：请求超时必须自己到点，回调必须恰好一次。见 test/cloud-timeout.js 顶部说明。
   node test/cloud-timeout.js        单跑
   node test/validate.js             第 1 层顺带跑（ci.sh 里就是它）

   为什么单独一份：js/cloud.js 的 raw() 以前是 `fetch(...).then(json).then(cb).catch(cb)`，
   两个毛病都只有弱网能暴露，而弱网不会出现在 e2e 的 curl 里：
     ① 请求挂着不回——既不 resolve 也不 reject——时，串行的意图队列（game.js 的 pump）
        会永久停在 running=true 上，玩家点什么都没反应，那套"退避重发／拉帧对齐"永远轮不到执行；
     ② cb 自己抛错时会被同一个链的 .catch 再叫一次——一次请求落两次购买。
   这两条都只能用"人造的坏 fetch"来验：跑真的服务器只会验出"网络是好的"。
*/
"use strict";
const fs = require("fs"), path = require("path"), vm = require("vm");

const SRC = path.join(__dirname, "..", "frontend", "js", "cloud.js");
const TIMEOUT_MS = 30;            // 真等 12 秒没人会跑这条回归；口径不变，只是把期限调小
const LATE_MS = 140;              // "迟到正文"要明显晚于期限，才能证明闩挡住了第二次回调

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/** 一个响应：body 是准备回的东西，boom=true 表示正文压根解析不出来（HTML 错误页就是这个样子）。 */
function respond(body, boom) {
  return {
    json: () => boom ? Promise.reject(new Error("Unexpected token <")) : Promise.resolve(body)
  };
}

/**
 * 造一个坏网：mode 决定这一次请求怎么坏。
 *   hang        永不 settle（弱网挂死的形状）——只认期限，不认 reject
 *   abortable   永不 settle，但收到 abort 就按 AbortError 拒绝（现代浏览器/WebView 的真实形状）
 *   late        无视 abort，LATE_MS 之后才回正文：专门验"迟到的那一份不许再叫一次 cb"
 *   json        正常回 {ok,data,code}
 *   parse       正文解析失败
 *   die         立刻拒绝（连不上的形状）
 */
function makeFetch(mode, seen) {
  return function (url, opts) {
    seen.push({ url: url, opts: opts || {} });
    if (mode === "hang") return new Promise(() => {});
    if (mode === "abortable") return new Promise((res, rej) => {
      const sig = opts && opts.signal;
      if (!sig) return;
      if (sig.aborted) { const e = new Error("aborted"); e.name = "AbortError"; return rej(e); }
      sig.addEventListener("abort", () => { const e = new Error("aborted"); e.name = "AbortError"; rej(e); });
    });
    if (mode === "late") return new Promise((res) => setTimeout(() => res(respond({ ok: true, data: { late: 1 } })), LATE_MS));
    if (mode === "parse") return Promise.resolve(respond(null, true));
    if (mode === "die") return Promise.reject(new TypeError("Failed to fetch"));
    return Promise.resolve(respond({ ok: true, code: undefined, data: { coins: 5, state: { coins: 5 } } }));
  };
}

/** 一份干净的浏览器环境：每次调用新建，用例之间不共享令牌与队列。 */
function load(mode, opts) {
  opts = opts || {};
  const seen = [];
  const store = Object.create(null);
  const ls = {
    getItem: (k) => (k in store ? store[k] : null),
    setItem: (k, v) => { store[k] = String(v); },
    removeItem: (k) => { delete store[k]; }
  };
  const sandbox = {
    setTimeout, clearTimeout, setInterval, clearInterval,
    localStorage: ls,
    location: { protocol: "https:", href: "https://example.com/" },
    fetch: makeFetch(mode, seen)
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  if (!opts.noAbortController) sandbox.AbortController = AbortController;
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(SRC, "utf8"), sandbox, { filename: "js/cloud.js" });
  const C = sandbox.window.CHEM.cloud;
  C.timeoutMs = TIMEOUT_MS;
  return { C, seen, store };
}

/** 收集 cb 的全部调用：既验内容，也验次数。 */
function once(fn) {
  const calls = [];
  const wrapper = function () { calls.push(Array.from(arguments)); };
  wrapper.calls = calls;
  return { cb: wrapper, calls };
}

async function run() {
  const errs = [];
  const A = (cond, msg) => { if (!cond) errs.push(msg); };

  /* ① 挂死的请求必须在期限处自己断开 */
  for (const mode of ["hang", "abortable"]) {
    const { C, seen } = load(mode);
    const { cb, calls } = once();
    C.game("market.buy", { id: "Na" }, cb);
    await wait(LATE_MS);
    A(calls.length === 1, mode + "：挂死的请求回调一次都不没来（拿到 " + calls.length + " 次）——超时没生效，意图队列会永久卡住");
    const j = calls[0] && calls[0][0];
    A(j && j.ok === false, mode + "：超时必须报失败");
    A(j && j.timeout === true, mode + "：超时结果要带 timeout 标记，UI 才分得开'放弃了'和'服务器拒了'");
    A(j && /太久没回应/.test(j.msg || ""), mode + "：超时要说人话并讲清进度未变动，实际：" + (j && j.msg));
    /* 这条最关键：game.js 靠 code 与 tag 双双缺席判定"根本没得到答复"，才会走退避／拉帧 */
    A(j && j.code === undefined, mode + "：超时不许带 code，否则会被当成服务端明确拒绝而不重试");
    A(j && !j.tag, mode + "：超时不许带 tag，否则 CURFEW 之类的分流会认错");
    A(/market\.buy/.test(seen[0] && seen[0].url || ""), "① 请求发到了意图端点");
  }
  /* ①-补 现代实现必须真的把 signal 交出去，否则 abort 无处可发（期限只是个空转的闹钟） */
  {
    const s = load("abortable");
    s.C.game("state", {}, () => {});
    await wait(8);
    const opts = s.seen[0] && s.seen[0].opts;
    A(!!(opts && opts.signal), "H2：现代浏览器这一路没把 AbortController 的 signal 交给 fetch");
  }

  /* ② 迟到的正文不许有第二次回调（这就是 .then(cb).catch(cb) 那个老毛病的位置） */
  {
    const { C } = load("late");
    const { cb, calls } = once();
    C.game("market.buy", { id: "Na" }, cb);
    await wait(LATE_MS * 2);
    A(calls.length === 1, "late：请求超时之后正文才回来，回调被叫了 " + calls.length + " 次——一次购买变两次");
    A(calls[0] && calls[0][0] && calls[0][0].timeout === true, "late：第一次报的应当是超时");
  }

  /* ③ 回调自己抛错时也只叫一次：旧链式写法会被 .catch 再叫一遍 */
  {
    const fired = [];
    const h = (e) => fired.push(e);
    process.on("unhandledRejection", h);
    const { C } = load("json");
    let n = 0;
    C.game("market.buy", { id: "Na" }, function () { n++; throw new Error("面板渲染炸了"); });
    await wait(LATE_MS);
    process.off("unhandledRejection", h);
    A(n === 1, "cb 抛错后被叫了 " + n + " 次：回调恰好一次是幂等的地基，不能被同一个链再补一次");
    A(fired.length === 1, "cb 抛错应当变成一次可观察的 rejection，实际 " + fired.length + " 次");
  }

  /* ④ 正常路径没被改动：信封照旧解包，data 平铺 */
  {
    const { C } = load("json");
    let j = null;
    C.game("market.buy", { id: "Na" }, function (r) { j = r; });
    await wait(8);
    A(j && j.ok === true, "json：正常应答要解出 ok");
    A(j && j.coins === 5, "json：data 要平铺到结果上（game.js 直接读 j.state / j.result）");
  }

  /* ⑤ 正文解析失败仍然是"响应解析失败"，不是超时 */
  {
    const { C } = load("parse");
    let j = null;
    C.game("state", {}, function (r) { j = r; });
    await wait(8);
    A(j && j.ok === false && /解析失败/.test(j.msg || ""), "parse：坏正文要报'响应解析失败'，实际：" + JSON.stringify(j));
    A(j && !j.timeout, "parse：这不是超时，别挂 timeout 标记（挂了下层会去重发）");
  }

  /* ⑥ 连不上仍然走原文案，且不带 timeout */
  {
    const { C } = load("die");
    let j = null;
    C.game("state", {}, function (r) { j = r; });
    await wait(8);
    A(j && j.ok === false && /网络错误/.test(j.msg || ""), "die：连接失败仍要走'网络错误'文案，实际：" + JSON.stringify(j));
    A(j && !j.timeout, "die：真失败和'我们放弃了'要能分开");
  }

  /* ⑦ 没有 AbortController 的老 WebView：不包超时，行为照旧 */
  {
    const s = load("json", { noAbortController: true });
    let j = null;
    s.C.game("market.buy", { id: "Na" }, function (r) { j = r; });
    await wait(8);
    A(j && j.ok === true && j.coins === 5, "无 AbortController：请求要照常完成，不能因为探不到 API 就没网");
    const opts = s.seen[0] && s.seen[0].opts;
    A(!opts || opts.signal === undefined, "无 AbortController：这一条路不该出现 signal 字段");
  }

  /* ⑧ 探活也吃同一个期限：它是启动失败页唯一的事实来源，挂着不回就等于转圈到天荒地老 */
  {
    const { C } = load("abortable");
    let j = null;
    C.health(function (r) { j = r; });
    await wait(LATE_MS);
    A(j && j.ok === false && j.reach === false, "health：超时要算'没连上'，不能报成'服务活着但库断了'");
    A(j && j.timeout === true, "health：结果要带 timeout，失败页才写得出'太久没回应'");
  }

  /* ⑨ 内容库共用这一个期限（首装那一次 fetchBundle 挂在弱网里 = cb 永远不来 = 启动卡死） */
  {
    const src = fs.readFileSync(path.join(__dirname, "..", "frontend", "js", "content.js"), "utf8");
    A(/CHEM\.cloud\.timedFetch/.test(src), "H2：content.js 的 fetchBundle／revalidate 没接 cloud.js 那个期限，首装弱网仍会卡在启动遮罩");
    A(!/fetch\(base\(\)\s*\+\s*"\/api\/content\/(bundle|version)"\)/.test(src), "H2：content.js 还留着裸 fetch，超时覆盖不到那一条路");
  }

  return errs;
}

module.exports = { run };

if (require.main === module) {
  run().then((errs) => {
    errs.forEach((e) => console.error("ERROR:", e));
    console.log(errs.length ? "FAIL: " + errs.length + " errors" : "PASS ✔ (cloud timeout)");
    process.exit(errs.length ? 1 : 0);
  }, (e) => { console.error("ERROR: cloud-timeout 崩了:", e && e.stack || e); process.exit(1); });
}
