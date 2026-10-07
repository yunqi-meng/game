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

> **改过前端 JS/CSS 必须同时顶版本号**：`frontend/index.html` 里所有 `css/` `js/` 引用共用一个 `?v=3.15`（当前值），而这些版本化资源的服务端响应头是 `max-age=31536000, immutable`——同一个 `?v=` 下重新发布，浏览器会一直吃那份一年期强缓存（这条我们踩过两次：一次是代码进了 jar 页面还在跑旧函数，一次是同轮里已经发过 `3.8` 又补了个函数进去，页面怎么刷都不见变化，只能整体顶到 `3.9`；变现轮新增 `js/ads.js` 播放桥时顶到 `3.10`，安卓化与迭代 3 的启动链路改造顶到 `3.14`，P0 收尾与 H3/H4/H5 表现层改动顶到 `3.15`）。所以：**每次动前端就整站 +0.1**（`e2e-api.sh` 有一条断言在盯"版本号是否全站唯一"），测的时候再强刷。顶了版本号还必须**重出安卓包**（`cd android && npx cap sync android && ./gradlew assembleDebug`）——APK 里的 `assets/public` 是 `frontend/` 的拷贝，忘了同步就是"服务端 3.14、包里还是 3.13"，这条 `test/android-check.sh` 会红给你看（本轮真的红过一次）。历史版本注册过的 Service Worker 会在下次更新检查时载入 `sw.js` 的自我注销桩，自动清掉旧的离线缓存。

> **改过前端的四步链，一步都不能省**（迭代 4 的 F6 把原来那句"三步"补全，因为后台产物这一环当时根本没人重建）：**① 顶 `?v=`**（全站共用一个值）→ **② `cd admin && npm run build`**（后台 SPA 的 dist 是要入库的，改了 `admin/src` 不重建，jar 里就是旧面板）→ **③ `cd server && mvn package`**（前端与后台产物都在 `process-resources` 阶段进 `target/classes/static`）→ **④ `cd android && npx cap sync android && ./gradlew assembleDebug`**（包里那份 `www` 是拷贝）。这条链现在由回归层替你盯着：第 ①/④ 步漏了 `android-check.sh` 比版本号会红，第 ② 步漏了 `test/admin-dist-check.sh`（`ci.sh` 第 2 层）会红，第 ③ 步的产物与源码不一致 CI 里 `git diff --cached --quiet` 那一步会红。

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
- 变现（无内购）：🪙金币 + 💎钻石双货币、月卡特权、皮肤、提示道具**全部改为看激励视频换积分**获取——玩家侧没有任何"花钱"入口与人民币标价（详见下面「激励视频变现」）
- 每日任务、19 项成就、图鉴节点奖励、5 档年级答题、7 天连续签到、5 步教程、助手精灵
- 🔊 WebAudio 合成音效 + 程序化 BGM（无音频文件），音量/开关等偏好也存服务器存档

### 服务端权威带来的防作弊约束
| 风险 | 服务端的处理 |
|---|---|
| 改包刷金币/物品 | 客户端只发意图，余额与背包变化全部来自本次服务端结算结果 |
| 直写存档 | 面向客户端的 `PUT /api/save` 已下线（见 `legacy/removed/SaveController.java`），只有 `GameService` 能写 `user_save` |
| 跳过条件用高温/电解 | `canTemp` / `canElectrolysis` 按已购设备裁决（灯/吹管/电解装置） |
| 答题穷举选项 | 题目由 `quiz.pickOne` 单独取，**不下发正确答案**；`quiz.answer` 只认当前发出的那一题，答完即焚 |
| 自证"看完广告"骗奖励 | 旧意图 `ad.bonus` 已关回绝；奖励只认**广告网络服务器回调**验签通过的工单（见「激励视频变现」），且 `trans_id` 唯一 + 条件 UPDATE，重复回调只算一次 |
| 绕过复活/双倍的费用 | `challenge.revive` 只消耗看广告换来的 `ad.revive` 次数，双倍必须由服务器签发的**当日一次性券**支付，券同样来自广告 |
| 反应/经济随机数可预测 | 结算随机源在服务端（`DoubleSupplier`），客户端随机只影响特效 |

- ☁️ 进度即时入库：**写意图**以带号 CAS 写回 `user_save`（`UPDATE … WHERE user_id=? AND revision=?`，抢输的一方重读最新帧重放，同一账号两台设备不会互相抹掉），只读意图（`state`/`ad.status`/`leaderboard`/`quiz.pickOne`）只在真有东西要落时才写盘；历史留在 `user_save_revision` 可回滚，`intent` 来源每 25 版抽记一条、后台改档/上传/回滚/重置与初始帧必记，且修剪那句 SQL 带 `AND revision > 1`——账号过 31 版也不会把第 1 版删掉，后台【回滚到最早一版】永远有锚点；`EngineCtx` 承载挑战/沙盒临时现场，服务重启后能从存档复原现场
- 账号：登录/注册页（三条路径：登录 / 注册 / 游客模式）+ BCrypt + JWT access/refresh 双令牌、改密（其余设备会话失效）、注销（物理清除该用户的会话/存档/历史/埋点）、退出后回到登录页

## 激励视频变现（玩家侧零付费）

没有版号就不能有内购计费，而激励视频不属于内购，所以原来那套"模拟充值 + 钻石档位 + 商店购买"整面下线，改为**看一段视频换一份积分、积分兑换原付费项**。奖励的唯一入口是服务端工单，客户端说的话一律不算数：

```
ad.request ──签发──▶ ad_ticket(issued) ──平台回调验签──▶ rewarded ──取帧开头 settle──▶ settled + 进存档
   ↑                        ↑                                        ↓
 闸门逐条拒          extra=32位随机票据                         events:[{type:"ad",granted:[…]}]
```

