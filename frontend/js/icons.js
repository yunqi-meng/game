/* 仪器线稿图标（唯一的图标真源）。
   为什么不用 emoji：曾经用的是 emoji 表，结果烧杯显示成"实验服"🥼、试管显示成"培养皿"🧫，
   而量筒/容量瓶/研钵/表面皿/点滴板根本没有对应键，全回落到同一颗试管——玩家分不清容器，
   而容器恰恰是这个游戏最核心的选择。emoji 还跟平台字体走，换台手机形状就变。
   所以这里改成手绘线稿：所有图形画在同一个 48×48 视图框里，用"图元数组"描述而不是拼好的字符串，
   这样 test/validate.js 能在 node 里检查覆盖率与越界、tools/icon-preview.mjs 能把它们栅格化成
   ASCII 供人眼确认"这条线真的像烧杯"。加仪器时请同时在这里补图形（validate 会挡住漏网的）。 */
(function () {
  "use strict";
  window.CHEM = window.CHEM || {};
  var I = {};
  CHEM.icon = I;

  var VB = 48; /* 视图框边长；所有图元坐标都应在 0..VB 内 */

  /* o = 轮廓（描边）  t = 细节（描边，比轮廓细）  l = 液体（填充，颜色随混合液） */
  var SHAPES = {
    /* ---- 反应容器 ---- */
    testtube: {
      o: [["path", "M19 9 v22 q0 6 5 6 t5 -6 v-22"], ["path", "M17 9 h14"]],
      l: [["path", "M19.6 24 v7 q0 5.4 4.4 5.4 t4.4 -5.4 v-7 z"]]
    },
    beaker: {
      o: [["path", "M13 15 v20 q0 4 4 4 h14 q4 0 4 -4 v-20"], ["path", "M11 15 h26"]],
      t: [["path", "M35 15 q4 -1 3 -5"], ["path", "M17 23 h5"], ["path", "M17 29 h5"]],
      l: [["path", "M13.7 27 v8 q0 3.3 3.3 3.3 h14 q3.3 0 3.3 -3.3 v-8 z"]]
    },
    flask: { /* 锥形瓶 */
      o: [["path", "M20 8 v9 l-9 17 q-1.5 3 2 3 h22 q3.5 0 2 -3 l-9 -17 v-9"], ["path", "M18 8 h12"]],
      l: [["path", "M14.7 27 l-3.7 7 q-1.2 2.6 1.6 2.6 h18.8 q2.8 0 1.6 -2.6 l-3.7 -7 z"]]
    },
    evapor: { /* 蒸发皿：浅碗 */
      o: [["path", "M10 23 h28"], ["path", "M12 23 q2 11 12 11 q10 0 12 -11"]],
      l: [["path", "M16 26 q3 6 8 6 q5 0 8 -6 z"]]
    },
    crucible: { /* 坩埚：下收窄的杯 + 盖弧 */
      o: [["path", "M15 18 h18"], ["path", "M16.5 18 l2 15 q.6 3 5.5 3 q4.9 0 5.5 -3 l2 -15"]],
      t: [["path", "M13 17 q11 -6 22 0"]],
      l: [["path", "M18.2 26 h11.6 l-.5 7.8 q-.2 1.9 -5.3 1.9 q-5.1 0 -5.3 -1.9 z"]]
    },
    spoon: { /* 燃烧匙：小杯 + 长柄 */
      o: [["path", "M14 27 h14"], ["path", "M14.6 27 q.6 9 6.8 9 q6.2 0 6.8 -9"], ["path", "M30 26 l6 -14"]],
      t: [["circle", 37.5, 10, 2.4]],
      l: [["path", "M17 30 q1.6 5 4 5 q2.4 0 4 -5 z"]]
    },
    watchglass: { /* 表面皿：双弧形浅盘 */
      o: [["path", "M9 24 q15 -8 30 0 q-4 12 -15 12 q-11 0 -15 -12 z"]],
      t: [["path", "M14 22 q6 -3 12 -1"]],
      l: [["path", "M15 27 q4 6 9 6 q5 0 9 -6 q-9 3 -18 0 z"]]
    },
    spotplate: { /* 点滴板：带孔板 */
      o: [["path", "M12 14 h24 q4 0 4 4 v12 q0 4 -4 4 h-24 q-4 0 -4 -4 v-12 q0 -4 4 -4 z"]],
      t: [["circle", 17, 24, 2.6], ["circle", 24, 24, 2.6], ["circle", 31, 24, 2.6],
          ["circle", 17, 31, 2.6], ["circle", 24, 31, 2.6], ["circle", 31, 31, 2.6]],
      l: [["circle", 24, 31, 2.6]]
    },
    /* ---- 计量 ---- */
    cylinder: { /* 量筒：细高 + 底座 + 刻度 */
      o: [["path", "M17 8 v28"], ["path", "M25 8 v28"], ["path", "M15 8 h12"],
          ["path", "M13 40 h16 l-4 -4 h-8 z"]],
      t: [["path", "M17 14 h4"], ["path", "M17 20 h4"], ["path", "M17 26 h4"], ["path", "M17 32 h4"]],
      l: [["path", "M17.5 22 h7 v14 h-7 z"]]
    },
    volumetric: { /* 容量瓶：细颈 + 梨形平底 + 瓶口 */
      o: [["path", "M20 8 h8"],
          ["path", "M22 8 v10 q-12 5 -12 13 q0 8 14 8 q14 0 14 -8 q0 -8 -12 -13 v-10"]],
      t: [["path", "M22 13 h4"]],
      l: [["path", "M11 30 q1 9 13 9 q12 0 13 -9 z"]]
    },
    /* ---- 分离提纯 ---- */
    funnel: { /* 普通漏斗：锥 + 细管 */
      o: [["path", "M9 12 h30"], ["path", "M11 12 l11 14 v12 h4 v-12 l11 -14"]],
      l: [["path", "M16 17 l6.5 9 v9 h3 v-9 l6.5 -9 z"]]
    },
    sepfunnel: { /* 分液漏斗：梨形 + 旋塞 + 细管 */
      o: [["path", "M19 8 h10"], ["path", "M21 8 v4 q-9 3 -9 10 q0 8 12 8 q12 0 12 -8 q0 -7 -9 -10 v-4"],
          ["path", "M18 32 h12"], ["path", "M23 32 v9 h2 v-9"]],
      l: [["path", "M12.6 21 q1.4 9 11.4 9 q10 0 11.4 -9 z"]]
    },
    distflask: { /* 蒸馏烧瓶：圆底 + 颈 + 支管 */
      o: [["circle", 20, 30, 11], ["path", "M17 8 v12"], ["path", "M25 8 v12"], ["path", "M15 8 h12"]],
      t: [["path", "M25 16 l12 -5"]],
      l: [["path", "M11 34 q1 6 9 6 q8 0 9 -6 z"]]
    },
    mortar: { /* 研钵：碗 + 杵 */
      o: [["path", "M10 24 h28"], ["path", "M12 24 q1 12 12 12 q11 0 12 -12"], ["path", "M27 21 l9 -11"]],
      t: [["circle", 37.5, 8.5, 2.6]],
      l: [["path", "M16 27 q3 7.5 8 7.5 q5 0 8 -7.5 z"]]
    },
    /* ---- 加热 ---- */
    lamp: { /* 酒精灯 */
      o: [["path", "M13 25 h22 l-3 14 q-.5 2 -3 2 h-10 q-2.5 0 -3 -2 z"], ["path", "M21 21 h6 v4 h-6 z"]],
      t: [["path", "M24 7 q6 7 6 11 q0 5 -6 5 q-6 0 -6 -5 q0 -4 6 -11 z"]],
      l: [["path", "M14.5 32 l2 7.6 q.4 1.4 2.5 1.4 h6 q2.1 0 2.5 -1.4 l2 -7.6 z"]]
    },
    blowtorch: { /* 酒精喷灯：灯身 + 斜喷管 + 直冲火 */
      o: [["path", "M14 26 h18 l-2.5 13 q-.4 2 -3 2 h-7 q-2.6 0 -3 -2 z"],
          ["path", "M19 26 v-6 h4 v6"], ["path", "M23 20 l11 -6"]],
      t: [["path", "M35 11 q5 -3 6 1 q-1 5 -6 4 z"]],
      l: [["path", "M15.4 33 l1.6 6.4 q.3 1.2 2 1.2 h6 q1.7 0 2 -1.2 l1.6 -6.4 z"]]
    },
    waterbath: { /* 水浴锅：宽浅锅 + 波纹 + 支脚 */
      o: [["path", "M9 22 h30 v9 q0 5 -5 5 h-20 q-5 0 -5 -5 z"]],
      t: [["path", "M13 36 v4"], ["path", "M35 36 v4"]],
      l: [["path", "M9.6 25 h28.8 v6 q0 4.4 -4.4 4.4 h-20 q-4.4 0 -4.4 -4.4 z"]]
    },
    /* ---- 计量 / 精密（设备类）---- */
    electrolyzer: { /* 电解槽：槽 + 两极 + 气泡 */
      o: [["path", "M9 20 h30 v13 q0 5 -5 5 h-20 q-5 0 -5 -5 z"],
          ["path", "M17 20 v-6 h14 v6"], ["path", "M19 20 v12"], ["path", "M29 20 v12"]],
      t: [["circle", 19, 27, 1.6], ["circle", 29, 25, 1.6], ["circle", 29, 29, 1.4]],
      l: [["path", "M9.6 24 h28.8 v9 q0 4.4 -4.4 4.4 h-20 q-4.4 0 -4.4 -4.4 z"]]
    },
    balance: { /* 电子天平：机座 + 托盘 + 显示屏 */
      o: [["path", "M9 28 h30 q2 0 2 2 v8 q0 2 -2 2 h-30 q-2 0 -2 -2 v-8 q0 -2 2 -2 z"],
          ["path", "M14 24 h20 v4 h-20 z"]],
      t: [["path", "M14 32 h11 v5 h-11 z"], ["circle", 31, 34.5, 1.8]]
    },
    phmeter: { /* pH 计：主机 + 屏幕 + 电极 */
      o: [["path", "M8 14 h24 v20 h-24 z"], ["path", "M11 17 h11 v7 h-11 z"],
          ["path", "M32 20 h4 v11 q0 3 -3 3"]],
      t: [["path", "M13 20 h7"], ["circle", 19, 30, 1.8], ["circle", 25, 30, 1.8]]
    },
    spectrometer: { /* 分光光度计：机身 + 比色皿 + 读数条 */
      o: [["path", "M7 18 h34 v14 q0 3 -3 3 h-28 q-3 0 -3 -3 z"]],
      t: [["path", "M13 22 h8 v7 h-8 z"], ["path", "M26 22 h10"], ["path", "M26 27 h6"],
          ["path", "M12 14 h12 v4 h-12 z"]]
    },
    /* ---- 分离（设备类）---- */
    condenser: { /* 冷凝管：内管 + 水套 + 进出水口 */
      o: [["path", "M6 24 h36"],
          ["path", "M14 18 h20 q3 0 3 3 v6 q0 3 -3 3 h-20 q-3 0 -3 -3 v-6 q0 -3 3 -3 z"]],
      t: [["path", "M18 14 v4"], ["path", "M30 30 v4"]]
    },
    centrifuge: { /* 离心机：碗 + 盖弧 + 转子 */
      o: [["path", "M10 22 h28 v9 q0 6 -6 6 h-16 q-6 0 -6 -6 z"], ["path", "M10 22 q14 -8 28 0"]],
      t: [["circle", 24, 28, 5.5], ["circle", 21, 28, 1.6], ["circle", 27, 28, 1.6]]
    },
    /* ---- 辅助 ---- */
    thermometer: { /* 温度计：细管（左右两壁 + 圆头）+ 水银柱 + 刻度 */
      o: [["path", "M22 33 v-22 q0 -2.5 2 -2.5 q2 0 2 2.5 v22"], ["circle", 24, 36, 5],
          ["path", "M22 14 h-3"], ["path", "M22 20 h-3"], ["path", "M22 26 h-3"]],
      t: [["path", "M24 34 v-16"]]
    },
    dropper: { /* 胶头滴管 */
      o: [["circle", 24, 12, 6], ["path", "M22 18 v9"], ["path", "M26 18 v9"],
          ["path", "M22 27 h4 l-1 11 q0 1.6 -1 1.6 q-1 0 -1 -1.6 z"]],
      t: [["circle", 24, 43, 1.6]]
    },
    spatula: { /* 药匙：斜长柄 + 一端小勺 */
      o: [["path", "M11 37 l17 -17"], ["path", "M27.5 19.5 q5 -8 10.5 -4.5 q-3.5 8 -10.5 5.5 z"]],
      t: [["path", "M31 16 q3 -1.5 5 .5"]]
    },
    stand: { /* 铁架台：底座 + 立杆 + 横臂 + 铁圈 */
      o: [["path", "M9 37 h24 v4 h-24 z"], ["path", "M29 8 v29"], ["path", "M29 17 h-9"],
          ["circle", 16, 24, 6]],
      t: [["path", "M29 12 h-5"]]
    },
    /* 兜底：新仪器漏画时至少是个像样的容器，且不参与"专属图形"统计 */
    other: {
      o: [["path", "M13 15 v20 q0 4 4 4 h14 q4 0 4 -4 v-20"], ["path", "M11 15 h26"]],
      l: [["path", "M13.7 27 v8 q0 3.3 3.3 3.3 h14 q3.3 0 3.3 -3.3 v-8 z"]]
    }
  };
  I.SHAPES = SHAPES;

  function prims(list, cls) {
    if (!list) return "";
    var out = "";
    for (var i = 0; i < list.length; i++) {
      var p = list[i], t = p[0];
      if (t === "path") out += '<path class="' + cls + '" d="' + p[1] + '"/>';
      else if (t === "circle") out += '<circle class="' + cls + '" cx="' + p[1] + '" cy="' + p[2] + '" r="' + p[3] + '"/>';
      else if (t === "rect") out += '<rect class="' + cls + '" x="' + p[1] + '" y="' + p[2] + '" width="' + p[3] + '" height="' + p[4] + '"/>';
    }
    return out;
  }

  /** 颜色来自后台可编辑的物质数据，进 style 前先削掉非法字符（防 CSS 注入）。 */
  function safeColor(c) {
    return typeof c === "string" && /^#[0-9a-fA-F]{3,8}$/.test(c) ? c
      : (/^rgba?\((\s*\d{1,3}\s*,){2}\s*\d{1,3}\s*(,\s*(0|1|0?\.\d+)\s*)?\)$/.test(c) ? c : "");
  }

  /**
   * 画一个图形。
   * 有液体层的容器会把液体层一起画出来，但默认不显示（CSS 里 .v-l 透明度为 0），
   * 传 opts.liq 才加 .wet 让它显形 —— 这样实验台换容器时只需切 class 和颜色，形状不必重画，液面才有渐变。
   */
  I.draw = function (shape, opts) {
    opts = opts || {};
    var s = typeof shape === "string" ? (SHAPES[shape] || SHAPES.other) : (shape || SHAPES.other);
    var liq = s.l ? safeColor(opts.liq) : "";
    var cls = "v-ic" + (opts.cls ? " " + opts.cls : "") + (liq ? " wet" : "");
    var style = liq ? ' style="--ic-liq:' + liq + '"' : "";
    var label = opts.label ? "<title>" + String(opts.label).replace(/[<&]/g, "") + "</title>" : "";
    return '<svg class="' + cls + '"' + style + ' viewBox="0 0 ' + VB + ' ' + VB +
      '" role="img" aria-hidden="true">' + label +
      (s.l ? prims(s.l, "v-l") : "") + prims(s.o, "v-o") + prims(s.t, "v-t") + "</svg>";
  };

  /** 按仪器 id 取专属图形；没有专属图形就用兜底（并由 has() 报给回归测试）。 */
  I.shapeOf = function (id) { return SHAPES[id] || null; };
  I.has = function (instr) { return !!(instr && SHAPES[instr.id]); };
  /** 渲染一个仪器图标：id 命中专属图形，否则按类别兜底。 */
  I.of = function (instr, opts) {
    opts = opts || {};
    opts.label = opts.label || (instr && instr.zh);
    return I.draw(I.shapeOf(instr && instr.id) || "other", opts);
  };
  I.VB = VB;
})();
