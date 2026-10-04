# 化学实验室：元素纪元（v3.0 · 在线游戏）

以真实化学为底座的竖屏沙盒合成网页游戏。**v3.0 起是纯在线游戏：玩法全部由服务端权威结算，进度实时保存在数据库，没有离线单机模式。**

- **后端（真源）**：Spring Boot 3.3 + MyBatis + MySQL + Flyway + JWT。游戏引擎（反应解析/结算/经济/图鉴/挂单）用 Java 原生重写，所有数值变动都发生在服务端的 `GameService.act()` 这一唯一入口。
- **前端**：纯 HTML/CSS/JS（无框架、无构建），住在仓库根的 `frontend/`，只负责**发意图 + 渲染服务器下发的整帧回执**；`frontend/js/data/` 内嵌数据只是首帧基线，联网后由后端内容库覆盖。
- **管理后台**：Vue 3 + Element Plus 独立 SPA，构建后由后端在同域 `/admin/` 托管，覆盖内容数据、运营配置、用户与存档、数据看板与审核四大域。
- **鉴权**：进页面先过**登录/注册页**（`frontend/js/gate.js`）——登录、注册、🎈游客模式三条路径都要拿到服务端令牌才进游戏；游客档同样存在服务器，注册后进度**并入新账号**（`/api/auth/upgrade`）。本机若已有令牌（含游客）则跳过该页直接续玩，退出登录/令牌彻底失效则回到该页。换设备登录同一账号即可续玩。

## 运行

### 1. 准备数据库（一次性）
本机 MySQL（或 `docker compose --env-file server/.env up -d` 起的 3307 容器），用 root 执行建库脚本。
口令只从环境变量注入，脚本里不落字面量：
```bash
cp server/.env.example server/.env      # 先填 CHEMERA_DB_PASSWORD（.env 已被 gitignore）
set -a; . server/.env; set +a
envsubst < server/db/bootstrap.sql | mysql -u root -p     # 建 chemera 库 + chem 用户
```

### 2. 启动后端（游戏页面也归它托管）
```bash
cd server
# .env 里至少要给：CHEMERA_DB_PASSWORD、CHEMERA_JWT_SECRET（≥32 字节随机串）
bash run.sh                                     # 或 Windows: run.bat；两者都会自动加载 .env
# 首次启动 Flyway 自动应用 V1 建表 / V2 内容种子 / V3 配置种子 / V4 游客档 / V5 令牌轮换 / V6 管理员账号，并创建默认超管
```
`run.sh` / `run.bat` 在缺少 `CHEMERA_DB_PASSWORD` 时直接报错退出，不会再静默使用仓库里的开发口令。超级管理员用户名与首次口令由 `CHEMERA_SEED_ADMIN_USER` / `CHEMERA_SEED_ADMIN_PASS` 决定；**没给口令时**它回落成公开的 `admin123`，该账号随即被标记"必须先改密"——登录后只能停在改密弹窗，其余后台端点一律 403，prod 更是直接拒绝启动。账号的增删改与自助改密都在【管理员账号】页做，见下文「管理员账号、玩家口令与备份」。

### 3. 打开游戏
浏览器访问 **http://localhost:8080/** —— 首页即游戏。第一次进入会先看到**登录 / 注册页**：可以登录、注册，或点「🎈 以游客身份进入」直接试玩（游客进度同样落在服务器）。之后本机保留令牌，刷新会跳过该页直接续玩；在【设置-账号】里可把游客档**转正**（进度并入新账号）或退出登录。忘了口令时登录页的【忘记密码？】给出找回路径（没有邮件服务，只能由管理员在后台重置，详见下文）。

- 前端资源住在 `frontend/`，由 `server/pom.xml` 的 `maven-resources-plugin` 在 `process-resources` 阶段从 `frontend/`（`js/ css/ images/ index.html sw.js`）复制进 `target/classes/static`，因此 `mvn clean` 之后仍然自带页面，不必手工拷贝；`targetPath` 保持不变，故对外 URL（`/`、`/js/*`、`/css/*`）与目录改造前完全一致。
- 想把前端放到别的静态站点：设 `window.CHEM_API_BASE="http://localhost:8080"`（跨域已在后端放开）。
- `file://` 双击打开会被启动门拦下并提示"请先启动后端"，这是预期行为。

> **改过前端 JS/CSS 必须同时顶版本号**：`frontend/index.html` 里所有 `css/` `js/` 引用共用一个 `?v=3.9`，而这些版本化资源的服务端响应头是 `max-age=31536000, immutable`——同一个 `?v=` 下重新发布，浏览器会一直吃那份一年期强缓存（这条我们踩过两次：一次是代码进了 jar 页面还在跑旧函数，一次是同轮里已经发过 `3.8` 又补了个函数进去，页面怎么刷都不见变化，只能整体顶到 `3.9`）。所以：**每次动前端就整站 +0.1**（`e2e-api.sh` 有一条断言在盯"版本号是否全站唯一"），测的时候再强刷。历史版本注册过的 Service Worker 会在下次更新检查时载入 `sw.js` 的自我注销桩，自动清掉旧的离线缓存。

### 4. 管理后台 SPA（开发模式）
```bash
cd admin
npm install
npm run dev     # vite :5273，/api 与 /admin/api 代理到 :8080
npm run build   # 产物输出到 server/src/main/resources/static/admin，随后端一起发布
```

## 玩法闭环

