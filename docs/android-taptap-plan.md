# 安卓 APK 化 + TapTap 上架设计方案

> 目标：把《化学实验室：元素纪元》从零构建的竖屏 H5（`frontend/` + Spring Boot 权威后端 + Vue 运营后台）改造成可安装、可上架 TapTap 的安卓手游。
> 结论先行、路线对比、合规硬门槛、代码改造清单、排期与风险都在下面。写这份方案前已经核对了 TapTap 现行开发者文档与本机安卓工具链的实际状态，不是纸面推演。

---

## 1. 结论先行

**推荐路线：Capacitor 原生壳 + 前端资源打进 APK + TapSDK 深度接入 + 后端上公网 HTTPS**，用 4 周拿到可过审的包；二期再决定是否用团结引擎（Tuanjie）重写表现层。

**三个必须先接受的事实**（它们决定成败，不是风格问题）：

1. **TapTap 明令拒绝"低质 HTML5 包装"**（审核规范 1.4.3：纯粹套用现成页面的产品会被清退）。所以"把网址塞进 WebView"这条路**走不通**。要过审，APK 必须表现得像个 App：资源离线内置、原生启动页与图标、返回键语义正确、TapTap 登录与防沉迷走原生 SDK、剪贴板/分享走原生桥、有原生强制更新链路。本方案第 6 章就是按这条标准设计的。
2. **没有版号就不能有任何内购计费**（审核规范 5.4：「没有版号的游戏不得有任何形式的内购计费内容」；付费下载同样需要版号）。这一条已经落到代码里：原先【设置·商店】的模拟充值、钻石档位与月卡购买**整面下线**，玩家侧不再有任何"花钱"入口与人民币标价，原来要付费的东西全部改由**看激励视频换积分**领取（第 10 章）。激励视频不是内购计费，个人主体可做；💎钻石仍在游戏内通过实验、答题、商会订单产出。要真收费必须先办版号，而版号个人申请不了，需要有出版资质的企业主体走代理，周期与成本都是数量级差异。
3. **联网 App 必须有已备案的 HTTPS 域名**（审核规范 5.6 要求提交 APP 备案号与主办单位；Android 9+ 默认禁止明文 HTTP）。当前项目跑在 `localhost:8080` 明文 HTTP，**这是工作量最被低估的一块**：买服务器与域名 → ICP 备案（境内云 1~3 周）→ Nginx/Caddy 上 TLS → 后端与客户端配置改指正式域名 → 公网重跑回归。

**软著、实名防沉迷、隐私政策 URL 是硬材料**，与代码并行推进，见第 4 章。

---

## 2. 现状盘点：哪些能直接复用

| 现有能力 | 位置 | 对上架的价值 |
| --- | --- | --- |
| 全服务端权威、客户端只发意图 | `game/GameService.java` + `frontend/js/game.js` | 防沉迷时段闸门、强制更新、防作弊都能加在服务端，**不用改客户端逻辑** |
| CORS 已可配置 | `config/WebConfig.java:77`（`chemera.cors.allowed-origins`），`config/ProdHardening.java:48` 在 prod 会告警 | 资源打进 APK 后 origin 变成 `https://localhost`，直接加白名单即可，无需新代码 |
| prod 加固 | `application-prod.yml` + `ProdHardening`（默认 JWT 密钥/无 seed 口令直接拒绝启动） | 公网部署的开关联，已就位 |
| 限流防爆破 + `X-Forwarded-For` 开关 | `security/RateGuard`、`CHEMERA_TRUST_XFF` | 反代后面必须开 `CHEMERA_TRUST_XFF=true`，否则限流按反代 IP 计数，会把全体玩家锁死 |
| 请求追踪 / 健康检查 | `RequestTraceInterceptor`、`GET /api/healthz` | 上线后可直接拿来做拨测与故障定位 |
| 后台敏感词过滤 + 举报处理 | `service/AuthService.java:212`（昵称过 `mod.allWords()`）、`controller/admin/AdminModerationController.java`（reports/words/audit） | 昵称是 UGC，TapTap 会看内容风控；这条**已有**，比多数独立开发者起点好 |
| 云存档 revision 乐观并发 | `SaveService` + `cloud.js` | 换设备/重装 App 不丢档，是 TapTap 玩家最在意的体验之一 |
| 竖屏移动 UI（430px 已实测无溢出） | `frontend/css/style.css`（`:root` token + 三套皮肤） | 不用重画布局 |
| 容器/仪器图标是手绘线稿 SVG | `frontend/js/icons.js` | **规避了安卓 emoji 漂移**——WebView 的 emoji 字体与 iOS/桌面不同，那套错配 emoji 会再翻车一次，现在已经没有这问题 |
| 激励视频工单链路（签发→平台回调验签→取帧结算） | `game/AdService.java` + `controller/AdController.java` + `V7__ad_reward.sql` | 变现面已经在服务端权威化：APK 里只要把原生 SDK 的回调地址指到同一个 `/api/ad/callback`，换壳不改经济系统 |
| 三层回归 | `test/ci.sh` = validate + 164 JUnit + e2e PASS=189 | 上架版本可以拿同一套断言对着公网域名跑，不用另建测试体系 |

**本机安卓工具链实测状态**（决定构建能不能在这台机器上跑通）：

