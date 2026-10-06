/* test/audio-haptics.js —— H4 的裁判：音频有没有在手势里点火、触感有没有落在该落的地方。
 *
 * 为什么又开一个 node 裁判（而不是 shell grep）
 *   这一项的两个 bug 都不是"字少了"，而是"顺序错了／条件错了"：
 *   ① AudioContext 第一次创建发生在网络回调里（在线版的音效都是服务端结算回来才响的），
 *      WebView 起来就是 suspended，而 resume() 在手势外必被拒 ⇒ 整局静音，界面上看不出任何异常；
 *   ② 音量滑条的 oninput 里写了一句无条件 setMusic(true) ⇒ 开关显示"已关闭"，拉一下滑条却自己响了。
 *   这两种只有跑起来才成立，grep 判不动；而"该振哪三处、各振多久、设备不支持就跳过、
 *   静音时照样振"同样是把那段切出来喂假 navigator 才能验。所以跟 render-budget.js 一个路子：
 *   第 1 层（validate.js）判仓库里那份，e2e 判服务端下发的那一份，两边共用同一个裁判。
 *
 * 用法（都从 stdin 进，退出码 0 通过 / 1 红 / 2 读不到料）
 *   curl -s $BASE/js/sfx.js    | node test/audio-haptics.js sfx
 *   curl -s $BASE/js/panels.js | node test/audio-haptics.js panels
 */
"use strict";

const fs = require("fs");

function say(lines, code) {
  (Array.isArray(lines) ? lines : [lines]).forEach(function (l) { console.log(l); });
  process.exit(code);
}

/* ---------- sfx 那份 ---------- */

/**
 * 把 `var BUZZ` 到 `var SCALE`（背景音乐的头）之间那段切出来求值：BUZZ 表、hapticOn/setHaptic/buzz/buzzMs、S.play 都在里面。
 * 它对外只用到四样东西（CHEM.state、window、localStorage、ac/SFX），全部可注：
 * 注进去的 navigator 就是"这台设备振不振得动"的假设，所以同一个函数能扮 iOS、扮旧 WebView、扮被系统打断。
 */
function loadHaptics(src) {
  const i = src.indexOf("var BUZZ");
  const j = src.indexOf("var SCALE");
  if (i < 0 || j < 0 || j <= i) return null;
  let make;
  try {
    make = new Function("CHEM", "window", "localStorage", "ac", "SFX",
      "var S = {}, vg = 1;\n" + src.slice(i, j) + "\nreturn S;");
  } catch (e) {
    return { loadError: "切出来那段无法求值：" + e.message };
  }
  /** opt: {noNav, nav, vibrateFalse, vibrateThrows, store, volSfx, audio} */
  return function (opt) {
    opt = opt || {};
    const seen = [];
    let nav;
    if (opt.noNav) nav = undefined;
    else if (opt.nav) nav = opt.nav;
    else if (opt.vibrateThrows) nav = { vibrate: function () { throw new Error("not allowed"); } };
    else nav = { vibrate: function (ms) { seen.push(ms); return opt.vibrateFalse ? false : true; } };
    const win = {};
    if (!opt.noNav) win.navigator = nav;
    const heard = [];
    const S = make(
      { state: { data: { volSfx: opt.volSfx === undefined ? 80 : opt.volSfx } } },
      win,
      opt.store || { getItem: function () { return null; }, setItem: function () {} },
      function () { return opt.audio ? { state: "running", currentTime: 0, sampleRate: 44100 } : null; },
      { boom: function () { heard.push("boom"); }, success: function () { heard.push("success"); },
        levelup: function () { heard.push("levelup"); }, click: function () { heard.push("click"); } }
    );
    return { S: S, seen: seen, heard: heard };
  };
}

const SHOULD_BUZZ = ["success", "boom", "levelup"];
const SHOULD_NOT = ["click", "place", "coin", "error", "pour", "boomboom"];