拖/点物质卡 → 放入容器（27 种仪器）→ 设温度/电解/催化剂 → ⚗️ 反应（服务端判定）→ 粒子特效 + 液面颜色 →
产物按容器品质入包（粗/纯/高纯）→ 解锁物质图鉴 + 方程式图鉴（含离子/可逆/热化学分类）+ 发现奖金 →
市场变现（直售 / 挂单竞价 / 商会订单 / 每日特惠 / 黑市 / 耗材）→ 升级仪器与安全设施 → **购买实验室房间（5 间，每间 = 一台独立工作台，可并行实验）** → 等级线逐步解锁 118 元素全收集。

- 双模式：简单（物质对即反应）/ 真实（温度、催化剂、电解、仪器全判定）—— 由服务端 `conditionOk` 裁决，客户端置灰只是预览
- 经济：买 1.2×起（商会声望递增至 1.08×）、卖 0.8×、品质 0.7/1.0/1.5×、首次发现奖、首次出售 1.5×、每日 ±8% 浮动（服务器进帧时下发行情）、挂单**由服务器结算**（关页面、换设备也照样推进）、产率波动与误用炸裂
- 危险混放（5 组）触发安全教育弹窗，可购买实验保险/防护罩减损
- 🎯 合成挑战（限定步数 + 干扰物质，可看广告复活 +2 步）、🌌 创意沙盒（全物质无限、零惩罚、不计收益）
- 社交：NPC 好友互访回礼、送礼涨声望、收集排行榜（服务器计算）、战绩分享
- 商业化（模拟）：🪙金币 + 💎钻石双货币、月卡特权、皮肤、提示道具、激励视频双倍、模拟充值
- 每日任务、19 项成就、图鉴节点奖励、5 档年级答题、7 天连续签到、5 步教程、助手精灵
- 🔊 WebAudio 合成音效 + 程序化 BGM（无音频文件），音量/开关等偏好也存服务器存档

### 服务端权威带来的防作弊约束
| 风险 | 服务端的处理 |
|---|---|
| 改包刷金币/物品 | 客户端只发意图，余额与背包变化全部来自本次服务端结算结果 |
| 直写存档 | 面向客户端的 `PUT /api/save` 已下线（见 `legacy/removed/SaveController.java`），只有 `GameService` 能写 `user_save` |
| 跳过条件用高温/电解 | `canTemp` / `canElectrolysis` 按已购设备裁决（灯/吹管/电解装置） |
| 答题穷举选项 | 题目由 `quiz.pickOne` 单独取，**不下发正确答案**；`quiz.answer` 只认当前发出的那一题，答完即焚 |
| 广告双倍重复领取 | 双倍必须由服务器签发的**当日一次性券**支付（`ad.bonus{kind:1}`） |
| 反应/经济随机数可预测 | 结算随机源在服务端（`DoubleSupplier`），客户端随机只影响特效 |

- ☁️ 进度即时入库：每次意图调用都以 `revision` 递增写回 `user_save`，历史留在 `user_save_revision` 可回滚；`EngineCtx` 承载挑战/沙盒临时现场，服务重启后能从存档复原现场
- 账号：登录/注册页（三条路径：登录 / 注册 / 游客模式）+ BCrypt + JWT access/refresh 双令牌、改密（其余设备会话失效）、注销（物理清除该用户的会话/存档/历史/埋点）、退出后回到登录页

## 数据规模

118 元素 + 96 化合物 + 143 条配平方程式（含离子/可逆/热化学与彩蛋链）+ 5 组危险混合 + 3 条实验工艺 + 27 种仪器 + 5 间实验室 + 25 道题库 + 19 成就 + 商店/任务/配置等，共 **458 条内容 + 13 项运营配置**，全部落库、可在后台在线编辑与发布。

## 目录结构

三个应用各自独立成目录：**前端游戏 `frontend/` · 后端 `server/` · 管理后台 `admin/`**；`tools/ test/ legacy/` 与 `docker-compose.yml` 作为跨切面的开发辅助留在仓库根。

### 前端 `frontend/`（纯 HTML/CSS/JS，无构建，由后端静态托管）
```
frontend/index.html                               入口页（#stage 同层两页 + 7 页签底部导航 + 页内分段条 #page-segs；css/js 统一带 ?v=3.9）
frontend/sw.js                                    已废弃的离线壳 → 自我注销桩（清缓存 + unregister）
frontend/css/style.css                            设计变量（tokens）+ 三套皮肤 + 全部语义类样式
frontend/images/                                  图标
frontend/js/data/*.js                             首帧基线数据（元素/化合物/反应/仪器…），联网后被后端 bundle 覆盖
frontend/js/content.js                            远端内容加载：拉取 bundle 覆盖全局 + 版本缓存
frontend/js/state.js  frontend/js/engine.js       服务器帧的只读视图 + 无状态预览（投放/反应判定不在本地算）
frontend/js/fx.js  frontend/js/sfx.js             粒子/液面 与 WebAudio 音效
frontend/js/cloud.js  frontend/js/game.js         账号 API 客户端 与 意图桥接（串行队列 + 整帧落地 + 启动门）
frontend/js/gate.js                               登录 / 注册页（鉴权门）：登录、注册、游客模式三条路径
frontend/js/icons.js                              仪器线稿图标的唯一真源（27 仪器 + 兜底，图元数组而非字符串）
frontend/js/ui.js  frontend/js/panels.js  main.js 工作台与整页导航（ui：台面/物质架/拖拽投放；panels：`P.go(tab)` 页面路由 + 六页渲染；全部只发意图）
```

