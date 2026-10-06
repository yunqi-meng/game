/* 音效与音乐：WebAudio 合成，零素材文件；音量存 d.volSfx / d.volMus，音乐开关 d.music
   触感不写在存档里：它是设备偏好（同一账号换台不该跟着走），存 localStorage，见下面 HAPTIC 段。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var S = {};
  CHEM.sfx = S;
  var ctx = null;
  var vg = 1; /* 当前播放音量系数 0-1 */

  function ac() {
    if (!ctx) {
      try { ctx = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { return null; }
    }
    if (ctx.state === "suspended") { var p = ctx.resume(); if (p && p.catch) p.catch(function () {}); }
    return ctx;
  }
  function tone(c, f0, f1, t0, dur, type, vol) {
    vol = Math.max(0.0001, vol * vg);
    var o = c.createOscillator(), g = c.createGain();
    o.type = type || "sine";
    o.frequency.setValueAtTime(f0, c.currentTime + t0);
    o.frequency.exponentialRampToValueAtTime(Math.max(30, f1), c.currentTime + t0 + dur);
    g.gain.setValueAtTime(0.0001, c.currentTime + t0);
    g.gain.exponentialRampToValueAtTime(vol, c.currentTime + t0 + 0.015);
    g.gain.exponentialRampToValueAtTime(0.0001, c.currentTime + t0 + dur);
    o.connect(g); g.connect(c.destination);
    o.start(c.currentTime + t0); o.stop(c.currentTime + t0 + dur + 0.05);
  }
  function noise(c, t0, dur, vol, freq) {
    vol = vol * vg;
    if (vol <= 0.0005) return;
    var n = Math.floor(c.sampleRate * dur);
    var buf = c.createBuffer(1, n, c.sampleRate);
    var data = buf.getChannelData(0);
    for (var i = 0; i < n; i++) data[i] = (Math.random() * 2 - 1) * (1 - i / n);
    var src = c.createBufferSource(); src.buffer = buf;
    var f = c.createBiquadFilter(); f.type = "lowpass"; f.frequency.value = freq || 900;
    var g = c.createGain(); g.gain.value = vol;
    src.connect(f); f.connect(g); g.connect(c.destination);
    src.start(c.currentTime + t0);
  }

  var SFX = {
    place: function (c) { tone(c, 520, 700, 0, 0.07, "sine", 0.12); },
    click: function (c) { tone(c, 660, 660, 0, 0.04, "sine", 0.06); },
    error: function (c) { tone(c, 220, 160, 0, 0.12, "square", 0.05); },
    coin: function (c) { tone(c, 988, 988, 0, 0.06, "triangle", 0.12); tone(c, 1319, 1319, 0.06, 0.12, "triangle", 0.12); },
    success: function (c) {
      tone(c, 523, 523, 0, 0.1, "triangle", 0.14);
      tone(c, 659, 659, 0.09, 0.1, "triangle", 0.14);
      tone(c, 784, 784, 0.18, 0.2, "triangle", 0.16);
    },
    levelup: function (c) {
      [523, 659, 784, 1047].forEach(function (f, i) { tone(c, f, f, i * 0.09, 0.16, "triangle", 0.15); });
    },
    boom: function (c) {
      tone(c, 160, 38, 0, 0.5, "sawtooth", 0.3);
      noise(c, 0, 0.45, 0.35, 500);
    },
    pour: function (c) { noise(c, 0, 0.25, 0.08, 2400); }
  };

  /* ---------- 触感（H4） ----------
     全站原先一处 navigator.vibrate 都没有：合成成功、实验事故、升级这三种"结果已定"的时刻
     只有画面和声音，手机握在手里却没有反馈。只给这三处，且都是 10–20ms 的单次短振——
     长振动既费电又更像故障，而点击类反馈每一跳都振一遍只会让人关掉它。 */
  var BUZZ = { success: 12, boom: 20, levelup: 16 };
  var HK = "chemera.haptic";
  function hapticOn() {
    try { return localStorage.getItem(HK) !== "off"; } catch (e) { return true; }   // 隐私模式取不到：按默认开
  }
  S.hapticOn = hapticOn;
  /** 纯客户端开关：不进存档，所以不用 run("settings")，也不会因为换设备而变。 */
  S.setHaptic = function (on) {
    try { localStorage.setItem(HK, on ? "on" : "off"); } catch (e) {}
    return !!on;
  };
  /** 三层都过关才真振：① 支持这个 API（iOS Safari 与旧 WebView 没有）② 用户没关 ③ 调用本身不抛。
      返回值只用来给判断/回归用，页面不消费它。 */
  S.buzz = function (name) {
    var ms = BUZZ[name];
    if (!ms || !hapticOn()) return false;
    var n = window.navigator;
    if (!n || typeof n.vibrate !== "function") return false;
    try { return !!n.vibrate(ms); } catch (e) { return false; }
  };
  S.buzzMs = function (name) { return BUZZ[name] || 0; };

  S.play = function (name) {
    /* 触感排在音量闸门之前：静音是"别出声"，不是"别理我"，炸了总得手里抖一下。 */
    S.buzz(name);
    try {
      var d = CHEM.state && CHEM.state.data;
      vg = d ? (d.volSfx || 0) / 100 : 0;
      if (!vg) return;
      var fn = SFX[name]; if (!fn) return;
      var c = ac(); if (!c) return;
      fn(c);
    } catch (e) { /* 音频不可用时静默 */ }
  };

  /* ---------- 背景音乐：五声音阶氛围循环（32 步一换的和声进行） ---------- */
  var SCALE = [262, 294, 330, 392, 440, 523, 587, 659];
  var CHORDS = [[0, 2, 4], [3, 5, 7], [1, 3, 5], [0, 2, 6]];
  var musTimer = null, step = 0, nextT = 0;

  function musTick() {
    if (!ctx) return;
    var d = CHEM.state && CHEM.state.data;
    var g = d ? (d.volMus || 0) / 100 : 0;
    if (!g) return;
    vg = 1;
    while (nextT < ctx.currentTime + 0.8) {
      var t0 = Math.max(0.02, nextT - ctx.currentTime);
      var ch = CHORDS[(step >> 4) % CHORDS.length];
      var i = step % 16;
      var f = SCALE[ch[i % 3] % SCALE.length];
      tone(ctx, f, f, t0, 0.55, "sine", 0.028 * g);
      if (i === 0) tone(ctx, f / 2, f / 2, t0, 1.6, "triangle", 0.03 * g);
      if (i % 4 === 2) tone(ctx, SCALE[(ch[1] + 2) % SCALE.length] * 2, SCALE[(ch[1] + 2) % SCALE.length] * 2, t0, 0.3, "sine", 0.014 * g);
      nextT += 0.4; step++;
    }
  }
  S.setMusic = function (on) {
    if (on) {
      var c = ac(); if (!c) return;
      if (!musTimer) { nextT = c.currentTime + 0.1; musTimer = setInterval(musTick, 250); musTick(); }
    } else if (musTimer) {
      clearInterval(musTimer); musTimer = null;
    }
  };
  /* ---------- 首次手势把音频唤醒（H4） ----------
     原先的写法是"网络回调里第一次 play 时才 new AudioContext"。在线版每条音效都是服务端结算
     回来才响的（ui.js 收到结果才 play），那一次创建/`resume()` 都发生在手势之外：
     WebView 起来就是 suspended，而 Chrome 的自动播放策略正是要拒掉手势外的 resume——
     于是整局听不见声音，屏幕上都看不出哪里坏了。安卓壳的 setMediaPlaybackRequiresUserGesture
     仍然是 true（不用手势外的方式起音频这条策略不动），改的是"在第一次触碰里就把引擎点火"。
     监听不摘：被系统抢走音频焦点之后 context 会再次进 suspended，下一次触碰要能再救回来。 */
  var warmed = false;
  function unlock() {
    var c = ac();
    if (!c) return false;
    try {
      // iOS WebKit 认的是"手势里真播过一次"，播一帧静音 buffer 就够，不占听觉。
      if (!warmed && c.state === "running") {
        var b = c.createBuffer(1, 1, c.sampleRate), s = c.createBufferSource();
        s.buffer = b; s.connect(c.destination); s.start(0);
        warmed = true;
      }
    } catch (e) { /* 没有 createBuffer 的实现：不影响主流程 */ }
    var d = CHEM.state && CHEM.state.data;
    if (d && d.music && !musTimer) S.setMusic(true);
    return c.state === "running";
  }
  S.unlock = unlock;
  /** 给真机排查用的读数：卡在哪一步（没建起来 / 建了但 suspended / 已 running）一眼能分。 */
  S.audioState = function () {
    return { created: !!ctx, state: ctx ? ctx.state : "none", warmed: warmed, music: !!musTimer };
  };
  document.addEventListener("pointerdown", function () {
    try { unlock(); } catch (e) { /* 唤醒失败就等下一次触碰，绝不把这次点击本身带崩 */ }
  }, { capture: true, passive: true });
})();