/** 行为判据：哪三处振、振多久、三层容错、静音与开关。 */
function hapticBehavior(inst, errs) {
  // ① 三处结果时刻各一次，且都在 10–20ms 这个"点一下"的量级
  SHOULD_BUZZ.forEach(function (name) {
    const t = inst();
    const r = t.S.buzz(name);
    if (r !== true) errs.push("buzz(" + name + ") 该振，实际返回 " + r);
    if (t.seen.length !== 1) return errs.push("buzz(" + name + ") 振了 " + t.seen.length + " 次，一次结果该只抖一下");
    const ms = t.seen[0];
    if (!(ms >= 10 && ms <= 20)) errs.push(name + " 的振动时长 " + ms + "ms：短于 10ms 摸不出来，长于 20ms 像故障还费电");
  });
  // ② 其余音效不该带振动：每一跳都振一遍，玩家只会把它关掉
  const t0 = inst();
  SHOULD_NOT.forEach(function (name) {
    if (t0.S.buzz(name) !== false) errs.push("buzz(" + name + ") 不该振（只有成功／事故／升级这三处）");
    if (t0.S.buzzMs(name) !== 0) errs.push("buzzMs(" + name + ") 报了时长，BUZZ 表里混进了点击类反馈");
  });
  if (t0.seen.length) errs.push("不该振的一共振了 " + t0.seen.length + " 次");
  // ③ 三层容错：没这个 API（iOS Safari／旧 WebView）／调用本身抛／页面不在前台返回 false
  const tNoApi = inst({ noNav: true });
  if (tNoApi.S.buzz("success") !== false) errs.push("没有 navigator 也说要振（iOS Safari 会直接抛）");
  const tEmpty = inst({ nav: {} });
  if (tEmpty.S.buzz("boom") !== false) errs.push("navigator 在但没有 vibrate，buzz 没走特性检测");
  const tThrow = inst({ vibrateThrows: true });
  if (tThrow.S.buzz("boom") !== false) errs.push("vibrate 抛异常时 buzz 没兜住（振动失败绝不能把结算渲染带崩）");
  const tFalse = inst({ vibrateFalse: true });
  if (tFalse.S.buzz("levelup") !== false) errs.push("vibrate 返回 false（页面不在前台）时 buzz 谎报成功");
  // ④ 本机开关：关了就不振，重新打开立刻能振；localStorage 取不到时按默认开
  const mem = (function () {
    const o = {};
    return { getItem: function (k) { return (k in o) ? o[k] : null; }, setItem: function (k, v) { o[k] = String(v); } };
  })();
  const tOff = inst({ store: mem });
  tOff.S.setHaptic(false);
  if (tOff.S.hapticOn() !== false) errs.push("setHaptic(false) 之后 hapticOn 还说开着");
  if (tOff.S.buzz("success") !== false) errs.push("开关关了还在振：设置页那个按钮就成了摆设");
  if (tOff.seen.length) errs.push("关了开关仍然调用了 vibrate");
  tOff.S.setHaptic(true);
  if (tOff.S.buzz("success") !== true) errs.push("重新打开后振不回来了（开关状态被写死）");
  const tPriv = inst({ store: { getItem: function () { throw new Error("SecurityError"); }, setItem: function () { throw new Error("x"); } } });
  if (tPriv.S.hapticOn() !== true) errs.push("隐私模式取不到 localStorage 时应当按默认开，实际判成关（等于无声无触）");
  if (tPriv.S.buzz("success") !== true) errs.push("localStorage 抛异常把触感一起带没了");
  // ⑤ 触感排在音量闸门之前：静音是"别出声"，不是"别理我"
  const tMute = inst({ volSfx: 0 });
  tMute.S.play("boom");
  if (tMute.seen.length !== 1) errs.push("音效静音时事故不再振动（buzz 被 vg 闸门挡在后面了）");
  if (tMute.heard.length) errs.push("volSfx=0 却还是走了合成，音量闸门没挡住");
  const tOn = inst({ volSfx: 80, audio: true });
  tOn.S.play("boom");
  if (tOn.seen.length !== 1) errs.push("有声音时反而不振了");
  if (tOn.heard.indexOf("boom") < 0) errs.push("S.play 没把 boom 交给合成表（音频段被改坏了）");
}