#### 客户端交互与页面结构（v3.3 · 整页导航，已废弃上滑抽屉）
手机 App 那种"每个底部图标是一整页"的形态：`#app` 是竖排三段（顶栏 / `#stage` / `#bottom-nav`），**底部导航 7 个页签常驻**（⚗️实验台 · 🧪物质 · 📚图鉴 · 🏪市场 · 📋任务 · 🏗️建设 · ⚙️设置）。`#stage` 内只有两个同层页面：`#bench`（实验台）与 `#page`（其余六页共用的内容区），切换靠 `.page.on` 控显隐、`.page.anim` 播一次进场动画（JS 里摘掉再塞回、中间读一次 `offsetWidth` 强制回流）。原来的 `#sheet` 底部抽屉、`#sheet-close` 与 `P.openTab` 已整套删除。

抽屉没了，投放源就搬到实验台这一页里：`#bench` 自带**横向滚动的物质架**（`#quick-shelf` / `#qs-row`），三态取数——沙盒=全物质按 z 排序、挑战=`given+decoys` 带剩余份数、日常=背包里 `countAll>0` 的按数量·价格倒序，超过 16 个尾部给一个"更多 →"芯片跳【物质】页。物质架**每次整帧落地都会重绘**（`renderQuickShelf` 由 `renderStage()` 调用），但拖拽途中跳过（`U.isDragging()` 守卫：换节点会把指针一起换掉）。投放两种手法，都由 `U.bindItemDrag` 一处实现：**点一下**（位移 ≤12px）投一份，**拖到台面**（`#bench-stage` 高亮 `.hot` + 跟手幽灵）松手投一份；横向滑架子被浏览器接管时收到的是 `pointercancel`，此时**不投放**只清理手势。其余六页的卡片退化为点一下投放（`U.place`），背包页底部另给一颗"⚗️ 台面 N 种 · 回实验台"药丸（`#page-fab`）当落点。回到实验台时 `P.go('bench')` 会先调 `CHEM.fx.resize()`——页面在 `display:none` 期间画布量出来是 0，不重量一次粒子特效就画不出来。挑战/沙盒、教程第 0/1 步现在都走 `CHEM.panels.go('bench')` 导航，而不是"关抽屉"。

新增一个底部页签＝在 `#bottom-nav` 加 `data-tab`、在 `panels.js` 的 `PAGES` 注册表里加一行 `{segs, draw}`；不要往 JS 里拼样式，也不要恢复任何"覆盖在实验台之上"的面板形态。

#### 页内分段与功能归位（v3.9 · 每个页签内部再分格）
七页骨架不动，但一整页塞十几种功能会把玩家扔在"找东西"上。现在每个二级页顶部有一条**粘顶分段条** `#page-segs`（由 `PAGES[tab].segs()` 供给），页面渲染器签名变成 `draw(body, which)`：

| 页签 | 分段 | 这一轮搬进来的东西 |
| --- | --- | --- |
| 🧪 物质 | 我的背包 / 提纯工坊 | 提纯工坊原在【建设】，但它吃的是物质、出的也是物质 → 挪来同页（临时工作台=挑战/沙盒时整页就是一件材料柜，`bagSegs()` 直接返回 `null` 不分段） |
| 📚 图鉴 | 物质 / 方程式 / 收集节点 | — |
| 🏪 市场 | 采购 / 特惠 / 黑市 / 耗材 / 出售 / 挂单 | 商会订单是**委托**不是货架 → 搬去【任务·今日】 |
| 📋 任务 | 今日（签到 + 每日任务 + 商会订单）/ 成就 / 玩法（挑战·沙盒·答题）/ 社交 | 每日签到原埋在【设置·分享与其他】，玩家根本翻不到 → 搬来并配 7 天礼包进度点 |
| 🏗️ 建设 | 房间 / 仪器 / 升级 | — |
| ⚙️ 设置 | 通用 / 外观 / 账号 / 商店 / 关于 | 内购从玩法运营里独立成"商店"段；账号段集中转正·改密·退出·注销 |

- **段上的数字＝现在有几件事可做**（可领的成就/待交付订单/买得起的仪器…），由 `claimCounts()` 与 `buyableCount()` 算一份，底部导航那枚 `.nav-badge` 读的是同一份函数——领完奖励红点自己掉，不存在两处数字打架。游客档没有口令，设置页给的是圆点而非数字（`-1` 约定）。
- **记忆与滚动**：`segState[tab]` 记住每页上次停在哪一段，`P.render()` 以 `tab + ":" + seg` 为键还原 `scrollTop`，切页回来不会掉到顶部；`qFocus` 在过滤重绘后把光标送回搜索框（选区一起还原）。
- **长列表本地过滤**：背包/图鉴/市场/方程式各有一条 `.searchbar`（`qState`，纯前端，不发请求）。方程式搜索**只筛已解锁的**，"尚未发现"清单不跟着关键词缩小——否则输入一个化学式看"命中几条"就能反推出还藏着什么反应，等于白送提示。
- 配套视觉件全部只吃 `:root` token，所以三套皮肤不用各写一遍：`.card`/`.card-hd`/`.card-sub`、页首 `.statbar`、`.chips`、`.empty-state`（空状态带一句"去哪做"并直接跳页）、行内 `.tag` 与排行榜 `.rk`。
- 签到那 7 个进度点在 `signInDots()` 里按 `((streak - 1) % 7) + 1` 计算，**必须与 `EconomyService` 服务端的 `day` 公式一致**（早先按 `streak % 7` 画，签第 1 天就亮两颗）。关于页的版本号也从 `<link>` 上的 `?v=` 反读（`appVer()`），不再手写第二份。