- Android SDK：`D:\AndroidStudio\SDK`，已装 `platforms/android-34`、`android-36`，`build-tools/36.1.0`、`37.0.0`，`platform-tools`（adb 可用），`emulator`，`system-images/android-34/google_apis_playstore/x86_64`。
- **缺 `cmdline-tools`** → 没有 `sdkmanager`/`avdmanager`，命令行装不了新 SDK 组件、也建不了 AVD。要么开 Android Studio 补装，要么按以往做法手挂 zip。
- Capacitor 7 模板默认 `compileSdk/targetSdk=35`、`minSdk=23`，而本机**没有 android-35**：两条解法，① 用 Android Studio 补 SDK Platform 35；② 把 `android/variables.gradle` 提到 36（本机有）并配 AGP 8.9+。方案里按 ② 写，避免依赖 GUI。
- Java：PATH 上是 JDK 22，Gradle 必须显式指向 `D:\Java17\jdk-17.0.12+7`（AGP 8.x 用 17 最稳），否则版本组合会漂。
- Gradle：PATH 上没有，但 `~/.gradle/wrapper/dists` 已缓存 8.9 / 8.14.3 / 9.2 / 9.3 → wrapper 指到已缓存版本可离线跑。
- npm registry = 华为云镜像（此前 aliyun 不可达、`gradlePluginPortal` 会挂 → Gradle 仓库顺序把 `google()`、`mavenCentral()` 放最前并设 HTTP timeout，这是本机既有经验）。
- 真机：`adb devices` 目前为空。上架前必须有真机验证（USB 调试），模拟器只能兜底。

---

## 3. 技术路线对比

| | A. Capacitor 原生壳（推荐） | B. 团结引擎 / Unity 重写表现层 | C. WebView 直连线上 URL（纯套壳） |
| --- | --- | --- | --- |
| 做法 | 现有 H5 打进 APK 的 `assets/public`，原生壳负责启动、返回键、SDK、桥接；玩法仍请求 HTTPS 后端 | 用 Tuanjie 重建 UI/粒子/动画，后端 API 不变，C# 重发意图 | 壳里只 `loadURL("https://…")` |
| 工作量 | 1~2 周到 debug 包，4 周到提审 | 3~6 个月（118 元素 + 143 反应的表现层、图鉴/市场/建设全部重做） | 2~3 天 |
| 过审概率 | 中高——前提是原生能力真接上了（登录/防沉迷/离线包/强制更新/图标启动页） | 高 | **低**：1.4.3 直接命中，且断网白屏、无原生体验，被拒与清退风险最大 |
| 体积 | APK 约 8~14 MB（WebView 依赖 + 前端 ~1.2 MB 资源） | 40~120 MB 起步 | 5~8 MB |
| 性能/耗电 | 与浏览器一致；canvas 粒子已在 `fx.js`，低端机需降档 | 最好，可做粒子与真 3D | 依赖网络，首屏最慢 |
| 后续 iOS/鸿蒙 | Capacitor 同壳多端 | 引擎多端最强 | — |
| 维护成本 | 前端零重写，`?v=` 那套流程照用 | 两套表现层要长期同步 | 最低但不被允许 |

**为什么 A 而不是 B**：项目的真源在服务端，玩法与内容已经全部结构化（147 条 e2e 断言、133 单测、后台可运营）。重写表现层会把这套资产的可信度清零重来，而 TapTap 审核并不要求原生渲染，只要求"不是把网页套个壳"。A 路线把力气花在**证明它是 App**（原生 SDK + 离线可用 + 原生交互语义）上，性价比最高。

**B 的正确位置**：等 TapTap 有真实玩家数据后，若决定做 3D 实验台/动画化反应，再按引擎重做表现层——后端与后台一行不用改，这是当初坚持服务端权威换来的红利。

---

## 4. 合规硬门槛清单（与代码并行，越早启动越好）

| # | 事项 | 具体要求 | 谁负责 / 周期 | 卡点 |
| --- | --- | --- | --- | --- |
| 1 | 开发者账号实名 | TapTap 开发者中心实名；个人可上架"免费 + 无内购" | 你，1~3 天 | 个人主体无法办版号与对公支付 |
| 2 | **软件著作权** | 审核规范 5.5 必须提交登记凭证；材料＝源代码前后各 30 页 + 说明书 | 中国版权保护中心，常规 30~60 工作日，可付费加急 | **最长的一条链路，第 1 周就要递交** |
| 3 | **ICP / APP 备案** | 5.6 要备案号与主办单位；备案主体须与开发者主体一致 | 境内云厂商提交，1~3 周 | 域名必须先买好；主体不一致会来回退 |
| 4 | **实名认证 + 防沉迷** | 5.7 需真实身份核验记录与防沉迷配置录屏；接入 TapSDK 登录（≥3.29.0）+ 合规认证模块（后台"游戏服务 > 合规认证"先开通） | 开发 3~5 天 | 强依赖登录 SDK 先接；未成年人**仅周五六日与法定节假日 20:00–21:00 可玩** |
| 5 | **隐私政策 + 合规弹窗** | 5.8 分发前须挂政策 URL 并通过安全扫描；用户同意前不得采集设备信息、不得初始化 TapSDK | 开发 1~2 天 + 文档 | 政策要列明收集项（本作：账号、存档、埋点） |
| 6 | **版号** | 只在"要真钱"时必需（5.4） | — | 本方案首版**主动不做**，避免卡死 |
| 7 | 签名与包名 | 4.1/4.5 包名唯一、**不得使用公用证书签名**；4.2 版本号只增不减 | 自建 keystore，`applicationId=com.chemera.lab`（示例，定稿后不可改） | keystore 丢失＝这个包永远无法更新，立刻异地备份 |
| 8 | 内容风险自查 | 本作是"虚拟化学实验 + 爆炸/危险标签"，需在商店描述与游戏内声明"虚拟实验，请勿模仿"；不得引导真实危险品操作 | 0.5 天 | 教育类标签会额外被看 |