/** 静态判据：手势里点火这件事有没有真的接线。 */
function sfxWiring(src, errs) {
  const n = (re) => (src.match(re) || []).length;
  const ln = src.indexOf('document.addEventListener("pointerdown"');
  if (ln < 0) return errs.push("没有任何 pointerdown 唤醒：AudioContext 仍会在网络回调里第一次创建");
  const listener = src.slice(ln, ln + 400);
  if (!/unlock\(\)/.test(listener)) errs.push("pointerdown 处理器没调 unlock()，等于只挂了个空 listener");
  if (!/capture:\s*true/.test(listener)) errs.push("唤醒监听没开 capture：手势被上层 stopPropagation 吃掉时就点不着火");
  if (/removeEventListener/.test(listener)) errs.push("唤醒监听被摘了：系统抢走音频焦点后 context 会再次 suspended，下一次触碰要能救回来");
  if (!/S\.unlock = unlock/.test(src)) errs.push("unlock 没导出，浏览器实测与真机排查都摸不到这一步");
  const ua = src.indexOf("function unlock");
  const ub = src.indexOf("S.unlock");
  if (ua < 0 || ub <= ua) errs.push("找不到 unlock 函数体，判不了它有没有在手势里建 context");
  if (!/ac\(\)/.test(src.slice(ua, ub))) errs.push("unlock 里没有 ac()：首次手势只补了音乐，context 照旧是手势外建的");
  if (n(/new \(window\.AudioContext/g) !== 1) errs.push("AudioContext 的创建点不止一处（" + n(/new \(window\.AudioContext/g) + " 处），唤醒时机就没法保证");
  if (!/state === "suspended"/.test(src)) errs.push("ac() 没对 suspended 状态做 resume");
  const pb = src.indexOf("S.play = function"), buzz = src.indexOf("S.buzz(name)"), gate = src.indexOf("if (!vg) return");
  if (pb < 0 || buzz < pb) errs.push("S.play 里没有 S.buzz(name)：成功／事故／升级还是只有画面和声音");
  if (gate > 0 && buzz > gate) errs.push("buzz 排在音量闸门之后：把音效拉到 0 就没了触感");
  if (/BUZZ\s*=\s*\{[^}]*\b(click|place)\b/.test(src)) errs.push("BUZZ 表里给点击类反馈配了振动，每跳都振只会让人关掉它");
  if (n(/navigator\.vibrate/g) > 1) errs.push("vibrate 调用点不止 buzz 一处（振动该只有那三个入口能触发）");
}

/* ---------- panels 那份：滑条与开关的联动 ---------- */
function panelsJudgment(src, errs) {
  const a = src.indexOf('body.querySelector("#vol-mus").oninput');
  const b = src.indexOf('body.querySelector("#vol-mus").onchange');
  if (a < 0 || b < 0 || b <= a) return errs.push("找不到 #vol-mus 的 oninput/onchange 绑定，判不了联动");
  const on = src.slice(a, b);
  if (/setMusic\(true\)/.test(on)) errs.push("拉音量仍会无条件起乐：音乐开关显示已关闭，滑条一拉就响（H4 要收口的那句）");
  if (!/setMusic\(!!st\.data\.music\)/.test(on)) errs.push("滑条没按音乐开关的状态决定起不起乐（开关不再是唯一权威）");
  if (!/st\.data\.music = true/.test(on)) errs.push("音量从 0 拉起来时没把开关一并打开：玩家听不到任何东西，只会以为坏了");
  if (!/\.textContent/.test(on)) errs.push("滑条改写了音乐开关却没同步按钮文案（下一次重绘又跳回旧状态）");
  const oc = src.slice(b, src.indexOf("};", b) + 2);
  if (!/music:/.test(oc)) errs.push("onchange 只送 volMus 不送 music：滑条打开的开关没落库，下一次存档快照把它抹回关闭");
  if (!/id="set-hap"/.test(src)) errs.push("设置页没有触感开关这一行（振动没有可关的入口，等于强卖）");
  if (!/"#set-hap"/.test(src)) errs.push("触感开关只渲染没接线：按钮点了没反应");
  if (!/CHEM\.sfx\.hapticOn\(\)/.test(src)) errs.push("设置页没读 hapticOn()：按钮状态与实际开关会漂移");
  if (/navigator\.vibrate/.test(src)) errs.push("panels 里直接调 vibrate：绕过了本机开关与特性检测，振动入口应当只有 sfx.buzz");
  if (!/CHEM\.sfx\.buzz\(/.test(src)) errs.push("触感开/关时没试振一下：玩家要等到下一次事故才知道这台机器振不振");
}

function main() {
  const mode = process.argv[2];
  let src = "";
  try { src = fs.readFileSync(0, "utf8"); } catch (e) { say("BAD · 读不到 stdin（用法：curl -s $BASE/js/xx.js | node test/audio-haptics.js " + mode + "）", 2); }
  const r = judge(mode, src);
  if (r.errs.length) return say(["BAD · H4（音频与触感）没做到位"].concat(r.errs.map(function (e) { return "    · " + e; })), 1);
  say("OK · " + r.info, 0);
}

/** 判一份源码：返回 { errs, info }，不打印也不退出——第 1 层（validate.js）与 e2e 都调这一个。 */
function judge(mode, src) {
  if (src.length < 200) return { errs: ["喂进来的代码只有 " + src.length + " 字节，不像是 js/sfx.js 或 js/panels.js（后端没起？路径 404？）"], info: "" };
  const errs = [];
  let info = "";
  if (mode === "sfx") {
    const inst = loadHaptics(src);
    if (!inst) errs.push("切不出触感那段（var BUZZ … var SCALE 之间没随包下发，或被改名）");
    else if (inst.loadError) errs.push(inst.loadError);
    else {
      // 判据本身不许抛：代码被改坏时（比如 try/catch 被摘掉）这里要落成一条红，而不是甩一屏堆栈给 e2e
      try { hapticBehavior(inst, errs); } catch (e) { errs.push("跑触感判据时抛了：" + e.message); }
    }
    sfxWiring(src, errs);
    info = "成功/事故/升级各一次 10–20ms 短振、静音也振、没 API 时三层容错；context 在首次 pointerdown 里点火";
  } else if (mode === "panels") {
    try { panelsJudgment(src, errs); } catch (e) { errs.push("判 panels 时抛了：" + e.message); }
    info = "音量滑条不再绕过音乐开关，触感开关有渲染也有接线";
  } else {
    return { errs: ["未知模式 " + mode + "（可用：sfx / panels）"], info: "" };
  }
  return { errs: errs, info: info };
}

module.exports = { judge: judge, loadHaptics: loadHaptics, hapticBehavior: hapticBehavior, sfxWiring: sfxWiring, panelsJudgment: panelsJudgment };

if (require.main === module) main();