- **签发**（`AdService.request`）：总开关 → 能不能真发奖 → kind 在目录内 → 该位每日次数 → 等级门槛 → 该位余量 → 全局日上限 → 冷却 → 同位仅一张在途。奖励类型·数量·积分在签发一刻**定格进工单**，运营中途改配置不影响玩家正在看的这一笔。
- **回调**（`POST /api/ad/callback`，`AdController`）：来访的是广告网络的服务器，**不带玩家 JWT**，身份全靠 SHA-256 验签；`trans_id` 唯一 + 条件 UPDATE 让平台重试与并发只算一次，且重复回调仍回成功（回失败平台会一直重试）。query / form / JSON 体三种回传方式都合并接，`chemera.ad.callback-ack` 切回执形态。
- **结算**（`AdService.settle`）：放在**每次意图的最前面**，所以"回调比玩家下一次请求晚到"也不会丢奖励；到账明细走整帧 `events`，客户端 `G.announce` 见 `type:"ad"` 就弹"🎁 广告奖励已到账"。
- **配置**：`app_config` 的 `ad` 键（后台【运营配置】可编辑，`ConfigSpec` 标为服务端结算）管 `enabled / dailyTotal / minLevel / ticketTtlSec / viewPoints / slots[] / unlocks[]`；密钥与拼接口令只走环境变量 `CHEMERA_AD_*`（`security-key / sign-template / sign-hex / callback-ack / dev-mode / space-id`），仓库里不落任何值。`/api/healthz` 的 `ad:{ready,devMode}` 是拨测点。
- **护栏**：全局每日 16 次上限 + 每位冷却，防止"肝广告"取代做实验成为主循环；`minLevel` 让新玩家先跑通核心玩法；`enabled=false` 时整页给"暂未开放"，不出现点了没下文的死按钮；奖励类型只认 `coins/diamonds/hints/coupon/revive/monthly_days/pack_el/skin` 八种，运营填错的那一位直接跳过而不是崩掉整帧。
- **演示通道**：`chemera.ad.dev-mode=true` 时浏览器预览用一段倒计时动画代替真广告、奖励走 `ad.devGrant`；prod profile 把该值写死 false，且 `ProdHardening` 检测到 true 直接拒绝启动——自证通道等于无限提款机，不可能带上线。
- 客户端接线：`js/ads.js` 是播放桥（原生壳 `window.ChemeraAd.showRewardVideo({spaceId, extra:ticket, …})`），`U.watchAd(kind, cb)` 是所有观看动作的唯一路径（广告中心、炸锅慰问金、挑战复活、双倍领取券），【设置·广告】整页只吃 `ad.status` 一个视图包。

深一点的字段口径、上线前还要比对的两件事（真实签名拼接与回执格式）写在 `docs/android-taptap-plan.md` 第 10 章。

## 数据规模

118 元素 + 96 化合物 + 143 条配平方程式（含离子/可逆/热化学与彩蛋链）+ 5 组危险混合 + 3 条实验工艺 + 27 种仪器 + 5 间实验室 + 25 道题库 + 19 成就 + 商店/任务/配置等，共 **458 条内容 + 14 项运营配置**，全部落库、可在后台在线编辑与发布。

## 目录结构

三个应用各自独立成目录：**前端游戏 `frontend/` · 后端 `server/` · 管理后台 `admin/`**；`tools/ test/ legacy/` 与 `docker-compose.yml` 作为跨切面的开发辅助留在仓库根。