---

## 5. 目标架构

```
┌─ APK（Capacitor 壳） ─────────────────────────────┐
│ MainActivity / Bridge                             │
│  ├─ 启动页 + 隐私弹窗（同意后才初始化 TapSDK）      │
│  ├─ TapSDK：TapBootstrap / TapCommon / TapLogin   │
│  │            + 防沉迷（合规认证）+ TapDB           │
│  ├─ 原生桥：剪贴板·分享·震动·返回键·强制更新         │
│  └─ WebView：assets/public/ = frontend 构建产物    │
│        index.html 头部注入 window.CHEM_API_BASE    │
└───────────────────┬───────────────────────────────┘
                    │ HTTPS（唯一允许的通道，明文被系统拦）
        https://api.<域名>/api/game/<intent>
                    │
┌─ 服务器（境内云，已备案） ─────────────────────────┐
│ Nginx/Caddy：TLS 终结 + X-Forwarded-For           │
│ chemera-server.jar（prod profile）                 │
│  ├─ AuthInterceptor（JWT）                         │
│  ├─ RateGuard（CHEMERA_TRUST_XFF=true）            │
│  ├─ GameService.dispatch                           │
│  │    └─ 🆕 CurfewGuard 防沉迷时段闸门（服务端权威）│
│  ├─ 🆕 /api/app/version（minBuild / 更新文案）      │
│  └─ MySQL 9.5 + Flyway + 备份脚本                  │
│ /admin/  Vue 运营后台（加 IP 白名单或独立域）        │
└───────────────────────────────────────────────────┘
```

要点：**代码不跟包发布**。改玩法发服务端，改壳/改资源才打 APK——这条既有约定在安卓上更值钱，因为 APK 更新要过审（5~7 天），能推到服务端的都别放进包里。

---

## 6. 工程改造清单

### 6.1 新增安卓壳工程

```
android/                     ← 新目录，与 frontend/ server/ admin/ 并列
  capacitor.config.ts        appId / appName / webDir=../frontend / ios 关闭
  variables.gradle           compileSdk=36 targetSdk=36 minSdk=23（本机有 android-36，避开缺 35；落地按 Capacitor 8 底线改 24，见 6.6）
  app/src/main/AndroidManifest.xml   锁竖屏、无权限（本作不需要网络/存储之外的任何权限）
  app/src/main/res/          图标（48/72/96/144/192 + 圆形自适应层）、启动页、strings
  keystore/                  git-ignored，只走环境变量（同 server/.env 的规矩）
```

壳配置：

- `webDir` 指向 `frontend/`（零构建，直接把 `index.html` + `js/css/images` 拷进 `assets/public`）。
- **API base 注入**：在壳里放一个 `app-config.js`（打进 assets，`index.html` 第一句加载），内容 `window.CHEM_API_BASE="https://api.<域名>"`。`cloud.js:13` 已经优先读这个全局，**前端代码零改动**。
- 返回键（安卓最容易挨骂的一处）：`modal-root 可见 → 关弹窗` → `tutorial-root 可见 → 关教程` → `当前 tab ≠ bench → 回实验台` → `page 有搜索词/分段非默认 → 复位` → `否则双击退出（Toast 提示）`。
- `Zoom` 关掉、`scrollIntoView` 抖动关掉、`setBackgroundColor` 跟皮肤变量同步（避免切页白闪）、`setMediaPlaybackRequiresUserGesture` 保持 true——`sfx.js:12` 本来就是懒创建 + 手势 `resume()`，符合策略，别改。
- 调试：debug 包 `WebView.setWebContentsDebuggingEnabled(true)`（chrome://inspect 直连），release 关闭并把 `console.*` 转 logcat。

### 6.2 前端改造（8 处，都是小改动）

| 项 | 位置 | 改法 |
| --- | --- | --- |
| API 地址 | `js/cloud.js:13` | 已由 `window.CHEM_API_BASE` 覆盖，只补一句：无注入时提示"配置缺失"而不是静默同源 |
| 可用协议判定 | `cloud.js:14` `/^https?:/.test(location.protocol)` | 安卓壳里 origin 是 `https://localhost`，判定通过；把文案从"需通过 http 访问后端"改成"网络连接失败，请检查网络" |
| 启动失败页 | `js/gate.js` / `boot-root` | 现在是整页 reload 兜底；改成「重试 / 检查网络 / 服务器维护中」三态，并把 `/api/healthz` 探一次给出人话 |
| 分享 | `js/ui.js:794` `navigator.share` | WebView 里没有 `navigator.share`，会退到 `document.execCommand("copy")`（WebView 上也不稳）→ 优先调原生桥 `Clipboard.writeString` / `Share.share` |
| 登录方式 | `js/gate.js` | 新增「用 TapTap 登录」按钮（原生拿 ticket → `POST /api/auth/taptap` 换本服令牌），把"游客档转正"改成一键绑定 |
| 防沉迷表现层 | 新增 `js/curfew.js` | 服务端返回 `code:"CURFEW"` 时，登录页与游戏内统一弹"未成年时段限制"倒计时；**不要**只做前端灰按钮（服务端已权威，正好加闸门） |
| 令牌存储 | `cloud.js:15-44` | localStorage 在 WebView 可持久，但清数据即丢；升级为 Capacitor Preferences（EncryptedSharedPreferences）——保留 localStorage 兜底，避免一次性改挂 |
| 断网/弱网 | `fx.js` + 各意图回调 | 顶栏加"重连中"指示（顺带完成升级计划里的 C4），低端机粒子降档开关 |

