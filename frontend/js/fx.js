/* Canvas 粒子特效：气泡/烟雾/火焰/发光/沉淀/爆炸 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var FX = {};
  CHEM.fx = FX;

  var cv, ctx, parts = [], raf = null, W = 0, H = 0, DPR = 1;

  FX.attach = function (canvas) {
    cv = canvas; ctx = canvas.getContext("2d");
    FX.resize();
  };
  FX.resize = function () {
    if (!cv) return;
    var r = cv.parentElement.getBoundingClientRect();
    DPR = Math.min(2, window.devicePixelRatio || 1);
    W = r.width; H = r.height;
    cv.width = W * DPR; cv.height = H * DPR;
    cv.style.width = W + "px"; cv.style.height = H + "px";
    ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
  };

  function rnd(a, b) { return a + Math.random() * (b - a); }

  FX.burst = function (kinds, color) {
    var cx0 = W / 2;
    (kinds || []).forEach(function (kind) {
      var n = kind === "explosion" ? 60 : 26;
      for (var i = 0; i < n; i++) {
        var p = { kind: kind, x: rnd(W * 0.25, W * 0.75), y: H * 0.8, life: 1 };
        if (kind === "bubble") { p.r = rnd(2, 6); p.vy = rnd(-1.6, -0.7); p.vx = rnd(-.3, .3); p.decay = rnd(.006, .013); p.color = "#cfeaff"; }
        else if (kind === "smoke") { p.r = rnd(6, 16); p.vy = rnd(-1.1, -0.4); p.vx = rnd(-.35, .35); p.decay = rnd(.004, .01); p.color = "rgba(200,200,205,"; }
        else if (kind === "flame") { p.y = H * 0.72; p.r = rnd(4, 10); p.vy = rnd(-2.4, -1.2); p.vx = rnd(-.5, .5); p.decay = rnd(.02, .045); p.color = Math.random() < .5 ? "#ffb300" : "#ff7043"; }
        else if (kind === "glow") { p.r = rnd(3, 8); p.vy = rnd(-1.2, -0.3); p.vx = rnd(-.8, .8); p.decay = rnd(.008, .02); p.color = color || "#7cffcb"; }
        else if (kind === "precip") { p.y = H * 0.35; p.r = rnd(2, 4.5); p.vy = rnd(.6, 1.4); p.vx = rnd(-.15, .15); p.decay = rnd(.004, .009); p.color = color || "#e57373"; }
        else if (kind === "explosion") {
          p.x = cx0; p.y = H * 0.55;
          var a = rnd(0, Math.PI * 2), v = rnd(2, 7);
          p.vx = Math.cos(a) * v; p.vy = Math.sin(a) * v;
          p.r = rnd(3, 9); p.decay = rnd(.015, .035);
          p.color = ["#ff5252", "#ffb300", "#8d6e63"][i % 3];
        } else { p.r = rnd(2, 5); p.vy = rnd(-.5, .5); p.vx = rnd(-.5, .5); p.decay = .012; p.color = color || "#90caf9"; }
        parts.push(p);
      }
      FX.start();
    });
  };

  FX.shake = function () {
    var el = document.getElementById("bench-stage");
    if (!el) return;
    el.classList.remove("shake"); void el.offsetWidth; el.classList.add("shake");
  };

  FX.start = function () {
    if (raf) return;
    var loop = function () {
      if (!parts.length) { ctx.clearRect(0, 0, W, H); raf = null; return; }
      ctx.clearRect(0, 0, W, H);
      for (var i = parts.length - 1; i >= 0; i--) {
        var p = parts[i];
        p.x += p.vx; p.y += p.vy; p.life -= p.decay;
        if (p.kind === "smoke") p.r += 0.25;
        if (p.kind === "bubble") p.x += Math.sin(p.y * 0.1) * 0.3;
        if (p.life <= 0 || p.y < -10 || p.y > H + 10) { parts.splice(i, 1); continue; }
        ctx.globalAlpha = Math.max(0, p.life);
        ctx.fillStyle = p.color;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.globalAlpha = 1;
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
  };
})();