### 前端 `frontend/`（纯 HTML/CSS/JS，无构建，由后端静态托管）
```
frontend/index.html                               入口页（#stage 同层两页 + 7 页签底部导航 + 页内分段条 #page-segs；css/js 统一带 ?v=3.15）
frontend/sw.js                                    已废弃的离线壳 → 自我注销桩（清缓存 + unregister）
frontend/css/style.css                            设计变量（tokens）+ 三套皮肤 + 全部语义类样式
frontend/images/                                  图标
frontend/js/data/*.js                             首帧基线数据（元素/化合物/反应/仪器…），联网后被后端 bundle 覆盖
frontend/js/content.js                            远端内容加载：拉取 bundle 覆盖全局 + 版本缓存（首装那一次也吃 cloud.js 的 12 秒期限，挂着不回就等于遮罩永远转圈）
frontend/js/state.js  frontend/js/engine.js       服务器帧的只读视图 + 无状态预览（投放/反应判定不在本地算）
frontend/js/fx.js  frontend/js/sfx.js             粒子/液面 与 WebAudio 音效
frontend/js/cloud.js  frontend/js/game.js         账号 API 客户端 与 意图桥接（串行队列 + 整帧落地 + 启动门）；每个请求自带 `AbortController` + 12 秒期限且回调恰好一次（H2），每笔意图领一个会话内 `seq` 交服务端幂等（F2），换身份时 `epoch++` 把上一轮的迟到回包和所有定时器一起剪掉（H2）
frontend/js/ads.js                                激励视频播放桥 `CHEM.ad`：原生壳走 `window.ChemeraAd.showRewardVideo`，浏览器预览退化成带"演示"标注的倒计时动画
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
| ⚙️ 设置 | 通用 / 外观 / 账号 / 广告 / 关于 | 变现面独立成"广告"段（v3.10 由"商店"改来：钻石商店与模拟充值已整面下线，皮肤与月卡的入口改成跳这一段的 `📺 积分兑换`）；账号段集中转正·改密·退出·注销 |

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
内容管理与运营配置由 `ContentSchema` 的字段描述符驱动：同一份描述符既生成编辑表单，也做写入期校验与内容体检（`/content` 的类型化表单、`/config` 的按 key 编辑器、`/health` 的问题行定位）。`/config` 每项都带**作用说明书**（`server/.../game/ConfigSpec.java`，经 `/admin/api/config/spec` 下发）：中文名、生效范围（服务端结算 / 服务端+前端 / 部分生效 / 仅前端）、"改了会怎样"的风险提示、以及读它的代码出处；`ConfigSpecTest` 保证说明书与 `Content.Config` 的字段一一对应，加字段不写说明即测试失败。变现口径最敏感的 `ad` 键额外有一道**存前守卫**：`AdConfigValidator` 按引擎实际读取的字段与取值域校验整份目录，`/admin/api/config/ad/schema` 把合法 reward 集合、皮肤集合、字段清单与边界值下发给面板，面板据此渲染类型化编辑器（广告位/兑换项逐行编辑，不用手改 JSON），被拒时服务端那句"广告位 #2 的 reward「coin」不被引擎支持…"会逐条显示在对话框里（`AdConfigValidatorTest` 13 例钉住守卫本身，`e2e-api.sh` 对着真接口再跑一遍并列 7 种坏配置）。**特别标注**：`start_coins` 与 `tier_names` 只被前端读取，改它们不会改变服务端结算（详见下表「运营配置速查」）。`/admins` 是超管专属的后台账号页（建号/改角色/停用/删除/重置口令），初始口令未改时整个 SPA 被钉在改密弹窗上；`/users` 里给玩家重置口令走的是同一道超管闸门。**尚未后台化**的写死项与架构缺口见 `docs/content-management-followups.md`（成就判定条件、等级曲线、内容版本历史等）。

### 运营配置速查（14 项）

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
| `ad` | 激励视频广告中心 | 服务端结算 | 6 个广告位 + 4 个积分兑换项的目录，含日上限/等级门槛/工单有效期/每段积分；闸门与发奖全在 `AdService`，改这里就是改变现口径。**写入即校验**（`AdConfigValidator`）：reward 不被引擎支持、kind/id 重复、冷却与门槛越界、计数类数量填 0、skin 兑换项漏给皮肤，全部在落库前拒掉并把行号与合法集合回给面板；面板是类型化编辑器（选项来自 `/admin/api/config/ad/schema`，与引擎用的是同一份常量） |
| `recharge` | 模拟充值档位 | **已退役（改它不生效）** | 付费面随变现改造下线，档位行仍在库里（不能改已应用的迁移），只是引擎不再读它；重新启用内购要先办版号 |


### 跨切面开发辅助（仓库根）
```
tools/export-seed.mjs                             把 frontend/js/data 导出为 Flyway 种子迁移（V2/V3）
tools/backup.sh                                   整库快照（专用备份账号 + gzip 完整性校验 + 按天保留）
tools/restore.sh                                  从快照恢复：支持 --into 别的库做恢复演练，覆写原库要二次确认
tools/icon-preview.mjs                            把 js/icons.js 栅格化成 ASCII 人眼确认形状；有问题图形（越框/液体溢出/漏画）退出码非 0
test/icon-geom.js                                 图标几何裁判（path 解析 + 越框/未知图元/液体溢出判定），validate.js 与上面的预览工具共用
test/css-guard.js                                 CSS 旧引擎兜底裁判（`color-mix()` 只能待在 @supports 块内、`--mix-*` 令牌有定义且都被用、不许裸 `inset:`、dvh/min/max 前面要有同属性旧写法），validate.js 调它，android-check.sh 拿包内那份 CSS 再判一次
test/cloud-timeout.js                             客户端请求期限裁判（vm 载真实 cloud.js + 注入坏 fetch）：超时/迟到正文/reject 三者只有第一个把回调叫起来，且不误报成彼此；validate.js 调它
test/intent-coverage.js                           意图覆盖率裁判（H7）：一端从 `GameService.dispatch` 的 `switch (intent)` 静态枚举出协议面，另一端从 `test/e2e-api.sh` 读出"真被打过的意图"（助手调用 / curl 字面量 / 数据驱动表三种形状都认），两端对账；缺口只有两种出路——补进 e2e，或进白名单并给出**从服务端源码读出来的**理由（缺理由也红，白名单里出现其实已覆盖的意图同样红）。validate.js 调它
test/validate.js                                  数据一致性校验（读 frontend/js/data 内嵌基线 + 反应图完整性 + 仪器图标覆盖率与几何 + CSS 兜底 + 请求期限 + 意图覆盖率）
test/e2e-api.sh                                   端到端回归：游客→意图闭环→防作弊闸门→鉴权令牌治理→存档带号 CAS 与意图幂等→转正并档→后台账号与口令→静态托管与客户端弱网锚点→内容管理→**五段意图覆盖面补齐（签到/提示/社交/排行榜、取回/换容器/卖货/挂单/特惠、升级族/提纯/换台、每日任务/图鉴里程碑/商会订单、挑战/沙盒/引导/演示发奖/重置）**→本轮账号自清
test/admin-dist-check.sh                          把 admin/src 重建到 admin/.dist-check，先比文件清单再逐文件比哈希（随包后台产物不许落后于源码）
test/ci.sh                                        一键串起五层回归：数据一致性 → 后台产物同源 → 后端单测 → 端到端 API → 安卓包自检（可挂 CI 或提交前钩子）
docs/content-management-followups.md              后台内容管理本轮边界 + 仍写死项/架构缺口清单
docs/upgrade-plan.md                              安全/运维/性能/代码健康的升级方案与迭代进度（迭代 1~3 已完成；迭代 4 是 2026-10-06 全面复查后的提案，**P0 三批、P1 的 G1~G8、H1/H3/H4/H5、H6 全六项、G7、H7 与发布收尾均已落地**，落地记录在第 7~12 节，"待做"只剩 E 那一组产品向项）
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
| `/api/game/{intent}` | POST `params` | **唯一玩法入口**，返回 `{state,revision,result,events,bench?,sandbox}`。意图涵盖 `bench.place/takeBack/clear/temp/electrolysis/vessel/switch`、`react{multiplier,insured?}`、`challenge.*`（含 `revive`，只消耗看广告换来的次数）、`sandbox.*`、`market.buy/consumable/special/black/sell`、`listing.create`、`order.fulfill`、`sign`、`quiz.pickOne/answer`、`upgrade.vessel/equipment/tier/lab/room`、`refine`、`claim.ach/daily/milestone`、`hint`、`friend.visit/gift`、`leaderboard`、`ad.request/status/exchange/devGrant`、`settings`、`tutorial.step`、`reset`。旧的三条付费面 `shop.buy`、`shop.recharge`、`ad.bonus` 已关回绝（回绝也带原因文案，客户端不再有任何入口） |
| `/api/ad/callback` | POST (query/form/JSON) | **激励视频服务器回调（SSV），不带玩家 JWT**：广告网络播完后来这里，`pid/user_id/trans_id/extra/sign` 验签通过才置 `rewarded`；刻意挂在 `/api/game/**` 之外，避免被鉴权拦截器挡成 401 |
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
CHEMERA_AD_SECURITY_KEY="<TapADN 后台的安全密钥；留空则广告中心不签发工单>" \
CHEMERA_AD_SPACE_ID="<激励视频推广位 ID>" \
CHEMERA_TRUST_XFF=true \
java -jar target/chemera-server.jar
```
`application-prod.yml` 相对开发配置的差别只有"默认值不再宽容"：跨域默认完全不下发（同域部署无需 CORS，需分离域名时用 `CHEMERA_CORS_ORIGINS` 明确列源）、限流收紧、错误页不泄栈、`forward-headers-strategy=native`、`chemera.ad.dev-mode` 写死 `false`。启动时 `ProdHardening` 会做自检：**prod 下若仍用仓库内置 JWT 密钥、没给管理员口令、或把广告自证通道 `dev-mode` 开着，进程直接拒绝启动**（开发环境只打 WARN）；没配广告密钥不拦启动，只 WARN 并把 `/api/healthz` 的 `ad.ready` 报成 false。HTTPS 与反向代理仍需在 Nginx/Caddy 层配置，配置后把 `CHEMERA_TRUST_XFF` 打开才能让限流看到真实来源 IP。

日志落在 `logs/chemera.log`（按天 + 20MB 滚动，保留 30 天 / 500MB 上限），每行带 `[traceId]`，与响应头 `X-Request-Id` 一一对应；`GET /api/healthz` 可直接给容器编排或 Uptime 探测用。数据快照见上文「管理员账号、玩家口令与备份」。

## 管理员账号、玩家口令与备份

**后台账号（`/admin/api/admins`，仅 `super`）**：列表、建号、重置口令、改角色、停用/启用、删除，全部写 `audit_log`。三条底线由 `AdminAccountService` 兜住：

- 口令比玩家侧更严（≥8 位、不含 `admin123` 这类公开值、不等于账号名），新建与被重置的账号都带 `must_change_password`，**改密之前后台其余端点一律 403**（拦截器只放行 `/admin/api/me/password`，前端躲不过）。
- 角色与状态**以库为准**：每个后台请求都回查一次 `admin_user`，所以停用/降级对已签发的 2 小时令牌立刻生效（这一点玩家侧还做不到，见「已知边界」）。
- 不能自锁：停用、降级、删除都会挡住"最后一个在岗超管"，也不允许对自己下手。

后台 SPA 里对应【管理员账号】页（`super` 才看得到菜单），顶栏有【修改口令】；改密后强制重新登录，因为无状态令牌无从撤销。

**玩家忘记密码**：本作没有邮件服务可自助验证身份，所以找回路径是人工的——登录页【忘记密码？】会说明怎么找管理员，管理员在【用户管理】按用户名定位后点【重置口令】给一个临时口令。重置会作废该玩家**全部刷新令牌**（旧口令立即失效），已打开的页面最长还能用到访问令牌自然过期（≤2 小时）。游客档没有口令，接口直接拒绝重置，避免凭空造出一个可登录的正式账号；列表里的「类型」列可区分游客档。

**调整玩家资产（`POST /admin/api/users/assets`，仅 `super`）**：金币和钻石不是 `app_user` 的列，而是 `user_save.payload` 这块 JSON 里的字段，所以【用户管理】列表用 `JSON_EXTRACT` 把余额直接带出来（没有存档的游客显示 `-`），【调整资产】弹窗改的也是这块 JSON。`PlayerAssetService` 有三道闸门：填的是**增减量**而非目标值（避免运营在两个界面来回抄数字时把玩家余额覆盖成别人的）、单次不超过 1000 万且结果不得为负或越过余额上限（金币 10 亿 / 钻石 100 万，量级沿用原最高充值档）、没有云端存档的玩家直接拒绝而不是凭空建一档。它只改 payload 里点名的字段（用 `JsonNode` 原地改，不走 `GameState` 反序列化，否则 `ignoreUnknown` 会静默丢掉客户端遗留字段），并且**每个请求都回库读档**的玩家侧会立刻看到新余额——`PUT /api/save` 早已 404，客户端无从把自己的旧数字盖回来。
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
cd server && mvn clean package        # 546 个 JUnit：领域模型/内容注册表/引擎/经济/意图层/内容校验/配置说明书/广告目录写入守卫/全量合成/令牌轮换/限流/上线自检/后台账号 CRUD/玩家资产调整/激励视频工单/防沉迷时段/合规目录/TapTap 票据验签/会话活体检查/存档迁移与版本位/存档带号 CAS 与并发意图/广告奖励随作废帧退回/历史抽样与修剪锚点/**意图幂等窗口（同 `(uid,sid,seq)` 只结算一次且逐字相同 / stale 与只读不进窗口 / 换 sid 可复用同号 / 配额淘汰不吞请求）**/内容分片报告/内容历史与条件写/后台乐观锁与分页/内存现场上界与闲置清扫/删号时的内存回收/坏内容行可见化/日报分步容错/敏感词 last-known-good/**内容缓存到底替这条意图干了多少活（温缓存不涨计数 / 版本号真读库算脏样本 / 改内容仍算涨）**/**意图成本表的口径（脏样本不进最小值·最大值·中位数三个数；预算判中位数；被 401/403/429 挡回去的那次不许成为"最省的那条"）+ HTTP 层鉴权与状态码
cd server && mvn spring-boot:run      # 或 java -jar target/chemera-server.jar；**跑 e2e 前后端必须带 dev profile**（`--spring.profiles.active=mysql,dev`），只给 `mysql` 会得到"演示票据已关闭"与"本机没有空闲且可演示发放的广告位"两类红——`dev-mode` 默认 false

bash test/ci.sh                       # 一键全量回归五层：前端数据一致性 → 后台随包产物与源码同源 → 后端单测 → 端到端 API（后端没起就跳过）→ APK 自检（本机产出过包才跑）
bash test/android-check.sh android/app/build/outputs/apk/debug/app-debug.apk   # 单独验一个包
node test/validate.js                 # 前端静态裁判：内嵌数据一致性（118 元素 / 143 反应 / 引用完整性）+ 图标几何 + CSS 旧引擎兜底 + 请求期限回调
bash test/e2e-api.sh                  # 端到端回归（需后端已启动）
```