顶栏 safe-area：`index.html` 已有 `viewport-fit=cover`，安卓侧再给 `#topbar` 补 `padding-top: env(safe-area-inset-top)`（本机 `android-36` 状态栏更高，刘海/挖孔必须实测）。

### 6.3 服务端改造（4 项，全部可回归）

1. **`CurfewGuard`（新增，约 80 行）**：`GameService.dispatch` 前统一判定时段（未成年人仅周五六日与法定节假日 20:00–21:00），命中返回 `ok:false, code:"CURFEW"`；配置化（`app_config` 加 `curfew_enabled` 并纳入 `ConfigSpec` 说明，保持"配置说明与引擎行为不漂移"的既有纪律）。
2. **`/api/app/version`（新增）**：返回 `{minBuild, latestBuild, note, url}`；壳启动检查——低于 `minBuild` 弹"必须更新"，低于 `latestBuild` 给"建议更新"。这样服务端能硬拒老包，避免旧客户端配新协议打出无解错误。
3. **`/api/auth/taptap`（新增）**：TapTap 登录票据换本服账号（首次自动建档，可把游客档并进来；复用既有"转正并档"路径）。
4. **CORS 白名单**：`CHEMERA_CORS_ORIGINS` 加 `https://localhost`（安卓壳）与正式域；`ProdHardening` 现有的告警保持不变。

### 6.4 运营后台（`admin/`）

- 【APP 版本】页：维护 `minBuild/latestBuild/更新说明`（写进 `app_config`，沿用现有类型化表单与 `ContentSchema` 校验）。
- 【合规】开关：`curfew_enabled`、`analytics_enabled`、真实计费**保持不存在**。
- 【审核】页已能管敏感词与举报，补一句"昵称命中已记录来源=APP"即可给审核演示。

### 6.5 回归与测试（新增一层，不动既有三层）

- `test/android-check.sh`：解包 APK 校验 `applicationId` 一致、`minSdk/targetSdk` 达标、`assets/public/index.html` 里 `CHEM_API_BASE` **不是** localhost、签名指纹与后台登记一致、图标五档齐全、`versionCode` 大于线上（4.2 递增）。
- `bash test/e2e-api.sh https://api.<域名>`：现有 264 条断言原样对着公网跑一遍（脚本本来就吃 BASE 参数），公网与本地不一致时当场暴露。
- 真机矩阵：低端安卓 8（WebView 85 左右，`color-mix()` 不支持 → **必须实测**）、Android 13/14 各一台；重点看三套皮肤、粒子、拖拽投放、软键盘遮挡登录框、返回键、切后台音频恢复。
- 手动过审预演清单：隐私弹窗先于 SDK 初始化、防沉迷时段与实名录屏、首段演示视频 5 秒内出现核心反应玩法（审核 2.5.1/2.6.1 的硬要求）。

### 6.6 落地状态（2026-10-05）

6.1~6.5 的代码项全部落地，逐条对应如下；没落地的只有需要外部账号/真机的那半截，写在后面。

| 计划 | 实际落地 | 与方案的差别 |
| --- | --- | --- |
| 6.1 壳工程 | `android/` 已建：`capacitor.config.ts`（`appId=com.chemera.game`、`webDir=../frontend`）、`variables.gradle`、竖屏无权限 Manifest、五档 mipmap + 自适应图标、`keystore/` 走 git-ignore + 环境变量 | `minSdk` 钉在 **24** 而不是方案写的 23——Capacitor 8.5 的硬底线，`android-check.sh` 里按"以壳框架为准"断言并注明来源。`compileSdk/targetSdk=36`（本机只有 android-36）+ AGP 8.9 与 `buildTools 36.1.0` |
| 6.1 原生插件 | `com.chemera.game` 下四个类：`ChemeraAdPlugin`（`showRewardVideo` 播放桥，未内置 SDK 时明确回"未内置"而不是假装播完）、`ChemeraLoginPlugin`（TapTap ticket → 换本服令牌）、`ChemeraInfoPlugin`（`appBuild` 供版本门）、`ConsentStore` + `AdProvider`/`LoginProvider`/`Providers` 抽象（SDK 到位后只换 Provider 实现，JS 契约不动） | `app/libs/*.aar` 未内置（要 Dirichlet 后台账号），因此 debug 包里广告位走"服务端演示通道"，`android-check.sh` 会打印一行"包里没带 TapADN SDK"的提醒而不是静默通过 |
| 6.2 前端八处 | 已随 `?v=3.14` 发布并被 APK 打进 `assets/public`：API 缺失提示、协议判定文案、启动失败三态 + 自动退避重试（C4）、原生分享/剪贴板桥、TapTap 登录按钮、`js/curfew.js` 倒计时、令牌双写（Capacitor Preferences + localStorage 兜底）、顶栏重连指示与低端机粒子降档 | 返回键与 safe-area 在 `MainActivity` / `style.css` 里；`android-check.sh` 会比对**壳内前端与仓库的 `?v=`**，本轮就靠这条抓到过"改了前端忘了 `cap sync`，包里还是 3.13" |
| 6.3 服务端四项 | `CurfewGuard`（`GameService` 前置判定 + `curfew_enabled` 进 `ConfigSpec`）、`/api/app/version`、`/api/auth/taptap`、`CHEMERA_CORS_ORIGINS`（默认开发值只放 `localhost/127.0.0.1` 通配，prod 留空即全关，`.env.example` 写清了壳里那个 `https://localhost` 的取舍） | TapTap 侧 `CHEMERA_TAPTAP_DEV_MODE=true` 时跳过真验签；真票据要等后台拿到 client_id/secret |
| 6.4 后台 | 【APP 版本】页、【合规】开关、【广告配置】类型化表单、【用户管理】资产调整与口令重置都在 | 审核演示用的"昵称命中来源=APP"仍未加（不影响过审，属运营便利） |
| 6.5 回归 | `test/android-check.sh` 29 条断言（结构 + 产物 + 版本号同源 + 版本门），已挂进 `test/ci.sh` 第 4 层：本机产出过 APK 就跑，没产出过就打一行说明再跳 | **e2e 对公网跑**（`bash test/e2e-api.sh https://api.<域名>`）与**真机矩阵**（低端 Android 8 的 `color-mix()` 兼容、刘海 safe-area、软键盘遮挡、切后台音频）没做——前者要有公网实例，后者要有真机；这两项是提审前必须补的，不在本地射程内 |