#### 容器与仪器图标（v3.7 · 线稿取代 emoji）
台面那个"实验服"是 emoji 表写错码点的结果（烧杯 🥼、试管 🧫、漏斗 🫙、分液漏斗 🍹、蒸发皿 🍽️ 全错位），而量筒/容量瓶/研钵/表面皿/点滴板压根没有对应键，一律回落到同一颗试管——偏偏"选哪个容器"是这游戏最核心的决定。emoji 还跟着平台字体走，换台手机形状就变。现在 **`frontend/js/icons.js` 是图标的唯一真源**：28 张图形（27 种仪器 + `other` 兜底）画在同一个 48×48 视图框里，每张由三组图元构成——`o` 轮廓、`t` 细节（刻度/支管/铁圈）、`l` 液体层，全部画在**轮廓内部**。

图形存成图元数组（`["path","M13 15 v20 …"]`）而不是拼好的 SVG 字符串，就是为了让机器能读懂几何：
- `test/icon-geom.js` 是共同裁判（`flatten` 解析 M/L/H/V/Q/T/Z，`auditShape` 判越框、未知图元、液体跑出器壁），`test/validate.js` 与 `tools/icon-preview.mjs` 共用它，避免"预览没报错、回归也没报错，浏览器里液体却画到杯外"。
- `node tools/icon-preview.mjs [id…]` 把图形栅格化成 ASCII（`#` 轮廓 / `+` 细节 / `~` 液体）供人眼确认"这条线真的像烧杯"，有问题图形时退出码非 0。
- `validate.js` 钉住四件事：**每种仪器必须有专属图形**（新增仪器不补图形直接 FAIL）、容器必须有液体层（否则装了东西看不出来）、几何体检全过、两张图形不得完全相同（不然玩家分不出）。

接线面：台面 `#vessel-icon` 由 `renderStage()` 现画，**换容器才重画形状**，形状不变时只切 `.wet` 与 `--ic-liq`——于是液面颜色随混合液渐变而不是闪一下；`mixColor()` 出来的颜色先过 `safeColor()` 白名单再进 `style`（颜色来自后台可编辑的物质数据）。选容器弹窗与【建设】仪器商店每行前挂 `.ic-slot` 缩略图，线条只吃 `--ic-line`（皮肤变量），三套皮肤零额外规则。无色气体（H₂/O₂）混出的液体近乎透明，所以 `.v-l` 带一圈 `--ic-line` 42% 的细边当液面，免得玩家以为台面是空的。

#### 视觉体系（v3.2）
`css/style.css` 只有一份，全部样式走 `:root` 设计变量：颜色（`--bg/--card/--ink/--sub/--blue/--gold` + 8 组 `--soft-x`/`--fg-x` 前景对）、间距 `--s1..--s5`、圆角 `--r-xs..--r-pill`、 elevation `--e1..--e3`、渐变 `--g-brand/--g-topbar/--g-gold`、动效 `--dur/--ease`。三套皮肤（`body[data-skin=""]|"cyber"|"retro"`）**只覆盖变量、不写新规则**，因此加皮肤=加一段变量表。
JS 生成的标记用语义类，不再写内联 `style`（动态色除外，如物质色块 `background:COLOR`）：`.sec` 面板小标题、`.hint-p`（`.lead`/`.coupon` 变体）说明文字、`.empty` 空态、`.btn-row` 按钮行、`.btn-s`/`.ok`（`.danger` 变体）按钮、`.dot` 市场状态点、`.quiz-q`/`.quiz-opt` 答题、`.eqrow` 方程式行、`.row.you` 排行榜自己那一行、`.sys-banner` 启动异常横幅；登录/注册页另有一组 `.gate-*`（`#gate-card` 卡片、`.gate-tabs`、`.gate-lbl`、`.gate-pw` + `.gate-eye`、`.gate-err`、`.gate-or`、`.gate-guest`、`.gate-foot .dot.up|down` 服务器状态点）。**新增界面状态请优先加变量+语义类，不要在 JS 里拼样式。**

### 后端 `server/`（Spring Boot：引擎 + 经济 + 鉴权 + 内容 + 管理）
```
pom.xml                                           Maven（Aliyun 镜像）+ 从 ../frontend 复制客户端资源随包发布
src/main/resources/application*.yml               mysql / **prod** profile：JWT、限流闸门、CORS、BCrypt 强度、存档上限、种子超管
src/main/resources/logback-spring.xml             控制台 + 按天/20MB 滚动文件日志（30 天、500MB 上限），每行带 traceId
src/main/resources/db/migration/V1..V5            Flyway：建表 / 内容种子 / 配置种子 / 游客档 / 令牌轮换
src/main/resources/static/admin/                  管理后台 SPA 构建产物（npm run build 生成）
db/bootstrap.sql                                  建库 + chem 用户（root 执行；口令经 envsubst 从 .env 注入）
src/main/java/com/chemera/server/game/            GameState / ContentRegistry / GameEngine / EconomyService / GameService
src/main/java/com/chemera/server/                 controller / service / mapper / entity / security / config
.env.example                                      环境变量模板（DB 口令、JWT secret 等）；真实值只写进 .env，已被 gitignore
```