**安卓壳**（Capacitor 8，工程在 `android/`，`webDir` 直指 `frontend/`——零构建，所以"打包"就是把目录拷进 `assets/public`）：

```bash
cd android && npx cap sync android       # 改了前端必须重跑这句，否则包里是旧 JS
./gradlew assembleDebug                  # debug 包；release 用 keystore/ 那份，口令走环境变量
bash test/android-check.sh android/app/build/outputs/apk/debug/app-debug.apk
```

- **`./gradlew` 要 JDK 21+，别把 `JAVA_HOME` 指到 17**：`capacitor-android` 模块按 release 21 编译，指 17 会在 `:capacitor-android:compileDebugJavaWithJavac` 挂在一句"无效的目标发行版：21"上（Windows 控制台还会把它显示成乱码）。后端与壳现在**都是** Java 21（`server/pom.xml` 写 `<java.version>21</java.version>` 与 `maven.compiler.release=21`），所以 CI/本机一套 21 就够；这条以前叫"两边不是一回事"，是因为工作流还装着 JDK 17 —— 那条不一致已经在迭代 4 的 CI 首跑里红过并改正了。
- **gradle 报"Could not read workspace metadata"别急着删缓存**：这台机器上遇到过删了 `~/.gradle/caches` 里的 `scripts`/`transforms` 仍一路复现的情形，真凶是一个**活着但状态坏掉的 gradle daemon**。`./gradlew --stop`（或直接把那个 java 进程杀掉）之后 `./gradlew --no-daemon assembleDebug` 就过了；`--no-daemon` 顺便保证下一次不会再被同一个坏 daemon 绊住。
- 版本号同源是这里最容易漏的一环：`/js/*` 走强缓存一年，APK 里的 `www` 又是仓库的拷贝，所以 `android-check.sh` 专门比"壳内 `?v=` == 仓库 `?v=`"，`test/ci.sh` 第 5 层会因此红。