还没落地的三件事，性质都是"要外部条件"，不是代码欠账：① Dirichlet 的 SDK `.aar` 与真实 `mediaKey/安全密钥`（拿到后必须用首笔真实回调比对签名口径，见 10.5）；② TapTap 开发者后台的 client_id/secret 与签名 MD5 登记（`android-check.sh` 会算出 debug 证书指纹，但 debug 证书不能提审，正式包要换成 `keystore/` 那份）；③ 商店素材（截图、玩法视频、隐私政策文本）。


---

## 7. 包体与性能

- 前端资源约 1.2 MB（16 个 JS + 1 CSS + 图标 PNG）。**实测 debug 包 6.68 MB**（未内置 TapADN AAR；内置后按 SDK 体积上浮，方案原先估的 8~14 MB 是含 AAR 的量级）。
- 首屏：壳内直接加载 `assets/public/index.html`，省掉 HTML/CSS/JS 的网络往返，只剩内容 bundle 与一帧状态。这条已经按实测做掉了（升级计划 C2/C3/C4，2026-10-05）：bundle **raw 149,966B / gzip 41,633B**，其中 reaction 61,801B + element 35,916B + compound 31,622B = 88%，而这三类恰是实验台首屏就要用的，所以**按类型分片懒取只省得到约 6%**，改做"命中缓存就直接进游戏"（老玩家启动时内容字节下载量为 0）+ 版本探测与 `/api/me` 并发 + 启动失败按 1.5s→3s→6s→12s→24s→30s 自动重试。要拆片时的尺子留在 `GET /api/content/shards`。
- 低端机风险：`color-mix(in srgb,…)` 大量用在皮肤 token 上，旧 WebView 会解析失败退回默认色——真机实测这一条，必要时给 `@supports not (color: color-mix(…))` 的兜底变量表。

---

## 8. 排期（4 周主线，第 5~6 周留驳回缓冲）

| 周 | 目标 | 交付物 | 关键路径 |
| --- | --- | --- | --- |
| W1 | 公网可玩 + 材料递交 | 域名 + 云 + HTTPS + 备案递交 + 软著递交；`https://…/` 手机浏览器直接能玩 | **备案与软著是长周期，今天就要启动** |
| W2 | 壳跑通 | `android/` 工程、debug APK 真机安装、返回键/图标/启动页/API 注入/剪贴板桥 | CORS 白名单 + `CHEM_API_BASE` |
| W3 | SDK 与合规 | TapBootstrap/TapLogin/防沉迷 + `CurfewGuard` + `/api/app/version` + 隐私弹窗 + TapDB + TapADN 激励视频插件（原生 `ChemeraAd` 桥已就位）与首笔真实回调的验签比对 | 后台先开"合规认证"，MD5 签名指纹登记（不登记登录必失败）；广告密钥与 `CHEMERA_AD_*` 一并配齐，SDK 必须晚于隐私弹窗初始化 |
| W4 | 过审包 | release 签名 APK、`android-check.sh` 全绿、商店素材（图标/5 张截图/首段 5 秒玩法视频/描述与安全声明）、提审 | 提审 5~7 天，预留 2 轮驳回 |

## 9. 风险与对策

| 风险 | 等级 | 对策 |
| --- | --- | --- |
| 被判"低质 HTML5 包装" | **高** | 离线内置资源 + TapSDK 原生登录/防沉迷/TapDB + 原生返回键与桥接 + 强制更新链路；商店描述写"离线壳 + 在线权威结算"而不是"网页版"；截图用真机 |
| 备案/软著周期压过开发 | 高 | W1 并行递交；材料不齐不阻塞 W2/W3 |
| 被误判"含内购" | 低（已降） | 付费面已整面下线，UI 里不再有「充值」「购买钻石」与人民币标价，回归里有一条静态断言兜着（把托管出来的 JS/HTML 剥掉注释后搜 `充值`/`shop.recharge`/`¥数字`，命中即失败）；提交前仍要人工过一遍商店页与隐私政策文案 |
| 防沉迷只做前端 → 5.7 驳回 | 中 | `CurfewGuard` 服务端判定 + 客户端倒计时 UI + 配置录屏 |
| 旧 WebView 不支持 `color-mix()` | 中 | 真机矩阵 + `@supports` 兜底 token |
| keystore 丢失 / 公用证书 | 中 | 自建证书、异地双备份、密钥只走环境变量（延续"凭证不落文件"的项目铁律） |
| 后端单实例，玩家一多即挂 | 中 | 至少：Nginx + systemd 守护 + `tools/backup.sh` 定时；把 A6（令牌绑会话）在上线前做掉，否则封禁/改密有 ≤2h 空窗（既有已知边界） |
| 教育内容合规（真实危险实验） | 低-中 | 游戏内与商店页加"虚拟实验，请勿模仿"声明；危险反应只给虚拟后果（爆炸/损失），不给操作教程 |