### 管理后台 `admin/`（Vue3 + Element Plus SPA）
```
vite.config.js                                    base=/admin/，构建产物 outDir 直出到 server/.../static/admin
src/                                              页面 / 组件 / 路由 / 接口封装
```
内容管理与运营配置由 `ContentSchema` 的字段描述符驱动：同一份描述符既生成编辑表单，也做写入期校验与内容体检（`/content` 的类型化表单、`/config` 的按 key 编辑器、`/health` 的问题行定位）。`/config` 每项都带**作用说明书**（`server/.../game/ConfigSpec.java`，经 `/admin/api/config/spec` 下发）：中文名、生效范围（服务端结算 / 服务端+前端 / 部分生效 / 仅前端）、"改了会怎样"的风险提示、以及读它的代码出处；`ConfigSpecTest` 保证说明书与 `Content.Config` 的字段一一对应，加字段不写说明即测试失败。**特别标注**：`start_coins` 与 `tier_names` 只被前端读取，改它们不会改变服务端结算（详见下表「运营配置速查」）。`/admins` 是超管专属的后台账号页（建号/改角色/停用/删除/重置口令），初始口令未改时整个 SPA 被钉在改密弹窗上；`/users` 里给玩家重置口令走的是同一道超管闸门。**尚未后台化**的写死项与架构缺口见 `docs/content-management-followups.md`（成就判定条件、等级曲线、内容版本历史等）。

### 运营配置速查（13 项）

| 键 | 名称 | 生效范围 | 一句话作用 |
| --- | --- | --- | --- |
| `sell_rate` | 卖出基础倍率 | 服务端结算 | 卖价 = 原价 × sell_rate × 品质倍率 × 行情漂移；调高直接放大全局金币产出 |
| `buy_rate` | 买入倍率（仅低声望档） | 部分生效 | 只作用于声望「生面孔」(rep<20)；常客/贵宾/荣誉会员的 1.16/1.12/1.08 写死在引擎 |
| `first_sell_bonus` | 首次出售加成 | 服务端结算 | 每种物质首卖额外乘一次，撑起"开图鉴就有钱"的节奏 |
| `quality` | 品质档与价格倍率 | 服务端+前端 | 粗/纯/高纯三档的计价倍率，参与卖价、投料估值、收购价 |
| `milestones` | 图鉴收集里程碑节点 | 部分生效 | 节点列表生效；奖励金额写死为 节点值×100 金币 + 一个 Lv.2 化合物 |
| `tier_names` | 容器档位名称 | 仅前端 | 纯文案，引擎按 tier 下标算；改数组长度会让前端显示错位 |
| `tier_up_cost` | 容器升档费用倍率 | 服务端结算 | 升档费 = 仪器原价 × tier_up_cost[当前 tier]，只有 0→1、1→2 两跳 |
| `lab_upgrades` | 实验室三条升级线 | 部分生效 | baseCost/growth/max 与 storage 的 step 生效；safety/bench 的实际效果常数写死在引擎 |
| `discover_bonus` | 发现奖励区间 | 服务端结算 | 按物质稀有度 1..4 在 [下限,上限] 内按 id 哈希取值（同物质恒定），反应顺带发现再折半 |
| `quiz_reward` | 答题基础奖励 | 部分生效 | 实发 = quiz_reward × 年级倍率（小学/初中 1.0、高中 1.2、大学 1.5 写死） |
| `tutorial_coins` | 新手引导完成奖励 | 服务端结算 | 走完教程第 3 步一次性发放，老玩家不会重领 |
| `start_coins` | 新号初始金币 | **仅前端** | 服务端新建档写死 5000，改这个值不会改变玩家实际到手的金币 |
| `recharge` | 模拟充值档位 | 服务端+前端 | c=标价（元）、d=到账钻石，按下标选档；当前不接真实支付渠道 |


### 跨切面开发辅助（仓库根）
```
tools/export-seed.mjs                             把 frontend/js/data 导出为 Flyway 种子迁移（V2/V3）
tools/backup.sh                                   整库快照（专用备份账号 + gzip 完整性校验 + 按天保留）
tools/restore.sh                                  从快照恢复：支持 --into 别的库做恢复演练，覆写原库要二次确认
tools/icon-preview.mjs                            把 js/icons.js 栅格化成 ASCII 人眼确认形状；有问题图形（越框/液体溢出/漏画）退出码非 0
test/icon-geom.js                                 图标几何裁判（path 解析 + 越框/未知图元/液体溢出判定），validate.js 与上面的预览工具共用
test/validate.js                                  数据一致性校验（读 frontend/js/data 内嵌基线 + 反应图完整性 + 仪器图标覆盖率与几何）
test/e2e-api.sh                                   端到端回归：游客→意图闭环→防作弊闸门→鉴权令牌治理→转正并档→后台账号与口令→静态托管→内容管理
test/ci.sh                                        一键串起上面三层回归（可挂 CI 或提交前钩子）
docs/content-management-followups.md              后台内容管理本轮边界 + 仍写死项/架构缺口清单
docs/upgrade-plan.md                              安全/运维/性能/代码健康的升级方案与迭代进度
docker-compose.yml                                可选：一键起 MySQL 9.5（宿主 3307）
legacy/                                           旧版零依赖 server.js、PWA manifest 等历史件
legacy/removed/                                   被在线化淘汰的代码（客户端存档直写端点、驱动本地引擎的 JS 冒烟/全量合成测试）
```

## 后端 API