**CI**：`.github/workflows/ci.yml` 在 Linux runner 上起 MySQL 8，口令每次作业现生成并 `::add-mask::`（仓库里不落任何字面量，也不建 `server/.env`），建库走仓库里那份 `server/db/bootstrap.sql`（与本地同一条初始化路径），Flyway 播种后跑 `bash test/ci.sh`。`ci.sh` 默认 `mvn -o`（本机 .m2 已满，省几分钟），CI 传 `MVN_FLAGS=` 走在线解析。放 Linux 不是偏好问题：Windows 控制台会把 curl 参数里的中文按 GBK 送出去，同一份 e2e 会假红。`ci.sh` 开了 `pipefail`——每层都是 `cmd | grep | tail`，不开的话退出码是 `tail` 的，mvn 编译失败也照样打"全部通过"。

迭代 4 给这条流水线补了三件事，每件都对应一次真实事故：**① `mvn package` 前先 `working-directory: admin` 跑 `npm ci && npm run build`，再拿 `git add -A -- <随包目录>` + `git diff --cached --quiet` 断"随包后台产物已入库"**——本项目定的是"后台产物入库、jar 直出"，可 CI 从来不重建它，于是干净检出的 jar 里【运营配置】是防沉迷之前的老表单，而本地那几条读工作区文件的断言永远绿。**② 后端起在 `SPRING_PROFILES_ACTIVE=mysql,dev`**：广告与 TapTap 的自证通道 `dev-mode` 默认已经翻成 `false`，只有 `dev` profile 打得开（见「上线自检」一节），漏掉 profile 的实例就是公网提款机。**③ 第 4 层不再 `| tail -8`**：e2e 全文落到 `server/logs/e2e-latest.log`，终端把每一行 `✗` 原样打出来。这条改动的直接价值是下一轮那个 MySQL 死锁能当场定位，而不是重跑碰运气。本地跑不了后台构建时可以 `ADMIN_CHECK=skip bash test/ci.sh` 跳第 2 层（其余四层照跑）。

这三件事补完，流水线**第一次真被跑起来就红了两回**，两回都是"本地永远绿、干净 runner 才暴露"那一类：工作流里 `actions/setup-java` 还写着 JDK 17，而 `pom.xml` 早就是 `release 21`，红在编译第一句；口令生成器 `openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | cut -c1-20` 截出 20 字符，而 `JwtService` 构造里 `< 32 字节` 直接抛，红成"后端 2 分钟没就绪"、真相只在日志尾部那一行。现在 JDK 顶到 21，生成器改 `gen(n)` 走 hex（没有 `+/` 与换行的风险），登录口令 20 字符、JWT 密钥单独 48 字符。**读自己那条流水线的日志时记住：`build failed` 的红色汇总行永远在真因之后，往上翻到第一句非 WARN 的报错。**

第三回红的是**时钟**，也是这一节里唯一一条会伤到真实玩家的东西。工作流给 job 设了 `TZ: Asia/Shanghai`，JVM 因此在 +08:00，而 `docker run mysql:8.0` 那只服务容器没带时区参数、跑的是 UTC；当时 `user_session` 的 `rotated_at`/`revoked_at` 由 SQL 的 `NOW()` 盖（库钟），`AuthService.refresh` 拿 `LocalDateTime.now()`（进程钟）判"是否超出 15 秒轮换宽限期"——差 8 小时，于是每次刷新都被算成"令牌泄露"并整户下线，e2e 里 51 条红、第一条就是"宽限期内的并发刷新仍可用"。修法写死成一句规矩：**谁判断，谁的时钟写**。会话生命周期五条 SQL、`analytics_event.day`、`last_login_at`、广告工单的 `issued_at` 与那几个按天窗口现在全收调用方递进来的时间（`#{now}`/`#{day}`/`#{since}`）；只有两处合法的库钟残留登记在 `MapperClockHygieneTest.SANCTIONED` 里并要求附理由（`rewarded_at` 是取证戳、`report.created_at >= CURDATE()` 是同列同钟），新增第三处就红。CI 里那只容器同时被拨到 `--default-time-zone=+08:00` 并在就绪后自验一次——这是 belt-and-braces 而不是修法：DDL 上 `DEFAULT CURRENT_TIMESTAMP` 的那些列（`app_user.created_at` 等）仍然由库钟盖，而看板拿 Java 的当天去比它们，所以**部署契约里"MySQL 的时区必须等于应用声明的时区"这一条是硬要求**（`serverTimezone` 只影响驱动怎么解释列值，不会把 `NOW()` 拉回来）。想在本机搬出这个形状不必动用容器：`java -Duser.timezone=Asia/Kamchatka -jar …`（JVM 比库快 4 小时，即 CI 那个致命方向）跑一轮 e2e，仍是 `PASS=633 / FAIL=0`。