## 10. 激励视频变现：TapADN / Dirichlet SSP 落地形态

> 这一章写的是**已经实现并进回归**的东西，不是待办。方向：个人主体没有版号就不能内购，但激励视频不属于内购计费，所以把原来那套"模拟充值"整面换成"看视频换奖励"，既满足 5.4，又保住月卡/皮肤/礼包这些原本要付费的钩子。

### 10.1 钱从哪来、奖励从哪发：三态工单

奖励的唯一发放入口是数据库里的工单，客户端说的话一律不算数。表 `ad_ticket`（`V7__ad_reward.sql`）存 `ticket`（32 位随机十六进制，给 SDK 当 `extra` 原样带回）、`user_id`、`kind`、`reward`、`amount`、`points`、`status`、`trans_id`、`expires_at`，状态机是 `issued → rewarded → settled`：

1. **签发** `ad.request`：按顺序过闸门——总开关 → 能不能真发奖（有验签口令或在演示模式）→ kind 在目录内 → 该位每日次数 > 0 → 等级 ≥ `minLevel` → 该位当日余量 → 全局 `dailyTotal` 余量 → 冷却 → 同一位只允许一张在途工单 → 顺手清理过期单。**奖励类型、数量、积分在签发一刻定格进工单**，所以运营中途改配置不会改变玩家正在看的这一笔，也不会出现"看完发现变少了"的纠纷。
2. **平台回调** `POST /api/ad/callback`（`AdController`）：来访的是广告网络的服务器，**不带玩家 JWT**，身份完全靠签名。入参按 TapADN 文档取 `pid / user_id / trans_id / extra / sign`，query、form 与 JSON 体三种回传方式都合并接。验签过才置 `rewarded`；`trans_id` 唯一约束 + 条件 UPDATE 保证平台重试与并发只算一次，且重复回调仍回成功（回失败平台会一直重试）。
3. **结算** `AdService.settle()`：在**每次意图请求的最前面**跑，把 `rewarded` 兑进存档、加积分、更新计数，并在整帧里带 `events:[{type:"ad", granted:[{kind, reward, amount, points, text}]}]`。放在开头是为了让"回调比玩家下一次请求晚到"也不丢奖励——玩家下次打开游戏照样能看到到账。

这条链路顺带把两个作弊口子关了：旧的 `ad.bonus` 是客户端自证"我看完了"，`shop.buy` / `shop.recharge` 是直写余额，三条意图现在都回绝并给出说明。复活也不再免费：`challenge.revive` 只消耗看广告换来的 `ad.revive` 次数。

### 10.2 广告位与积分目录（首版默认值，后台可改）

配置存在 `app_config` 的 `ad` 键，`ConfigSpec` 标为**服务端结算**（改了立刻生效，且真的生效），代码里的兜底默认见 `Content.AdConfig.DEFAULT`：

| kind | 名称 | 奖励 | 数量 | 每日 | 冷却 |
| --- | --- | --- | --- | --- | --- |
| `boom` | 事故慰问金 | coins | 500 | 2 | 300s |
| `dbl` | 双倍领取券 | coupon | 1 | 1 | — |
| `diamond` | 钻石补给 | diamonds | 8 | 4 | 180s |
| `hint` | 精灵提示 | hints | 2 | 2 | 300s |
| `revive` | 挑战复活 | revive | 1 | 3 | 60s |
| `monthly` | 月卡时长 | monthly_days | 1 | 1 | — |

每看一段得 `viewPoints`（默认 1）积分，积分不清零；兑换目录默认四项：镧系·锕系礼包 20 分（限一次）、皮肤·赛博纪元 12 分、皮肤·复古炼金 12 分（各限一次）、月卡·30 天 25 分（可重复）。奖励类型只认 `coins / diamonds / hints / coupon / revive / monthly_days / pack_el / skin` 八种——运营在后台填了引擎不认的字符串，这个广告位会被直接跳过而不是崩掉整帧（`knownReward` 里那个 null 判断就是为这个）。

设计上的取舍：**广告只补关键卡口，不做唯一产出**。全局每日 16 次上限 + 每位冷却，是为了防止"肝广告"取代做实验变成主循环；`minLevel` 让新玩家先跑通核心玩法再看到广告中心；运营把 `enabled` 关掉时整页给"暂未开放"，金币与玩法产出照常，不出现点了没下文的死按钮。

### 10.3 配置面（口令只走环境变量）

`application.yml` 的 `chemera.ad.*` 全部由环境变量注入，仓库里不落任何密钥值：