### 游戏侧（`/api`，除 auth/content 外均需用户 JWT；游客令牌同样是 `typ=user`）
| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/auth/register` | POST `{user,pass}` | 注册（用户名 2-24；密码 ≥6；BCrypt 散列），成功即签发 access+refresh |
| `/api/auth/guest` | POST | 开一个服务端游客档（试玩即建档，进度不丢） |
| `/api/auth/upgrade` | POST (Bearer) `{user,pass}` | 游客转正：注册新账号并把游客进度并入，返回新令牌 `{merged:true}` |
| `/api/auth/login` · `/refresh` · `/logout` | POST | 登录 / **轮换式**续期（用一张废一张，15s 宽限容忍多标签页；超期重现按泄露处理并整户下线）/ 吊销当前会话（硬撤销，之后绝不补发） |
| `/api/healthz` | GET | 运维探活：`SELECT 1` + 内容版本 + 在线会话数，数据库不通返回 503。所有响应都带 `X-Request-Id`（traceId，进日志） |
| `/api/auth/pass` · `/delete` | POST (Bearer) | 改密（其余会话失效）/ 注销（连带清除存档与埋点） |
| `/api/game/state` | GET | 整帧快照：载入存档 + 每日刷新落库后返回最新 `state` |
| `/api/game/{intent}` | POST `params` | **唯一玩法入口**，返回 `{state,revision,result,events,bench?,sandbox}`。意图涵盖 `bench.place/takeBack/clear/temp/electrolysis/vessel/switch`、`react{multiplier,insured?}`、`challenge.*`、`sandbox.*`、`market.buy/consumable/special/black/sell`、`listing.create`、`order.fulfill`、`sign`、`quiz.pickOne/answer`、`shop.buy/recharge`、`upgrade.vessel/equipment/tier/lab/room`、`refine`、`claim.ach/daily/milestone`、`hint`、`friend.visit/gift`、`leaderboard`、`ad.bonus`、`settings`、`tutorial.step`、`reset` |
| `/api/me` | GET (Bearer) | 当前账号与云端存档概要 |
| `/api/content/version` · `/bundle` | GET | 内容版本号 / 全量内容 + 配置（客户端按版本缓存） |
| `/api/analytics/event` | POST (Bearer) `{event,props}` | 埋点（白名单事件；未登录忽略） |

> `PUT /api/save`（客户端上传存档）随在线化一并下线：游戏状态只能由服务端结算产生，管理侧仍可在后台查看/回滚历史版本。

### 管理侧（`/admin/api`，除 login/ping 外均需 `typ=admin` 的 JWT；`viewer` 只读）
内容 CRUD 与发布（bump 版本、通知客户端）、内容元数据（`/content/schema` 字段描述符、`/content/options` 下拉候选、`/content/health` 逐行体检）、写入期 `strict` 字段与引用校验、运营配置 CRUD、用户检索/封禁/存档查看/历史回滚/删除、**超管专属的口令重置（`/users/reset-password`）与后台账号 CRUD（`/admins`、`/admins/create|password|role|status|remove`）**、数据看板（KPI + 登录趋势 + 事件分布）、审核（举报处理、敏感词、操作日志）。自助改密 `/admin/api/me/password` 任何角色可用，且是"初始口令未改"期间唯一放行的端点。

安全：JWT（HS，`typ` 区分 user/admin，`uname` 声明供审计识别操作者）、BCrypt（开发 10 轮 / prod 12 轮）、刷新令牌仅存 SHA-256 摘要且**轮换作废**、鉴权端点全量过闸门（登录按 IP+账号 8 次失败锁 15 分钟、注册与游客档按 IP 配额、后台登录独立更严档，超限返回 429）、拦截器统一鉴权（后台侧每个请求回查账号表，令牌里的角色不作数）、审计日志记录后台写操作。凭证一律走环境变量：仓库内的 `.env.example` / `run.sh` / `bootstrap.sql` / `docker-compose.yml` / 备份脚本都不落真实口令字面量（本地值只写在被 gitignore 的 `server/.env`）。

### 上线（prod profile）
```bash
cd server && mvn -o package
SPRING_PROFILES_ACTIVE=mysql,prod \
CHEMERA_JWT_SECRET="<至少 32 字节随机串>" \
CHEMERA_SEED_ADMIN_PASS="<首次创建超管用，之后留空不重置>" \
CHEMERA_DB_PASSWORD="<库口令>" \
CHEMERA_TRUST_XFF=true \
java -jar target/chemera-server.jar
```
`application-prod.yml` 相对开发配置的差别只有"默认值不再宽容"：跨域默认完全不下发（同域部署无需 CORS，需分离域名时用 `CHEMERA_CORS_ORIGINS` 明确列源）、限流收紧、错误页不泄栈、`forward-headers-strategy=native`。启动时 `ProdHardening` 会做自检：**prod 下若仍用仓库内置 JWT 密钥、或没给管理员口令，进程直接拒绝启动**（开发环境只打 WARN）。HTTPS 与反向代理仍需在 Nginx/Caddy 层配置，配置后把 `CHEMERA_TRUST_XFF` 打开才能让限流看到真实来源 IP。

日志落在 `logs/chemera.log`（按天 + 20MB 滚动，保留 30 天 / 500MB 上限），每行带 `[traceId]`，与响应头 `X-Request-Id` 一一对应；`GET /api/healthz` 可直接给容器编排或 Uptime 探测用。数据快照见上文「管理员账号、玩家口令与备份」。

## 管理员账号、玩家口令与备份

**后台账号（`/admin/api/admins`，仅 `super`）**：列表、建号、重置口令、改角色、停用/启用、删除，全部写 `audit_log`。三条底线由 `AdminAccountService` 兜住：

- 口令比玩家侧更严（≥8 位、不含 `admin123` 这类公开值、不等于账号名），新建与被重置的账号都带 `must_change_password`，**改密之前后台其余端点一律 403**（拦截器只放行 `/admin/api/me/password`，前端躲不过）。
- 角色与状态**以库为准**：每个后台请求都回查一次 `admin_user`，所以停用/降级对已签发的 2 小时令牌立刻生效（这一点玩家侧还做不到，见「已知边界」）。
- 不能自锁：停用、降级、删除都会挡住"最后一个在岗超管"，也不允许对自己下手。

后台 SPA 里对应【管理员账号】页（`super` 才看得到菜单），顶栏有【修改口令】；改密后强制重新登录，因为无状态令牌无从撤销。

**玩家忘记密码**：本作没有邮件服务可自助验证身份，所以找回路径是人工的——登录页【忘记密码？】会说明怎么找管理员，管理员在【用户管理】按用户名定位后点【重置口令】给一个临时口令。重置会作废该玩家**全部刷新令牌**（旧口令立即失效），已打开的页面最长还能用到访问令牌自然过期（≤2 小时）。游客档没有口令，接口直接拒绝重置，避免凭空造出一个可登录的正式账号；列表里的「类型」列可区分游客档。

**调整玩家资产（`POST /admin/api/users/assets`，仅 `super`）**：金币和钻石不是 `app_user` 的列，而是 `user_save.payload` 这块 JSON 里的字段，所以【用户管理】列表用 `JSON_EXTRACT` 把余额直接带出来（没有存档的游客显示 `-`），【调整资产】弹窗改的也是这块 JSON。`PlayerAssetService` 有三道闸门：填的是**增减量**而非目标值（避免运营在两个界面来回抄数字时把玩家余额覆盖成别人的）、单次不超过 1000 万且结果不得为负或越过余额上限（金币 10 亿 / 钻石 100 万，对齐最高充值档）、没有云端存档的玩家直接拒绝而不是凭空建一档。它只改 payload 里点名的字段（用 `JsonNode` 原地改，不走 `GameState` 反序列化，否则 `ignoreUnknown` 会静默丢掉客户端遗留字段），并且**每个请求都回库读档**的玩家侧会立刻看到新余额——`PUT /api/save` 早已 404，客户端无从把自己的旧数字盖回来。
每次成功调整写两道留痕：存档历史的 `source='admin'`（与 `upload`/`rollback` 区分，玩家侧【历史】也看得到），以及 `audit_log` 的 `user.assets` 记录，明细带 `coinsBefore/coinsAfter/diamondsBefore/diamondsAfter/revision`——【审核与审计 → 操作日志】的「明细」列直接把这段前后值显示出来。权限与重置口令同档（`requireSuper`）：这一口能凭空造钱，编辑角色不该有路径；角色仍以库为准，所以把超管降为编辑后他手里的旧令牌立即调不动它。

**备份与恢复**：玩家进度只存在于 `user_save` + `user_save_revision`，这是全项目唯一的丢失面。

```bash
bash tools/backup.sh                          # 整库快照 → backups/chemera-<时间戳>.sql.gz，默认保留 14 天
KEEP_DAYS=30 bash tools/backup.sh /data/chem  # 自定义保留期与输出目录
bash tools/restore.sh backups/chemera-xxx.sql.gz --into chemera_drill --root   # 恢复到别的库=恢复演练
bash tools/restore.sh backups/chemera-xxx.sql.gz                              # 恢复到原库（要输入库名二次确认）
```

`backup.sh` 用**专用备份账号** `CHEMERA_BACKUP_USER`（默认 `chem_bak`，`server/db/bootstrap.sql` 里建）：mysqldump 取一致性快照必须 `FLUSH TABLES`，这个权限不该发给应用账号。脚本每份快照都验 gzip 完整性与结尾的 `Dump completed` 标记，坏文件不计成功；`backups/` 已在 `.gitignore` 里（含玩家数据，绝不入库）。Windows 上 `mysqldump`/`mysql` 常不在 PATH，用 `MYSQLDUMP_BIN` / `MYSQL_BIN` 指到安装目录即可。**建议排期**：`cron`/任务计划每日一次 `backup.sh`，并按季度做一次 `--into chemera_drill` 的恢复演练——没验过能恢复的备份等于没有备份。

## 构建与测试

```bash
cd server && mvn clean package        # 133 个 JUnit：领域模型/内容注册表/引擎/经济/意图层/内容校验/配置说明书/全量合成/令牌轮换/限流/上线自检/后台账号 CRUD/玩家资产调整 + HTTP 层鉴权
cd server && mvn spring-boot:run      # 或 java -jar target/chemera-server.jar

