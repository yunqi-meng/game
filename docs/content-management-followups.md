# 后台内容管理：本轮边界与后续清单

本轮把管理后台做成"游戏内容的一套真源"：13 类内容（reaction / element / compound / instrument / process / room / danger / consumable / npc / shop / achievement / task / quiz）与 13 项运营配置都有类型化表单，字段描述符集中在 `server/.../game/ContentSchema.java`，写入时按 `strict` 开关做字段 + 引用完整性校验，`/admin/api/content/health` 逐行体检现存 458 行。

下面这些**没有**进入本轮：它们仍写死在 Java 引擎里，或属于需要新表/新接口的架构项。按"改哪里、为什么现在没改、最小改法"记录，供后续排期。

## 一、引擎里写死、后台改不动的玩法项

### 1. 成就达成条件（最该先做）
`GameEngine.achDone(GameState, String id)`（`GameEngine.java:637`）是一段按成就 id 硬编码的 `switch`：`aD200 → discovered.size() >= 200`、`aWater → discovered 含 H2O`、`aEq30 → reactionsKnown.size() >= 30` 等 19 条。

后台的 `achievement` 内容行只管理**文案与奖励**（名称、描述、奖励金币/钻石、图标）。也就是说：**新增一行成就而不同步改这段 Java，它永远判定为未达成**——这是当前最容易踩的坑，因为表单看起来"什么都能配"。

最小改法：把条件降成数据。给 achievement 加结构化字段，例如 `{metric: "discoveredCount" | "successCount" | "hasSubstance" | "hasReaction" | "level" | "coins" | "rep" | "quizOk" | "boom" | "challenges" | "sandbox", op: ">=", value: 200}`，`metric` 走 `ContentSchema` 的闭合枚举，引擎按描述符解释执行；现有 19 条 id 先保留白名单映射做过渡，逐条迁到数据里，`FullSynthesisTest` 风格补一组"条件成就"用例。风险：老存档的 `achClaimed` 键不变，改的只是判定，不涉及迁移。

### 2. 等级经验曲线
`Content.levelExp(int lv)`（`Content.java:16`）写死 `80 + lv*lv*25`，被升级循环 `GameEngine.java:589` 使用。策划想调"多少经验升一级"必须改代码。

最小改法：`app_config` 新增 `level_curve`（三项 `{base, factor, power}` 或前 N 级阈值数组 + 末段公式），`Config.vue` 加一个类型化编辑器，引擎从 `Snapshot.config()` 读。风险：**已在途玩家的经验是按旧曲线结算的**，改曲线会让同一 `exp` 对应不同等级；要么只影响后续经验增量，要么一次性发布并公告。

### 3. 零散的难度/商业化常数
- 危险反应的事故结算：`GameEngine.java:423` 的赔付概率 `max(0.08, 0.5 - safety*0.1 - accidentReduce)` 与修复费 `100 + exp*2`。
- 容器容量：`GameEngine.MAX_LINES = 6`（`GameEngine.java:19`）。
- 实验台扩容的兜底步长：`GameEngine.java:547` 的 `50 + level.storage * step`。
- 答题年级奖励倍率：`EconomyService.GRADE_MULT`（`EconomyService.java:536`）。

这些都可以原样搬进 `app_config`（`quiz_grade_mult` 是现成的 Map 型配置样例）。优先级低于前两项，但每次数值调优都要改代码重启，运营成本高。

**另有两个键本身就是空转**（本轮的处置是"标清楚"而不是"修好"）：`start_coins` 只有 `frontend/js/state.js` 读，服务端新建档走 `GameState.java:83` 写死的 5000，所以在后台调它一分钱都不会变；`tier_names` 是纯文案，引擎按 tier 下标算。两者已在 `game/ConfigSpec.java` 的「生效范围」里标为**仅前端**，运营配置面板与 README 速查表都直接显示这个结论。若要真做成可调：新建档初始金币改读 `start_coins`（老档不补发，避免刷号），并给 `tier_names` 加长度校验——都属于改数值的动作，要跟一轮经济回归一起做，不适合顺手改。