- `FullSynthesisTest` 逐条驱动 Java 引擎，证明 143 条反应与 3 条工艺都能按其声明条件正常合成 —— 取代了原先驱动本地 JS 引擎的 `test/smoke.js` 与 `test/full-synthesis.js`（两者已移入 `legacy/removed/`）。
- `e2e-api.sh` 额外校验：无 token 取帧 401、未知意图被拒、温度/电解设备闸门、答题防透题与一次一题、付费面三条意图（`shop.buy` / `shop.recharge` / `ad.bonus`）一律回绝、偏好持久化、游客转正并档、`PUT /api/save` 已 404、首页静态托管与离线壳清理、登录门（`js/gate.js` 随包发布 + 首页含 `#gate-root` 与忘记密码指引 + `game.js` 未登录先过门）、注册可省昵称与弱密码被拒、**令牌轮换（用一张废一张 / 宽限期内并发刷新可用 / 退出后硬撤销绝不补发 / 陌生令牌 401）**、**鉴权防爆破（连续失败转 429、锁定按账号隔离、游客档配额内可建）**、**缓存与运维面（版本化脚本 immutable、入口文档 no-cache、`X-Request-Id`、`/api/healthz` 报库连通）**；管理侧另有一组（schema 覆盖 13 类、options 候选、内容体检 0 问题覆盖 458 行、`strict` 非法写入被拒且不留脏行、`strict=false` 后门可写）、**后台账号 CRUD（建号响应不含哈希、弱口令被拒、首登 mustChange、锁定期内只放行自助改密、viewer 管不了账号、停用后旧令牌立刻失效、最后一个在岗超管动不了、测完自动清理）**、**玩家口令重置（按用户名定位、旧刷新令牌整户作废、旧口令立即 401、游客档拒绝重置）**、**运营配置说明书（`/admin/api/config/spec` 覆盖 14 个键、每条带中文名/作用/风险/出处/生效范围、`sell_rate` 标服务端结算而 `start_coins` 老实标仅前端、`recharge` 老实标已退役、库里的键必须全部收进说明书、自定义键确认不在说明书里）**、**玩家资产调整（列表带出 payload 里的余额、加金币后玩家下一次取帧即生效、只调一项不清零另一项、存档历史标为 `admin`、审计明细带前后值、扣穿/超单次上限/零增减/非整数各被拒、玩家令牌与无令牌 401）**。当前 PASS=633 / FAIL=0。断言本身不污染后续运行（限流段、建号段、资产段与临时配置键都自清理，守卫段写坏后按 V7 种子字节级还原），**本轮建的每一个测试号在收尾一段里逐个 `DELETE /admin/api/users` 收回**（逐表清干净后再确认列表与存档都查无此人，并断言 `app_user` 的总数不涨——回归不该一轮一轮给后台攒测试号，这条同时就是 G6 的验收），**顺带钉一条"被删那位手上的令牌当场失效"**（会话随行撤销）。但**注册与游客建档是按 IP 的时间窗限流**（`chemera.guard.register-max: 20`/小时、`guest-max: 40`/小时），同一窗口内连跑多轮会在第一句 register 上撞 429——脚本现在会直接给出这句诊断并以 `exit 2` 退出，而不是让后面几十条断言级联成红；等窗口重置或重启后端（计数在内存里，重启即清零）即可再跑。变现这轮新增三组：`激励视频` 把「`ad.request` 签发并定格数量 → 假签名回调必拒且余额不动 → 用 `.env` 口令按运营模板算真签名 → 回调受理 → 同 `trans_id` 重复回调幂等 → 下一次取帧精确入账 → 整帧带 `type:ad` 事件 → 同位连点被冷却拦住 → `ad.status` 下发 6 位 4 兑换项 → 积分不足给出'还差 N'」串成一条链；`广告目录写入守卫` 对着真后台接口试 7 种会静默不发奖的配置（reward 拼错 / 缺 reward / 冷却越界 / kind 或 id 重复 / ticketTtlSec 低于引擎回落线 / 计数类数量填 0 / skin 兑换项没给合法皮肤），逐条确认被拒且**库里原值一字未改**，再写一份合法空目录、按 V7 种子恢复并做字节比对（守卫测试不留脏配置），最后确认 `/admin/api/config/ad/schema` 与后台打包产物里确实有这套类型化表单（"添加广告位""积分兑换"能在托管出来的 js 里搜到）；请求体一律走 stdin——这台机器的控制台会把 argv 里的中文按 GBK 送出去，服务端只会回"请求体无法解析"；`广告中心接线与付费面残留清理` 校验 `js/ads.js` 随包发布且 `<script>` 排在 `ui.js` 之前、`U.watchAd` 与原生 `showRewardVideo` 契约就位、把托管出来的 JS/HTML **剥掉注释后**搜 `充值` / `simAd` / `shop.recharge` / `buyDiamondItem` / `¥数字` 全部为空、设置页分段已由"商店"改名"广告"且 `drawStore` 不复存在（注释里留"充值已下线"的历史说明是允许的，代码里再出现就是真入口）。编辑/只读角色拿超管令牌调资产被拒这类**角色以库为准**的分支由 `AdminUserAssetsHttpTest` 在 HTTP 层钉住，e2e 只跑真实存在的超管与玩家两种令牌。另有一组**客户端页面结构**断言守着整页化改造：首页不再有 `#sheet`、`#stage`/`#page-body` 就位、底部导航 7 个 `data-tab`（含 `bench` 本身是一页）、实验台自带 `#quick-shelf`、`style.css` 里 `#sheet` 清零且改由 `.page.on` 驱动、`panels.js` 是 `P.go(tab)` 而非 `openTab`、回实验台会 `CHEM.fx.resize`、`ui.js` 有 `renderQuickShelf` 与 `pointercancel`/`isDragging` 守卫，以及全站 `?v=` 唯一。另有一组**容器图标**断言守着 emoji 错配不再回归：`js/icons.js` 随包发布且 `<script>` 排在 `ui.js` 之前、首页有 `#vessel-icon` 且 `#vessel-emoji`/`#liquid-layer` 清零、`ui.js` 里既没有实验服那个 emoji 码点也没有残留的 `VESSEL_EMOJI` 表、台面与【建设】仪器商店确实改调 `CHEM.icon`、`style.css` 里 `.v-ic.wet` 与 `--ic-liq` 就位（液体画在容器内并随皮肤走）。还有一组**页面信息架构**断言守着这轮归位不回退：外壳有 `#page-segs`、6 个二级页各一枚 `.nav-badge`（实验台不留）、`panels.js` 由 `PAGES` 注册表描述页面、段点数字与角标同源于 `claimCounts`/`buyableCount` 且 `U.updateNav()` 进了 `G.refresh()`、签到在任务页（`#t-sign`）而设置页只剩指路按钮、商会订单卡片跟着 `d.orders.list`、提纯工坊在物质页、临时工作台不分段、市场恰好 6 段、四类长列表都有搜索框、方程式搜索带"未发现清单不跟着过滤"的说明、六类新组件样式齐全、关于页版本号取自 `?v=`。写这组断言时留了个坑位提醒：**本机 grep 在 `C.UTF-8` 下匹配不了 4 字节 emoji**（星平面字符会静默返回 0 命中），断言只能用中文词或结构锚点。
- 迭代 3 又加了三组。**启动链路与断线重连**（静态锚点，跟着托管出来的 js 走）：`content` 与 `me` 并发、只有只读意图（`state`/`ad.status`/`leaderboard`）配自动重放、写意图没回音改为拉权威帧对齐、退避节奏与定时器收口、启动失败自己按 1.5s→3s→6s→12s→24s→30s 重试且有 `.boot-auto` 样式，整页重载只允许留在两处有意的地方（退出壳兜底 + 换身份重进）——这条一开始写成"数出现次数 ≤1"，结果把两处合法重载也算成失败，改成"剥掉这两处后必须为空"才真正钉住意图。**内容体积**：`/api/content/shards` 报每类字节数（降序、`config` 自算一片、明细之和必须等于总数），并对 raw <220KB、gzip 必小于 raw 且 <80KB 设上限；这是留给下一次的尺子，本轮的实测结论是"按类型拆片只省 ~6%，先做命中缓存就别等网络"。**存档结构版本位**：权威帧与落库 payload 都带 `sv` 且两处同源、后台改资产不会把版本位写没、回滚到最早一版仍能载入并进游戏、回滚到不存在的版本回 404 而不是把存档写成空。会话侧的旧刻画性断言（"已签发的访问令牌在过期前仍有效"）已按 A6 翻反：重置口令后旧访问令牌立即失效、封禁当刻即踢线、解封后重登是新 `sid` 而不是把旧令牌救活。
- 迭代 4 的 P0 两批（存档写回 + 连点 + 提审四处 + 低端 CSS + 演示通道 + 后台产物）加了这些。**存档写回**：只读意图（`state`/`ad.status`/`leaderboard`/`quiz.pickOne`）连着发三轮，`data.revision` 纹丝不动；一个写意图恰好 +1；**同一账号四笔并发购买**必须有下落——`成交数 + 撞号 stale 数 == 4`、被静默吞掉的那笔数为 0、金币只按成交回执扣、`filterpaper` 到货件数 == 成交笔数（这条第一次跑就抓出一个 MySQL S→X 锁升级死锁，症状是四笔里一笔 500，见 `docs/upgrade-plan.md` 迭代 4 第 7 节）。**广告奖励不随作废帧丢**：抢到结算权却没落盘的那一帧会把工单退回 `rewarded`，下一次取帧补发，而"退回"只动 `settled` 行，所以一张券永远只发一次。**连点**：`G.call` 这个唯一咽喉在 capture 阶段记发起按钮、飞行中 `disabled` + `.busy`、20 秒兜底解锁，e2e 打五条静态锚点，另加一条**正向刻画断言**把边界钉住：同一份 `params` 连发两次 `market.consumable` 就是真的执行两次（金币扣两笔、`filterpaper` +2）——服务端从不按"看着一样"去重；注释里写死了"连点锁 ≠ 并发安全"——跨设备／链路重发仍会执行两次，那是 F2 的 `seq` 该管的，上面那组四笔并发与这条重复执行断言就是这条边界的裁判，F2 落地之后两条都必须仍然绿。**低端 WebView**：`node test/validate.js` 里新增 `test/css-guard.js` 四条裁判（`color-mix()` 只能待在单个 `@supports` 块内、用到的 `--mix-*` 必须有定义且不许留未用定义、不许裸 `inset:`、`100dvh`/`min()`/`max()` 的函数值前面必须有同属性旧写法），`android-check.sh` 拿**APK 里那份 CSS** 再判一次——仓库绿、包里是旧的，一样红。**提审面**：`android-check.sh` 从 29 条扩到 42 条（release 合并清单不许有 `android:debuggable=true`、`exported=true` 与 LAUNCHER 数量收口、包内 `capacitor.config.json` 的 `webContentsDebuggingEnabled`/`server.url`/`androidScheme` 三态、`versionName` == 壳内 `?v=`、包内 CSS 兜底、`tapadn` provider 的 classdef 与 `NO_CONSENT` 字符串、`tapsdk-stub` 桩不许漏进产物）。**演示通道**：`dev-mode` 默认 false，`ProdHardening` 对"profile 既不是 prod 也没有 dev 却开着 dev-mode"直接拒启；广告回调先过 `RateGuard.checkAdCallback(ip)` 再比 `chemera.ad.callback-allow-ips`，**没配验签口令一律拒**（e2e 有一条专门盯这个，且 GET 形态也验）。**后台产物**：`test/admin-dist-check.sh` 把 `admin/src` 重建到 `admin/.dist-check`，先比文件清单再逐文件比哈希，红过一次的场景是"源码改了、随包 js 没重出"。