| 环境变量 | 默认 | 作用 |
| --- | --- | --- |
| `CHEMERA_AD_SECURITY_KEY` | 空 | TapADN 后台的"安全密钥"。空 ⇒ 不签发工单，`/api/healthz` 报 `ad.ready=false`，玩家侧按钮按住并说明原因 |
| `CHEMERA_AD_SIGN_TEMPLATE` | `{transId}{key}` | 验签拼接模板，占位符 `{transId} {userId} {pid} {extra} {key}` |
| `CHEMERA_AD_SIGN_HEX` | `lower` | 平台回签名的十六进制大小写；比对时两边按它归一 |
| `CHEMERA_AD_CALLBACK_ACK` | `json` | 回执形态：`{"code":0,"msg":"success"}` 或纯文本 `success` |
| `CHEMERA_AD_DEV_MODE` | `true`（prod 写死 `false`） | 本机演示自证通道；`ProdHardening` 在 prod 检测到它为 true 直接拒绝启动 |
| `CHEMERA_AD_SPACE_ID` | 空 | TapADN 激励视频推广位 ID，随 `ad.request` 下发给原生 SDK |

`/api/healthz` 里的 `ad:{ready, devMode}` 是拨测点：上线后只要看一眼就知道"广告能不能发奖、是不是还挂着演示通道"。

**目录改坏了没人会报错，所以存之前先拦一道**。引擎读 `ad` 的方式决定了它的失败模式是静默的：`@JsonIgnoreProperties(ignoreUnknown)` 会把拼错的字段丢掉（`cooldown` ≠ `cooldownSec` ⇒ 冷却凭空消失），`AdService` 会 `continue` 掉 reward 不在集合里的行（⇒ 玩家看完广告什么都拿不到），`amount=0` 能正常走完整个流程，`ticketTtlSec<30` 会被回落到 900（运营以为改了其实没改）。所以 `AdminConfigController.upsert` 对 `key=ad` 先跑 `AdConfigValidator.problems()`，任一问题即整次写入拒绝，消息里带行号、现值和合法集合（"广告位 #2 的 reward「coin」不被引擎支持，可用：coins / coupon / …"）。面板侧不要求运营记住这些规则：`GET /admin/api/config/ad/schema` 把 reward 集合、皮肤集合、字段清单与边界值从**同一份 Java 常量**下发，`Config.vue` 据此渲染逐行类型化编辑器（下拉里出现的选项就是引擎认的选项，不存在第二份清单要手工同步）。这里有个必须显式处理的语义差：`slotsOr()` 在字段为 `null` 时回落到内置默认目录、为 `[]` 时是"真的要清空"，所以编辑器展开缺省值时展开的是**真正生效的那份**，而不是把 `null` 显示成空表。

### 10.4 客户端接线（一个入口、一个视图包）

- `frontend/js/ads.js` 是播放桥：原生壳里调 `window.ChemeraAd.showRewardVideo({spaceId, extra: ticket, rewardName, rewardAmount, userId, transId})`；浏览器预览退化成一段带"演示"标注的倒计时动画 + `ad.devGrant`，两种形态在 UI 上说得很明白。
- `U.watchAd(kind, cb)`（`ui.js`）是所有看广告动作的唯一路径：广告中心的「📺 观看」、炸锅弹窗的领慰问金、挑战失败的「📺 复活 +2 步」、任务页的双倍领取券。原生播放回调里再轮询 `ad.status` 等 `trans_id` 到账（5 次 × 1.2s），拿不到就提示"稍后自动到账"，不重复发。
- 到账提示不靠轮询：`G.announce` 看到 `events` 里有 `type:"ad"` 就 toast 明细并 `P.adInvalidate()`，广告中心下次渲染强制重取视图。
- 广告中心整页只吃 `ad.status` 一个包：余额、余量、冷却、积分、目录、锁定状态、待到账笔数全由服务端算好，客户端只做展示与倒计时动画；能不能点最终由服务端签发时再判一次。
- 【设置】第五个分段由"商店"改名"广告"，`drawStore` 由 `drawAds` 取代；皮肤页与月卡行的购买按钮改成 `📺 积分兑换` 跳转按钮。

### 10.5 上线前还剩两件事（无法在本地确认的部分）

1. **真实签名口径**：Dirichlet 文档只写了"trans_id 结合安全密钥做 SHA256"，没写死拼接顺序与大小写。现在的模板可配正是为此——拿到首笔真实回调（或用后台的测试回调）比对一次，改 `CHEMERA_AD_SIGN_TEMPLATE` / `CHEMERA_AD_SIGN_HEX` 即可，**不必改代码**。在此之前，本地回归用的是同一条验签路径、只是口令与模板自配。
2. **回执格式**：`callback-ack` 提供 `json` / `text` 两态。若平台要求特定字段（例如固定 `code` 名或空 body），在 Nginx 反代处转换或扩展这一个方法即可，回调路径本身不产生余额以外的副作用。

另外两条属于合规而非代码：TapADN SDK **必须在隐私弹窗同意之后**才初始化（审核 5.8 + 个保法），隐私政策里要列明广告 SDK 收集的设备标识；未成年人防沉迷时段落地后（`CurfewGuard`）会天然拦住 `ad.request`（它走同一条意图派发），不需要为广告单开一栏。

### 10.6 这一块的回归覆盖

