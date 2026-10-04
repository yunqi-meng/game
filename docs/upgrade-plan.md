# 升级方案与落地进度（迭代 1、2、客户端整页化与仪器图标线稿已完成）

本文是"下一步做什么"的排期表；`docs/content-management-followups.md` 记的是**内容/引擎侧**仍写死的项，两份互补：本文管安全、运维、性能与代码健康，那份管玩法数据可编程性。

## 迭代 1：安全加固 + 缓存（✅ 已完成，2026-09-25）

| 项 | 落地 | 代码 | 回归 |
| --- | --- | --- | --- |
| A1 刷新令牌轮换 + 泄露处置 + 会话清理 | `refresh` 用一张废一张；`V5` 迁移区分 `revoked_at`（硬撤销，绝不补发）与 `rotated_at`（15s 宽限容忍多标签页并发）；超出宽限按泄露处理，整户 `revokeAll`；`SessionJanitor` 每日清理过期与超 7 天的作废行 | `AuthService.refresh` · `SessionMapper` · `SessionJanitor` · `V5__session_rotation.sql` | `AuthServiceRefreshTest` 10 例；e2e「令牌轮换与会话治理」4 例 |
| A2 鉴权端点防爆破 | 进程内固定窗口配额 + 连续失败锁定：登录按 `IP+用户名`（8 次失败锁 15 分钟）、注册/游客档按 IP 配额、后台登录独立更严档；429 走 `GlobalExceptionHandler` 映射；`X-Forwarded-For` 仅在可信反代后才采信 | `security/RateGuard` · `AuthController` · `AdminAuthController` | `RateGuardTest` 9 例；e2e「鉴权防爆破闸门」3 例 |
| A3 生产 profile | 新增 `application-prod.yml`（密钥不给就不启动、CORS 默认关、BCrypt 12、限流收紧、`forward-headers-strategy`、错误页不泄栈）+ `ProdHardening` 启动闸门：prod 下命中仓库默认密钥或没给管理员口令即拒绝启动，dev 只 WARN | `config/ProdHardening` · `application-prod.yml` | `ProdHardeningTest` 5 例 |
| B1 可观测性 | `logback-spring.xml`（控制台 + 按天/20MB 滚动、保留 30 天、上限 500MB，日志格式带 `[%X{rid}]`）+ `RequestTraceInterceptor`（traceId 进响应头 `X-Request-Id`、API 请求一行访问日志、>1s 记慢请求）+ `GET /api/healthz`（`SELECT 1` 探活，库不通返回 503，附带内容版本与在线会话数）。刻意不引 actuator/micrometer：本机 Maven 离线，且这些需求 60 行代码就够 | `security/RequestTraceInterceptor` · `controller/HealthController` · `logback-spring.xml` | e2e「缓存与运维面」traceId + healthz 2 例 |
| C1 静态资源缓存 | `/js` `/css` `/images` 与 `/admin/assets` 给 `max-age=31536000, public, immutable`（客户端资源靠 `?v=N` 换版、后台资源靠 Vite 内容哈希）；入口文档 `/`、`/index.html`、`/admin/` 给 `no-cache`，避免玩家被钉在旧版本。此前所有响应完全没有 `Cache-Control`，每次进游戏重下 ~182KB | `WebConfig.addResourceHandlers` · `RequestTraceInterceptor` | e2e 缓存头 2 例 |

顺带修掉的两个真问题：`JwtService.issue` 从不写 `uname` 声明，导致 `AuthContext.adminName()` 永远回落成 `"admin"`（多人后台的审计日志会分不清是谁）；`SessionMapper.findValidUserId` 在轮换改造后成为死代码，已删。

**迭代 1 验收基线**：JUnit 77（+23）、`test/e2e-api.sh` PASS=62（+11）、`node test/validate.js` PASS，且 e2e 可连续多轮跑（限流段不污染后续运行）。`bash test/ci.sh` 现在是一条命令跑完三层回归。

## 迭代 2：可信运维（✅ 已完成，2026-09-25）