- 迭代 4 的 P0 **第三批（F2 幂等 + H2 弱网收口）**加的这些。**意图幂等**：客户端每笔意图在 `G.call` 里领一个**会话内**单调 `seq`（重发用同一个号，换一笔才换号），服务端 `IntentDedupe` 按 **`(uid, sid, seq)`** 认"这一句我执行过了"，第二次交付一个字都不写、直接退回第一次那一帧（缓存的是序列化后的字符串，所以两次回执**逐字节相同**）。e2e 那一组七条断言判的就是这四件事：同 seq 只到一件货只扣一笔、`revision` 不动、两份响应体字符串相等、不同 seq 是两笔真生意；另外两条盯边界——**重新登录换了 sid 之后复用同一个号必须真执行**（否则症状是"点了没反应"，比重复扣款难查），**seq 涨出窗口（默认每会话 8 条）之后笔笔照做**（淘汰只该退回 F2 之前的水平，绝不允许吞请求）。只读意图、`stale` 回执、不带 `seq` 的旧包三处刻意不去重。**弱网与换身份**：`cloud.js` 每个请求自带 `AbortController` + 12 秒期限（`C.timeoutMs` 是可调常量），回调用闩收成**恰好一次**，超时结果不带 `code`/`tag` 所以走的是 C4 已有的静默分支（读→退避重发、写→拉权威帧对齐）；`content.js` 的首装 `fetchBundle` 共用同一个期限（它挂着不回就等于启动遮罩永远转圈）；会话**纪元** `epoch` 随每个 job 记下，落地前先比一次，上一个账号的迟到回包既不画当前界面也不回调；`stopForNewIdentity()` 把退避、对齐、挂单轮询、广告等待递归、防沉迷倒计时一起停（断网/重连那条路**不**调它，那里合法地保留轮询）；`ads.js` 的 `awaitReward` 可取消**且必交代**——被顶替的那一路也回调用方一次，否则复活／双倍券按钮会永远卡在禁用态。回归新加一层 `test/cloud-timeout.js`（挂在 `node test/validate.js` 第 1 层）：`vm` 载真实的 `cloud.js`，注入六种坏 `fetch`（挂死／可中断／迟到正文／非 JSON／解析失败／直接 reject），把期限压到 30ms 判"回调恰好来一次、而且不是误报成超时"；把 `raw()` 换回改动前那版重跑，它报 15 条错，所以这层不是空转。

