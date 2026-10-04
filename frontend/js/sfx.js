/* 音效与音乐：WebAudio 合成，零素材文件；音量存 d.volSfx / d.volMus，音乐开关 d.music */
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

  S.play = function (name) {
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
  /* 浏览器自动播放策略：若用户已开启音乐但上下文被挂起，首次交互时补启动 */
  document.addEventListener("pointerdown", function unlock() {
    var d = CHEM.state && CHEM.state.data;
    if (d && d.music && !musTimer) S.setMusic(true);
  }, { passive: true });
})();