`AdServiceTest` 16 条：无口令不签发、六道闸门逐条拒且给原因、全局日上限、坏 JSON 不崩、伪造/缺参/归属不符/凭空交易号必拒、幂等与重复回调、过期单不可置 rewarded、并发结算只发一次、六种奖励各自到账、跨日只清当日计数、兑换扣积分与限领一次、可重复兑换项、演示通道只在 devMode 且只认自己的单、视图字段与未就绪提示、验签模板与大小写可配。

`AdConfigValidatorTest` 13 条：V7 种子目录必须自证通过（改种子忘了改守卫立刻红）、空目录与缺值合法（关停整个变现面是合法操作）、不被支持的 reward、缺 reward、字段名拼错（Jackson 会静默丢）、kind/id 重复、计数类数量填 0 而礼包/皮肤类不填也合法、各道闸门越界且**总开关关着照样拒**（重开之前它就是坏的）、`skin` 兑换项必须给引擎认识的目标、标识符必须能当存档键、非对象值、schema 与引擎常量逐项对齐（含 `Set` 迭代顺序不可依赖 ⇒ 输出必须排序）、默认值可被编辑器反序列化。

`test/e2e-api.sh` 三组：「激励视频：签发工单 → 服务器回调验签 → 取帧结算」把假签名必拒、真签名受理、重复回调幂等、结算金额精确到账、`type=ad` 事件、冷却拦截、视图下发、积分不足差额文案串成一条链；「广告目录写入守卫」对着真后台接口依次试 7 种坏配置并逐条确认拒因，再写一份合法空目录、按 V7 种子恢复并做字节比对（守卫测试自己也不留脏配置），最后确认 `/admin/api/config/ad/schema` 有内容、后台打包产物里确实搜得到这套类型化表单；「广告中心接线与付费面残留清理」把托管出来的 JS/HTML **剥掉注释后**搜 `充值` / `simAd` / `shop.recharge` / `buyDiamondItem` / `¥数字`，命中即失败——注释里留"充值已随付费面下线"的历史说明是允许的，代码里再出现就是真入口。整脚本当前 PASS=189 / FAIL=0。

---

## 11. 二期演进

1. **Tuanjie/Unity 重写表现层**（3D 实验台、反应动画、粒子）：后端 API 与后台不变，客户端换壳，是真正的"从 H5 长成手游"。
2. **iOS 与鸿蒙**：Capacitor 同壳出 iOS（需苹果开发者账号）；鸿蒙需另评估 ArkWeb/适配。
3. **真钱变现**：先办企业主体 + 版号 + 支付对接，再考虑把内购面加回来。第 10 章那套工单机制正好是它的底座——将来内购也是"服务端签发订单 → 支付平台回调验签 → 取帧结算"同一条链路，只是把 `ad_ticket` 换成 `pay_order`、把签名换成支付平台的签名规则。原来的 `RECHARGE` 档位与月卡定价在库里仍在，`ConfigSpec` 已标成"已退役（改它不生效）"，重新启用是运营在后台的动作而不是新加迁移。
4. **TapTap 生态**：正版验证、内嵌动态、预约页、TapDB 漏斗看留存——这些都在 TapSDK 能力池里，壳已经接了基础链路，加模块成本低。
5. **广告面精细化**：现在只有"看一段给一份"。等有了真实 eCPM 数据，再按位调 `amount` / `daily` / 冷却，并考虑分层（新用户首段加倍、流失召回段免冷却）——全部是 `app_config` 的 `ad` 键，不动代码。

---

## 12. 需要你确认的三件事

1. **上架主体是个人还是公司？** 个人＝首版必须"免费无内购"，版号与支付都做不了；公司＝可以走版号，但周期 +3~6 个月。
2. **有没有已备案的域名，或者愿不愿意新买一台境内云？** 没有 HTTPS + 备案就没有联网 App 上架，这条无法绕过，且是排期里最长的一条。
3. **接受"APK 里带可离线打开的前端 + 服务器只提供 API"这个形态吗？** 接受＝按第 6 章推进；只想要"打开就是最新版"＝必须远程加载，而那正是 1.4.3 拒绝的形态。

---

### 参考资料（TapTap 现行开发者文档）

- [TapTap 上架资质与标准操作](https://developer.taptap.cn/docs/en/store/standardies-operation/)（版号与内购/付费下载的关系、5~7 天审核、试玩态）
- [TapTap 游戏审核规范细则](https://developer.taptap.cn/docs/store/release/publish/agree/)（1.4.3 拒绝低质 HTML5 包装；4.1/4.2/4.5 包名·版本递增·不得公用证书；5.4 版号；5.5 软著；5.6 APP 备案；5.7 实名防沉迷；5.8 隐私政策与安全扫描）
- [实名认证和防沉迷功能介绍](https://developer.taptap.cn/docs/v3/sdk/anti-addiction/features/)（未成年人周五六日与法定节假日 20:00–21:00；充值分档；需先接登录；有版号需中宣部凭证）
- [TapSDK 快速开始](https://developer.taptap.cn/docs/en/v3/sdk/start/quickstart/)（本地 AAR 导入、`com.taptap:lc-storage-android` / `lc-realtime-android`、API 21 底线、后台登记签名 MD5）
- [政策解读：无版号的游戏可以上架 TapTap 吗](https://www.taptap.cn/moment/764175580655520801)
- [Dirichlet SSP（TapADN）开发者文档](https://ssp.dirichlet.cn/docs/)（激励视频服务端回调字段 `pid / user_id / trans_id / extra / sign`、安全密钥、Android SDK 接入）——第 10 章的验签与工单机制按这份文档设计，签名拼接口径待首笔真实回调比对