| 项 | 落地 | 代码 | 回归 |
| --- | --- | --- | --- |
| A5 管理员账号 CRUD + 自助改密 | `admin_user` 从"有表无接口"补齐为超管专属 CRUD（建号/重置口令/改角色/停用/删除，全部进 `audit_log`，响应永不含哈希）。三条底线：口令强度比玩家侧更严且拒绝公开值；新建与被重置的账号带 `must_change_password`，改密前拦截器只放行 `/admin/api/me/password`（前端躲不过）；停用/降级/删除都挡住"最后一个在岗超管"与对自己下手。`AdminInterceptor` 改为**每请求回查账号表**，角色与状态以库为准——2 小时无状态令牌不再成为降级的空窗。`V6__admin_accounts.sql` 加 `must_change_password` + `updated_at`。SPA 侧新增【管理员账号】页与强制改密弹窗，顶栏【修改口令】改密后强制重登 | `AdminAccountService` · `AdminAccountController` · `AdminInterceptor` · `AdminSupport.requireSuper` · `AdminAuthController` · `V6__admin_accounts.sql` · `admin/src/views/Admins.vue` | `AdminAccountServiceTest` 13 例；`AdminAccountHttpTest` 12 例（401/玩家令牌越界/角色以库为准/停用即时生效/改密闸门/最后一超管/429）；e2e「管理员账号 CRUD」12 例 |
| B2 备份与恢复 | `tools/backup.sh`：整库 `mysqldump --single-transaction` + gzip，逐份验 gzip 完整性与结尾 `Dump completed` 才计成功，按 `KEEP_DAYS` 滚动清理；用**专用备份账号** `chem_bak`（`bootstrap.sql` 建，含 `FLUSH_TABLES`——MySQL 9.5 的 `--single-transaction` 必然发 `FLUSH TABLES`，这个权限不该给应用账号，9.5 也早已删掉 `--locking-mode`）。`tools/restore.sh`：支持 `--into <库>` 恢复到别的库做**恢复演练**、`--root` 建库、覆写原库要输入库名二次确认，改库名用整行重写而非 `s///`（快照里的 `/*!40100 ...*/` 会让斜杠分隔符炸掉）。口令全部来自 `server/.env`。已做真演练：88KB 快照 → `chemera_drill` → 7 张表行数与原库逐项一致 → 删库 | `tools/backup.sh` · `tools/restore.sh` · `server/db/bootstrap.sql` · `.gitignore`（`/backups/`） | 手工恢复演练（脚本不可自动化的部分已按上文验完并记入 README） |
| B4 HTTP 层集成测试 | 新增不启 Spring 上下文、不连库的 standalone MockMvc 一层，把拦截器 → 角色裁决 → 控制器 → 异常映射的分支钉在单测里（此前这些只被活服务上的 e2e 覆盖）。429 一例特意用不带拦截器的 mvc 并把 `guard.checkAdminLogin` 打桩——MockMvc 里没有真实 socket，`ipOf` 返回 null | `web/AdminAccountHttpTest` · `web/AdminUserResetPasswordHttpTest` | 17 例（另有 C5 的 5 例） |
| C5 忘记密码 | 没有邮件服务可自助验证身份，所以走人工：登录页【忘记密码？】展开说明找回路径与游客档的差别（此前这是一条死路——忘了只能注销重玩，而注销是物理删除）；后台【用户管理】按用户名定位后【重置口令】，仅 `super` 可用并写审计。游客档直接拒绝（它没有口令，硬写等于凭空造出可登录账号），列表新增「类型」列区分。`ProdHardening` 同时补一刀：prod 给了 `CHEMERA_SEED_ADMIN_PASS` 但值是 `admin123` 这类公开口令也拒绝启动 | `AuthService.adminResetPassword` · `AdminUserController./reset-password` · `UserMapper.page` 带 `is_guest` · `frontend/index.html#gate-help` · `gate.js` · `Admins/Users.vue` | `AuthServiceRefreshTest` +4 例；`AdminUserResetPasswordHttpTest` 5 例；e2e「忘记密码」8 例 + 登录门指引 1 例 |
| 配置作用说明书 | 运营配置面板原先只有键名 + `remark`，运营改一个键之前无法知道它到底影不影响结算——`start_coins` 就是例子：改它一分钱都不会变，服务端新建档的 5000 写死在引擎里。新增 `ConfigSpec`（13 个键，每项带中文名/作用/风险/代码出处/**生效范围**四档：服务端结算、服务端+前端、部分生效、仅前端），说明写在 Java 里而不是库里，因为它的唯一价值就是"和引擎实际行为一致"。面板改为说明书驱动：列表按生效范围上色并给一句话作用（悬停看全文+当前值+出处），编辑弹窗顶部提示条直说这项改了会发生什么、哪部分其实写死；库里出现说明书没有的键就标「未收录」并警告"不会参与任何结算" | `game/ConfigSpec` · `AdminConfigController./spec` · `admin/src/views/Config.vue` · README「运营配置速查（13 项）」 | `ConfigSpecTest` 4 例（说明书键集 == `Content.Config` 字段集 / 种子 SQL 每个键都有说明 / 文案非空可当运营话术 / 不起作用的键必须标出来）；e2e「运营配置说明书」7 例 |

**这一轮发现并如实留下的边界（→ 迭代 3 的 A6）**：玩家侧访问令牌不绑定会话，`sessions.revokeAll` 只能作废刷新令牌。所以**封禁、改密、重置口令**都有 ≤2h 的空窗——被盗设备还能把手上的访问令牌用完。后台侧这轮已经改成每请求回查，玩家侧要同样处理才闭合。e2e 里有一条**刻画性断言**明确钉住这个现状（`已知边界：已签发的访问令牌在过期前仍有效`），A6 落地时它会失败，正是我们想要的提醒。

顺带修掉的：`ProdHardening` 原来只查管理员口令"非空"，照抄 README 示例的 `admin123` 照样能上线；`requireSuper` 的提示语原先写死"仅超级管理员可管理后台账号"，现在也管着玩家口令重置，改成不误导的措辞；后台顶栏「退出」按钮绑到 `Layout.vue` 里一个从未定义的 `logout`（模板引用不在 setup 作用域，Vue 不报错也不生效），点下去什么都没发生——补上真正的 `auth.logout() + router.push("/login")`；后台令牌过期后的 401 处理只 `localStorage.removeItem`，Pinia 里的 `token` 还活着，路由守卫据此判定"仍登录"把 `/login` 弹回 `/dashboard`，运营于是停在一堆 `-` 的空白看板上出不去——改为调 store 的 `logout()`（内存与存储一起清）。这类"绑了个不存在的处理函数 / 只清了一半状态"的 bug，构建期和 e2e 都看不见，只有点一遍才发现，所以 D 项的前端 ESLint（`vue/no-undef-components` 一类规则）优先级要提高。

| 玩家资产调整（金币/钻石） | 后台此前只能**看**余额（开存档 JSON）和回滚，不能改。新增 `POST /admin/api/users/assets`：填增减量而非目标值（目标值等于让运营在两个界面之间抄数字时覆盖玩家）、单次 ≤1000 万且结果不得为负或越过余额上限（金币 10 亿 / 钻石 100 万，对齐最高充值档）、没有云端存档直接拒绝而不是凭空建档。改的是 `user_save.payload` 里点名的那两个字段——用 `JsonNode` 原地改，不走 `GameState` 反序列化（它 `ignoreUnknown`，读成对象再写回会静默丢客户端遗留字段）。留痕两道：存档历史 `source='admin'`（`SaveService.put` 为此多一个 `source` 参数，玩家侧【历史】也看得到）+ `audit_log.user.assets` 带前后值，【操作日志】新增「明细」列把它显示出来。权限与重置口令同档 `requireSuper`（这口能凭空造钱，编辑角色不该有路径），角色仍以库为准所以降级即断权。列表用 `JSON_EXTRACT` 直接带出余额列 | `service/PlayerAssetService` · `SaveService.put(…,source)` · `AdminUserController./assets` · `UserMapper.page` · `admin/src/views/Users.vue` · `Moderation.vue` | `PlayerAssetServiceTest` 10 例（含"不认识字段必须原样保留"与损坏存档拒绝触碰）；`AdminUserAssetsHttpTest` 7 例（401/玩家令牌/降权后旧超管令牌 403/审计带前后值）；e2e「后台调整玩家资产」17 例（含玩家下一次取帧即生效、只调一项不清零另一项、历史标 `admin`） |

**迭代 2 验收基线**：JUnit 133（+56，含配置说明书 4 例与玩家资产 17 例）、`test/e2e-api.sh` PASS=108（+46）、`node test/validate.js` PASS；`bash test/ci.sh` 一条命令跑完三层全绿，e2e 连跑三轮不互相污染（建号段、资产段与临时配置键都自带清理）。

## 客户端整页化：抽屉 → 底部导航各成一页（✅ 已完成，2026-09-26）

玩家反馈的交互问题：点底部图标是"向上弹一个 68% 高的抽屉"，实验台被压在后面，抽屉里的东西看一眼就得关回去。改成手机 App 那种形态——底部导航常驻，每个图标是完整的一整页。

| 项 | 落地 | 代码 | 回归 |
| --- | --- | --- | --- |
| 页面外壳与路由 | `#app` 竖排三段（顶栏 / `#stage` / `#bottom-nav`），`#stage` 内同层两页（`#bench` + 共用二级页 `#page`），`.page.on` 控显隐、`.page.anim` 靠"摘类 → 读 `offsetWidth` → 塞回"重播进场动画。`#sheet`/`#sheet-close`/`P.openTab` 整套删除，导航改 `P.go(tab)`（7 个 `data-tab`，`bench` 自己也是页签）。`#bench` 的样式不能再写 `display:flex`：id 选择器会盖过 `.page{display:none}`，实验台会永远压在别的页上面 | `frontend/index.html` · `css/style.css` · `js/panels.js` | e2e「客户端页面结构」11 例 |
| 投放源搬家 | 抽屉的价值就是"台面还露着，能边看边投"。整页化后把这个能力做进实验台页：`#quick-shelf` 横向物质架，沙盒/挑战/背包三态取数，超过 16 项给"更多 →"跳【物质】页；其余六页卡片退化为点击投放（`U.place`），背包页给"⚗️ 台面 N 种 · 回实验台"药丸（`#page-fab`） | `js/ui.js renderQuickShelf` · `js/panels.js syncFab` | 同上（`#quick-shelf` 存在 + `renderQuickShelf` 被调用） |
| 手势语义 | 一个 `U.bindItemDrag` 同时管点击与拖拽：位移 >12px 才算拖（跟手幽灵 + `#bench-stage.hot`），落点在台面内才投放。横向滑架子会被浏览器判成滚动并发 `pointercancel`，此时**不能投**，否则每滑一次多一份物质。`setPointerCapture` 对无效 `pointerId` 会抛，抛在 `pointerdown` 里等于整笔手势作废、`drag` 永久悬挂（`isDragging` 守卫会让物质架再也不刷新）——捕获包 try/catch，且新指针按下时先摘掉上一笔的监听 | `js/ui.js bindItemDrag/unbindDrag` | e2e `pointercancel` + `isDragging` 断言；浏览器实测三种手势各投/不投符合预期 |
| 回页副作用 | 页面在 `display:none` 期间 `#fx-canvas` 量出来是 0，回实验台必须重重量一次画布，否则粒子特效画不出来；`#page-body` 整帧重绘会丢滚动位置，按"同一页才保持 scrollTop"处理 | `js/panels.js P.go/P.render` | e2e `CHEM.fx.resize` 断言 + 实测 scrollTop 300→300 |

**顺带记一笔踩过的坑**：改完 JS 重新打包、重启、页面还在跑旧函数。原因是 `/js/*` 的资源头是 `max-age=31536000, immutable`，而我们沿用了同一个 `?v=3.4`——强缓存一年意味着**同版本号 = 同一份文件**，服务端换了包浏览器也不认。因此：动前端必须整站顶 `?v=`（本次 3.4 → 3.5），并新增一条"全站 `?v=` 唯一"的 e2e 断言防只改一半。历史 Service Worker 那层已经废掉了，不再是嫌疑面。

**验收基线**：`node test/validate.js` PASS、JUnit 133、`test/e2e-api.sh` PASS=122（+14）、`bash test/ci.sh` 三层全绿。浏览器实测覆盖：7 页导航与独立滚动、物质架三态、点击/拖拽/横滑三种手势、投放后回台、挑战与沙盒的进出导航、教程 5 步的 coach 遮罩（第 1~3 步不吃点击）、简单/真实模式切换、排行榜重绘、430×589 视口无横向溢出。

## 容器与仪器图标：emoji 错配 → js/icons.js 线稿（✅ 已完成，2026-09-26）

玩家反馈："当前烧杯的图片是实验服，不匹配。" 查下去发现问题是系统性的，不止烧杯一个：`ui.js` 里那张 `VESSEL_EMOJI` 表把烧杯写成 🥼（实验服）、试管写成 🧫（培养皿）、漏斗 🫙（罐子）、分液漏斗 🍹（热带饮料）、蒸发皿 🍽️（餐具），还留了一个数据里根本不存在的 `gasbottle` 键；而量筒、容量瓶、研钵、表面皿、点滴板这 5 种容器压根没有条目，全回落到同一颗试管。可"选哪个容器"恰恰是这个游戏最核心的选择——玩家分不清自己用的是哪个。emoji 还跟着平台字体走，换台手机形状就变。

| 项 | 落地 | 代码 | 回归 |
| --- | --- | --- | --- |
| 图标真源 | 28 张手绘线稿（27 种仪器 + `other` 兜底）画在同一个 48×48 视图框，每张分 `o` 轮廓 / `t` 细节 / `l` 液体三层。**存图元数组而非拼好的 SVG 字符串**，为的是让 node 能解析几何：`["path","M13 15 v20 …"]`、`["circle",cx,cy,r]`、`["rect",x,y,w,h]` | `frontend/js/icons.js` | validate.js：覆盖率 + 几何 + 唯一性 |
| 几何裁判 | `flatten()` 支持 M/L/H/V/Q/T/Z（含小写相对量），`auditShape()` 判三件事：图元越出视图框、图元类型解析不了（`draw()` 会静默丢线）、**液体横向/纵向跑出器壁**。预览工具与回归共用这一份，避免两边判定漂移 | `test/icon-geom.js` | 负例已验证：把液体画宽、写 `["line",…]`、留空轮廓都能报错 |
| 人眼确认 | 图标是"像不像"的问题，机器判不了，所以给终端做了个 ASCII 栅格器：`node tools/icon-preview.mjs [id…]` 打 `#` 轮廓 / `+` 细节 / `~` 液体，有问题图形退出码非 0。修好的第一轮就抓到坩埚液体沉到轮廓下 1.8px、容量瓶液体右壁外扩 3.5px | `tools/icon-preview.mjs` | 同上（退出码） |
| 接线 | 台面 `#vessel-icon` 替掉 `#vessel-emoji` + `#liquid-layer`：**换容器才重画形状**，形状不变只切 `.wet` 与 `--ic-liq`，液面因此是渐变而不是闪一下；选容器弹窗 14 行、【建设】仪器商店 27 行各挂 `.ic-slot` 缩略图；线条只吃 `--ic-line`（皮肤变量），三套皮肤零新增规则 | `index.html` · `css/style.css` · `js/ui.js` · `js/panels.js` | e2e「容器图标」10 例 |
| 两处判断 | ① 颜色来自后台可编辑的物质数据，进 `style="--ic-liq:…"` 前过 `safeColor()` 白名单（只放 `#hex` / `rgb[a]()`），防止 CSS 注入；② H₂/O₂ 这类无色气体混出的液体近乎透明，只靠填充玩家会以为台面是空的，于是给 `.v-l` 描一圈 `--ic-line` 42% 的细边当液面 | `js/icons.js safeColor` · `.v-ic .v-l` | validate.js 有非法颜色负例 |

**为什么不动数据库/后台**：图标是表现层，不是玩法数据。挂进 `content_item`/`ContentSchema` 会让运营面凭空多一层"改错一个码点游戏就变样"的风险，而线稿的正确性本来就有回归兜着。

**验收基线**：`node test/validate.js` PASS（新增 `instrument icons: 28 shapes / 27 instruments`）、JUnit 133、`test/e2e-api.sh` PASS=132（+10）、`bash test/ci.sh` 三层全绿。浏览器实测（`take_screenshot` 在这个宿主不可用，全部走 `evaluate_script` 读 DOM 与 `getBBox`）：台面渲染出 `svg.v-ic`；投放 H₂×2 + O₂×1 后 `.wet` 生效、`--ic-liq` 等于混合色；`getBBox` 证液体确在器壁内（13.7~34.3 ⊂ 11~37）；切到烧杯形状随之改变而液面保留；选容器 14/14、仪器商店 27/27 每行带图；三套皮肤下描边各自跟随；`2H₂+O₂→H₂O` 反应链路照常（发现奖金 120 金币吐司、台面清空）。前端资源整站顶到 `?v=3.7`。

## 前端信息架构与视觉：整页之内再分段（✅ 已完成，2026-09-26）

整页化解决了"面板叠面板"，但七页里塞了三十来个功能，玩家的抱怨从"手势别扭"变成"东西找不到"。这一轮不动玩法、不动数据，只做**归位 + 可扫读**：

| 项 | 落地 | 代码 | 回归 |
| --- | --- | --- | --- |
| 页面模型 | `panels.js` 顶部一张 `PAGES` 注册表：`tab → {segs(), draw(body, which)}`，段表由数据算出来（可领几件、买得起几台），渲染器只管当前段。页头的 `titleOf`/`subOf` 两行照旧，但副标题只留"我现在在哪"，具体数字交给页首统计条 | `js/panels.js` | e2e 断言 `PAGES`/`claimCounts` 存在且导出 |
| 功能归位 | 每日签到 从【设置·分享与其他】→【任务·今日】（旧位置留 `data-goto` 指路按钮）；商会订单 从【市场】标签→【任务·今日】（它是委托不是货架）；提纯工坊 从【建设】→【物质】页（进料出料都在物质域）；内购从玩法运营拆成【设置·商店】 | 同上 | e2e 5 例钉住四个搬迁点 |
| 一处真源 | 段上的数字（pip）与底部导航角标读的是同一对 `claimCounts()` / `buyableCount()`；`U.updateNav()` 挂进 `G.refresh()`，领完奖励红点自己掉。游客档没有口令可改，设置页那枚用圆点而不是数字 | `js/ui.js` · `js/game.js` | e2e：6 枚 `.nav-badge` + 同源导出 + 进 refresh |
| 可扫读 | 页首 `.statbar`（进度/余额/待办一眼看全）、`.card`/`.card-hd`/`.card-sub` 把长流水切成有标题的组、`.chips` 过滤、`.empty-state` 空态带"去哪做"的跳转、排行榜前三 `.rk` 变色 | `css/style.css`（920→1060 行） | e2e 组件样式齐全 |
| 长列表过滤 | 背包/图鉴/市场/方程式各一条 `.searchbar`（纯前端 `qState`，不发请求）；`segState` 记住每页停在哪段，`P.render()` 以 `tab:seg` 为键还原 `scrollTop`，`qFocus` 过滤重绘后把光标与选区送回搜索框 | `js/panels.js` | e2e 四类搜索框 + 方程式防泄底说明 |
| 防透题 | 方程式搜索**只筛已解锁**，"尚未发现"清单不跟着关键词缩小并在段尾标注原因——否则输入一个化学式看"命中几条"就能反推还藏着什么反应 | `renderEq` | e2e 断言那句"不计入关键词搜索"在 |
| 版本自白 | 关于页版本行改为从 `<link>` 的 `?v=` 反读（`appVer()`），不再手写第二份；签到 7 格 `signInDots()` 与 `signCycle()` 按服务端的 `((streak-1)%7)+1` 对齐 | `js/panels.js` | 浏览器实测：签第 1 天只亮 1 格 |

**为什么只动表现层**：所有段、卡片、搜索都只是重新摆放服务端下发的同一帧 `GameState`，意图集合与校验一字未改；因此 JUnit 仍 133 例、DB 无迁移、后台无改动。图标那轮的判断在这里同样成立——布局是表现，不该进 `content_item`。

**验收基线**：`node test/validate.js` PASS、JUnit 133、`test/e2e-api.sh` PASS=147（+15）、`bash test/ci.sh` 三层全绿。浏览器实测（`evaluate_script`，430px 视口）：7 页 × 全部段落共 26 个组合渲染 **0 报错、0 横向溢出**；关键词过滤后焦点与选区仍在搜索框；切页再回来段与滚动位置都记住；真实签到与领成就后 `coins` 账目移动、角标/pip/`.statbar` 三处数字同步掉；方程式段搜索"点燃"命中 1 条、搜"zzz"命中 0 条而"尚未发现 · 142 条"纹丝不动。

**两处踩坑记录**：① 同一轮里先发了 `?v=3.8` 又往 `panels.js` 补了个搜索框 —— immutable 强缓存让页面怎么刷都不更新，只能整站顶到 `?v=3.9`（README 那条铁律的第二次现场）；② 皮肤切换时用 `getComputedStyle` 读 `.card` 背景，三套皮肤读出来全是白色，差点去"修 CSS"。实际是页面处于 `visibilityState: hidden`，过渡动画被冻住，`getComputedStyle` 返回的是过渡起点的值；把 `transition` 关掉再读即得正确值，另起一次调用追加的新节点也直接正确。**结论：这个宿主里不要用带过渡的属性值做皮肤判定**，改判 `--card` 变量本身或临时禁过渡。

## 迭代 3：数据可编程性与增长（待做）

- **A6 访问令牌绑定会话**：`JwtService.issue` 给玩家令牌带 `sid`（刷新时随新会话行换发），`AuthInterceptor` 查一次 `user_session`（主键索引，成本与后台侧回查同量级）。做完之后封禁/改密/重置口令/注销才是立即生效，也顺带把"退出登录"变成真踢线。代价：上线那一刻所有历史令牌失效，玩家重登一次。
- **A4 存档版本位**：`GameState` 落库 payload 不带 schema 版本，字段改名会被 Jackson 静默填默认值（金币丢了都不报错）。加 `sv` 字段 + 按版本迁移，越晚做越贵。
- **A7 HTTP 状态码收口**：`GlobalExceptionHandler` 只显式映射了 BizException/校验/无资源几类，其余全落 `Exception → 500`。实测 `GET /admin/api/me/password`（路径对、方法不对）返回 500 而非 405，未知路径也按 500 处理。补 `HttpRequestMethodNotSupportedException→405`、`NoHandlerFound→404`，顺手把"500 其实是客户端调错"这类噪音从错误日志里分出去。
- **B3 把回归挂上 CI**：`test/ci.sh` 已就位，缺一个 runner（仓库目前还不是 git 仓库，先建库再挂 Actions；CI 放 Linux 跑，Windows 控制台会按 GBK 送中文参数）。
- **C2/C3 启动链路与内容体积**：`G.start()` 现在 `content.load → me → state` 三级串行，可把版本探测与 `me` 并发；bundle 148KB(gzip 40KB) 可按内容类型分片懒取。
- **C4 断线与重连**：意图队列串行、`bootFail` 靠整页重载兜底；补指数退避与顶栏「重连中」指示。
- **D 代码健康**：前端补 ESLint（零构建是刻意选择，不换框架），`panels.js`(58KB)/`ui.js`(33KB) 按面板域拆多个 `<script>`；引擎写死常数按 followups 第 2、3 项外提 `app_config`。
- **E 玩法与运营**：成就条件数据化（followups 第 1 项）、赛季化排行榜（现在无调度 job，榜单靠读时算）、教育向内容扩展（实验安全/教材对齐/教师模式）。真实支付、真实好友、对战、UGC 审核仍需外部服务。

## 刻意不做

微服务化、Redis/多实例会话外置（单实例 + 内存 `EngineCtx` 在现有量级够用，见 followups 第 8 项）、前端换框架、ORM 换 JPA。