bash test/ci.sh                       # 一键全量回归：数据一致性 → 后端单测 → 端到端 API（后端没起就跳过第三层）
node test/validate.js                 # 前端内嵌数据一致性（118 元素 / 143 反应 / 引用完整性）
bash test/e2e-api.sh                  # 端到端回归（需后端已启动）
```

- `FullSynthesisTest` 逐条驱动 Java 引擎，证明 143 条反应与 3 条工艺都能按其声明条件正常合成 —— 取代了原先驱动本地 JS 引擎的 `test/smoke.js` 与 `test/full-synthesis.js`（两者已移入 `legacy/removed/`）。
- `e2e-api.sh` 额外校验：无 token 取帧 401、未知意图被拒、温度/电解设备闸门、答题防透题与一次一题、广告券一次一用、偏好持久化、游客转正并档、`PUT /api/save` 已 404、首页静态托管与离线壳清理、登录门（`js/gate.js` 随包发布 + 首页含 `#gate-root` 与忘记密码指引 + `game.js` 未登录先过门）、注册可省昵称与弱密码被拒、**令牌轮换（用一张废一张 / 宽限期内并发刷新可用 / 退出后硬撤销绝不补发 / 陌生令牌 401）**、**鉴权防爆破（连续失败转 429、锁定按账号隔离、游客档配额内可建）**、**缓存与运维面（版本化脚本 immutable、入口文档 no-cache、`X-Request-Id`、`/api/healthz` 报库连通）**；管理侧另有一组（schema 覆盖 13 类、options 候选、内容体检 0 问题覆盖 458 行、`strict` 非法写入被拒且不留脏行、`strict=false` 后门可写）、**后台账号 CRUD（建号响应不含哈希、弱口令被拒、首登 mustChange、锁定期内只放行自助改密、viewer 管不了账号、停用后旧令牌立刻失效、最后一个在岗超管动不了、测完自动清理）**、**玩家口令重置（按用户名定位、旧刷新令牌整户作废、旧口令立即 401、游客档拒绝重置）**、**运营配置说明书（`/admin/api/config/spec` 覆盖 13 个键、每条带中文名/作用/风险/出处/生效范围、`sell_rate` 标服务端结算而 `start_coins` 老实标仅前端、库里的键必须全部收进说明书、自定义键确认不在说明书里）**、**玩家资产调整（列表带出 payload 里的余额、加金币后玩家下一次取帧即生效、只调一项不清零另一项、存档历史标为 `admin`、审计明细带前后值、扣穿/超单次上限/零增减/非整数各被拒、玩家令牌与无令牌 401）**。当前 PASS=147 / FAIL=0，可连续多轮执行（限流段、建号段、资产段与临时配置键都不污染后续运行）。编辑/只读角色拿超管令牌调资产被拒这类**角色以库为准**的分支由 `AdminUserAssetsHttpTest` 在 HTTP 层钉住，e2e 只跑真实存在的超管与玩家两种令牌。另有一组**客户端页面结构**断言守着整页化改造：首页不再有 `#sheet`、`#stage`/`#page-body` 就位、底部导航 7 个 `data-tab`（含 `bench` 本身是一页）、实验台自带 `#quick-shelf`、`style.css` 里 `#sheet` 清零且改由 `.page.on` 驱动、`panels.js` 是 `P.go(tab)` 而非 `openTab`、回实验台会 `CHEM.fx.resize`、`ui.js` 有 `renderQuickShelf` 与 `pointercancel`/`isDragging` 守卫，以及全站 `?v=` 唯一。另有一组**容器图标**断言守着 emoji 错配不再回归：`js/icons.js` 随包发布且 `<script>` 排在 `ui.js` 之前、首页有 `#vessel-icon` 且 `#vessel-emoji`/`#liquid-layer` 清零、`ui.js` 里既没有实验服那个 emoji 码点也没有残留的 `VESSEL_EMOJI` 表、台面与【建设】仪器商店确实改调 `CHEM.icon`、`style.css` 里 `.v-ic.wet` 与 `--ic-liq` 就位（液体画在容器内并随皮肤走）。还有一组**页面信息架构**断言守着这轮归位不回退：外壳有 `#page-segs`、6 个二级页各一枚 `.nav-badge`（实验台不留）、`panels.js` 由 `PAGES` 注册表描述页面、段点数字与角标同源于 `claimCounts`/`buyableCount` 且 `U.updateNav()` 进了 `G.refresh()`、签到在任务页（`#t-sign`）而设置页只剩指路按钮、商会订单卡片跟着 `d.orders.list`、提纯工坊在物质页、临时工作台不分段、市场恰好 6 段、四类长列表都有搜索框、方程式搜索带"未发现清单不跟着过滤"的说明、六类新组件样式齐全、关于页版本号取自 `?v=`。写这组断言时留了个坑位提醒：**本机 grep 在 `C.UTF-8` 下匹配不了 4 字节 emoji**（星平面字符会静默返回 0 命中），断言只能用中文词或结构锚点。