### 4. 内容行解析失败仍会静默丢
`ContentService.java:39/45` 在反序列化 `content_item.data` 时 `catch (Exception ignored) {}`：坏行不会阻塞启动，但也不会下发给任何玩家。本轮的 `/content/health` 能把它列成问题行，`strict=true` 写入也能提前拦住，可运行期仍然是"静默降级"。后续把这里改成记一条带 type/id 的 WARN 日志（并计入看板异常计数）即可，不需要改行为。

## 二、需要新表 / 新接口的架构项

### 5. 内容版本历史与回滚
`content_item` 是覆盖写，`content_version` 只是一个自增计数器，改完就查不到上一版了。用户存档侧已经有 `user_save_revision` 可以做历史回滚，内容侧没有对应物。

最小改法：新增 `content_revision(content_type, item_id, data_json, version, operator, ts)`，在 upsert/delete 前落一行；后台加"历史"抽屉 + 单行回滚（回滚本身也走 strict 校验）。这是运营敢在正式服直接改内容的**前提**。

### 6. 管理员账号管理 ✅ 已完成（迭代 2）
`admin_user` 表已存在（含 role/status），但 `AdminAuthController` 只有 `login / me / ping`：建号、改密、停用都只能手写 SQL。默认超管 `admin/admin123` 由 Flyway 种子创建，**没有任何界面能改它的口令**。

最小改法：`/admin/api/admins` 做 super 专属 CRUD（列表、建号、重置密码、启停、自助改密），全部写 `audit_log`；首次登录强制改密的标记位放 `admin_user`。安全上这条应当排在所有内容功能之前。

**实际落地**（`AdminAccountService` + `AdminAccountController` + `Admins.vue` + `V6`）：按上面的最小改法做完，并额外补了两处——`must_change_password` 期间拦截器只放行自助改密端点（不是只弹个框），以及"最后一个在岗超管"不能被停用/降级/删除；`AdminInterceptor` 顺带改成每请求回查账号表，令牌的 2 小时有效期不再给降级留空窗。

### 7. 意图层幂等与断线重连
`POST /api/game/{intent}` 不带客户端序号，网络重试会被当成两次合法意图重复结算（连续两次 `react` 就是两次入账/两次扣耗材）。当前靠"帧返回最新状态"缓解了显示层错乱，没解决重复结算。

最小改法：客户端为每个意图带单调 `seq`（+ 会话内 `intentId`），服务端按 `(uid, seq)` 缓存最近若干帧结果，重复 seq 直接返回缓存帧。与第 8 条的会话外置可以一起做。

### 8. 单实例假设
挑战/沙盒的临时现场放在每 uid 的内存 `EngineCtx`（README「已知边界」）。要横向扩容就得先把它外置（Redis 或表）或做会话粘滞；后台的"内容热更新"同样假设所有实例同时看到新版本。属于部署形态决策，不宜顺手改。

## 三、建议排期

1. 内容版本历史（第 5 项）——运营前置。
2. 成就条件数据化（第 1 项）——它是"后台能管所有内容"这句话目前唯一不成立的地方。
3. 等级曲线与数值常数（第 2、3 项）、解析失败可见化（第 4 项）。
4. 意图幂等 + 会话外置（第 7、8 项）——与正式扩容/多实例一起规划。

（原第 1 项"管理员账号 CRUD + 改密"已随迭代 2 完成；安全侧新的前置项变成 `docs/upgrade-plan.md` 的 A6：玩家访问令牌绑定会话，否则封禁与口令重置有 ≤2h 空窗。）

做 1、2、3 时请同步扩 `test/e2e-api.sh`（现 PASS=91）、`ContentSchemaTest` 与 `ConfigSpecTest`（现 JUnit 116），保持"新增能力必带回归"的节奏。