- 迭代 4 的**收尾批（G7 尾巴 + 健壮性小债 + H6 ④⑤⑥ + H7 + CI 首跑 + 发布）**加的这些，细节与判断在 `docs/upgrade-plan.md` 第 11、12 节。**意图覆盖面从此是一个数**：`test/intent-coverage.js`（挂在 `node test/validate.js` 第 1 层）一端从 `GameService.actOnce` 里那唯一一处 `switch (intent)` 静态枚举出 47 条协议面意图，另一端从 `test/e2e-api.sh` 认三种调用形状（`act` 直调 / `"intent|payload"` 数据驱动表 / 任意 helper 间接调用，helper 名不写死），当前 **45/47 = 95.7%**；缺口要么补进 e2e，要么进白名单并附**从服务端源码推得出的理由**（缺理由红、白名单里放着其实已覆盖的意图也红），现在两条白名单是 `challenge.revive` 与 `market.black`。**那条 SQL 预算尺子被重造过一次**（这是本轮最值钱的一课）：`单次意图 ≤6 条 SQL` 原来读 `minSql`，结果它量的是**排期**而不是链路——脏样本有三种形状，① 后台改过一次内容 → 紧跟的那条意图多跑 `content_item` + `app_config` 把包重攒出来，② `ContentService` 那份 1 秒版本号缓存到期 → 多一条 `SELECT version FROM content_version`（样本数只有 1 的意图因此会在 6 与 7 之间来回跳），③ 被 401/403/429 挡回去的那次只走到会话校验就出去，最小值被读成 **1**，于是那条预算永远量不到东西（尺子空转比尺子严更害人）。现在服务端把"这次替内容缓存干了活"整个标出来（`ContentRegistry.cacheWork()` = 重建次数 + 版本号真读库次数，拦截器在前后各读一次；再加 `res.getStatus() >= 400`），预算判**干净样本的中位数 `p50Sql`**（32 格环），上限按意图给（`ad.request` = 7，其余 6——那个 7 的唯一来源是 `AdService.request` 每次都发一条全表 `expireStale` UPDATE，挪它之前必须先给 `live()` 补时间谓词），`minSql`/`maxCleanSql` 留给人看形状、`avgSql` 是含脏样本口径只报总成本，另有一条 `本轮至少 8 条意图有干净样本` 钉的是尺子自己不许空转。**回归不再给后台攒测试号**：e2e 收尾一段先记 `app_user` 总数基线、逐令牌反查用户名 → uid、`DELETE /admin/api/users` 清干净、回读总数并断言"被删那位手上的令牌当场 401"。新增 `POST /api/auth/pass` 那 12 条断言**单开一个号**走，因为成功那一步 `sessions.revokeAll` 会撤销整户登录态。后台侧：`admin/src/busy.js` 一处收口防连点（`run(key, fn)` 同 key 复用未结束的那次，key 带行标识所以 A 行不会灰掉 B 行）+ echarts/Element Plus 按需（入口 js 1,114,326B → 237,308B）+ 【发布】按钮等死逻辑收口。**两个 bash/Jackson 侧的坑值得单独记**：`node -e '…' "-2"` 会被 node 当 CLI 旗标（`bad option: -2`），在 e2e 里的表现是"一条健康的链被判预算超支"，所以差值判断整个放 node 里算、bash 只接 `over|in` 字符串；`spring.jackson.default-property-inclusion: non_null` 会把值为 null 的字段**整个抹掉**，所以"新列存在性"这类判据只能对**有干净样本的行**判。

## 已知边界

- 断网即不可玩（这是"在线游戏"的定义）；页面会停在启动门并提示启动后端。
- 挑战/沙盒的临时现场存在每 uid 的内存 `EngineCtx`，多实例部署需会话粘滞或把它外置（当前为单实例设计）。复查补的那一条已经落地：`GameService.sessions` 与防沉迷的 `CurfewGuard.minor` 缓存**现在都有上界**——`LinkedHashMap` + LRU 逐出 + 十分钟一趟的闲置清扫，参数在 `application.yml` 的 `chemera.game.*` / `chemera.curfew.cache-max-entries`，删号也接上了同一份内存回收（见 `docs/upgrade-plan.md` 迭代 4 第 11 节 G7）。鉴权限流那张 `RateGuard` 走的是另一条线：窗口与锁定期都会自然过期，`purge()` 十分钟摘一次空 key（`RateGuard.java:201-208`），占用量由运维面板的 `trackedKeys()` 看得见——它的 key 是 `IP + 种类`，换 IP 就是换一格，所以只有 TTL 清扫、没有叠 LRU 上界，这是"挡刷子"而不是"记账"的表，留作扩容前的已知边界。计数仍是进程内、重启清零，与"单实例"这个前提一起成立。
- 排行榜是"全体玩家收集进度"的服务端计算快照，按天缓存于客户端。
- 意图幂等窗口是**进程内**的（`IntentDedupe`，每会话最近 8 条 / 全局 400 个玩家，LRU 淘汰）：同一实例上的重发只结算一次，**多实例扩容时各认各的**，横向扩容前必须把它与 `EngineCtx`、`RateGuard` 一起外置或做会话粘滞（同一个边界，见 G7）。另外它只覆盖"发了 `seq` 的客户端"——**旧安装包不发 `seq`，行为与 F2 之前完全一致**（不做任何猜测式去重），所以这条防线要等玩家更新到新版才生效，靠 `/api/app/version` 的版本门推上去。
- 鉴权限流与刷新令牌宽限都是**单实例内存态**：横向扩容时每台各算各的额度（重启即清零）。要么保持单实例，要么把它外置（与 `EngineCtx` 会话外置同一批做）。
- 后台内容管理尚不能覆盖引擎写死项（成就判定条件、等级曲线、事故常数），也没有内容版本历史——详见 `docs/content-management-followups.md`。后台账号本身已可在【管理员账号】页增删改查，prod 带着 `admin123` 这类公开口令会拒绝启动。
- **访问令牌已绑定会话**（A6，迭代 3）：玩家访问令牌带 `sid`（`user_session` 行主键），`AuthInterceptor` 每请求查一次会话行，所以封禁、改密、重置口令**当场踢线**，不再有 ≤2h 的空窗；代价是这套上线那一刻历史令牌全失效，玩家需重登一次。这条 `sid` 现在还是**两处判断的依据**：存档写回带它做乐观锁的号（F1：撞号就重读重放，不再后写覆盖前写），意图幂等把它放进键里（F2：`(uid, sid, seq)`）——因为客户端的 `seq` 是会话内自增的，重登后又从 1 数起，只按 uid 认会把重登后的第一句意图当成"已经执行过"而**静默不执行**。
- **激励视频的签名口径未经真实平台验证**：Dirichlet 文档只写"trans_id 结合安全密钥做 SHA256"，没写死拼接顺序与大小写，所以模板与大小写都做成了可配（`CHEMERA_AD_SIGN_TEMPLATE` / `_SIGN_HEX`）。本地回归跑的是同一条验签代码、自配口令，**上线前必须拿首笔真实回调比对一次**；回执格式同理（`_CALLBACK_ACK` 提供 json/text 两态）。原生播放桥 `window.ChemeraAd.showRewardVideo({spaceId, extra, rewardName, rewardAmount, userId, transId})` 的 Capacitor 插件已在 `android/app/src/main/java/com/chemera/game/ChemeraAdPlugin.java` 就位，缺的是 TapADN 的 `.aar` 与真实密钥（`app/libs/*.aar` 不在时插件明确回"未内置"，不假装播完）；浏览器里看到的是带"演示"标注的倒计时动画 + 服务端演示通道。