## 已知边界

- 断网即不可玩（这是"在线游戏"的定义）；页面会停在启动门并提示启动后端。
- 挑战/沙盒的临时现场存在每 uid 的内存 `EngineCtx`，多实例部署需会话粘滞或把它外置（当前为单实例设计）。
- 排行榜是"全体玩家收集进度"的服务端计算快照，按天缓存于客户端。
- 意图端点不带幂等序号：网络重试会被当作两次合法意图重复结算，正式运营前需补客户端 `seq` 去重（见后续清单）。
- 鉴权限流与刷新令牌宽限都是**单实例内存态**：横向扩容时每台各算各的额度（重启即清零）。要么保持单实例，要么把它外置（与 `EngineCtx` 会话外置同一批做）。
- 后台内容管理尚不能覆盖引擎写死项（成就判定条件、等级曲线、事故常数），也没有内容版本历史——详见 `docs/content-management-followups.md`。后台账号本身已可在【管理员账号】页增删改查，prod 带着 `admin123` 这类公开口令会拒绝启动。
- **访问令牌不绑定会话**（玩家侧）：封禁、改密、重置口令都只能作废刷新令牌，已签发的访问令牌在自然过期前（≤2 小时）仍然可用。后台侧已经每请求回查账号表，玩家侧还没做——补齐方案见 `docs/upgrade-plan.md` 的 A6（令牌带 `sid`，鉴权时查一次会话行）。
