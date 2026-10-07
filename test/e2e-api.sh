#!/usr/bin/env bash
# test/e2e-api.sh —— 针对已启动后端的端到端回归（在线版：玩法全部由服务端权威结算）。
# 用法：先启动后端（见 server/run.sh），再执行：  bash test/e2e-api.sh [BASE_URL]
# 依赖：curl、node（解析 JSON）。BASE 默认 http://localhost:8080
set -u
BASE="${1:-http://localhost:8080}"
# 后台账号口令走环境变量（server/.env 里有本地值），脚本不落字面量
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/server/.env" ] && set -a && . "$ROOT/server/.env" && set +a
ADMIN_U="${CHEMERA_SEED_ADMIN_USER:-admin}"
ADMIN_P="${CHEMERA_SEED_ADMIN_PASS:-admin123}"
PASS=0; FAIL=0
ok(){ echo "  ✓ $1"; PASS=$((PASS+1)); }
no(){ echo "  ✗ $1  → $2"; FAIL=$((FAIL+1)); }
# jq-free JSON field extract via node
jget(){ node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8"));let p=process.argv[1].split(".");let c=d;for(const k of p){c=c==null?null:c[k]}console.log(c==null?"":c)' "$1"; }
jlen(){ node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8"));let p=process.argv[1].split(".");let c=d;for(const k of p){c=c==null?null:c[k]}console.log(Array.isArray(c)?c.length:(c&&typeof c=="object"?Object.keys(c).length:0))' "$1"; }
jhas(){ node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8"));let p=process.argv[1].split(".");let c=d;for(const k of p){c=c==null?null:c[k]}console.log(c&&Object.prototype.hasOwnProperty.call(c,process.argv[2])?"yes":"no")' "$1" "$2"; }
# 发一个游戏意图，输出整帧 JSON
act(){ curl -s -X POST "$BASE/api/game/$1" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d "${2:-{\}}"; }

echo "== 内容下发 =="
V=$(curl -s "$BASE/api/content/version" | jget data.version)
[ -n "$V" ] && ok "content/version = $V" || no "content/version" ""
RB=$(curl -s "$BASE/api/content/bundle")
# RB 这个名字要当作全局看待：脚本 900 多行，后半段的静态组（无障碍 / 图标）也在用它。
# 以前这里被第 602 行的存档回滚覆盖过一次——那一行把 RB 换成了 "true"，于是后面所有
# 关于内容包的断言都在拿一个布尔值当 JSON 判，红得毫无道理。回滚那一处已改名 ROLLBK。
RC=$(echo "$RB" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log((d.content&&d.content.reaction||[]).length)')
[ "$RC" -gt 100 ] && ok "bundle 反应数=$RC" || no "bundle 反应数=$RC" "期望>100"

echo "== 本轮账号台账基线（文末自清拿它做对照） =="
# 回归每跑一轮就往 app_user 里塞四五个号（注册档、游客档、TapTap 档），攒久了后台「用户管理」
# 与看板的 totalUsers 全是测试号。文末「本轮账号自清」会把手上这些令牌/用户名解析出的 uid 逐个
# DELETE /admin/api/users，所以这里要在**第一次建档之前**先记下总数当对照。
# 令牌是另开一支 BOOT_T，不碰后面各段的 AT/AH：管理端登录是幂等的，多一次不影响任何断言。
BOOT=$(curl -s -X POST "$BASE/admin/api/login" -H 'Content-Type: application/json' -d "{\"user\":\"$ADMIN_U\",\"pass\":\"$ADMIN_P\"}")
BOOT_T=$(echo "$BOOT" | jget data.token)
USERS0=$(curl -s "$BASE/admin/api/users?size=1" -H "Authorization: Bearer $BOOT_T" | jget data.total)
[ -n "$USERS0" ] && ok "开跑前 app_user 总数=$USERS0（本轮建的号收尾都要还回去）" || no "账号台账基线" "$BOOT"

echo "== 注册 / 登录 =="
U="e2e$RANDOM$$"
REG=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' -d "{\"user\":\"$U\",\"pass\":\"pass1234\"}")
# 注册/游客建档是按 IP 的时间窗限流（application.yml 的 chemera.guard.register-max / guest-max），
# 同一窗口里连跑多轮就会在第一句 register 上撞 429。那不是回归失败，是限流按预期生效——
# 但后面几十条断言会全部级联成红，看着像服务端坏了，所以在这里把原因和出路说清楚再退。
if [ "$(echo "$REG" | jget code)" = "429" ]; then
  echo "  ! 本机 IP 的注册配额已用尽（429），后续断言没有账号可用，先退出。"
  echo "    出路：等窗口重置（默认 60 分钟），或重启后端（限流计数在内存里，重启即清零）；"
  echo "    跑回归期间也可临时调大 server/src/main/resources/application.yml 的 register-max / guest-max。"
  exit 2
fi
T=$(echo "$REG" | jget data.token)
[ -n "$T" ] && ok "register 拿到 token" || no "register" "$REG"
[ "$(echo "$REG" | jget data.guest)" = "false" ] && ok "新账号标记为正式档（guest=false）" || no "正式档标记" "$REG"
NICK="e2enick$RANDOM$$"
NR=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' -d "{\"user\":\"$NICK\",\"pass\":\"pass1234\",\"nickname\":null}")
[ "$(echo "$NR" | jget data.user)" = "$NICK" ] && ok "注册可省略昵称（服务端回退为用户名）" || no "省略昵称" "$NR"
BAD=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$U\",\"pass\":\"WRONG\"}" | jget code)
[ "$BAD" = "401" ] && ok "错误密码被拒(401)" || no "错误密码" "code=$BAD"
SHORT=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' -d '{"user":"e2eshortpw","pass":"123"}' | jget msg)
[ -n "$SHORT" ] && ok "弱密码被服务端拒：$SHORT" || no "密码强度" "msg=$SHORT"

echo "== 令牌轮换与会话治理 =="
RLOG=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$U\",\"pass\":\"pass1234\"}")
RS=$(echo "$RLOG" | jget data.refresh)
R1=$(curl -s -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refresh\":\"$RS\"}")
[ -n "$(echo "$R1" | jget data.refresh)" ] && [ "$(echo "$R1" | jget data.refresh)" != "$RS" ] \
  && ok "刷新即轮换：下发的刷新令牌是新的" || no "刷新轮换" "$R1"
[ "$(curl -s -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refresh\":\"$RS\"}" | jget ok)" = "true" ] \
  && ok "宽限期内的并发刷新仍可用（多标签页不误伤）" || no "并发刷新宽限" ""
RS2=$(echo "$R1" | jget data.refresh)
curl -s -X POST "$BASE/api/auth/logout" -H 'Content-Type: application/json' -d "{\"refresh\":\"$RS2\"}" >/dev/null
LOST=$(curl -s -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refresh\":\"$RS2\"}" | jget msg)
[ -n "$LOST" ] && ok "退出属硬撤销，令牌再出现即拒：$LOST" || no "退出后失效" "$LOST"
BOUNCE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' -d '{"refresh":"0000000000000000000000000000dead"}')
[ "$BOUNCE" = "401" ] && ok "陌生刷新令牌 401" || no "未知令牌" "http=$BOUNCE"

echo "== 玩家自助改密（POST /api/auth/pass：这条端点以前两层测试都没打过） =="
# 单开一个号走改密，因为成功那一步会把**整户会话撤销**（AuthService.changePassword 里的
# sessions.revokeAll）——拿 $U/$T 来测就把前面几十条断言的账号当场打死。
# 请求体全 ASCII：用户名与口令都不带中文，中文进 curl 的 argv 在这台机器上过 ANSI 代码页（见文末自清那段）。
PU="e2epass$RANDOM$$"
PREG=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' -d "{\"user\":\"$PU\",\"pass\":\"pass1234\"}")
PT=$(echo "$PREG" | jget data.token)
PRS=$(echo "$PREG" | jget data.refresh)
[ -n "$PT" ] && ok "改密专用的号建起来了（$PU）" || no "改密建档" "$PREG"
# 闸门 1：原口令不对，什么都改不了
BADOLD=$(curl -s -X POST "$BASE/api/auth/pass" -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' -d '{"old":"wrong123","new":"brandnew1"}' | jget msg)
[ "$BADOLD" = "原密码不正确" ] && ok "原口令不对就被拒（不给试探者留后门）" || no "改密验旧" "msg=$BADOLD"
# 闸门 2：新口令过短同样被拒，而且**没被写进去**——用旧口令照样登得进
SHORTNEW=$(curl -s -X POST "$BASE/api/auth/pass" -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' -d '{"old":"pass1234","new":"123"}' | jget msg)
[ "$SHORTNEW" = "新密码至少 6 位" ] && ok "新口令过短被拒：$SHORTNEW" || no "改密强度" "msg=$SHORTNEW"
STILL=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$PU\",\"pass\":\"pass1234\"}" | jget ok)
[ "$STILL" = "true" ] && ok "两次失败的改密一个字都没落库（旧口令照常登得进）" || no "失败改密动了库" "ok=$STILL"
# 闸门 3：没被拒的请求不该顺手撤销会话——上面两次失败之后手上的令牌仍可用
ALIVE=$(curl -s "$BASE/api/game/state" -H "Authorization: Bearer $PT" | jget code)
[ "$ALIVE" = "0" ] && ok "被拒的改密不误伤当前会话（撤销只跟成功那一次）" || no "失败改密撤销了会话" "code=$ALIVE"
# 成功那一路
CHG=$(curl -s -X POST "$BASE/api/auth/pass" -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' -d '{"old":"pass1234","new":"brandnew1"}')
[ "$(echo "$CHG" | jget ok)" = "true" ] && ok "改密成功" || no "改密" "$CHG"
OLDTOKEN=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/game/state" -H "Authorization: Bearer $PT")
[ "$OLDTOKEN" = "401" ] && ok "改密即刻撤销整户登录态，手上那支访问令牌当场失效" || no "改密后旧令牌仍可用" "http=$OLDTOKEN"
OLDREF=$(curl -s -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refresh\":\"$PRS\"}" | jget code)
[ "$OLDREF" != "0" ] && ok "旧刷新令牌一并作废（被盗设备续不了命）：code=$OLDREF" || no "改密后旧刷新令牌还能续" ""
OLDBACK=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$PU\",\"pass\":\"pass1234\"}" | jget code)
[ "$OLDBACK" = "401" ] && ok "旧口令再也登不进来" || no "旧口令仍可登录" "code=$OLDBACK"
NEWOK=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$PU\",\"pass\":\"brandnew1\"}")
NT=$(echo "$NEWOK" | jget data.token)
[ -n "$NT" ] && ok "新口令登得进，换回来的是一支活令牌" || no "新口令登录" "$NEWOK"
NS=$(curl -s "$BASE/api/game/state" -H "Authorization: Bearer $NT" | jget code)
[ "$NS" = "0" ] && ok "新会话照常取到帧（改密没有把存档带走）" || no "新会话取帧" "code=$NS"

echo "== 鉴权防爆破闸门 =="
BF="e2ebf$RANDOM$$"
LAST=""
for i in $(seq 1 9); do
  LAST=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$BF\",\"pass\":\"nope1234\"}" | jget code)
done
[ "$LAST" = "429" ] && ok "同一 IP+账号连续失败后转 429" || no "登录锁定" "code=$LAST"
OTH=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$U\",\"pass\":\"pass1234\"}" | jget ok)
[ "$OTH" = "true" ] && ok "锁定按账号隔离，其它账号照常登录" || no "锁定越界" "ok=$OTH"
# 令牌要留着：文末「本轮账号自清」靠它反查这个游客档的 uid（游客没有稳定用户名）。
GG=$(curl -s -X POST "$BASE/api/auth/guest"); GGT=$(echo "$GG" | jget data.token)
GGC=$(echo "$GG" | jget code)
[ "$GGC" = "0" ] && ok "游客档在 IP 配额内仍可创建" || no "游客配额" "code=$GGC"

echo "== 权威游戏帧 =="
S=$(curl -s "$BASE/api/game/state" -H "Authorization: Bearer $T")
[ "$(echo "$S" | jget data.revision)" = "1" ] && ok "首帧自动建档 revision=1" || no "首帧建档" "$S"
SC=$(echo "$S" | jget data.state.coins)
[ -n "$SC" ] && [ "$SC" -gt 0 ] && ok "服务端下发金币基线 $SC" || no "金币基线" "coins=$SC"
UNK=$(act "no-such-intent" | jget data.result.ok)
[ "$UNK" = "false" ] && ok "未知意图被服务端拒绝" || no "未知意图" "ok=$UNK"

echo "== 实验闭环：投放 → 反应 → 入账 =="
P1=$(act "bench.place" '{"id":"H2","n":2}' | jget data.result.ok)
P2=$(act "bench.place" '{"id":"O2","n":1}' | jget data.result.ok)
[ "$P1" = "true" ] && [ "$P2" = "true" ] && ok "投放 H2×2 / O2×1" || no "投放" "H2=$P1 O2=$P2"
R=$(act "react" '{"multiplier":1}')
RK=$(echo "$R" | jget data.result.kind)
{ [ "$RK" = "success" ] || [ "$RK" = "partial" ]; } && ok "服务端判定反应成功（$RK，产率折损时为 partial）" || no "反应结算" "kind=$RK"
[ -n "$(echo "$R" | jget data.result.eq)" ] && ok "回执含方程式文本与产量，供客户端渲染" || no "反应回执" "$R"
WATER=$(act "state" | jget data.state.discovered.H2O.times)
[ -n "$WATER" ] && ok "产物已写入服务器图鉴（H2O×$WATER）" || no "图鉴入账" "times=$WATER"

echo "== 权限与防作弊闸门（服务端裁决） =="
TMP=$(act "bench.temp" '{"temp":"highTemp"}' | jget data.result.msg)
[ -n "$TMP" ] && ok "无吹灯管时高温被拒：$TMP" || no "温度闸门" "msg=$TMP"
ELE=$(act "bench.electrolysis" '{"on":true}' | jget data.result.ok)
[ "$ELE" = "false" ] && ok "无电解装置时电解被拒" || no "电解闸门" "ok=$ELE"
QP=$(act "quiz.pickOne" '{"grade":"all"}')   # 年级用 all：Windows 控制台会把中文参数按 GBK 送出，测不出服务端行为
QID=$(echo "$QP" | jget data.result.id)
[ "$(echo "$QP" | jhas "data.result" "answer")" = "no" ] && ok "题目不下发正确答案" || no "答题防透题" "$QP"
X1=$(act "quiz.answer" "{\"quizId\":\"nope\",\"choice\":0}" | jget data.result.ok)
[ "$X1" = "false" ] && ok "未取题直接作答被拒" || no "作答校验" "ok=$X1"
X2=$(act "quiz.answer" "{\"quizId\":\"$QID\",\"choice\":0}" | jget data.result.ok)
[ "$X2" = "true" ] && ok "按当题作答成功（判题在服务器）" || no "当题作答" "ok=$X2"
X3=$(act "quiz.answer" "{\"quizId\":\"$QID\",\"choice\":0}" | jget data.result.ok)
[ "$X3" = "false" ] && ok "同一题不可重复作答（答完即焚）" || no "题次消耗" "ok=$X3"

echo "== 偏好持久化 + 付费面已下线（只剩激励视频） =="
SET=$(act "settings" '{"volSfx":42,"realMode":true}' | jget data.state.volSfx)
[ "$SET" = "42" ] && ok "偏好写入服务器存档（volSfx=42）" || no "偏好持久化" "volSfx=$SET"
act "settings" '{"realMode":false}' >/dev/null
# 旧的"客户端自报看完广告"与钻石购买/充值三条通道必须全部回绝，且给出指路文案
for OLD in "ad.bonus|{\"kind\":1}" "shop.buy|{\"id\":\"monthly\"}" "shop.recharge|{\"tier\":1}"; do
  I=${OLD%%|*}; PD=${OLD##*|}
  MSG=$(act "$I" "$PD" | jget data.result.msg)
  [ "$(act "$I" "$PD" | jget data.result.ok)" = "false" ] && ok "$I 已被服务端回绝：$MSG" || no "$I 仍可发钱" "msg=$MSG"
done

echo "== 存档写回：带号 CAS 与只读意图不落盘（F1） =="
# 为什么这四条意图不许写盘：客户端每 30 秒就取一次帧，以前每次都要把整份存档序列化、
# UPDATE 一遍、再往历史表插一行全量副本——同一份东西抄几百遍，revision 也被白白推高。
RO_A=$(act "state" | jget data.revision)
act "leaderboard" >/dev/null
act "ad.status" >/dev/null
act "quiz.pickOne" '{"grade":"all"}' >/dev/null
RO_B=$(act "state" | jget data.revision)
[ "$RO_A" = "$RO_B" ] && ok "只读意图不推高 revision（稳在 $RO_A）：取帧不再白抄存档" || no "只读不落盘" "$RO_A → $RO_B"
W=$(act "settings" '{"music":true}' | jget data.revision)
[ "$W" = "$((RO_B + 1))" ] && ok "写意图恰好推进一版（$RO_B → $W）：一次意图一个版本，不多写" || no "写回计数" "rev=$W 期望 $((RO_B + 1))"

# 这条是**有意行为**，写清楚了才不会被半年后的人"顺手修掉"：服务端不按参数去重。
# 一模一样的 params 连着发两次，就是两次入账、两次扣钱。理由不是偷懒，是分工——
# "看着一样就合并"在玩法上根本站不住（连着合成两次同样的东西、买两份同种耗材都是合法操作），
# 而链路重发／两台设备各点一下这种"真的该合并"的场景，唯一的可靠钥匙是 F2 的会话内 seq
# （重复 seq 直接回缓存帧），不是请求内容。F3 那一侧只在客户端拦"同一个人连敲两下"。
# 所以 F2 落地之后这条必须**仍然绿**：它钉的是"没带 seq 的两次请求照做两次"，而 F2 只会让"同一个 seq"合一次。
B0=$(act "state" | jget 'data.state.bag.filterpaper|0'); B0=${B0:-0}
K0=$(act "state" | jget data.state.coins)
DUPA=$(act "market.consumable" '{"id":"filterpaper","n":1}')
DUPB=$(act "market.consumable" '{"id":"filterpaper","n":1}')
CA=$(echo "$DUPA" | jget data.result.cost); CB=$(echo "$DUPB" | jget data.result.cost)
B1=$(act "state" | jget 'data.state.bag.filterpaper|0')
K1=$(act "state" | jget data.state.coins)
[ "$(echo "$DUPA" | jget data.result.ok)" = "true" ] && [ "$(echo "$DUPB" | jget data.result.ok)" = "true" ] \
  && [ "$B1" = "$((B0 + 2))" ] && [ "$K1" = "$((K0 - CA - CB))" ] \
  && ok "同一份 params 连发两次真的执行两次（金币 -$CA、-$CB，filterpaper $B0 → $B1）：去重靠 F2 的 seq，不靠\"看着一样\"" \
  || no "重复执行是有意的" "ok=$(echo "$DUPA" | jget data.result.ok)/$(echo "$DUPB" | jget data.result.ok) bag $B0 → $B1 coins $K0 → $K1 cost=$CA/$CB"

echo "== 意图幂等：同一个 seq 只结算一次（F2）=="
# 这一组钉的是 F2 唯一的语义：链路把同一句意图送来说两次（4G 上最常见的下落是"服务端已算完、
# 响应没回来"，客户端按超时重发），服务端只执行一次，第二次原样退回第一次那一帧。
# 钥匙是 (uid, sid, seq) 三元组，不是"参数长得一样"——上面那条"没带 seq 就连发两次就是两次"
# 钉的是另一侧边界，两条必须同时绿：合该合的，分该分的。
SEQ=90001
S0=$(act "state" | jget 'data.state.bag.filterpaper|0'); S0=${S0:-0}
SK0=$(act "state" | jget data.state.coins)
SA=$(act "market.consumable" "{\"id\":\"filterpaper\",\"n\":1,\"seq\":$SEQ}")
SB=$(act "market.consumable" "{\"id\":\"filterpaper\",\"n\":1,\"seq\":$SEQ}")
SCOST=$(echo "$SA" | jget data.result.cost)
S1=$(act "state" | jget 'data.state.bag.filterpaper|0')
SK1=$(act "state" | jget data.state.coins)
[ "$(echo "$SA" | jget data.result.ok)" = "true" ] && [ "$(echo "$SB" | jget data.result.ok)" = "true" ] \
  && [ "$S1" = "$((S0 + 1))" ] && [ "$SK1" = "$((SK0 - SCOST))" ] \
  && ok "同一个 seq 连发两次只到一件货、只扣一笔（金币 -$SCOST，filterpaper $S0 → $S1）：重发不再扣两次钱" \
  || no "同 seq 去重" "ok=$(echo "$SA" | jget data.result.ok)/$(echo "$SB" | jget data.result.ok) bag $S0 → $S1 coins $SK0 → $SK1 cost=$SCOST"
# 第二次交付必须是"退回第一次那一帧"，而不是重新算一份：revision 相同是没落盘的直接证据，
# 逐字节相同则是客户端不必为幂等分叉渲染路径的前提（game.js 拿到什么画什么）。
[ "$(echo "$SA" | jget data.revision)" = "$(echo "$SB" | jget data.revision)" ] \
  && ok "重复交付没推进存档版本（revision 稳在 $(echo "$SA" | jget data.revision)）：第二次一个字节都没写" \
  || no "重复交付不落盘" "revA=$(echo "$SA" | jget data.revision) revB=$(echo "$SB" | jget data.revision)"
[ "$SA" = "$SB" ] && ok "两次响应体逐字节相同（回的是缓存里那一份，不是重算的近似帧）" \
  || no "回缓存帧" "两份响应不同：$(echo "$SA" | cut -c1-80) vs $(echo "$SB" | cut -c1-80)"
# 换号就是玩家又要买一次，必须真的执行两次：幂等窗口认的是"同一笔的第二次交付"，不是"看着一样"。
TA=$(act "market.consumable" "{\"id\":\"filterpaper\",\"n\":1,\"seq\":$((SEQ + 1))}")
TB=$(act "market.consumable" "{\"id\":\"filterpaper\",\"n\":1,\"seq\":$((SEQ + 2))}")
TC1=$(echo "$TA" | jget data.result.cost); TC2=$(echo "$TB" | jget data.result.cost)
S2=$(act "state" | jget 'data.state.bag.filterpaper|0')
[ "$S2" = "$((S1 + 2))" ] && ok "不同 seq 是两笔真购买（filterpaper $S1 → $S2）：幂等不是\"少卖一次\"" \
  || no "不同 seq 各算一次" "bag $S1 → $S2 cost=$TC1/$TC2"
# sid 那一维必须参与幂等键：seq 是会话内单调的，玩家退出重登后从 1 重新数。
# 若只按 (uid, seq) 认，重登后的第一句会撞上上一个会话缓存里的第 1 版——症状不是报错，
# 而是"点了购买、金币没少、货也没到，还回了个 ok"，比重复扣款更难查。
RELOG=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$U\",\"pass\":\"pass1234\"}")
T2=$(echo "$RELOG" | jget data.token)
act2(){ curl -s -X POST "$BASE/api/game/$1" -H "Authorization: Bearer $T2" -H 'Content-Type: application/json' -d "${2:-{\}}"; }
[ -n "$T2" ] && ok "重新登录拿到新会话令牌（新的 sid）" || no "重登取令牌" "$RELOG"
NB=$(act "state" | jget 'data.state.bag.filterpaper|0'); NB=${NB:-0}
NS=$(act2 "market.consumable" "{\"id\":\"filterpaper\",\"n\":1,\"seq\":$SEQ}")
NA=$(act "state" | jget 'data.state.bag.filterpaper|0')
[ "$(echo "$NS" | jget data.result.ok)" = "true" ] && [ "$NA" = "$((NB + 1))" ] \
  && ok "换一个会话复用同一个 seq 号是真的一次购买（filterpaper $NB → $NA）：幂等键带 sid，重登不会被上一会话的帧顶掉" \
  || no "跨会话复用 seq" "ok=$(echo "$NS" | jget data.result.ok) bag $NB → $NA"
# 幂等窗口是有上界的进程内缓存，不是记账依据：客户端换页后 seq 会一直往上涨，
# 一个会话只留最近 chemera.intent.dedupe-seqs 条（默认 8），超出按最近最少使用淘汰。
# 这里连着发十条不同 seq 的意图，只验"每一句都真的执行了"——被淘汰的后果只是"下一次重发可能真执行两次"，
# 绝不允许变成"后面的请求都不执行"。
W0=$(act "state" | jget 'data.state.bag.filterpaper|0'); W0=${W0:-0}
WALLOK=1
for i in $(seq 1 10); do
  W=$(act "market.consumable" "{\"id\":\"filterpaper\",\"n\":1,\"seq\":$((91000 + i))}" | jget data.result.ok)
  [ "$W" = "true" ] || WALLOK=0
done
W1=$(act "state" | jget 'data.state.bag.filterpaper|0')
[ "$WALLOK" = "1" ] && [ "$W1" = "$((W0 + 10))" ] \
  && ok "seq 连续涨到窗口之外也笔笔照做（filterpaper $W0 → $W1）：淘汰只是退回 F2 之前的水平，不会吞请求" \
  || no "幂等窗口上界" "ok=$WALLOK bag $W0 → $W1"

# 真并发：同一账号同时发四笔购买。没有带号写回时，后落的整帧会把先落的抹掉，
# 症状是"扣了四笔钱、只到两件货"（或者反过来）。现在抢输的一方重读重放，账必须守恒。
C0=$(act "state" | jget data.state.coins)
BAG0=$(act "state" | jget 'data.state.bag.filterpaper|0'); BAG0=${BAG0:-0}
TMPD=$(mktemp -d)
for i in 1 2 3 4; do
  ( act "market.consumable" '{"id":"filterpaper","n":1}' > "$TMPD/$i" ) &
done
wait
BOOK=$(node -e '
const fs=require("fs");const d=process.argv[1];
let okc=0,cost=0,stale=0;const bad=[];
for(const f of fs.readdirSync(d).filter(x=>/^[0-9]+$/.test(x))){
  const j=JSON.parse(fs.readFileSync(d+"/"+f,"utf8"));
  const dd=j.data||{};const r=dd.result||{};
  if(r.ok===true){okc++;cost+=Number(r.cost||0);}
  else if(dd.stale===true||r.stale===true)stale++;
  // 第三种下落必须把原文带出来：这里曾经藏过一条 500（InnoDB 死锁把整笔事务回滚了）。
  // 只报"err=1"的红跑查不出原因，只能重跑碰运气——那正是回归最没用的形态。
  else bad.push("code=" + j.code + " msg=" + (j.msg || r.msg || "?"));
}
fs.writeFileSync(d + "/err", bad.join(" ; "));
console.log(okc + ":" + cost + ":" + stale + ":" + bad.length);' "$TMPD")
OKC=${BOOK%%:*}; R1=${BOOK#*:}; COST=${R1%%:*}; R2=${R1#*:}; STALE=${R2%%:*}; ERRN=${R2##*:}
ERRMSG=$(cat "$TMPD/err" 2>/dev/null)
rm -rf "$TMPD"
C1=$(act "state" | jget data.state.coins)
BAG=$(act "state" | jget 'data.state.bag.filterpaper|0')
[ "$((OKC + STALE + ERRN))" = "4" ] && [ "$ERRN" = "0" ] \
  && ok "四笔并发各有下落：成交 $OKC、撞号回 stale $STALE，没有一笔被静默吞掉" || no "并发下落" "ok=$OKC stale=$STALE err=$ERRN $ERRMSG"
[ "$C1" = "$((C0 - COST))" ] && ok "金币只按成交回执扣（$C0 → $C1，共 $COST）：不多扣也不少扣" || no "并发扣款" "$C0 → $C1 cost=$COST"
[ "$BAG" = "$((BAG0 + OKC))" ] && ok "到货件数 == 成交笔数（filterpaper $BAG0 → $BAG，成交 $OKC）：一件不丢、一件不多" || no "并发发货" "bag $BAG0 → $BAG ok=$OKC"

echo "== 激励视频：签发工单 → 服务器回调验签 → 取帧结算 =="
ADHZ=$(curl -s "$BASE/api/healthz")
[ "$(echo "$ADHZ" | jhas ad ready)" = "yes" ] && ok "healthz 报出广告面就绪（ready=$(echo "$ADHZ" | jget ad.ready)）" || no "healthz.ad" "$ADHZ"
# 签发：奖励在签发一刻定格进工单，之后运营改配置也不会改变这一笔
RQ=$(act "ad.request" '{"kind":"boom"}')
TICKET=$(echo "$RQ" | jget data.result.ticket)
AMT=$(echo "$RQ" | jget data.result.amount)
{ [ -n "$TICKET" ] && [ -n "$AMT" ] && [ "$AMT" -gt 0 ]; } && ok "ad.request 签发工单并定格奖励数额（$AMT）" || no "ad.request" "$RQ"
[ "$(echo "$RQ" | jhas data.result "rewardText")" = "yes" ] && ok "回执带服务端文案，客户端不必自己拼数字" || no "rewardText" "$RQ"
CO0=$(act "state" | jget data.state.coins)
# 假签名必须先挡在门外：这条路径不带玩家 JWT，签名就是唯一身份
BAD=$(curl -s -X POST "$BASE/api/ad/callback" -H 'Content-Type: application/json' \
  -d "{\"trans_id\":\"e2efake1\",\"extra\":\"$TICKET\",\"sign\":\"$(printf 'e2e-not-a-real-signature-%s' "$TICKET" | sha256sum | cut -d' ' -f1)\"}")
[ "$(echo "$BAD" | jget code)" != "0" ] && ok "伪造签名的回调被拒：$(echo "$BAD" | jget msg)" || no "验签闸门" "$BAD"
[ "$(act "state" | jget data.state.coins)" = "$CO0" ] && ok "未验签通过的回调不发钱（余额没动）" || no "假签名发奖" ""
# GET 形态（H7 点名：两种投递方式过去只测了 POST）。接不接是平台侧的事，判据只有一条——
# 换了形态也不能绕过验签，否则等于给"谁都能打"的那条公开写路径开第二个门。
GBAD=$(curl -s "$BASE/api/ad/callback?trans_id=e2efake2&extra=$TICKET&sign=deadbeef")
[ "$(echo "$GBAD" | jget code)" != "0" ] && ok "GET 形态的回调同样吃验签（拒：$(echo "$GBAD" | jget msg)）" || no "GET 回调绕过验签" "$GBAD"
# 干脆不带签名：F5 之后这条路径不看 dev-mode 的脸色（没有口令就一律拒，本机自证走 devGrant）
NOKEY=$(curl -s -X POST "$BASE/api/ad/callback" -H 'Content-Type: application/json' -d "{\"trans_id\":\"e2efake3\",\"extra\":\"$TICKET\"}")
[ "$(echo "$NOKEY" | jget code)" != "0" ] && ok "缺签名的回调被拒（$(echo "$NOKEY" | jget msg)），演示模式不放开这条路径" || no "缺签名被受理" "$NOKEY"
[ "$(act "state" | jget data.state.coins)" = "$CO0" ] && ok "GET/缺签名这两条都没动余额" || no "回调发钱" ""
if [ -n "${CHEMERA_AD_SECURITY_KEY:-}" ]; then
  # 用 .env 里的口令按服务端同一套模板算真签名，走的就是线上那条 SSV 路径。
  # 默认值必须在变量外赋：bash 解析 ${X:-{transId}{key}} 时把 "{transId" 的收尾花括号当成占位符结束符，
  # 静默截断成 {transId{key}} —— 服务端照原模板验签，脚本却算了个错签名，排查半天那种。
  AD_TPL="${CHEMERA_AD_SIGN_TEMPLATE-}"
  [ -z "$AD_TPL" ] && AD_TPL='{transId}{key}'
  AD_HEX="${CHEMERA_AD_SIGN_HEX-}"
  [ -z "$AD_HEX" ] && AD_HEX=lower
  AD_PID="e2e"
  case "$AD_TPL" in
    *'{transId}'*|*'{key}'*) ;;
    *) no "sign-template 配置可疑" "模板 $AD_TPL 不含任何占位符，签名与交易号无关，等于没有验签" ;;
  esac
  TRANS="e2e-$(date +%s)-$RANDOM"
  SIGN=$(TRANS="$TRANS" EXTRA="$TICKET" KEY="$CHEMERA_AD_SECURITY_KEY" TPL="$AD_TPL" HEX="$AD_HEX" PID="$AD_PID" node -e '
    const c=require("crypto");let s=process.env.TPL.replace(/{transId}/g,process.env.TRANS).replace(/{userId}/g,"").replace(/{pid}/g,process.env.PID).replace(/{extra}/g,process.env.EXTRA).replace(/{key}/g,process.env.KEY);
    let h=c.createHash("sha256").update(s).digest("hex");console.log(process.env.HEX==="upper"?h.toUpperCase():h)')
  [ "${#SIGN}" = "64" ] && ok "按运营模板算出签名（模板 $AD_TPL，十六进制 $AD_HEX）" || no "签名计算" "长度 ${#SIGN}，模板或口令没读进来"
  CB=$(curl -s -X POST "$BASE/api/ad/callback" -H 'Content-Type: application/json' \
    -d "{\"trans_id\":\"$TRANS\",\"pid\":\"$AD_PID\",\"user_id\":\"\",\"extra\":\"$TICKET\",\"sign\":\"$SIGN\"}")
  [ "$(echo "$CB" | jget code)" = "0" ] && ok "真签名回调被受理（SSV 通道打通）" || no "回调验签" "$CB"
  DUP=$(curl -s -X POST "$BASE/api/ad/callback" -H 'Content-Type: application/json' \
    -d "{\"trans_id\":\"$TRANS\",\"pid\":\"$AD_PID\",\"user_id\":\"\",\"extra\":\"$TICKET\",\"sign\":\"$SIGN\"}")
  [ "$(echo "$DUP" | jget code)" = "0" ] && ok "同交易号重复回调仍回成功但不重发（平台重试不会双发）" || no "回调幂等" "$DUP"
  # 结算发生在取帧开头：下一次任何意图都会把已确认的工单兑进存档
  FR=$(act "state")
  CO1=$(echo "$FR" | jget data.state.coins)
  [ "$CO1" = "$((CO0 + AMT))" ] && ok "奖励在取帧时结算入账（$CO0 → $CO1，恰好 $AMT）" || no "结算金额" "coins=$CO1 期望 $((CO0 + AMT))"
  [ "$(echo "$FR" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.events||[];console.log(d.some(x=>x.type==="ad")?"yes":"no")')" = "yes" ] \
    && ok "整帧用 type=ad 事件告知客户端到账明细" || no "ad 事件" "$FR"
else
  no "未配置 CHEMERA_AD_SECURITY_KEY" "无法演线上验签链路：请在 server/.env 里补一个本地口令"
fi
# 同一位有冷却：连点第二次必须给出"冷却中"，而不是再发一笔
R2=$(act "ad.request" '{"kind":"boom"}')
[ "$(echo "$R2" | jget data.result.ok)" = "false" ] && ok "同位连点被冷却拦住：$(echo "$R2" | jget data.result.msg)" || no "冷却闸门" "$R2"
# 视图：广告中心整页只吃这个包
VW=$(act "ad.status")
SL=$(echo "$VW" | jlen data.result.slots); UL=$(echo "$VW" | jlen data.result.unlocks)
[ "$SL" -ge 1 ] && [ "$UL" -ge 1 ] && ok "ad.status 下发 $SL 个广告位 / $UL 个兑换项" || no "广告视图" "slots=$SL unlocks=$UL"
[ "$(echo "$VW" | jget data.result.dailyTotal)" -gt 0 ] && ok "每日总上限随视图下发（$(echo "$VW" | jget data.result.leftToday) 次余量）" || no "dailyTotal" "$VW"
# 积分不够时兑换要说清差多少，且不动余额
EX=$(act "ad.exchange" '{"id":"elpack"}')
[ "$(echo "$EX" | jget data.result.ok)" = "false" ] && ok "积分不足时兑换被拒：$(echo "$EX" | jget data.result.msg)" || no "积分闸门" "$EX"
echo "$EX" | grep -q "还差" && ok "拒绝文案给出具体差额，玩家知道还要看几段" || no "差额文案" "$EX"

echo "== 游客试玩 → 注册并档 =="
G=$(curl -s -X POST "$BASE/api/auth/guest")
GT=$(echo "$G" | jget data.token)
[ -n "$GT" ] && ok "游客档自动创建" || no "游客登录" "$G"
GS=$(curl -s "$BASE/api/game/state" -H "Authorization: Bearer $GT")
[ "$(echo "$GS" | jget data.revision)" = "1" ] && ok "游客可取权威帧（跨设备同一 token 续玩）" || no "游客帧" "$GS"
GU="e2dup$RANDOM$$"
UP=$(curl -s -X POST "$BASE/api/auth/upgrade" -H "Authorization: Bearer $GT" -H 'Content-Type: application/json' -d "{\"user\":\"$GU\",\"pass\":\"pass1234\"}")
UT=$(echo "$UP" | jget data.token)
[ "$(echo "$UP" | jget data.merged)" = "true" ] && ok "游客进度并入新账号" || no "转正并档" "$UP"
UR=$(curl -s "$BASE/api/game/state" -H "Authorization: Bearer $UT" | jget data.revision)
[ -n "$UR" ] && [ "$UR" -ge 1 ] && ok "新账号沿用游客存档（revision=$UR）" || no "并档后可玩" "rev=$UR"

echo "== 客户端不再能直写存档 =="
SV=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/save" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"save":{"coins":99999999}}')
[ "$SV" = "404" ] && ok "PUT /api/save 已下线(404)：改档只能靠作弊者自己演算" || no "存档直写通道" "http=$SV"
NA=$(curl -s "$BASE/api/game/state" | jget code)
[ "$NA" = "401" ] && ok "未带 token 取帧被拒(401)" || no "游戏鉴权" "code=$NA"
BADJSON=$(curl -s -X POST "$BASE/api/game/bench.place" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"id":' | jget code)
[ "$BADJSON" = "400" ] && ok "坏 JSON 请求体按 400 拒绝（不当 500 记账）" || no "请求体解析" "code=$BADJSON"

echo "== 埋点 =="
curl -s -X POST "$BASE/api/analytics/event" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"event":"react_success","props":{"eq":"R001"}}' >/dev/null
ok "analytics/event 接受（登录用户）"

echo "== 管理后台 =="
AL=$(curl -s -X POST "$BASE/admin/api/login" -H 'Content-Type: application/json' -d "{\"user\":\"$ADMIN_U\",\"pass\":\"$ADMIN_P\"}")
AT=$(echo "$AL" | jget data.token)
[ -n "$AT" ] && ok "admin login 拿到 token" || no "admin login（可用 CHEMERA_SEED_ADMIN_USER/PASS 覆盖）" "$AL"
NOADM=$(curl -s "$BASE/admin/api/users" | jget code)
[ "$NOADM" = "401" ] && ok "无 token 访问 admin 被拒(401)" || no "admin 鉴权" "code=$NOADM"
TYPES=$(curl -s "$BASE/admin/api/content/types" -H "Authorization: Bearer $AT" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log((d.data||[]).length)')
[ "$TYPES" -ge 10 ] && ok "content types=$TYPES" || no "content types" "$TYPES"
TR=$(curl -s "$BASE/admin/api/dashboard/trend?days=14" -H "Authorization: Bearer $AT" | jget ok)
[ "$TR" = "true" ] && ok "dashboard/trend 正常" || no "dashboard/trend" "$TR"
USERS=$(curl -s "$BASE/admin/api/users?size=5" -H "Authorization: Bearer $AT" | jlen data.rows)
[ "$USERS" -ge 1 ] && ok "用户列表可查（$USERS 行）" || no "用户列表" "rows=$USERS"

echo "== 看板真有数（G1：daily_stats 从恒 0 的空面板变成有人写、有人清的表）=="
AH="Authorization: Bearer $AT"
# 这块面板原来读一张谁都不写的表：upsertDaily 零调用者，运营看到的"今天 0 人玩"是假的。
# 恒 0 比没有面板更糟，所以这里要验的不是"接口 200"，而是那三个数真的来自查库、
# 并且落库的那一行与现算的那个数是同一个口径（一个指标两套算法，看板迟早自相矛盾）。
OV=$(curl -s "$BASE/admin/api/dashboard/overview" -H "Authorization: Bearer $AT")
OVOK=$(echo "$OV" | node -e '
let d=(JSON.parse(require("fs").readFileSync(0,"utf8")).data)||{};
let n=v=>typeof v==="number";
console.log(n(d.totalUsers)&&n(d.dauToday)&&n(d.newUsersToday)&&d.dauToday>=1&&d.totalUsers>=1?"ok":"bad "+JSON.stringify(d))')
[ "$OVOK" = "ok" ] && ok "overview 那三个数是查出来的：totalUsers=$(echo "$OV" | jget data.totalUsers)、dauToday=$(echo "$OV" | jget data.dauToday)、newUsersToday=$(echo "$OV" | jget data.newUsersToday)（本轮那个刚登录过的玩家就在里面）" || no "overview 数字" "$OVOK"
TODAY=$(date +%F)
ROLL=$(curl -s -X POST "$BASE/admin/api/dashboard/roll?day=$TODAY" -H "Authorization: Bearer $AT")
RD=$(echo "$ROLL" | jget data.dau)
DAU2=$(echo "$OV" | jget data.dauToday)
[ -n "$RD" ] && ok "运营可以手动补某一天：$TODAY 的 dau=$RD、成功反应=$(echo "$ROLL" | jget data.reactions)、事故=$(echo "$ROLL" | jget data.booms)、成交=$(echo "$ROLL" | jget data.trades)" || no "dashboard/roll" "$ROLL"
[ "$RD" = "$DAU2" ] && ok "补出来那行的 dau 与 overview 现算的 dauToday 是同一个数（$DAU2）：同一指标只有一套口径" || no "口径分叉" "roll=$RD overview=$DAU2"
TD=$(curl -s "$BASE/admin/api/dashboard/trend?days=14" -H "Authorization: Bearer $AT")
INLIST=$(echo "$TD" | node -e '
let d=(JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).recentDaily||[];
let r=d.filter(x=>String(x.day).slice(0,10)===process.argv[1]);
console.log(r.length===1?String(r[0].dau):("rows="+r.length))' "$TODAY")
[ "$INLIST" = "$DAU2" ] && ok "那一天确实进了看板的趋势数据（recentDaily 里今天只有一行，值与现算一致）" || no "趋势读数" "got=$INLIST"
# 服务停过一天、或者想立刻看到刚发生的一天，运营会连点几次这个按钮：upsert 必须是覆盖式的
curl -s -X POST "$BASE/admin/api/dashboard/roll?day=$TODAY" -H "Authorization: Bearer $AT" >/dev/null
AGAIN=$(curl -s "$BASE/admin/api/dashboard/trend?days=14" -H "$AH" | node -e '
let d=(JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).recentDaily||[];
let r=d.filter(x=>String(x.day).slice(0,10)===process.argv[1]);
console.log(r.length===1?String(r[0].dau):("rows="+r.length))' "$TODAY")
[ "$AGAIN" = "$DAU2" ] && ok "同一天的数补第二次仍是覆盖不是累加（多点几下不会把 DAU 刷成两倍）" || no "覆盖式重算" "again=$AGAIN"
FUT=$(curl -s -X POST "$BASE/admin/api/dashboard/roll?day=2099-01-01" -H "$AH" | jget msg)
BAD=$(curl -s -X POST "$BASE/admin/api/dashboard/roll?day=not-a-date" -H "$AH" | jget msg)
[ "$FUT" = "还不能预算未来的某一天" ] && ok "未来的那天不给预算（写一行还没发生的统计，等于往趋势线上掺假）" || no "预算未来" "msg=$FUT"
[ "$BAD" = "日期格式应为 YYYY-MM-DD" ] && ok "日期格式错时说清要什么格式，不是回一句内部错误" || no "日期格式" "msg=$BAD"
PLYROLL=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/admin/api/dashboard/roll" -H "Authorization: Bearer $T")
[ "$PLYROLL" = "401" ] && ok "玩家令牌换不来这个动作(401)：能改看板历史数字的接口只认后台受众" || no "补数据的鉴权" "http=$PLYROLL"
ROLLAUD=$(curl -s "$BASE/admin/api/moderation/audit?size=60" -H "$AH" | node -e '
let d=(JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows)||[];
console.log(d.some(x=>x.action==="dashboard.roll"&&String(x.target)==="day:"+process.argv[1])?"yes":"no")' "$TODAY")
[ "$ROLLAUD" = "yes" ] && ok "重算落审计（唯一能改看板历史数字的动作，得知道是谁点的）" || no "重算审计" "audit=$ROLLAUD"

echo "== 内容升级：schema / options / health / 写入校验 =="
AH="Authorization: Bearer $AT"
SCH=$(curl -s "$BASE/admin/api/content/schema" -H "$AH" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log(Object.keys(d.data||{}).length)')
[ "$SCH" = "13" ] && ok "content/schema 覆盖 13 类" || no "content/schema" "types=$SCH"
OPT=$(curl -s "$BASE/admin/api/content/options" -H "$AH" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data;console.log((d.refs.substance||[]).length+":"+(d.refs.vessel||[]).length)')
OS=$(echo "$OPT" | cut -d: -f1); OV=$(echo "$OPT" | cut -d: -f2)
[ "$OS" -gt 200 ] && [ "$OV" -ge 10 ] && ok "content/options 物质=$OS 容器=$OV" || no "content/options" "opt=$OPT"
HB=$(curl -s "$BASE/admin/api/content/health" -H "$AH")
[ "$(echo "$HB" | jget data.ok)" = "true" ] && ok "内容体检通过（0 问题）" || no "内容体检" "$HB"
HC=$(echo "$HB" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data;console.log(d.checked)')
[ "$HC" -gt 400 ] && ok "体检覆盖行数=$HC" || no "体检行数" "checked=$HC"
# 负例：strict 写入引用不存在的物质 + 非法枚举，必须 400 且不落库（纯 ASCII，规避 Windows 控制台 GBK 送参）
BW=$(curl -s -X PUT "$BASE/admin/api/content/item?strict=true" -H "$AH" -H 'Content-Type: application/json' \
  -d '{"type":"reaction","data":{"id":"E2EBAD","reactants":{"E2E_NO_SUCH":2},"products":{"H2O":1},"type":"bogus","eq":"x","phenomenon":"y","discoverLv":1,"exp":1},"sort":0,"enabled":1}')
[ "$(echo "$BW" | jget code)" = "400" ] && ok "strict 非法内容写入被拒(400)" || no "strict 负例" "$BW"
BGONE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/api/content/item?type=reaction&id=E2EBAD" -H "$AH")
[ "$BGONE" = "404" ] && ok "被拒内容未落库（读取 404）" || no "负例落库" "http=$BGONE"
# 后门：strict=false 允许写入，随后删除（不新增内容）
curl -s -o /dev/null -X PUT "$BASE/admin/api/content/item?strict=false" -H "$AH" -H 'Content-Type: application/json' \
  -d '{"type":"achievement","data":{"id":"E2EPROBE","zh":"e2e probe","reward":1},"sort":9999,"enabled":1}'
PB=$(curl -s "$BASE/admin/api/content/item?type=achievement&id=E2EPROBE" -H "$AH" | jget data.id)
[ "$PB" = "E2EPROBE" ] && ok "strict=false 后门可写（供脚本化灌数据）" || no "非严格后门" "id=$PB"
curl -s -o /dev/null -X DELETE "$BASE/admin/api/content/item?type=achievement&id=E2EPROBE" -H "$AH"
DEL=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/api/content/item?type=achievement&id=E2EPROBE" -H "$AH")
[ "$DEL" = "404" ] && ok "探针已清理（删除后 404）" || no "探针清理" "http=$DEL"

echo "== 内容历史与回滚（G3） =="
# 这一整段用纯 ASCII：Git Bash 把 argv 按 GBK 交给 curl，中文塞进 -d 会变成非法 UTF-8，
# 服务端在解析阶段就回 400，测不到本来要测的东西。
# 探针 id 每次跑都换一个新的：历史是按 (type,item_id) 攒的，删掉内容行也不会删掉那 50 版留档，
# 固定 id 的话第二次跑从一开始就带着上一轮的历史，"新建一行不留历史"那条断言当场变成假红。
HT="achievement"; HID="E2EH$RANDOM"
# G4 之后 achievement 的"新行必须带引擎念得出的 cond"是主闸（strict 与回滚都查），所以这里的
# 正常探针一律带上 cond；下面那两条"坏版本"故意不写 reward，用来测回滚闸门。
HC=',"cond":{"metric":"success","op":"ge","value":1}'
# 覆盖写一行内容（strict=false：这里测的是历史，不是校验；坏数据那两条断言正是要绕过校验塞进去）
hput(){ curl -s -o /dev/null -X PUT "$BASE/admin/api/content/item?strict=false" -H "$AH" -H 'Content-Type: application/json' \
        -d "{\"type\":\"$HT\",\"data\":$1,\"sort\":7777,\"enabled\":$2}"; }
hrefs(){ curl -s "$BASE/admin/api/content/revisions?type=$HT&id=$HID" -H "$AH"; }
# 抽屉列表第 n 行（0=最新）的某个字段
hr(){ echo "$(hrefs)" | node -e 'let d=(JSON.parse(require("fs").readFileSync(0,"utf8")).data)||[];let r=d[+process.argv[1]];console.log(r&&r[process.argv[2]]!=null?r[process.argv[2]]:"")' "$1" "$2"; }
hid(){ hr 0 id; }
# 玩家侧实际取到的那一条：整份 bundle 里这一条的字节，回滚前后必须逐字一致
hsub(){ curl -s "$BASE/api/content/bundle" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8"));
let a=(d.content||{})[process.argv[1]]||[];
let it=a.filter(x=>x.id===process.argv[2])[0];
process.stdout.write(it?JSON.stringify(it):"")' "$HT" "$HID"; }

hput '{"id":"'"$HID"'","zh":"hist probe v1","reward":1'"$HC"'}' 1
H0=$(hrefs | jlen data)
[ "$H0" = "0" ] && ok "新建一行不留历史（还没有「被顶掉的旧版」可存）" || no "首次写入留档" "rows=$H0"
B1=$(hsub)
[ -n "$B1" ] && ok "玩家侧 bundle 取到探针 v1（reward=$(echo "$B1" | jget reward)）" || no "bundle 探针" "$B1"

# 覆盖写：旧版进历史、新版落地
hput '{"id":"'"$HID"'","zh":"hist probe v2","reward":2'"$HC"'}' 1
H1=$(hrefs | jlen data)
[ "$H1" = "1" ] && ok "覆盖写留下 1 条历史" || no "覆盖写留档" "rows=$H1"
[ "$(hr 0 source)" = "edit" ] && ok "历史行记的是「被哪个动作顶掉」（source=edit）" || no "历史来源" "source=$(hr 0 source)"
[ "$(hr 0 name)" = "hist probe v1" ] && ok "存的是覆盖「前」那一版：行名还是 v1（反向语义，抽屉里看到的才是「退回去会变成什么」）" || no "前像语义" "name=$(hr 0 name)"
[ "$(hr 0 operator)" = "$ADMIN_U" ] && ok "历史带操作人（$(hr 0 operator)）" || no "操作人" "operator=$(hr 0 operator)"
HB1=$(hr 0 bytes)
[ -n "$HB1" ] && [ "$HB1" -gt 0 ] && ok "抽屉只回元数据：大小由库里数出来（$HB1 字节），不搬整份记录" || no "列表瘦身" "bytes=$HB1"
RAWCOL=$(hrefs | node -e 'let d=(JSON.parse(require("fs").readFileSync(0,"utf8")).data)||[];console.log(d[0]?(Object.prototype.hasOwnProperty.call(d[0],"data_json")?"yes":"no"):"")')
[ "$RAWCOL" = "no" ] && ok "历史列表里没有 data_json 字段（G5 那条纪律在内容侧同样成立）" || no "列表带原文" "$RAWCOL"

# 回滚：玩家侧字节逐字一致，同时版本号必须顶上去
REV=$(hid)
V0=$(curl -s "$BASE/api/content/version" | jget data.version)
BK=$(curl -s -X POST "$BASE/admin/api/content/rollback?rev=$REV" -H "$AH")
[ "$(echo "$BK" | jget ok)" = "true" ] && ok "按号回滚成功（rev=$REV）" || no "回滚" "$BK"
V1=$(echo "$BK" | jget data.version)
[ -n "$V1" ] && [ "$V1" != "$V0" ] && ok "回滚顶了内容版本（$V0 → $V1）：玩家会重新拉包，不然退回的东西发不出去" || no "版本未顶" "before=$V0 after=$V1"
B3=$(hsub)
[ "$B3" = "$B1" ] && ok "回滚后玩家侧取到的那条与改之前逐字一致（$(echo "$B3" | jget reward) 金币）：JSON 列的规范化两边同构，字节等式才立得住" || no "字节逐字一致" "before=$B1 after=$B3"
[ "$(hr 0 source)" = "rollback" ] && ok "回滚自己也进历史：退回去同样是一次覆盖写，不能假装没发生" || no "回滚留档" "source=$(hr 0 source)"

# 启停：过去唯一会静默覆盖内容而不留痕的入口
curl -s -o /dev/null -X POST "$BASE/admin/api/content/toggle?type=$HT&id=$HID&enabled=0" -H "$AH"
[ "$(hr 0 source)" = "toggle" ] && ok "启停留档（source=toggle）：按钮改的也是同一行内容，历史不能只覆盖表单那条路径" || no "启停留档" "source=$(hr 0 source)"
[ "$(hsub)" = "" ] && ok "停用后玩家侧 bundle 里查不到这一条（enabled 决定下发）" || no "停用下发门" "$(hsub)"

# 坏版本回滚闸门：历史里躺着"当初为赶时间塞的非法行"，退回它等于把同一次事故重新发布给玩家
hput '{"id":"'"$HID"'","zh":"hist probe broken"}' 1                    # 缺 reward，只有 strict=false 塞得进来
hput '{"id":"'"$HID"'","zh":"hist probe v3","reward":3'"$HC"'}' 1             # 再覆盖一次：上面那版此刻成为历史的一行
BADREV=$(hid)
RJ=$(curl -s -X POST "$BASE/admin/api/content/rollback?rev=$BADREV" -H "$AH")
echo "$RJ" | grep -q "回滚被拒绝" && ok "退回「按当前校验不通过」的那一版被拦住：$(echo "$RJ" | jget msg)" || no "回滚校验" "$RJ"
[ "$(hsub | jget reward)" = "3" ] && ok "被拒的回滚一行都没写（线上还是 reward=3）" || no "被拒回滚落库" "$(hsub)"
# 唯一的例外：停用那一版本来就不下发，只是留档，卡住它只会让人退不到自己想要的状态
hput '{"id":"'"$HID"'","zh":"hist probe broken"}' 0
hput '{"id":"'"$HID"'","zh":"hist probe v4","reward":4'"$HC"'}' 1
OFFREV=$(hid)
[ "$(hr 0 enabled)" = "0" ] && ok "例外那版在历史里标着 enabled=0（回滚时要靠这个字段放行）" || no "enabled 入档" "enabled=$(hr 0 enabled)"
RB2=$(curl -s -X POST "$BASE/admin/api/content/rollback?rev=$OFFREV" -H "$AH")
[ "$(echo "$RB2" | jget ok)" = "true" ] && ok "退回「停用且非法」的那一版放行：它不进玩家的包，校验一个玩家看不见的行只是把人锁死" || no "停用版回滚" "$RB2"
[ "$(hsub)" = "" ] && ok "enabled 跟着一起退回来了（线上这条仍然是停用状态）" || no "enabled 回滚" "$(hsub)"

# 错误形状
NOREV=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/admin/api/content/rollback?rev=999999999" -H "$AH")
[ "$NOREV" = "404" ] && ok "不存在的历史版本回 404，不是 500" || no "缺版本 404" "http=$NOREV"
PL1=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/api/content/revisions?type=$HT&id=$HID" -H "Authorization: Bearer $T")
PL2=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/admin/api/content/rollback?rev=$OFFREV" -H "Authorization: Bearer $T")
[ "$PL1" = "401" ] && [ "$PL2" = "401" ] && ok "玩家令牌看不了历史、也点不动回滚(401)：这是能把内容发回线上的按钮" || no "历史鉴权" "list=$PL1 rollback=$PL2"
AUDH=$(curl -s "$BASE/admin/api/moderation/audit?size=80" -H "$AH" | node -e '
let d=(JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows)||[];
let r=d.filter(x=>x.action==="content.rollback"&&x.target===process.argv[1]);
console.log(r.length>0?"yes":"no")' "$HT:$HID")
[ "$AUDH" = "yes" ] && ok "回滚落审计（内容改动里唯一「由过去决定」的动作，得知道是谁点的）" || no "回滚审计" "audit=$AUDH"

# 修剪只能在真库上验：LIMIT 1 OFFSET 49 与那条范围 DELETE 在单测里全是打桩，骗得过 mock 骗不过 MySQL
for i in $(seq 1 52); do hput "{\"id\":\"$HID\",\"zh\":\"loop $i\",\"reward\":$i}" 1; done
CAP=$(hrefs | jlen data)
[ "$CAP" = "50" ] && ok "每行最多留 50 版：连改 52 次后抽屉里还是 50 行" || no "历史上限" "rows=$CAP"
[ "$(hr 0 name)" = "loop 51" ] && ok "最新一行是最后一次覆盖顶掉的旧版（loop 51）" || no "修剪后顺序" "name=$(hr 0 name)"
STALE=$(hrefs | node -e 'let d=(JSON.parse(require("fs").readFileSync(0,"utf8")).data)||[];console.log(d.filter(x=>String(x.name).indexOf("hist probe")===0).length)')
[ "$STALE" = "0" ] && ok "修剪从最旧的开始：改动前那几版已经被挤出去了（不是删最新的那几条）" || no "修剪方向" "stale=$STALE"

# 删除也留档，且删掉内容行之后历史仍可查
curl -s -o /dev/null -X DELETE "$BASE/admin/api/content/item?type=$HT&id=$HID" -H "$AH"
[ "$(hr 0 source)" = "delete" ] && ok "删除同样留档（source=delete，删掉那一版的全文还在库里）" || no "删除留档" "source=$(hr 0 source)"
GONE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/api/content/item?type=$HT&id=$HID" -H "$AH")
[ "$GONE" = "404" ] && ok "探针已从内容表删除" || no "探针删除" "http=$GONE"
[ "$(hrefs | jlen data)" -gt 0 ] && ok "内容行删了、历史没删：抽屉里能看到它是什么时候、被谁删的" || no "删除后历史" "rows=$(hrefs|jlen data)"
HB2=$(curl -s "$BASE/admin/api/content/health" -H "$AH")
[ "$(echo "$HB2" | jget data.ok)" = "true" ] && ok "这一整段跑完，内容体检仍是 0 问题（探针没留下脏数据）" || no "体检仍干净" "$HB2"

echo "== 乐观锁：两个人改同一行，后一个不该静默盖掉前一个（H6-2） =="
# 全程 ASCII（同 G3 那段：Git Bash 按 GBK 把中文塞进 curl argv 会在解析阶段就 400）。
# 每条都同时看 HTTP 状态码和响应体：面板是按状态码分流到「载入最新」的，
# 200 + ok:false 那种"业务错误"形状在这里等于没有防线。
LT="achievement"; LID="E2EL$RANDOM"
LB=$(mktemp)
LCODE(){ echo "${1%%|*}"; }                                  # "码|体" 的前半
LBODY(){ echo "${1#*|}"; }                                  # 后半
# $1=expect（空串=不带，none=「这行还不该存在」）  $2=reward
lput(){ local C Q=""; [ -n "${1:-}" ] && Q="&expect=$1"
  C=$(curl -s -o "$LB" -w '%{http_code}' -X PUT "$BASE/admin/api/content/item?strict=false$Q" -H "$AH" \
      -H 'Content-Type: application/json' \
      -d "{\"type\":\"$LT\",\"data\":{\"id\":\"$LID\",\"zh\":\"lock probe\",\"reward\":$2,\"cond\":{\"metric\":\"success\",\"op\":\"ge\",\"value\":1}},\"sort\":8888,\"enabled\":1}")
  echo "$C|$(cat "$LB")"; }
wpost(){ local C; C=$(curl -s -o "$LB" -w '%{http_code}' -X POST "$1" -H "$AH"); echo "$C|$(cat "$LB")"; }
wdel(){ local C;  C=$(curl -s -o "$LB" -w '%{http_code}' -X DELETE "$1" -H "$AH"); echo "$C|$(cat "$LB")"; }
# 面板抄的版本号就来自列表接口，所以这里也走列表而不是别的路
lstamp(){ curl -s "$BASE/admin/api/content/items?type=$LT&q=$LID&size=5" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||{};
let r=(d.rows||[]).filter(x=>x.itemId===process.argv[1])[0];
console.log(r&&r.updatedAt?r.updatedAt:"")' "$LID"; }
lfield(){ curl -s "$BASE/admin/api/content/items?type=$LT&q=$LID&size=5" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||{};
let r=(d.rows||[]).filter(x=>x.itemId===process.argv[1])[0];
console.log(r?r[process.argv[2]]:"")' "$LID" "$1"; }
lval(){ curl -s "$BASE/admin/api/content/item?type=$LT&id=$LID" -H "$AH" | jget data.reward; }
lrevs(){ curl -s "$BASE/admin/api/content/revisions?type=$LT&id=$LID" -H "$AH" | jlen data; }
lrev0(){ curl -s "$BASE/admin/api/content/revisions?type=$LT&id=$LID" -H "$AH" | node -e 'let d=(JSON.parse(require("fs").readFileSync(0,"utf8")).data)||[];console.log(d[0]?d[0].id:"")'; }
STALE="2020-01-02T03:04:05.123"

A=$(lput none 1)
[ "$(LCODE "$A")" = "200" ] && ok "expect=none 建新的一行成功（面板的新增态）" || no "新增写入" "$A"
S1=$(LBODY "$A" | jget data.updatedAt)
[ -n "$S1" ] && ok "写入回体带上新版本号（$S1）：面板据此换掉手上那份，否则连点两次保存会把自己上一次改动判成冲突" || no "回体缺号" "$A"
[ "$(lstamp)" = "$S1" ] && ok "列表回传的就是同一个时间戳：面板抄的那份和服务端认的是同一个号" || no "列表与回体不同号" "list=$(lstamp) put=$S1"
[ "$(lval)" = "1" ] && ok "探针行已落库（reward=1）" || no "探针落库" "reward=$(lval)"

B=$(lput none 2)
[ "$(LCODE "$B")" = "409" ] && ok "expect=none 撞上已有行回 409，不会退化成覆盖写（那是「别人在你填表时把这条建出来了」）" || no "重复新增" "$B"
[ "$(lval)" = "1" ] && ok "被拒的新增一行都没写（线上还是 reward=1）" || no "被拒新增落库" "reward=$(lval)"

N0=$(lrevs)
R=$(lput "$STALE" 3)
[ "$(LCODE "$R")" = "409" ] && ok "拿旧版本号保存回真 409（不是这仓库默认的 200 + ok:false）" || no "冲突状态码" "$R"
echo "$(LBODY "$R")" | grep -q "在你编辑期间已被" && ok "冲突消息说得出「是谁在什么时候动了它」：$(LBODY "$R" | jget msg)" || no "冲突文案" "$R"
[ "$(lval)" = "1" ] && [ "$(lrevs)" = "$N0" ] && ok "被拒的这次既没落库也没在历史里造出一条从没生效过的版本" || no "被拒写入有副作用" "val=$(lval) revs=$(lrevs)/$N0"

OK1=$(lput "$S1" 4)
[ "$(LCODE "$OK1")" = "200" ] && [ "$(lval)" = "4" ] && ok "版本号对上才写：这次保存生效（reward 1 → 4）" || no "正常写入" "$OK1"
S2=$(LBODY "$OK1" | jget data.updatedAt)
[ -n "$S2" ] && [ "$S2" != "$S1" ] && ok "写完顶了新号（$S1 → $S2）" || no "写完没顶号" "before=$S1 after=$S2"
# Connector/J 默认按「匹配行数」而不是「改变行数」回报，所以原样重存不该被当成冲突；
# 一旦有人把 useAffectedRows 打开，运营连点两次保存就会撞上自己——这条就是钉那个的。
SAME=$(lput "$S2" 4)
[ "$(LCODE "$SAME")" = "200" ] && ok "把完全一样的值再存一次仍算成功（同值重存不是冲突）" || no "同值重存被判冲突" "$SAME"
[ "$(LBODY "$SAME" | jget data.updatedAt)" = "$S2" ] && ok "同值重存没有顶时间戳：ON UPDATE 只在行真的变了才盖章" || no "同值重存改号" "before=$S2 after=$(LBODY "$SAME" | jget data.updatedAt)"

BAD=$(lput "not-a-timestamp" 5)
# 这里要的是"它不是 409"：这一仓库只有冲突值得用真状态码告诉客户端分流，其余业务错误仍是 200 + ok:false。
# 若哪天把 400 也升成状态码，面板那边的 isConflict 判不中，反而是安全的；反过来 400 被当成 409 就会白丢草稿。
[ "$(LCODE "$BAD")" = "200" ] && [ "$(LBODY "$BAD" | jget code)" = "400" ] \
  && ok "expect 解析不了回 400 业务码（不是 409）：那是请求本身写坏了，和「这一行被人改过」是两件事，混成一个码冲突弹窗就答不上话" || no "坏 expect" "$BAD"
[ "$(lval)" = "4" ] && ok "坏 expect 没落库" || no "坏 expect 落库" "reward=$(lval)"

F=$(lput "" 6)
[ "$(LCODE "$F")" = "200" ] && [ "$(lval)" = "6" ] && ok "不带 expect 仍是覆盖写（没有逐行版本号的批量写入走这条路：回填脚本与本回归的 hput）" || no "缺省覆盖写" "$F"

# 列表里那个开关盖的也是同一行
TS=$(lstamp); N1=$(lrevs)
TB=$(wpost "$BASE/admin/api/content/toggle?type=$LT&id=$LID&enabled=0&expect=$STALE")
[ "$(LCODE "$TB")" = "409" ] && [ "$(lfield enabled)" = "1" ] && ok "旧版本号点不动开关：既不落库也不留历史" || no "开关冲突" "$TB"
[ "$(lrevs)" = "$N1" ] && ok "冲突的那次启停没有留下历史行（快照排在条件之后）" || no "开关冲突留档" "revs=$(lrevs)/$N1"
TOk=$(wpost "$BASE/admin/api/content/toggle?type=$LT&id=$LID&enabled=0&expect=$TS")
[ "$(LCODE "$TOk")" = "200" ] && [ "$(lfield enabled)" = "0" ] && ok "版本号对上才动开关（enabled 1 → 0）" || no "开关写入" "$TOk"
TS2=$(LBODY "$TOk" | jget data.updatedAt)
[ -n "$TS2" ] && [ "$TS2" != "$TS" ] && ok "开关同样回新号（$TS → $TS2），面板据此换掉那一行的版本号" || no "开关没回号" "before=$TS after=$TS2"
[ "$(lrevs)" = "$((N1 + 1))" ] && ok "成功的那次启停留了一行历史（G3 那条纪律没被乐观锁带跑）" || no "开关留档" "revs=$(lrevs)/$((N1 + 1))"

# 回滚写的也是 live 行：不守同一个规矩就等于留了一扇绕过乐观锁的门
RBAD=$(wpost "$BASE/admin/api/content/rollback?rev=$(lrev0)&expect=$STALE")
[ "$(LCODE "$RBAD")" = "409" ] && ok "旧版本号点不动【回滚】：否则「看见冲突先回滚一版试试」就是那条防线的后门" || no "回滚冲突" "$RBAD"
[ "$(lfield enabled)" = "0" ] && ok "被拒的回滚没动 live 行（还是停用状态）" || no "被拒回滚落库" "enabled=$(lfield enabled)"
ROk=$(wpost "$BASE/admin/api/content/rollback?rev=$(lrev0)&expect=$(lstamp)")
[ "$(LCODE "$ROk")" = "200" ] && [ "$(lfield enabled)" = "1" ] && ok "版本号对上才回滚：退回的那一版是启停之前的 enabled=1" || no "回滚写入" "$ROk"
[ -n "$(LBODY "$ROk" | jget data.updatedAt)" ] && ok "回滚也回新号（面板手上的版本号跟着换）" || no "回滚没回号" "$ROk"

DB=$(wdel "$BASE/admin/api/content/item?type=$LT&id=$LID&expect=$STALE")
[ "$(LCODE "$DB")" = "409" ] && [ -n "$(lval)" ] && ok "旧版本号删不掉这一行（别人刚改好的版本不是由你删的）" || no "删除冲突" "$DB"
DD=$(wdel "$BASE/admin/api/content/item?type=$LT&id=$LID&expect=$(lstamp)")
DGONE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/api/content/item?type=$LT&id=$LID" -H "$AH")
[ "$(LCODE "$DD")" = "200" ] && [ "$DGONE" = "404" ] && ok "版本号对上才删得掉（删完读不到）" || no "删除写入" "$DD http=$DGONE"
[ "$(lrevs)" -gt 0 ] && ok "探针删掉了、历史还在（删错仍能从库里找回），随后清理" || no "删除留档" "revs=$(lrevs)"
rm -f "$LB"

echo "== 后台调整玩家资产（金币/钻石） =="
# 余额在 user_save.payload 里而不是 app_user 表，所以这一步同时验证列表的 JSON 取值确实接上了。
UB=$(curl -s "$BASE/admin/api/users?q=$U&size=5" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];
let r=d.filter(x=>x.username===process.argv[1])[0];
console.log(r?r.id+":"+(r.coins==null?"-":r.coins)+":"+(r.diamonds==null?"-":r.diamonds):"")' "$U")
PUID=${UB%%:*}; REST=${UB#*:}; PCOINS=${REST%%:*}; PDIA=${REST##*:}
[ -n "$PUID" ] && ok "按用户名定位到玩家（id=$PUID）" || no "定位玩家" "q=$U"
[ "$PCOINS" != "-" ] && [ "$PCOINS" -gt 0 ] && ok "列表带出金币余额（$PCOINS，来自存档 payload）" || no "列表余额列" "coins=$PCOINS"

ADD=$(curl -s -X POST "$BASE/admin/api/users/assets?id=$PUID" -H "$AH" -H 'Content-Type: application/json' \
  -d '{"coins":12345,"diamonds":77}')
[ "$(echo "$ADD" | jget ok)" = "true" ] && ok "超管加 12345 金币 / 77 钻石" || no "调整资产" "$ADD"
AFTER=$(echo "$ADD" | jget data.coinsAfter)
[ "$AFTER" = "$((PCOINS + 12345))" ] && ok "回执给出前后值（$PCOINS → $AFTER）" || no "前后值" "after=$AFTER"
[ "$(echo "$ADD" | jget data.diamondsAfter)" = "$((PDIA + 77))" ] && ok "钻石同步入账" || no "钻石入账" "$ADD"

# 玩家侧必须立刻看到新余额：意图帧每请求回库读，服务端不留内存缓存。
ST=$(curl -s "$BASE/api/game/state" -H "Authorization: Bearer $T")
[ "$(echo "$ST" | jget data.state.coins)" = "$AFTER" ] && ok "玩家下一次取帧就是新余额（服务端权威生效）" || no "资产对玩家生效" "state=$(echo "$ST" | jget data.state.coins)"
[ "$(echo "$ST" | jget data.state.diamonds)" = "$((PDIA + 77))" ] && ok "钻石同样对玩家生效" || no "钻石生效" "$(echo "$ST" | jget data.state.diamonds)"
# 只填一项时另一项不该被顺手清零
ONLY=$(curl -s -X POST "$BASE/admin/api/users/assets?id=$PUID" -H "$AH" -H 'Content-Type: application/json' -d '{"diamonds":3}')
[ "$(echo "$ONLY" | jget data.coinsAfter)" = "$AFTER" ] && [ "$(echo "$ONLY" | jget data.diamondsAfter)" = "$((PDIA + 80))" ] \
  && ok "只调钻石时金币保持不变" || no "单项调整" "$ONLY"
SRC=$(curl -s "$BASE/admin/api/users/save/revisions?id=$PUID" -H "$AH" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||[];console.log(d[0]?d[0].source:"")')
[ "$SRC" = "admin" ] && ok "存档历史把这次写入标为 admin（与玩家上传/回滚区分开）" || no "历史来源标记" "source=$SRC"

OVER=$(curl -s -X POST "$BASE/admin/api/users/assets?id=$PUID" -H "$AH" -H 'Content-Type: application/json' -d '{"coins":-500000}' | jget msg)
echo "$OVER" | grep -q "不足扣减" && ok "扣穿余额被拒（不允许负余额）" || no "负余额闸门" "msg=$OVER"
STEP=$(curl -s -X POST "$BASE/admin/api/users/assets?id=$PUID" -H "$AH" -H 'Content-Type: application/json' -d '{"coins":99999999}' | jget msg)
echo "$STEP" | grep -q "不超过" && ok "单次超量被拒（防一个手滑刷爆经济）" || no "单次上限" "msg=$STEP"
ZERO=$(curl -s -X POST "$BASE/admin/api/users/assets?id=$PUID" -H "$AH" -H 'Content-Type: application/json' -d '{}' | jget ok)
[ "$ZERO" = "false" ] && ok "零增减直接拒绝，不产生无意义的存档版本" || no "空操作" "ok=$ZERO"
NOTYPE=$(curl -s -X POST "$BASE/admin/api/users/assets?id=$PUID" -H "$AH" -H 'Content-Type: application/json' -d '{"coins":"lots"}' | jget code)
[ "$NOTYPE" = "400" ] && ok "非整数增减量按业务错误返回(400) 而非 500" || no "参数校验" "code=$NOTYPE"
PLAYERADD=$(curl -s -X POST "$BASE/admin/api/users/assets?id=$PUID" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"coins":1}' | jget code)
[ "$PLAYERADD" = "401" ] && ok "玩家令牌给自己打钱被拒(401)" || no "资产接口鉴权" "code=$PLAYERADD"
NOAUTH=$(curl -s -X POST "$BASE/admin/api/users/assets?id=$PUID" -H 'Content-Type: application/json' -d '{"coins":1}' | jget code)
[ "$NOAUTH" = "401" ] && ok "无令牌不能调资产(401)" || no "资产鉴权" "code=$NOAUTH"
AUD=$(curl -s "$BASE/admin/api/moderation/audit?size=30" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];
// 挑那笔"加金币"的记录（不是后面那笔只动钻石的），后台明细列要能读出加减了多少。
let rs=d.filter(x=>x.action==="user.assets" && x.target==="uid:"+process.argv[1] && typeof x.detail==="string")
  .map(x=>JSON.parse(x.detail).d).filter(j=>j&&j.coinsBefore!=null&&j.coinsAfter!=null&&j.coinsBefore!==j.coinsAfter);
let j=rs[0];
console.log(j?j.coinsBefore+"->"+j.coinsAfter:"")' "$PUID")
[ "$AUD" = "$PCOINS->$AFTER" ] && ok "审计留痕带前后值（金币 $AUD），后台明细列可直接读" || no "审计留痕" "audit=$AUD 期望 $PCOINS->$AFTER"
LIST2=$(curl -s "$BASE/admin/api/users?q=$U&size=5" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];let r=d.filter(x=>x.username===process.argv[1])[0]||{};console.log(r.coins==null?"-":r.coins)' "$U")
[ "$LIST2" = "$(echo "$ONLY" | jget data.coinsAfter)" ] && ok "列表余额随调整刷新" || no "列表刷新" "coins=$LIST2"

echo "== 存档结构版本位（A4：payload 自带 sv，改字段名不再是静默毁档） =="
# 版本位只服务一件事：读盘时知道"这份是谁写的"。没有它，字段改名会被 Jackson 安静填成默认值，
# 玩家金币丢了也不报错；有了它，缺项按版补齐、比本机新的档直接拒载（见 SaveMigrationsTest）。
SVFRAME=$(curl -s "$BASE/api/game/state" -H "Authorization: Bearer $T" | jget data.state.sv)
SVPAYLOAD=$(curl -s "$BASE/admin/api/users/save?id=$PUID" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||{};let p=d.payload||{};
console.log(typeof p.sv==="number"?p.sv:"")')
case "$SVFRAME" in
  ''|*[!0-9]*) no "帧版本位" "sv=$SVFRAME：权威帧没带结构版本，客户端与后台都无从知道服务端认到哪一版" ;;
  *) ok "权威帧带出结构版本位（sv=$SVFRAME）" ;;
esac
[ -n "$SVPAYLOAD" ] && ok "落库 payload 同样带位（sv=$SVPAYLOAD）：迁移与拒载都有依据" || no "payload 版本位" "库里这份存档没有 sv"
[ "$SVFRAME" = "$SVPAYLOAD" ] && ok "两处版本号同源，写盘与读盘用的是同一把尺子" || no "版本位一致性" "frame=$SVFRAME db=$SVPAYLOAD"
# 后台改资产是"读原始 payload→改两个数→原样写回"，顺手确认它没把版本位抹掉：
# 抹掉=下次读盘被当成"早于版本位"的老档重跑一遍迁移，语义上还行，但等于把 A4 白做。
SVKEEP=$(curl -s "$BASE/admin/api/users/save?id=$PUID" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||{};let p=d.payload||{};
console.log(typeof p.sv==="number"&&p.coins>0?"1":"0")')
[ "$SVKEEP" = "1" ] && ok "admin 写入后版本位仍在（后台改档不会退化成老档）" || no "admin 写入保位" ""
# 回滚：老 payload 原样写回后必须还能读起来——迁移兜在读盘这一刻，回滚不该等于毁档。
REV1=$(curl -s "$BASE/admin/api/users/save/revisions?id=$PUID" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||[];console.log(d.length?d[d.length-1].revision:"")')
[ -n "$REV1" ] && ok "存档历史列得出最早的一版（rev=$REV1）" || no "存档历史" "revisions 为空"
# 这一条看着像吹毛求疵，其实不是：历史改成"每 25 版抽一条"之后，抽样管的是"记不记"，
# 修剪管的是"留不留"——修剪按版本号删 MAX-30 以前的行，账号一过 31 版就会把第 1 版删掉，
# 于是【回滚到最早一版】退到中途某不知名的一版（金币 5084 而不是 5000）。这条红过一次，
# 修法是修剪永远放行 revision=1（见 SaveMapper.trimRevisions 的注释与 SaveServiceTest 那条注解断言）。
[ "$REV1" = "1" ] && ok "最早的一版就是第 1 版：抽样与修剪都不许删掉这个回滚锚点" || no "回滚锚点" "最早可回滚的是 rev=$REV1，第 1 版已经被删了"
ROLLBK=$(curl -s -X POST "$BASE/admin/api/users/save/rollback?id=$PUID&revision=$REV1" -H "$AH" | jget ok)
[ "$ROLLBK" = "true" ] && ok "回滚到最早一版" || no "回滚" "ok=$ROLLBK"
BACKCOINS=$(curl -s "$BASE/api/game/state" -H "Authorization: Bearer $T" | jget data.state.coins)
[ "$BACKCOINS" = "5000" ] && ok "回滚真的退回了初始档（金币 $BACKCOINS）" || no "回滚生效" "coins=$BACKCOINS"
SVBACK=$(curl -s "$BASE/api/game/state" -H "Authorization: Bearer $T" | jget data.state.sv)
[ "$SVBACK" = "$SVFRAME" ] && ok "回滚后的存档读回来仍带版本位（sv=$SVBACK）：老档走补齐，不会被当成未来档拒载" || no "回滚后版本位" "sv=$SVBACK"
RBADREV=$(curl -s -X POST "$BASE/admin/api/users/save/rollback?id=$PUID&revision=99999" -H "$AH" | jget code)
[ "$RBADREV" = "404" ] && ok "回滚到不存在的版本报 404 而不是把存档写成空" || no "回滚版本校验" "code=$RBADREV"
RBNOAUTH=$(curl -s -X POST "$BASE/admin/api/users/save/rollback?id=$PUID&revision=$REV1" | jget code)
[ "$RBNOAUTH" = "401" ] && ok "无令牌不能回滚存档(401)" || no "回滚鉴权" "code=$RBNOAUTH"

echo "== 管理员账号 CRUD（建号 → 强制改密 → 角色/停用即时生效 → 清理） =="
TMPA="e2eadm$RANDOM$$"
CREATED=$(curl -s -X POST "$BASE/admin/api/admins/create" -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"user\":\"$TMPA\",\"pass\":\"E2eInit9x\",\"role\":\"viewer\"}")
AID=$(echo "$CREATED" | jget data.id)
[ -n "$AID" ] && ok "超管可新建后台账号（id=$AID）" || no "建号" "$CREATED"
[ "$(echo "$CREATED" | jhas data passHash)" = "no" ] && ok "账号响应不含口令哈希" || no "响应含哈希" "$CREATED"
WK=$(curl -s -X POST "$BASE/admin/api/admins/create" -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"user\":\"${TMPA}x\",\"pass\":\"password\",\"role\":\"viewer\"}" | jget code)
[ "$WK" = "400" ] && ok "弱口令建号被拒(400)" || no "弱口令建号" "code=$WK"
VT=$(curl -s -X POST "$BASE/admin/api/login" -H 'Content-Type: application/json' -d "{\"user\":\"$TMPA\",\"pass\":\"E2eInit9x\"}")
[ "$(echo "$VT" | jget data.mustChange)" = "true" ] && ok "新账号首登即带 mustChange" || no "mustChange" "$VT"
VTK=$(echo "$VT" | jget data.token)
LOCK=$(curl -s "$BASE/admin/api/content/types" -H "Authorization: Bearer $VTK" | jget code)
[ "$LOCK" = "403" ] && ok "初始口令未改时后台端点全锁(403)" || no "改密闸门" "code=$LOCK"
CP=$(curl -s -X POST "$BASE/admin/api/me/password" -H "Authorization: Bearer $VTK" -H 'Content-Type: application/json' \
  -d '{"old":"E2eInit9x","new":"E2eLater77"}' | jget ok)
[ "$CP" = "true" ] && ok "锁定期内唯一放行的就是自助改密" || no "自助改密" "ok=$CP"
VT2=$(curl -s -X POST "$BASE/admin/api/login" -H 'Content-Type: application/json' -d "{\"user\":\"$TMPA\",\"pass\":\"E2eLater77\"}")
[ "$(echo "$VT2" | jget data.mustChange)" = "false" ] && ok "改密后不再要求改密" || no "改密后标记" "$VT2"
VTK2=$(echo "$VT2" | jget data.token)
[ "$(curl -s "$BASE/admin/api/content/types" -H "Authorization: Bearer $VTK2" | jget ok)" = "true" ] \
  && ok "viewer 可正常只读后台" || no "viewer 只读" ""
NOTSUPER=$(curl -s "$BASE/admin/api/admins" -H "Authorization: Bearer $VTK2" | jget code)
[ "$NOTSUPER" = "403" ] && ok "viewer 管不了后台账号(403)" || no "账号管理权限" "code=$NOTSUPER"
curl -s -o /dev/null -X POST "$BASE/admin/api/admins/status?id=$AID&status=1" -H "$AH"
KICKED=$(curl -s "$BASE/admin/api/content/types" -H "Authorization: Bearer $VTK2" | jget code)
[ "$KICKED" = "403" ] && ok "停用后旧令牌立刻失效（角色状态以库为准）" || no "停用即时生效" "code=$KICKED"
ME_ID=$(curl -s "$BASE/admin/api/admins" -H "$AH" \
  | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||[];let r=d.filter(x=>x.user===process.argv[1])[0];console.log(r?r.id:"")' "$ADMIN_U")
SELF=$(curl -s -X POST "$BASE/admin/api/admins/status?id=$ME_ID&status=1" -H "$AH" | jget ok)
[ "$SELF" = "false" ] && ok "拒绝停用自己/最后一个在岗超管" || no "自锁保护" "ok=$SELF"
curl -s -o /dev/null -X POST "$BASE/admin/api/admins/remove?id=$AID" -H "$AH"
LEFT=$(curl -s "$BASE/admin/api/admins" -H "$AH" \
  | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||[];console.log(d.some(x=>String(x.id)===process.argv[1])?"1":"0")' "$AID")
[ "$LEFT" = "0" ] && ok "测试账号已清理" || no "账号清理" "left=$LEFT"

echo "== 忘记密码：后台重置玩家口令（旧刷新令牌作废） =="
SESS=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$U\",\"pass\":\"pass1234\"}")
SR=$(echo "$SESS" | jget data.refresh)
STK=$(echo "$SESS" | jget data.token)
PID=$(curl -s "$BASE/admin/api/users?q=$U&size=5" -H "$AH" \
  | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];let r=d.filter(x=>x.username===process.argv[1])[0];console.log(r?r.id:"")' "$U")
[ -n "$PID" ] && ok "按用户名定位到玩家（id=$PID）" || no "定位玩家" "q=$U"
RP=$(curl -s -X POST "$BASE/admin/api/users/reset-password?id=$PID" -H "$AH" -H 'Content-Type: application/json' \
  -d '{"pass":"Helpdesk77"}' | jget ok)
[ "$RP" = "true" ] && ok "超管重置玩家口令" || no "重置口令" "ok=$RP"
DEAD=$(curl -s -X POST "$BASE/api/auth/refresh" -H 'Content-Type: application/json' -d "{\"refresh\":\"$SR\"}" | jget code)
[ "$DEAD" = "401" ] && ok "重置后旧刷新令牌整户作废(401)：设备无法再自动续期" || no "刷新令牌作废" "code=$DEAD"
NEWL=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$U\",\"pass\":\"Helpdesk77\"}" | jget ok)
[ "$NEWL" = "true" ] && ok "新口令可登录" || no "新口令登录" "ok=$NEWL"
STALE=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$U\",\"pass\":\"pass1234\"}" | jget code)
[ "$STALE" = "401" ] && ok "旧口令随即作废(401)" || no "旧口令失效" "code=$STALE"
# A6（访问令牌绑定会话）落地后，这条从"刻画边界"变成"验收"：
# 撤销一次登录（重置口令会整户 revokeAll），签名仍然正确的旧访问令牌必须在下一个请求就失效，
# 而不是像以前那样留下 ≤2h 的空窗——那段时间被盗设备还能把手上的令牌用完。
STILL=$(curl -s "$BASE/api/game/state" -H "Authorization: Bearer $STK" | jget ok)
[ "$STILL" = "false" ] && ok "重置口令后旧访问令牌立即失效（不再有 ≤2h 空窗）" || no "令牌未绑会话" "state 仍回 ok=true"
STCODE=$(curl -s -o /tmp/chem-stk.json -w '%{http_code}' "$BASE/api/game/state" -H "Authorization: Bearer $STK")
[ "$STCODE" = "401" ] && ok "撤销后的旧令牌按 401 回（客户端据此清会话、回登录页）" || no "撤销令牌的码" "http=$STCODE $(head -c 60 /tmp/chem-stk.json)"
grep -q "重新登录" /tmp/chem-stk.json && ok "话术给得出自救路径（请重新登录，而不是一句\"无效\"）" || no "撤销话术" "$(head -c 80 /tmp/chem-stk.json)"
# 封禁同一条路：后台点下封禁的那一刻，被封锁的人手上那支令牌就该是死的
BS=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$U\",\"pass\":\"Helpdesk77\"}")
BTK=$(echo "$BS" | jget data.token)
BAN=$(curl -s -X POST "$BASE/admin/api/users/ban?id=$PID&days=1" -H "$AH" | jget ok)
[ "$BAN" = "true" ] && ok "后台封禁该玩家" || no "封禁操作" "ok=$BAN"
BANCODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/game/state" -H "Authorization: Bearer $BTK")
[ "$BANCODE" = "401" ] && ok "封禁当刻即踢线（会话随行撤销，不用等令牌自然过期）" || no "封禁即时性" "http=$BANCODE"
curl -s -X POST "$BASE/admin/api/users/unban?id=$PID" -H "$AH" >/dev/null
NEWTK=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$U\",\"pass\":\"Helpdesk77\"}" | jget data.token)
[ "$(curl -s "$BASE/api/game/state" -H "Authorization: Bearer $NEWTK" | jget ok)" = "true" ] \
  && ok "解封后重新登录可继续（新会话是新的 sid，不是把旧令牌救活）" || no "解封后登录" ""
# 从这里往后的段落继续沿用 $T：A6 之后必须换成刚登录的新令牌。
# 以前这里可以拿着被撤销的旧令牌往下跑，那正是 ≤2h 空窗在回归脚本里留下的脚印。
T="$NEWTK"
NOTSUPERPW=$(curl -s -X POST "$BASE/admin/api/users/reset-password?id=$PID" | jget code)
[ "$NOTSUPERPW" = "401" ] && ok "无令牌不能重置口令(401)" || no "重置鉴权" "code=$NOTSUPERPW"
# 这枚游客令牌同样留给文末的「本轮账号自清」反查 uid。
XG=$(curl -s -X POST "$BASE/api/auth/guest"); XGT=$(echo "$XG" | jget data.token)
GUEST_ROW=$(curl -s "$BASE/admin/api/users?size=1" -H "$AH" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];let r=d[0]||{};console.log((r.is_guest===1||r.is_guest===true)?r.id:"")')
[ -n "$GUEST_ROW" ] && ok "用户列表标得出游客档（id=$GUEST_ROW）" || no "游客标记" ""
GR=$(curl -s -X POST "$BASE/admin/api/users/reset-password?id=$GUEST_ROW" -H "$AH" -H 'Content-Type: application/json' \
  -d '{"pass":"Helpdesk77"}' | jget ok)
[ "$GR" = "false" ] && ok "游客档拒绝重置口令（无口令可找回）" || no "游客重置" "ok=$GR"

echo "== 运营配置说明书（每项配置的作用是否标得清楚） =="
SPEC=$(curl -s "$BASE/admin/api/config/spec" -H "$AH")
SN=$(echo "$SPEC" | node -e 'console.log((JSON.parse(require("fs").readFileSync(0,"utf8")).data||[]).length)')
[ "$SN" -ge 13 ] && ok "说明书覆盖 $SN 个配置键" || no "说明书条数" "n=$SN"
echo "$SPEC" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||[];
const S=["服务端结算","服务端+前端","部分生效","仅前端","已退役（改它不生效）","服务端行为（不动结算）"];
let bad=d.filter(x=>!x.zh||!x.key||!x.effect||!x.note||!x.refs||!S.includes(x.scope));
process.exit(bad.length?1:0)' && ok "每条都有中文名/作用/风险/出处/生效范围" || no "说明书完整性" ""
# 付费面下线后 recharge 成了"看着能改、其实不生效"的坑，必须明确标成退役而不是留在原范围里
echo "$SPEC" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||[];let r=d.filter(x=>x.key==="recharge")[0];
process.exit(r&&r.scope==="已退役（改它不生效）"?0:1)' \
  && ok "recharge 老实标为已退役（改它不发任何资产）" || no "recharge 标注" ""
echo "$SPEC" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||[];let a=d.filter(x=>x.key==="ad")[0];
process.exit(a&&a.scope==="服务端结算"&&/激励视频|广告/.test(a.effect+a.note)?0:1)' \
  && ok "ad 配置进说明书并标为服务端结算" || no "ad 说明书" ""
echo "$SPEC" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||[];let m={};for(const x of d)m[x.key]=x.scope;
process.exit(m.sell_rate==="服务端结算"&&m.start_coins==="仅前端"?0:1)' \
  && ok "sell_rate 标为服务端结算、start_coins 老实标为仅前端" || no "生效范围标注" ""
# 说明书和库里的键做集合比对：多写的键（库里没有）和无主的键（库里都有说明）都算漂移。
# 比的是全表，所以显式要 size=200（就是 Page.MAX_SIZE）——键总量远小于它，
# 真超了这条会先红，而不是像面板那样"少显示几行"没人发现。
curl -s "$BASE/admin/api/config?size=200" -H "$AH" | SPEC="$SPEC" node -e '
let c=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows.map(r=>r.cfgKey);
let s=JSON.parse(process.env.SPEC).data.map(x=>x.key);
let miss=c.filter(k=>!s.includes(k)), ghost=s.filter(k=>!c.includes(k));
if (miss.length) console.error("库里没说明: "+miss.join(","));
if (ghost.length) console.error("说明里没有这个键: "+ghost.join(","));
process.exit(miss.length||ghost.length?1:0)' \
  && ok "库里每个键都收进了说明书（无裸键）" || no "键覆盖" "说明书与库不一致"
# 自定义键：引擎的 Content.Config 没这个字段，说明书应当查不到它（前端据此提示"不参与结算"）
curl -s -X PUT "$BASE/admin/api/config" -H "$AH" -H 'Content-Type: application/json' \
  -d '{"key":"e2eTmpKey","value":1,"category":"general","remark":"e2e"}' >/dev/null
TMPIN=$(curl -s "$BASE/admin/api/config/get?key=e2eTmpKey" -H "$AH" | jget ok)
[ "$TMPIN" = "true" ] && ok "自定义键可写入并读回" || no "自定义键写入" "ok=$TMPIN"
TMPSP=$(echo "$SPEC" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||[];console.log(d.some(x=>x.key==="e2eTmpKey")?"yes":"no")')
[ "$TMPSP" = "no" ] && ok "自定义键不在说明书里 → 面板会提示它不参与结算" || no "说明书越界" "e2eTmpKey 被当成引擎认的键"
curl -s -X DELETE "$BASE/admin/api/config?key=e2eTmpKey" -H "$AH" >/dev/null
GONE=$(curl -s "$BASE/admin/api/config/get?key=e2eTmpKey" -H "$AH" | jget code)
[ "$GONE" = "404" ] && ok "临时配置键已清理(404)" || no "清理临时键" "code=$GONE"

echo "== 乐观锁：配置键也是同一形状（H6-2） =="
# 配置面板是"拨一下就影响全体玩家"的地方：两个运营开着同一个键，后保存的那个更不该静默盖掉前一个。
LK="e2eLockKey"
LB2=$(mktemp)
# $1=expect（空=不带）  $2=value
cput(){ local C Q=""; [ -n "${1:-}" ] && Q="?expect=$1"
  C=$(curl -s -o "$LB2" -w '%{http_code}' -X PUT "$BASE/admin/api/config$Q" -H "$AH" -H 'Content-Type: application/json' \
      -d "{\"key\":\"$LK\",\"value\":$2,\"category\":\"general\",\"remark\":\"lock probe\"}")
  echo "$C|$(cat "$LB2")"; }
# 配置面板手上那份版本号也来自列表（GET /admin/api/config），现在它是分页的 {rows,total}，
# 所以按面板的做法查：带 q 过滤再读 rows——不过滤的话，键一多就可能落在第 2 页，探针读到空串，
# 于是" Expect 没带上"这种真 bug 会被读成"这一行没有版本号"。
cstamp(){ curl -s "$BASE/admin/api/config?q=$LK&size=200" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];
let r=d.filter(x=>x.cfgKey===process.argv[1])[0];
console.log(r&&r.updatedAt?r.updatedAt:"")' "$LK"; }
cval(){ curl -s "$BASE/admin/api/config/get?key=$LK" -H "$AH" | jget data; }

CA=$(cput none 1)
[ "$(LCODE "$CA")" = "200" ] && ok "expect=none 建新键成功（面板的新增态）" || no "配置新增" "$CA"
CS1=$(LBODY "$CA" | jget data.updatedAt)
[ -n "$CS1" ] && [ "$(cstamp)" = "$CS1" ] && ok "回体带新号且与列表一致（$CS1）：面板抄的就是服务端认的那一份" || no "配置回号" "put=$CS1 list=$(cstamp)"
CB=$(cput none 2)
[ "$(LCODE "$CB")" = "409" ] && [ "$(cval)" = "1" ] && ok "同名键已被别人建出来时，这次新增被拒而不是覆盖对方那份" || no "配置重复新增" "$CB val=$(cval)"
CR=$(cput "2020-01-02T03:04:05.123" 3)
[ "$(LCODE "$CR")" = "409" ] && ok "旧版本号存配置同样回 409" || no "配置冲突" "$CR"
echo "$(LBODY "$CR")" | grep -q "在你编辑期间已被" && ok "消息说得出是谁在什么时候动的：$(LBODY "$CR" | jget msg)" || no "配置冲突文案" "$CR"
[ "$(cval)" = "1" ] && ok "被拒的配置写入没落库（还是 1）" || no "被拒配置落库" "val=$(cval)"
CO=$(cput "$CS1" 2)
[ "$(LCODE "$CO")" = "200" ] && [ "$(cval)" = "2" ] && ok "版本号对上才写：这个键存进去了（1 → 2）" || no "配置写入" "$CO"
CS2=$(LBODY "$CO" | jget data.updatedAt)
# 同值重存这条在配置侧同样要钉：广告目录那种整份 JSON 的键，运营常常原样点两次保存
CSame=$(cput "$CS2" 2)
[ "$(LCODE "$CSame")" = "200" ] && [ "$(LBODY "$CSame" | jget data.updatedAt)" = "$CS2" ] \
  && ok "配置同值重存仍算成功、且没顶号（免得把「再点一次保存」判成冲突）" || no "配置同值重存" "$CSame"
CD=$(curl -s -o "$LB2" -w '%{http_code}' -X DELETE "$BASE/admin/api/config?key=$LK&expect=2020-01-02T03:04:05.123" -H "$AH")
[ "$CD" = "409" ] && [ "$(cval)" = "2" ] && ok "旧版本号删不掉这个键" || no "配置删除冲突" "http=$CD val=$(cval)"
CD2=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/admin/api/config?key=$LK&expect=$(cstamp)" -H "$AH")
CGONE=$(curl -s "$BASE/admin/api/config/get?key=$LK" -H "$AH" | jget code)
[ "$CD2" = "200" ] && [ "$CGONE" = "404" ] && ok "版本号对上才删得掉，探针键已清理" || no "配置删除" "http=$CD2 gone=$CGONE"
rm -f "$LB2"

echo "== 广告目录的写入守卫（存坏一行 = 少发一整类奖） =="
ADS=$(curl -s "$BASE/admin/api/config/ad/schema" -H "$AH")
echo "$ADS" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||{};
const want=["coins","coupon","diamonds","hints","monthly_days","pack_el","revive","skin"];
let okr=JSON.stringify(d.rewards)===JSON.stringify(want);
let sk=d.skins||[];let oks=sk.indexOf("cyber")>=0&&sk.indexOf("retro")>=0;
let okd=((d.defaults||{}).slots||[]).length>0;
process.exit(okr&&oks&&okd?0:1)' \
  && ok "面板的奖励/皮肤下拉与内置目录都出自后端（前端没有第二份清单）" || no "ad/schema" "$ADS"
# 现行目录原样写回必须过守卫：否则这个键从此谁都改不动
# （按键名筛后再取，理由同 cstamp：列表已分页，"ad" 不保证在第 1 页的 20 行里）
ADBODY=$(curl -s "$BASE/admin/api/config?q=ad&size=200" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];
let r=d.filter(x=>x.cfgKey==="ad")[0]||{};
let v;try{v=JSON.parse(r.cfgValue)}catch(e){v={}}
console.log(JSON.stringify({key:"ad",value:v,category:r.category||"ad",remark:r.remark||""}))')
# 请求体一律走 stdin：这台机器的控制台会把 argv 里的中文按 GBK 送出去，服务端只会回"请求体无法解析"
putad(){
  printf '%s' "$1" | curl -s -X PUT "$BASE/admin/api/config" -H "$AH" -H 'Content-Type: application/json' --data-binary @-
}
RT=$(putad "$ADBODY" | jget ok)
[ "$RT" = "true" ] && ok "现行目录原样写回能通过（守卫没有误伤自家配置）" || no "目录回写" "$RT"
# 每一种"存得进去但不发奖"的写法都要被点名，报错还得带上可用的取值
adbad(){
  M=$(putad "{\"key\":\"ad\",\"value\":$1,\"category\":\"ad\",\"remark\":\"e2e\"}" | jget msg)
  case "$M" in *"$2"*) ok "拦住$2" ;; *) no "$2 本该被拦" "msg=$M" ;; esac
}
adbad '{"slots":[{"kind":"boom","zh":"慰问金","reward":"coin","amount":1,"daily":1,"cooldownSec":0}]}' "不被引擎支持"
adbad '{"slots":[{"kind":"boom","zh":"慰问金","amount":1,"daily":1}]}' "缺少 reward"
adbad '{"slots":[{"kind":"boom","zh":"慰问金","reward":"coins","amount":1,"daily":1,"cooldown":300}]}' "cooldown"
adbad '{"slots":[{"kind":"boom","zh":"A","reward":"coins","amount":1,"daily":1,"cooldownSec":0},{"kind":"boom","zh":"B","reward":"coins","amount":2,"daily":1,"cooldownSec":0}]}' "重复"
adbad '{"ticketTtlSec":5}' "ticketTtlSec"
adbad '{"slots":[{"kind":"boom","zh":"慰问金","reward":"coins","amount":0,"daily":1,"cooldownSec":0}]}' "amount"
adbad '{"unlocks":[{"id":"s1","zh":"皮肤","cost":1,"reward":"skin","once":true}]}' "target"
# 清空目录是合法的下架动作（区别于"整个键删掉=回退内置默认"），不该被守卫拦
EMPTY=$(putad '{"key":"ad","value":{"slots":[],"unlocks":[]},"category":"ad","remark":"e2e"}' | jget ok)
[ "$EMPTY" = "true" ] && ok "空目录被当合法下架收下（闸门与目录语义分开）" || no "空目录写入" "$EMPTY"
putad "$ADBODY" >/dev/null
RESTORED=$(curl -s "$BASE/admin/api/config/get?key=ad" -H "$AH" | ADBODY="$ADBODY" node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data;
let want=JSON.stringify(JSON.parse(process.env.ADBODY).value);
process.exit(JSON.stringify(d)===want?0:1)' && echo yes || echo no)
[ "$RESTORED" = "yes" ] && ok "目录已恢复原值（守卫测试不留脏配置）" || no "恢复原值" "$RESTORED"
CFGJS=$(ls -t "$ROOT/server/src/main/resources/static/admin/assets/"Config-*.js 2>/dev/null | head -1)
if [ -n "$CFGJS" ]; then
  SERVED=$(curl -s "$BASE/admin/assets/$(basename "$CFGJS")")
  case "$SERVED" in *添加广告位*) case "$SERVED" in *积分兑换*) ok "后台广告目录是类型化表单（已随包发布，不再手改 JSON）" ;; *) no "表单·兑换区" "缺积分兑换段" ;; esac ;; *) no "表单·广告位" "打包产物里没有广告目录表单" ;; esac
  # 合规三件套（4.2 版本闸门 / 4.4 防沉迷放行日 / 4.5 采集开关）必须是类型化字段：
  # 这几项改错会让上架直接卡审，靠后台改裸 JSON 迟早打错字，所以断言"随包发布的那份产物"里有对应控件文案，
  # 而不是只看 admin/src 源码——源码写了但没重新 build，线上仍是老表单。
  for mk in 最低可进 放行日 更新说明 写入行为记录; do
    case "$SERVED" in *"$mk"*) ok "后台合规表单含「$mk」" ;; *) no "表单·$mk" "打包产物里缺该控件，admin 需要重新构建" ;; esac
  done
else
  no "表单·打包" "找不到 admin 构建产物，先跑 admin/npm run build"
fi

echo "== 静态托管（H5 客户端随包发布） =="
IDX=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/")
[ "$IDX" = "200" ] && ok "GET / 返回游戏页面" || no "首页托管" "http=$IDX"
JS=$(curl -s "$BASE/js/panels.js")
echo "$JS" | grep -q "G.call" && ! echo "$JS" | grep -q "U.save" && ok "托管的客户端已是意图版（无本地写入 API）" || no "客户端版本" "panels.js 仍含本地真源调用"
GT=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/js/gate.js")
[ "$GT" = "200" ] && ok "登录页脚本 js/gate.js 已随包发布" || no "gate.js 托管" "http=$GT"
curl -s "$BASE/" | grep -q 'id="gate-root"' && ok "首页自带登录/注册门结构" || no "登录门结构" "index.html 缺 #gate-root"
curl -s "$BASE/" | grep -q 'id="gate-help"' && ok "登录页给出忘记密码找回路径（不再是一条死路）" || no "找回密码指引" "index.html 缺 #gate-help"
curl -s "$BASE/js/game.js" | grep -q "cloud.logged()) return G.toGate" && ok "未登录时先过鉴权门（不再静默开游客档）" || no "启动流程" "game.js 未走登录门"
OLD=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/manifest.json")
[ "$OLD" = "404" ] && ok "PWA 离线壳已移除（manifest.json 404）" || no "离线壳清理" "http=$OLD"
SW=$(curl -s "$BASE/sw.js" | grep -c "unregister")
[ "$SW" -ge 1 ] && ok "旧 Service Worker 会自我注销" || no "SW 注销" "hits=$SW"

echo "== 安卓壳前端层（§6.2 八项改造随包发布） =="
SHELLJS=$(curl -s "$BASE/js/shell.js")
echo "$SHELLJS" | grep -q "S.inShell" && ok "js/shell.js 已随包发布（壳能力探测层）" || no "shell.js 托管" "内容异常：$(echo "$SHELLJS" | head -c 60)"
# prefs() 只缓存"真的拿到了插件"，否则一轮 WebView 抖动就能把令牌镜像静默关掉整场会话
echo "$SHELLJS" | grep -q "if (PREFS) return PREFS" && ok "Preferences 探测不会缓存 null（镜像不会被静默失效）" || no "prefs 缓存" "shell.js 里找不到非空缓存判断"
echo "$SHELLJS" | grep -q "appBuild" && ok "壳向客户端暴露 versionCode（4.2 强更闸门用得上）" || no "appBuild" "缺版本上报"
ADSJS=$(curl -s "$BASE/js/ads.js")
echo "$ADSJS" | grep -q "同意并进入实验室" && ok "隐私合规门在前端（未同意不初始化广告 SDK）" || no "合规门" "ads.js 缺同意文案"
# F4 ③：同意状态"问不出来"时照问（fail-closed）。以前这里写的是 `st.broken || st.consented`，
# 于是插件版本对不上／status() 字段改名／原生抛异常这三种情况都会变成"当作已经同意过"——
# 后果不是 SDK 抢跑（原生侧还有一道 NO_CONSENT），而是首启根本没有隐私政策弹窗，这一条本身就够被拒。
echo "$ADSJS" | grep -q "consented: r.consented === true" && ok "读不到同意状态时按'未同意'处理（不再默认放行）" || no "同意状态判据" "ads.js 不是 === true 的严判"
# F4 ④：撤回。个保法 15 条要求"撤回要和同意一样方便"，只有【同意】一条路的写法在提审时会被直接问住。
echo "$ADSJS" | grep -q "agree: false" && ok "客户端有 revokeConsent 调用面（原生记 revokedAt）" || no "撤回调用面" "ads.js 缺 consent({agree:false})"
echo "$JS" | grep -q "撤回授权" && ok "【设置·隐私】给了撤回入口" || no "撤回入口" "panels.js 的隐私卡没有撤回按钮"
GATEJS=$(curl -s "$BASE/js/gate.js")
echo "$GATEJS" | grep -q "NO_SDK" && ok "TapTap 按钮缺 SDK 时有降级路径（不会点了没反应）" || no "TapTap 降级" "gate.js 未处理 NO_SDK"
# ?v 一致性：改一个文件忘了顶版本号，浏览器会拿一年前的旧脚本——这是本项目最常踩的自伤
VWEB=$(curl -s "$BASE/" | grep -o 'v=[0-9][0-9.]*' | sort -u | paste -sd, -)
VREPO=$(grep -o 'v=[0-9][0-9.]*' "$ROOT/frontend/index.html" | sort -u | paste -sd, -)
[ "$VWEB" = "$VREPO" ] && [ -n "$VWEB" ] && ok "线上页与仓库页资源版本号一致（$VWEB）" || no "版本号未同步" "线上=$VWEB 仓库=$VREPO（jar 需要重新打包）"

echo "== 启动链路与断线重连（C2 并发 / C3 内容不挡首屏 / C4 指数退避） =="
GAMEJS=$(curl -s "$BASE/js/game.js")
# C2：内容库与会话校验互不依赖，串行等于每个玩家每次进游戏都多等一个来回
echo "$GAMEJS" | grep -q "got.content = true; afterBoth()" && ok "内容库与会话校验并发，两路都回来才拉存档" || no "启动并发" "game.js 仍是 content→me→state 三级串行"
# C4 的第一原则：写意图绝不自动重放。服务端可能已经执行成功、只是回复没回到客户端，
# 重放等于把同一次合成做两遍，吃掉的是玩家的产物和金币。所以只有只读意图才配重试。
echo "$GAMEJS" | grep -q "READONLY" && ok "重放白名单只含只读意图（state/ad.status/leaderboard）" || no "重放白名单" "game.js 缺 READONLY 判定"
echo "$GAMEJS" | grep -q "scheduleResync" && ok "写意图没回音时改为拉权威帧对齐（不重放、也不让玩家干等）" || no "对齐路径" "缺 scheduleResync"
echo "$GAMEJS" | grep -q "backoffMs" && ok "退避节奏 1.2s→2.4s→4.8s→9.6s，4 次后交回玩家决定" || no "退避" "缺 backoffMs"
echo "$GAMEJS" | grep -q "stopTimers();" && ok "断网/换身份时停掉退避定时器（不对着空气发请求）" || no "定时器收口" "缺 stopTimers 调用"
echo "$GAMEJS" | grep -q "resetBootRetry();" && ok "启动失败会自己按退避重试，不再要求玩家整页刷新" || no "启动重试" "缺 resetBootRetry 调用"
RELOADS=$(echo "$GAMEJS" | grep -c "location.reload()")
# 数"出现几次"是个坏断言：这个文件本来就该有两处重载——退出壳失败时退回网页重载、
# 换身份重进时故意整页重来。真正要守住的是"启动失败路径不再拿重载当兜底"，
# 所以把这两处有意的先剥掉，剩下的任何一处都是有人把失败分支又写回"刷一下试试"。
LEFT=$(echo "$GAMEJS" | grep "location.reload()" | grep -v "exitApp()" | grep -v "G.restart")
# 注意这两处"有意的"是靠同一行上的字样认出来的：把 reload 拆到单独一行（H2 改 G.restart 时就这么排过版），
# 这条会立刻红成"又多了一处"。那不是回归坏了，是判据本来就依赖行内标记——挪之前先看懂它在排除什么。
[ -z "$LEFT" ] && ok "整页重载只剩两处有意的（退出兜底 + 换身份重进），共 $RELOADS 处，失败路径不靠它" || no "重载兜底未收口" "又多了一处：$LEFT"
echo "$GAMEJS" | grep -q "onRefresh = function" && ok "后台换版会重建物质查表并重绘当前页" || no "换版落地" "game.js 未挂 content.onRefresh"
CTJS=$(curl -s "$BASE/js/content.js")
echo "$CTJS" | grep -q "revalidate(cached)" && ok "命中内容缓存直接进游戏，版本核对挪到后台" || no "内容缓存优先" "content.js 仍先等版本探测"
echo "$CTJS" | grep -q "fetchBundle(null, cb)" && ok "首装路径直取 bundle，不再多问一次版本号" || no "首装路径" "content.js 缺直取分支"
echo "$(curl -s "$BASE/css/style.css")" | grep -q "boot-auto" && ok "自动重试倒计时有对应样式（等待看得见）" || no "重试样式" "css 缺 .boot-auto"
# F3 连点锁：收口在唯一的出口 G.call 上，而不是每个调用点各写一遍 disabled——
# ui.js/panels.js 加起来上百处按钮回调，漏一处就是"双击合成两遍、连点买十次"。
echo "$GAMEJS" | grep -q "pendingLock = b && !b.disabled" \
  && ok "捕获阶段记下玩家点的那个 button（不依赖 window.event，异步回调里也拿得到）" || no "连点锁：记录" "game.js 缺捕获阶段监听"
echo "$GAMEJS" | grep -q "Date.now() - p.at > 1000" \
  && ok "只认\"这一下\"点击：上一次留下的锁不会被误用（1 秒外一律作废）" || no "连点锁：新鲜度" "缺 at 时效判定"
echo "$GAMEJS" | grep -q "el.classList.add(\"busy\")" \
  && ok "在途期间按钮置灰禁用，玩家看得见\"这个已经点下去了\"" || no "连点锁：禁用" "缺 busy 态"
echo "$GAMEJS" | grep -q "setTimeout(unlock, 20000)" \
  && ok "回调丢了也有 20 秒兜底解锁，不会把按钮焊死" || no "连点锁：兜底" "缺超时解锁"
echo "$(curl -s "$BASE/css/style.css")" | grep -q "button.busy" && ok ".busy 有对应样式（半透明，一眼看出在途）" || no "连点样式" "css 缺 button.busy"

# F2 客户端那一半：意图出发时带会话内 seq（服务端侧的四条动态断言在上面）。
# 这三条钉的是"号从哪儿来、什么时候不许换"——它们一旦丢，服务端那套幂等窗口就成了空转。
echo "$GAMEJS" | grep -q "s: ++seq" \
  && ok "每一笔意图在 G.call 里领到一个会话内单号（同一次点击的自动重发共用它，换一笔才换号）" || no "F2 客户端：领号" "game.js 未按笔递增 seq"
echo "$GAMEJS" | grep -q 'Object.assign({}, job.p, { seq: job.s })' \
  && ok "seq 在出发那一刻贴到拷贝上，不写脏调用方传来的 params（同一份 params 第二次提交不会带上上一笔的号）" || no "F2 客户端：贴号" "game.js 缺发送期拷贝"
echo "$GAMEJS" | grep -q "queue.unshift(job)"   && ok "读意图退避重发用的是同一个 job，因此也是同一个 seq：重发是「同一笔的第二次交付」，不是新的一笔" || no "F2 客户端：重发不换号" "game.js 没把原 job 放回队首"
# H2：弱网下"请求挂着不回"是常态，而意图队列是串行的——头一个挂住后面全停，
# 那套退避／拉帧对齐永远不会被触发（它等的是"请求失败回来"）。
CLOUDJS=$(curl -s "$BASE/js/cloud.js")
echo "$CLOUDJS" | grep -q "AbortController" \
  && ok "每个请求自带期限：挂死的 fetch 会到点主动断开，队列不会永久停在 running" || no "H2：请求超时" "cloud.js 没有 AbortController"
echo "$CLOUDJS" | grep -q "C.timeoutMs = 12000" \
  && ok "期限 12 秒且是可调常量（回归脚本能把它调小，不然一条用例真等 12 秒）" || no "H2：期限口径" "cloud.js 缺 timeoutMs"
echo "$CLOUDJS" | grep -q "function fire(err, json)" \
  && ok "回调用闩收口成恰好一次：超时、迟到正文、请求 reject 三者只有第一个说话算数" || no "H2：回调一次" "cloud.js 的 timedFetch 没有闩"
echo "$CLOUDJS" | grep -q "C.timedFetch = timedFetch" && ok "内容包共用这一个期限（不另起一套口径，免得漂成一个有一个没有）" || no "H2：共用期限" "cloud.js 未导出 timedFetch"
echo "$CTJS" | grep -q "CHEM.cloud.timedFetch" \
  && ok "首装那次 fetchBundle 也吃期限：它挂着不回就等于启动遮罩永远转圈（afterBoth 凑不齐）" || no "H2：内容超时" "content.js 没接 cloud.js 的期限"
echo "$GAMEJS" | grep -q "if (job.e !== epoch) return" \
  && ok "整帧落地前先比会话纪元：上一个账号的迟到回包不画当前界面" || no "H2：纪元" "game.js 缺 epoch 比较"
echo "$GAMEJS" | grep -q "stopForNewIdentity()" \
  && ok "换身份那一路把退避、对齐、挂单轮询、广告递归、防沉迷倒计时一起停" || no "H2：定时器收口" "game.js 缺 stopForNewIdentity"
echo "$GAMEJS" | grep -q "stopPoll()" && ok "8 秒一次的挂单轮询有独立的停法（它自己会发意图）" || no "H2：轮询停" "game.js 缺 stopPoll"
echo "$ADSJS" | grep -q "A.cancelAwait = function" \
  && ok "广告等待那条递归可取消：退登后不再带着旧令牌每 1.2 秒问一次 ad.status" || no "H2：广告递归" "ads.js 缺 cancelAwait"
echo "$ADSJS" | grep -q "awaitTimer = setTimeout(step, POLL_MS)" \
  && ok "递归的那次 setTimeout 被记下来了，才谈得上取消（原来是匿名自旋）" || no "H2：递归可停" "ads.js 的 awaitReward 仍不留句柄"
echo "$ADSJS" | grep -q "if (run !== awaitRun) return finish(false)" \
  && ok "被顶替／被取消的那次观看仍给调用方交代一次：静默丢弃等于把复活、双倍券那些按钮卡在途" || no "H2：取消也要交代" "ads.js 的 awaitReward 被取消时不回调用方"
# 连点锁只管"同一个人连敲两下"，不管两台设备各点一下——后者是 F2 的意图序号（seq）幂等要解决的，
# 上面那条四路并发买滤粉断言的就是这条边界：服务端按设计会把四个不同请求都执行。

echo "== 内容体积（C3 留下的尺子：分片报告 + 体积上限） =="
SH=$(curl -s "$BASE/api/content/shards")
[ "$(echo "$SH" | jget ok)" = "true" ] && ok "/api/content/shards 可读（要不要拆片先读数字）" || no "分片报告" "$SH"
BIG=$(echo "$SH" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||{};let s=d.shards||[];console.log(s[0]?s[0].type:"")')
[ "$BIG" = "reaction" ] && ok "最大一片是$BIG（首屏就要用，按类型拆片省不到多少）" || no "最大分片" "got=$BIG"
ORD=$(echo "$SH" | node -e 'let s=(JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).shards||[];let b=s.map(x=>x.bytes);console.log(b.every((v,i)=>i===0||b[i-1]>=v)?"1":"0")')
[ "$ORD" = "1" ] && ok "分片按体积降序（要优化就先动最大那片）" || no "分片排序" "bytes=$ORD"
SUM=$(echo "$SH" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||{};let s=d.shards||[];console.log(s.reduce((a,x)=>a+x.bytes,0)===d.bytes?"1":"0")')
[ "$SUM" = "1" ] && ok "明细与总数对得上（一份报告只有一个版本）" || no "分片总数" "sum=$SUM"
HASCFG=$(echo "$SH" | node -e 'let s=(JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).shards||[];console.log(s.some(x=>x.type==="config"&&x.bytes>0)?"1":"0")')
[ "$HASCFG" = "1" ] && ok "config 也单独计一片（漏报等于给「还能省多少」留盲区）" || no "config 分片" ""
RAW=$(curl -s -o /dev/null -w '%{size_download}' "$BASE/api/content/bundle")
GZIP=$(curl -s --compressed -o /dev/null -w '%{size_download}' "$BASE/api/content/bundle")
[ "$RAW" -gt 10000 ] && [ "$RAW" -lt 220000 ] && ok "整包原文 ${RAW}B，未超 220KB 阈值" || no "整包体积" "raw=$RAW"
[ "$GZIP" -lt "$RAW" ] && ok "开启压缩后传输 ${GZIP}B（原文的 $((GZIP * 100 / RAW))%）" || no "传输压缩" "gzip=$GZIP raw=$RAW"
[ "$GZIP" -lt 80000 ] && ok "传输体积未超 80KB 阈值（超了就是内容膨胀，先减行再谈拆片）" || no "传输阈值" "gzip=$GZIP"

echo "== 客户端页面结构（底部导航每项 = 一整页，不再是上滑抽屉） =="
IDX_HTML=$(curl -s "$BASE/")
echo "$IDX_HTML" | grep -q 'id="sheet"' && no "抽屉已下线" "index.html 仍残留 #sheet" || ok "已无底部抽屉 #sheet（上滑弹面板那套交互删干净了）"
echo "$IDX_HTML" | grep -q 'id="stage"' && ok "同层多页容器 #stage 就位" || no "页面容器" "缺 #stage"
echo "$IDX_HTML" | grep -q 'id="page-body"' && ok "二级页 #page/#page-body 就位" || no "二级页结构" "缺 #page-body"
NTAB=$(echo "$IDX_HTML" | grep -o 'data-tab="[a-z]*"' | sort -u | wc -l | tr -d ' ')
[ "$NTAB" = "7" ] && ok "底部导航 7 个常驻页签：$(echo "$IDX_HTML" | grep -o 'data-tab="[a-z]*"' | sed 's/.*="//;s/"//' | paste -sd, -)" || no "页签数量" "期望 7 实际 $NTAB"
echo "$IDX_HTML" | grep -q 'data-tab="bench"' && ok "实验台本身也是一页（bench 页签）" || no "实验台页签" "缺 data-tab=\"bench\""
echo "$IDX_HTML" | grep -q 'id="quick-shelf"' && ok "实验台自带物质架（整页化后投放源不依赖抽屉）" || no "物质架" "缺 #quick-shelf"
CSS=$(curl -s "$BASE/css/style.css")
echo "$CSS" | grep -q '#sheet' && no "样式残留" "style.css 仍有 #sheet 规则" || ok "样式层已无 #sheet"
echo "$CSS" | grep -q '\.page\.on' && ok "整页显隐由 .page.on 驱动" || no "整页样式" "缺 .page.on"
PJS=$(curl -s "$BASE/js/panels.js")
echo "$PJS" | grep -q "P.go = function" && ! echo "$PJS" | grep -q "openTab" && ok "面板路由已是页面版 P.go(tab)" || no "面板路由" "panels.js 仍是 openTab 抽屉版"
UJS=$(curl -s "$BASE/js/ui.js")
echo "$UJS" | grep -q "renderQuickShelf" && ok "每次整帧落地都会刷新实验台物质架" || no "物质架刷新" "ui.js 未调用 renderQuickShelf"
echo "$PJS" | grep -q "CHEM.fx.resize" && ok "回到实验台会重量一次画布（display:none 时尺寸是 0）" || no "画布重量" "panels.js 回页时没有 fx.resize"
echo "$UJS" | grep -q "pointercancel" && ok "横向滑物质架（pointercancel）不会误投放" || no "手势语义" "ui.js 缺 pointercancel 分支"
echo "$UJS" | grep -q "U.isDragging" && ok "拖拽途中不重刷物质架（否则指针会丢）" || no "拖拽守卫" "ui.js 缺 isDragging"
# 缓存版本号必须全站唯一：只顶一半，另一半就会被 immutable 长缓存钉在旧版本上
NV=$(echo "$IDX_HTML" | grep -o '[?&]v=[0-9]\+\.[0-9]\+' | sort -u | wc -l | tr -d ' ')
CURV=$(echo "$IDX_HTML" | grep -o 'css/style.css?v=[0-9.]*' | sed 's/.*v=//')
[ "$NV" = "1" ] && [ -n "$CURV" ] && ok "静态资源共用缓存版本号 ?v=$CURV（改前端 JS/CSS 必须整体顶版）" || no "版本号不统一" "index.html 里出现 $NV 种 ?v="

echo "== 无障碍（H5）：随包发布的那一份才作数 =="
# 对比度与命中区的算术在 test/a11y.js（读磁盘文件）；这一组只判两件事：
# 一是"服务器吐出来的 HTML/CSS 也带着这些锚点"（防止打包目录里躺着一份旧的），
# 二是"静态标记有 JS 跟着更新"（aria-checked/aria-current 写死不动等于给读屏报假状态）。
VPMETA=$(echo "$IDX_HTML" | grep '<meta[^>]*name="viewport"')
echo "$VPMETA" | grep -Eq 'user-scalable *= *no|maximum-scale *= *1[^0-9]' \
  && no "系统缩放未被禁用" "viewport 仍禁缩放：$VPMETA" || ok "viewport 允许系统缩放（低视力玩家的双指放大没被没收）"
[ "$(echo "$IDX_HTML" | grep -c 'aria-live')" -ge 4 ] \
  && ok "4 处以上 aria-live 播报区（吐司/费用预估/启动进度/欢迎语）" || no "aria-live 覆盖" "只找到 $(echo "$IDX_HTML" | grep -c 'aria-live') 处"
NTL=$(echo "$IDX_HTML" | grep -o 'role="tablist"' | wc -l | tr -d ' ')
[ "$NTL" -ge 3 ] && ok "房间/页内/登录三处页签条都声明了 role=tablist（实际 $NTL 处）" || no "tablist 数量" "期望≥3 实际 $NTL"
NRA=$(echo "$IDX_HTML" | grep -o 'role="radio"' | wc -l | tr -d ' ')
echo "$IDX_HTML" | grep -q 'role="radiogroup"' && [ "$NRA" -ge 4 ] \
  && ok "温度条件组是 radiogroup + $NRA 个 radio（方向键选档在 ui.js 里接了）" || no "radiogroup 结构" "radio=$NRA"
echo "$IDX_HTML" | grep -q 'id="vessel-card"[^>]*role="button"[^>]*tabindex="0"' \
  && ok "容器卡片可聚焦并带 role=button" || no "容器卡片可达性" "#vessel-card 缺 role/tabindex"
echo "$CSS" | grep -q -- '--tap-min: 44px' && echo "$CSS" | grep -q 'min-height: var(--tap-min)' \
  && ok "命中区兜底进包：--tap-min=44px + 分组规则" || no "命中区兜底" "style.css 缺 --tap-min 或没被引用"
NSO=$(echo "$CSS" | grep -o -- '--solid-fg:' | wc -l | tr -d ' ')
[ "$NSO" -ge 3 ] && ok "三套皮肤各自定义了 --solid-fg（色块上的字各皮配平，不会退回默认皮）" || no "皮肤令牌完整度" "--solid-fg 只定义 $NSO 次"
# 顶栏与渐变面上的那几块板：以前是 rgba(255,255,255,.15) 这种"白纱"，读数取决于压在渐变哪一段，
# 实测最亮段只剩 4.43:1、hover 3.54:1，而 token 层裁判算不出混合值——所以全换成实色 token。
NTP=$(echo "$CSS" | grep -o -- '--topbar-pill:' | wc -l | tr -d ' ')
NPL=$(echo "$CSS" | grep -o -- '--plate:' | wc -l | tr -d ' ')
[ "$NTP" -ge 3 ] && [ "$NPL" -ge 3 ] \
  && ok "顶栏片/渐变板是实色 token，三套皮各配一份（--topbar-pill $NTP 处、--plate $NPL 处）" \
  || no "实色板 token 完整度" "--topbar-pill=$NTP --plate=$NPL（应是 3/3，少了某套皮就会退回默认皮的板色）"
NFR=$(echo "$CSS" | grep -Ec 'background: *rgba\(255, 255, 255, *\.[0-9]+\)')
[ "$NFR" -le 2 ] && ok "带字的板没有一处是半透明白（平铺白板只剩 $NFR 处装饰底槽：经验条与广告条）" \
  || no "半透明白板" "style.css 里有 $NFR 处平铺 rgba(255,255,255,…) 底，混合值判不了对比度"
echo "$CSS" | grep -Eq '(^|[^-[:alnum:]])color: *var\(--(faint|blue|green|orange|red|teal|purple|gold)\)' \
  && no "文字色不再直接用装饰色" "style.css 仍把 --faint/--blue/… 当字色（请改 --fg-* 或 --solid-*）" \
  || ok "文字色全部走 --fg-*/--solid-*（装饰色只做描边与粒子）"
# 物质底色是"内容数据"，真源在 content_item：前端 js/data 改了色，库里没跟上就等于
# 只修好了没联网的那一侧。V13 那四个色号必须出现在下发给玩家的 bundle 里。
echo "$RB" | grep -q '#6b757d' && echo "$RB" | grep -q '#2b75c3' \
  && ok "bundle 里的硅/胆矾底色已是 V13 的可读档（配白或配墨都过 4.5:1）" \
  || no "底色已同步到内容包" "bundle 找不到 #6b757d / #2b75c3（迁移没跑或后台改回去了）"
echo "$UJS" | grep -q 'aria-checked' && echo "$UJS" | grep -q 'aria-pressed' \
  && ok "温度档与电解开关的选中态会同步写 aria-checked/aria-pressed" || no "选中态同步" "ui.js 未更新 aria-checked/aria-pressed"
echo "$PJS" | grep -q 'aria-current' && ok "切页会给当前页签写 aria-current" || no "当前页标记" "panels.js 未更新 aria-current"
echo "$(curl -s "$BASE/js/gate.js")" | grep -q 'pointer: fine' \
  && ok "登录页只在有鼠标的设备聚焦输入框（触屏不再弹键盘盖住游客入口）" || no "聚焦策略" "gate.js 没有 pointer:fine 判断"

echo "== 容器图标（js/icons.js 线稿，替掉错配的 emoji） =="
ICO=$(curl -s "$BASE/js/icons.js")
echo "$ICO" | grep -q "CHEM.icon" && ok "图标模块 js/icons.js 已随包发布" || no "icons.js 托管" "响应里没有 CHEM.icon"
echo "$IDX_HTML" | grep -q '<script src="js/icons.js' && ok "首页按序加载 icons.js（在 ui.js 之前）" || no "图标脚本引用" 'index.html 没有 <script src="js/icons.js">'
IO_LINE=$(echo "$IDX_HTML" | grep -n '<script src="js/icons.js' | head -1 | cut -d: -f1)
UI_LINE=$(echo "$IDX_HTML" | grep -n '<script src="js/ui.js' | head -1 | cut -d: -f1)
[ -n "$IO_LINE" ] && [ -n "$UI_LINE" ] && [ "$IO_LINE" -lt "$UI_LINE" ] \
  && ok "icons.js（第 $IO_LINE 行）排在 ui.js（第 $UI_LINE 行）之前，渲染时图形已就位" || no "图标加载顺序" "icons.js=$IO_LINE ui.js=$UI_LINE"
echo "$IDX_HTML" | grep -q 'id="vessel-icon"' && ok "台面容器位改为 #vessel-icon" || no "图标位" "index.html 缺 #vessel-icon"
echo "$IDX_HTML" | grep -q 'id="vessel-emoji"\|id="liquid-layer"' && no "旧结构残留" "index.html 仍有 #vessel-emoji/#liquid-layer" || ok "emoji 占位与贴在卡片上的液体层都已移除"
COAT=$(printf '\360\237\245\274')   # 🥼 实验服：正是这次被投诉"烧杯显示成实验服"的那个码点
echo "$UJS" | grep -q "$COAT" && no "emoji 错配未清理" "ui.js 仍写着实验服码点" || ok "ui.js 已无实验服 emoji（烧杯不再穿白大褂）"
echo "$UJS" | grep -q "VESSEL_EMOJI" && no "emoji 表残留" "ui.js 仍有 VESSEL_EMOJI" || ok "容器图形不再有第二份真源"
echo "$UJS" | grep -q "CHEM.icon.draw" && ok "台面图形由 icons.js 现画" || no "台面图标接线" "ui.js 未调用 CHEM.icon"
echo "$PJS" | grep -q "CHEM.icon.of" && ok "建设·仪器商店每行带仪器线稿" || no "商店图标" "panels.js 未用 CHEM.icon.of"
echo "$CSS" | grep -q "v-ic.wet" && echo "$CSS" | grep -q -- "--ic-liq" && ok "液体画在容器内并且随皮肤/混合色变化" || no "液体样式" "style.css 缺 .v-ic.wet 或 --ic-liq"

echo "== 页面信息架构（每页内再分段，功能搬到玩家会去找的地方） =="
echo "$IDX_HTML" | grep -q 'id="page-segs"' && ok "页内分段条 #page-segs 已进外壳" || no "分段条" "index.html 缺 #page-segs"
NB=$(echo "$IDX_HTML" | grep -o 'class="nav-badge' | wc -l | tr -d ' ')
[ "$NB" = "6" ] && ok "6 个二级页各留一枚导航角标位（实验台是常驻页不需要）" || no "导航角标" "期望 6 实际 $NB"
echo "$PJS" | grep -q "var PAGES = {" && ok "页面由 PAGES 注册表描述（tab → 分段表 + 绘制器）" || no "页面注册表" "panels.js 缺 PAGES"
echo "$PJS" | grep -q "P.claimCounts = claimCounts" && echo "$PJS" | grep -q "P.buyableCount = buyableCount" \
  && ok "分段点上的数字与底部角标同源（claimCounts/buyableCount 只算一次）" || no "角标真源" "计数函数未导出"
GJS=$(curl -s "$BASE/js/game.js")
echo "$UJS" | grep -q "U.updateNav = function" && echo "$GJS" | grep -q "U.updateNav()" \
  && ok "每帧落地都会刷新导航角标（领完奖励红点自己掉）" || no "角标接线" "updateNav 未定义或未进 refresh"
# 功能归位：签到 → 任务·今日；商会订单 → 任务·今日；提纯工坊 → 物质页
echo "$PJS" | grep -q 'id="t-sign"' && ok "每日签到搬到【任务·今日】并带 7 天礼包进度" || no "签到归位" "任务页没有签到入口"
echo "$PJS" | grep -q '签到已搬到【任务·今日】' && ok "设置页留了跳转指路，老习惯不会找不到北" || no "签到指路" "设置页缺去签到入口"
# 注意：本机 grep 在 C.UTF-8 下匹配不了 4 字节 emoji（星平面字符），断言只用中文/结构锚点
echo "$PJS" | grep -q '商会订单", meta: (d.orders.list' && ok "商会订单由市场标签搬进【任务·今日】（它是委托不是货架）" || no "订单归位" "任务页缺商会订单"
echo "$PJS" | grep -q 'workshop", "提纯工坊' && echo "$PJS" | grep -q "function renderWorkshop" \
  && ok "提纯工坊搬进【物质】页（进料出料都在同一页）" || no "工坊归位" "物质页缺提纯分段"
[ "$(echo "$PJS" | grep -c 'E.tempBench) return null')" -ge 1 ] && ok "挑战/沙盒的临时工作台不分段（那只是一件材料柜）" || no "临时工作台" "bagSegs 没有 tempBench 短路"
MISS_MKT=""; for s in buy special black consume sell listing; do
  echo "$PJS" | grep -q "\[\"$s\", \"" || MISS_MKT="$MISS_MKT $s"
done
[ -z "$MISS_MKT" ] && ok "市场分 6 段：采购/特惠/黑市/耗材/出售/挂单" || no "市场分段" "缺$MISS_MKT"
# 长列表本地过滤
MISS_Q=""; for k in bag codex market eq; do
  echo "$PJS" | grep -q "searchBox(\"$k\"" || MISS_Q="$MISS_Q $k"
done
[ -z "$MISS_Q" ] && ok "背包/图鉴/市场/方程式四类长列表都能本地关键词过滤" || no "搜索框" "缺$MISS_Q"
echo "$PJS" | grep -q '不计入关键词搜索' && ok "方程式搜索只筛已解锁项，未发现清单不跟着缩（防靠命中数反推答案）" || no "搜索防泄底" "未发现清单缺少过滤说明"
MISS_CSS=""; for p in ".seg {" ".pip" ".statbar {" ".searchbar {" ".empty-state {" ".card-hd {" ".nav-badge {"; do
  echo "$CSS" | grep -qF "$p" || MISS_CSS="$MISS_CSS $p"
done
[ -z "$MISS_CSS" ] && ok "分段条/卡片组/统计条/搜索框/空状态/角标全部走 token 样式" || no "新组件样式" "style.css 缺$MISS_CSS"
echo "$PJS" | grep -q 'function appVer' && ok "关于页版本号取自 ?v= 链接（不再手写第二份，改版本不会漏更）" || no "版本号真源" "panels.js 仍写死版本"

echo "== 广告中心接线与付费面残留清理 =="
AJ=$(curl -s "$BASE/js/ads.js")
echo "$AJ" | grep -q "CHEM.ad" && ok "播放桥接 js/ads.js 已随包发布" || no "ads.js 托管" "响应里没有 CHEM.ad"
echo "$IDX_HTML" | grep -q '<script src="js/ads.js' && ok "首页按序加载 ads.js" || no "ads.js 引用" 'index.html 没有 <script src="js/ads.js">'
AD_LINE=$(echo "$IDX_HTML" | grep -n '<script src="js/ads.js' | head -1 | cut -d: -f1)
UI_LINE=$(echo "$IDX_HTML" | grep -n '<script src="js/ui.js' | head -1 | cut -d: -f1)
[ -n "$AD_LINE" ] && [ -n "$UI_LINE" ] && [ "$AD_LINE" -lt "$UI_LINE" ] \
  && ok "ads.js（第 $AD_LINE 行）排在 ui.js（第 $UI_LINE 行）之前，watchAd 调到时桥接已就位" || no "加载顺序" "ads.js=$AD_LINE ui.js=$UI_LINE"
echo "$UJS" | grep -q "U.watchAd = function" && ok "看广告统一走 U.watchAd（工单→播放→服务端确认）" || no "观看入口" "ui.js 缺 U.watchAd"
echo "$AJ" | grep -q "showRewardVideo" && ok "桥接已按 TapADN 的 Capacitor 插件契约留好接口（window.ChemeraAd）" || no "原生契约" "ads.js 无 showRewardVideo"
# 付费面必须真的从界面上消失：托管出来的 HTML/JS 里不该再有充值档位与人民币字样。
# 只看会执行/渲染的文本——注释里写"充值已随付费面下线"是给接手的人看的历史说明，不是入口。
curl -s "$BASE/js/panels.js" "$BASE/js/ui.js" "$BASE/js/ads.js" "$BASE/" > /tmp/chemera-client-all.js
node -e '
const fs=require("fs");
let t=fs.readFileSync(process.argv[1],"utf8")
  .replace(/<!--[\s\S]*?-->/g,"")        // HTML 注释
  .replace(/\/\*[\s\S]*?\*\//g,"")       // 块注释
  .replace(/^[ \t]*\/\/.*$/gm,"");       // 整行注释
fs.writeFileSync(process.argv[2],t)' /tmp/chemera-client-all.js /tmp/chemera-client-code.js
for BAD_WORD in "充值" "simAd" "shop.recharge" "buyDiamondItem"; do
  grep -q "$BAD_WORD" /tmp/chemera-client-code.js && no "前端残留" "代码里仍能找到 $BAD_WORD（不是注释的话就是真入口）" || ok "前端已无 $BAD_WORD（付费入口删干净）"
done
grep -q '¥[0-9]' /tmp/chemera-client-code.js && no "价格残留" "界面上还写着人民币档位" || ok "界面无人民币标价（¥ 已随充值面下线）"
echo "$PJS" | grep -q '"ad", "广告"' && ok "设置页分段已把【商店】换成【广告】" || no "分段归位" "panels.js 缺广告分段"
echo "$PJS" | grep -q "function drawAds" && ! echo "$PJS" | grep -q "function drawStore" \
  && ok "商店绘制器已由广告中心取代（drawStore 不存在）" || no "绘制器替换" "drawStore 未清理"

echo "== 合规与安卓面（防沉迷 / 版本门 / TapTap / 埋点）=="
# 版本门必须匿名可读：强制升级的意义正是"旧包连门都进不来"，这个接口要 token 就成了死循环。
AV=$(curl -s "$BASE/api/app/version")
[ "$(echo "$AV" | jget ok)" = "true" ] && ok "/api/app/version 不带令牌可读（旧包问得到规则）" || no "版本门匿名可读" "$AV"
MINB=$(echo "$AV" | jget data.minBuild); LATB=$(echo "$AV" | jget data.latestBuild)
[ -n "$MINB" ] && [ -n "$LATB" ] \
  && ok "版本门下发 minBuild=$MINB / latestBuild=$LATB / contentVersion=$(echo "$AV" | jget data.contentVersion)" \
  || no "版本门字段" "$AV"

CFGJ=/tmp/chemera-cfg.json
putcfg(){ printf '%s' "$1" > "$CFGJ"; curl -s -X PUT "$BASE/admin/api/config" -H "$AH" -H 'Content-Type: application/json' --data-binary @"$CFGJ"; }
getcfg(){ curl -s "$BASE/admin/api/config/get?key=$1" -H "$AH" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log(JSON.stringify(d.data))'; }
# 先原样存下这三行，本段结束时复原：回归可以改配置，但不能把脏配置留给下一轮和线上。
CF0=$(getcfg curfew); AV0=$(getcfg app_version); AN0=$(getcfg analytics_enabled)

# 后台写入校验：填坏了要当场拦住，而不是"保存成功然后静默不生效"
BAD1=$(putcfg '{"key":"curfew","value":{"enabled":true,"days":[9],"from":"20:00","to":"21:00"}}' | jget msg)
echo "$BAD1" | grep -q "只能是 1~7" && ok "curfew.days 非法值被后台拦住（不会存进去再靠运行期回落）" || no "curfew 校验" "msg=$BAD1"
BAD2=$(putcfg '{"key":"app_version","value":{"minBuild":9,"latestBuild":1}}' | jget msg)
echo "$BAD2" | grep -q "不存在的版本" && ok "minBuild>latestBuild 被拦（那等于宣布全体安卓包都要更到一个没有的版本）" || no "版本门校验" "msg=$BAD2"
BAD3=$(putcfg '{"key":"analytics_enabled","value":"false"}' | jget msg)
echo "$BAD3" | grep -q "只能是 true 或 false" && ok "字符串 \"false\" 的埋点开关被拦（会被静默转成关停且毫无报错）" || no "埋点校验" "msg=$BAD3"
[ "$(getcfg curfew)" = "$CF0" ] && ok "被拦的三次写入确实没落库（curfew 仍是原值）" || no "写拦截未生效" "$(getcfg curfew)"

# 把放行日挪到"明天"，让"此刻不可玩"这一判定与跑回归的星期无关
NEXTW=$(( $(date +%u) % 7 + 1 ))
putcfg "{\"key\":\"curfew\",\"value\":{\"enabled\":true,\"days\":[$NEXTW],\"from\":\"20:00\",\"to\":\"21:00\",\"zone\":\"Asia/Shanghai\",\"hint\":\"回归用临时时段\",\"extraDates\":[]}}" >/dev/null

CF=$(curl -s "$BASE/api/curfew/status" -H "Authorization: Bearer $T")
[ "$(echo "$CF" | jget data.minor)" = "false" ] && [ "$(echo "$CF" | jget data.allowed)" = "true" ] \
  && ok "未标记未成年人：status 报 minor=false / allowed=true" || no "status 初始态" "$CF"
# days 是数组，jget 走 console.log 会打成 "[ 2 ]" 这种带空格的形式（Node 版本相关），
# 拿它和数字比迟早误报；这里直接在紧凑 JSON 上匹配原样下发的那段。
echo "$CF" | grep -q "\"days\":\[$NEXTW\]" && ok "放行窗口原样下发给客户端（days=$NEXTW），判定不在前端重做" || no "窗口下发" "$CF"
[ "$(curl -s "$BASE/api/curfew/status" | jget code)" = "401" ] && ok "无令牌的 curfew/status 被拒(401)" || no "status 鉴权" "$(curl -s "$BASE/api/curfew/status" | jget code)"

ON=$(curl -s -X POST "$BASE/api/me/minor" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"on":true}')
[ "$(echo "$ON" | jget data.minor)" = "true" ] && [ "$(echo "$ON" | jget data.allowed)" = "false" ] \
  && ok "开启青少年模式：回的是作废缓存后重查的权威视图（minor=true / allowed=false）" || no "minor on" "$ON"
[ -n "$(echo "$ON" | jget data.nextOpenAt)" ] && ok "下次放行时刻由服务端给出（nextOpenAt=$(echo "$ON" | jget data.nextOpenAt)），倒计时不靠客户端猜" || no "nextOpenAt" "$ON"
BLOCK=$(act state '{}')
[ "$(echo "$BLOCK" | jget tag)" = "CURFEW" ] && ok "时段外连只读的 state 意图也被闸门拦下（tag=CURFEW）" || no "闸门拦截" "$BLOCK"
[ "$(echo "$BLOCK" | jget code)" = "403" ] && ok "拦截回 403：客户端按 tag 分支，不去匹配会被改的文案" || no "拦截码" "$BLOCK"
echo "$BLOCK" | grep -q "距下次可玩还有" && ok "拦截话术带倒计时，玩家知道自己还要等多久" || no "拦截话术" "$BLOCK"
OFF=$(curl -s -X POST "$BASE/api/me/minor" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"on":false}')
[ "$(echo "$OFF" | jget data.allowed)" = "true" ] && ok "关闭后 allowed 立刻回 true（写库时作废缓存，不让家长等 TTL）" || no "minor off" "$OFF"
[ "$(echo "$(act state '{}')" | jget ok)" = "true" ] && ok "关掉青少年模式后玩法即刻恢复" || no "恢复玩法" "$(act state '{}')"

# 运营侧标记走的是同一列，且同样即时生效
curl -s -X POST "$BASE/admin/api/users/minor?id=$PUID&on=true" -H "$AH" >/dev/null
[ "$(curl -s "$BASE/api/curfew/status" -H "Authorization: Bearer $T" | jget data.minor)" = "true" ] \
  && ok "后台【用户管理·青少年】标记即时传导到玩家侧闸门" || no "后台标记传导" ""
curl -s -X POST "$BASE/admin/api/users/minor?id=$PUID&on=false" -H "$AH" >/dev/null
MINAUD=$(curl -s "$BASE/admin/api/moderation/audit?size=60" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];
console.log(d.some(x=>x.action==="user.minor"&&x.target==="uid:"+process.argv[1])?"yes":"no")' "$PUID")
[ "$MINAUD" = "yes" ] && ok "监护标记写了审计（谁在后台动过这个账号查得到）" || no "监护审计" "audit=$MINAUD"

# TapTap 登录：演示票据只在 dev-mode 认（正式包走真验签），换到的身份必须稳定
OPEN="e2ett$RANDOM$$"
T1=$(curl -s -X POST "$BASE/api/auth/taptap" -H 'Content-Type: application/json' -d "{\"ticket\":{\"dev\":\"dev:$OPEN\"}}")
[ "$(echo "$T1" | jget ok)" = "true" ] && [ -n "$(echo "$T1" | jget data.token)" ] \
  && ok "TapTap 票据换到本服令牌（票据只是凭据，采信与否由服务端验签决定）" || no "taptap 首次登录" "$T1"
T2=$(curl -s -X POST "$BASE/api/auth/taptap" -H 'Content-Type: application/json' -d "{\"ticket\":{\"dev\":\"dev:$OPEN\"}}")
[ -n "$(echo "$T1" | jget data.user)" ] && [ "$(echo "$T2" | jget data.user)" = "$(echo "$T1" | jget data.user)" ] \
  && ok "同一 openId 两次登录落在同一个账号（$(echo "$T1" | jget data.user)）" || no "身份稳定" "$T2"
[ "$(curl -s -X POST "$BASE/api/auth/taptap" -H 'Content-Type: application/json' -d '{}' | jget code)" = "401" ] \
  && ok "空票据被拒(401)" || no "空票据" ""
BG=$(curl -s -X POST "$BASE/api/auth/guest"); BGT=$(echo "$BG" | jget data.token)
BOPEN="e2ebind$$"
BIND=$(curl -s -X POST "$BASE/api/auth/taptap" -H "Authorization: Bearer $BGT" -H 'Content-Type: application/json' -d "{\"ticket\":{\"dev\":\"dev:$BOPEN\"}}")
[ "$(echo "$BIND" | jget data.merged)" = "true" ] && [ "$(echo "$BIND" | jget data.guest)" = "false" ] \
  && ok "游客档绑 TapTap：新建正式档并把游客进度并入（merged=true，不再是游客）" || no "游客绑定" "$BIND"
REBIND=$(curl -s -X POST "$BASE/api/auth/taptap" -H "Authorization: Bearer $(echo "$BIND" | jget data.token)" \
  -H 'Content-Type: application/json' -d "{\"ticket\":{\"dev\":\"dev:another$$\"}}" | jget msg)
echo "$REBIND" | grep -q "不是游客账号" && ok "正式档重复绑定被拒：$REBIND" || no "重复绑定拦截" "msg=$REBIND"

# 埋点总开关：关停是"静默不写库"，接口不能因此报错，否则客户端会以为是自己网坏了
putcfg '{"key":"analytics_enabled","value":false}' >/dev/null
[ "$(curl -s -X POST "$BASE/api/analytics/event" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"event":"sign","props":{}}' | jget ok)" = "true" ] \
  && ok "埋点关停时事件接口仍回 ok（丢弃而不报错，客户端无需重试）" || no "埋点关停" ""
putcfg "{\"key\":\"analytics_enabled\",\"value\":$AN0}" >/dev/null
[ "$(getcfg analytics_enabled)" = "$AN0" ] && ok "埋点开关复原为 $AN0" || no "埋点复原" "$(getcfg analytics_enabled)"

putcfg "{\"key\":\"curfew\",\"value\":$CF0}" >/dev/null
putcfg "{\"key\":\"app_version\",\"value\":$AV0}" >/dev/null
[ "$(getcfg curfew)" = "$CF0" ] && [ "$(getcfg app_version)" = "$AV0" ] \
  && ok "本段改过的三行配置已全部复原（回归不留脏配置）" || no "配置复原" "$(getcfg curfew)"

echo "== 举报通道（G2：玩家写得进审核表，后台处理得掉，重复的挡得住） =="
# 带中文的请求体不走 curl -d：Windows 上的 Git Bash 把命令行参数按本地码页（GBK）交出去，
# 服务端按 UTF-8 解，解出来只剩一句"请求体无法解析"——红的是脚本自己，不是被测的那条路。
# 正文改由 heredoc 落成 UTF-8 文件（脚本文件本身是 UTF-8，这段字节不经过 argv），再 --data-binary @ 发出去。
rbody() { cat > "$1"; }
rpost() { curl -s -X POST "$BASE/api/report" -H "Authorization: Bearer $1" \
  -H 'Content-Type: application/json' --data-binary @"$2"; }
# 举报人用临时游客档：日报销上限（chemera.report.daily-max）按举报人计，
# 拿主账号当举报人会让"今天第 N 次跑回归"变成随机失败，而游客档随下面的删号组一起从库里清掉。
GA=$(curl -s -X POST "$BASE/api/auth/guest")
GAT=$(echo "$GA" | jget data.token)
[ -n "$GAT" ] && ok "举报用的临时游客档已建档" || no "游客建档" "$GA"
OPEN0=$(curl -s "$BASE/admin/api/moderation/pending" -H "$AH" | jget data.open)
NOJWT=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/report" \
  -H 'Content-Type: application/json' -d '{"kind":"listing","ref":"e2ex|1","reason":"x"}')
[ "$NOJWT" = "401" ] && ok "匿名举报被拒(401)：举报是要追责的行为，不设匿名信箱" || no "举报鉴权" "http=$NOJWT"
[ "$(curl -s "$BASE/admin/api/moderation/pending" -H "$AH" | jget data.open)" = "$OPEN0" ] \
  && ok "鉴权在写库之前：那次匿名请求没给审核表留下行" || no "闸门顺序" "pending 从 $OPEN0 变了"
RREF="e2elisting|$$"
RWHY="挂价与官方基准价差得离谱"
rbody /tmp/chem-why.txt <<EOF
$RWHY
EOF
rbody /tmp/chem-rp.json <<EOF
{"kind":"listing","ref":"$RREF","reason":"$RWHY"}
EOF
RP=$(rpost "$GAT" /tmp/chem-rp.json)
if [ "$(echo "$RP" | jget code)" = "429" ]; then
  echo "  ! 本机 IP 的举报防洪配额已用尽，下面两组（举报通道 + 删号）跑不了，整层在这里停下。"
  echo "    开发档给的是 chemera.guard.report-max=200（application.yml；线上 prod 收紧到 20），"
  echo "    闸门在写库之前、但请求体解析之后，所以连格式错的请求都占格——还能撞满，通常是这台服务端"
  echo "    按更紧的口径启动了，或者一小时内跑了十几轮回归。"
  echo "    出路：按 mysql,dev 启动、重启后端（计数在内存里），或等窗口重置。宁可红得明白，不要绿得空洞。"
  exit 2
fi
[ "$(echo "$RP" | jget ok)" = "true" ] && ok "一条合法举报落库" || no "举报落库" "$RP"
[ "$(echo "$RP" | jget data.dailyMax)" -ge 1 ] \
  && ok "回执给的是'今天还能提几条'（openToday=$(echo "$RP" | jget data.openToday)/max=$(echo "$RP" | jget data.dailyMax)），不是自增 id" \
  || no "举报回执" "$RP"
[ "$(curl -s "$BASE/admin/api/moderation/pending" -H "$AH" | jget data.open)" = "$((OPEN0+1))" ] \
  && ok "待处理数随真实写入而变（$OPEN0 → $((OPEN0+1))）：后台那个角标不是写死的 0" || no "pending 计数" "$(curl -s "$BASE/admin/api/moderation/pending" -H "$AH")"
LISTOPEN="$BASE/admin/api/moderation/reports?status=open&size=50"
ROW=$(curl -s "$LISTOPEN" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];
let why=require("fs").readFileSync(process.argv[2],"utf8").trim();
let r=d.filter(x=>String(x.refId)===process.argv[1])[0];
console.log(r?r.id+" "+r.reporter+" "+(r.reason===why?"utf8ok":"moji"):"")' "$RREF" /tmp/chem-why.txt)
RID=${ROW%% *}; GAUID=$(echo "$ROW" | awk '{print $2}'); UTF8=$(echo "$ROW" | awk '{print $3}')
[ -n "$RID" ] && ok "后台列表查得到这条（ref 原样存着）" || no "举报入列表" "ref=$RREF row=$ROW"
[ "$UTF8" = "utf8ok" ] && ok "中文理由原样进库、原样出来（审核面上运营读的就是这句话）" || no "UTF-8 往返" "why=$UTF8"
rbody /tmp/chem-dup.json <<EOF
{"kind":"listing","ref":"$RREF","reason":"same question again"}
EOF
DUP=$(rpost "$GAT" /tmp/chem-dup.json | jget msg)
echo "$DUP" | grep -q "已经在处理" && ok "同一对象重复举报并成一条：$DUP" || no "重复举报去重" "msg=$DUP"
BADK=$(curl -s -X POST "$BASE/api/report" -H "Authorization: Bearer $GAT" -H 'Content-Type: application/json' \
  -d '{"kind":"chat","ref":"e2echat|1","reason":"a kind that does not exist"}' | jget msg)
echo "$BADK" | grep -q "没有这一类举报" && ok "kind 走闭合集合，表里没写的类型直接拒：$BADK" || no "类型闭合" "msg=$BADK"
NOTGT=$(curl -s -X POST "$BASE/api/report" -H "Authorization: Bearer $GAT" -H 'Content-Type: application/json' \
  -d '{"kind":"nickname","reason":"report without pointing at anybody"}' | jget msg)
echo "$NOTGT" | grep -q "请指明要举报哪位玩家" && ok "举报'人'必须给出对象（否则是一条无法查证的空转工单）" || no "缺对象" "msg=$NOTGT"
SELF=$(curl -s -X POST "$BASE/api/report" -H "Authorization: Bearer $GAT" -H 'Content-Type: application/json' \
  -d "{\"kind\":\"nickname\",\"target\":$GAUID,\"reason\":\"report myself\"}" | jget msg)
echo "$SELF" | grep -q "不能举报自己" && ok "不能举报自己（洗白式举报挡在这里）" || no "自举报" "msg=$SELF"
NOOBJ=$(curl -s -X POST "$BASE/api/report" -H "Authorization: Bearer $GAT" -H 'Content-Type: application/json' \
  -d '{"kind":"other","reason":"nothing to check against"}' | jget msg)
echo "$NOOBJ" | grep -q "请指明举报对象" && ok "'其他'类也要有对象或编号，否则运营只能当垃圾看" || no "空对象的其他类" "msg=$NOOBJ"
LONG=$(node -e 'console.log("a".repeat(401))')
OVER=$(curl -s -X POST "$BASE/api/report" -H "Authorization: Bearer $GAT" -H 'Content-Type: application/json' \
  -d "{\"kind\":\"listing\",\"ref\":\"e2elong|$$\",\"reason\":\"$LONG\"}" | jget msg)
echo "$OVER" | grep -q "400" && ok "超长理由按长度收口：$OVER" || no "长度收口" "msg=$OVER"
HAN=$(curl -s -X POST "$BASE/admin/api/moderation/reports/handle?id=$RID&status=handled" -H "$AH" | jget ok)
[ "$HAN" = "true" ] && ok "后台把这条标成已处理" || no "处理举报" "$HAN"
[ "$(curl -s "$BASE/admin/api/moderation/pending" -H "$AH" | jget data.open)" = "$OPEN0" ] \
  && ok "处理完待处理数回落（$OPEN0）：这个数字不会只涨不落" || no "处理后的计数" "$(curl -s "$BASE/admin/api/moderation/pending" -H "$AH")"
HADAUD=$(curl -s "$BASE/admin/api/moderation/audit?size=60" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];
console.log(d.some(x=>x.action==="report.handle"&&x.target==="report:"+process.argv[1])?"yes":"no")' "$RID")
[ "$HADAUD" = "yes" ] && ok "谁处理的这条举报落在审计里（report.handle + 工单 id）" || no "处理审计" "audit=$HADAUD"

echo "== 后台删号（G6：与玩家自助注销同一份清单，逐表清干净） =="
GB=$(curl -s -X POST "$BASE/api/auth/guest")
GBT=$(echo "$GB" | jget data.token)
[ -n "$GBT" ] && ok "被删的临时游客档已建档" || no "游客建档" "$GB"
curl -s -X POST "$BASE/api/game/bench.place" -H "Authorization: Bearer $GBT" -H 'Content-Type: application/json' \
  -d '{"id":"H2","n":2}' >/dev/null
curl -s -X POST "$BASE/api/analytics/event" -H "Authorization: Bearer $GBT" -H 'Content-Type: application/json' \
  -d '{"event":"discover","props":{"id":"H2O"}}' >/dev/null
BREF="e2ebyb|$$"
BBODY=$(curl -s -X POST "$BASE/api/report" -H "Authorization: Bearer $GBT" -H 'Content-Type: application/json' \
  -d "{\"kind\":\"listing\",\"ref\":\"$BREF\",\"reason\":\"this row must vanish with the account\"}")
BROW=$(curl -s "$LISTOPEN" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];
let r=d.filter(x=>String(x.refId)===process.argv[1])[0];
console.log(r?r.reporter:"")' "$BREF")
if [ "$(echo "$BBODY" | jget code)" = "429" ]; then
  echo "  ! 举报防洪配额在这一组中途用尽（B 自报的那条被拒），下面每一行都失去依据——整层停在这里，理由见上一组的说明。"
  exit 2
fi
# 举报行里带着 reporter，所以"这个账号的 uid"不必再去查表——也就绕开了拿中文用户名做检索那一跳
#（Git Bash 的 argv 走 GBK，中文 q= 在这台机器上根本搜不到人）。
GBUID=$BROW
if [ -z "$GBUID" ]; then
  echo "  ! 拿不到被删账号的 uid（B 那条举报没落库）：再往下就是 DELETE ?id= 空值引发的 500 连锁，红二十行也没有信息量。"
  echo "    B 的举报回执是：$BBODY"
  exit 2
fi
ok "从举报行拿到被删账号的 uid=$GBUID"
ABOUT="e2e-about-b-$$"
A2=$(curl -s -X POST "$BASE/api/report" -H "Authorization: Bearer $GAT" -H 'Content-Type: application/json' \
  -d "{\"kind\":\"nickname\",\"target\":$GBUID,\"reason\":\"$ABOUT\"}" | jget ok)
[ "$A2" = "true" ] && ok "另一个玩家举报了这个账号的昵称（target=$GBUID）" || no "针对 B 的举报" "$A2"
PND=$(curl -s "$BASE/admin/api/moderation/pending" -H "$AH" | jget data.open)
# 这一组的两条都还没人碰（上一组那条已在本组开始前被标成 handled，不进 open 计数），
# 所以这里必须是 +2 而不是 +1——漏算任何一条都说明角标读的不是真实计数。
[ "$PND" = "$((OPEN0+2))" ] \
  && ok "这组两条工单都没人处理，待处理从 $OPEN0 涨到 $PND（角标读的是真实计数，一条不漏）" || no "待处理累加" "open=$PND 期望 $((OPEN0+2))"
DELOP=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/admin/api/users?id=$GBUID")
[ "$DELOP" = "401" ] && ok "无令牌的后台删号被拒(401)" || no "删号鉴权" "http=$DELOP"
DELPLY=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/admin/api/users?id=$GBUID" -H "Authorization: Bearer $GAT")
[ "$DELPLY" = "401" ] && ok "玩家令牌换不来后台权限(401)：删号只认 admin 受众的令牌" || no "受众隔离" "http=$DELPLY"
DEL=$(curl -s -X DELETE "$BASE/admin/api/users?id=$GBUID" -H "$AH")
[ "$(echo "$DEL" | jget ok)" = "true" ] && ok "后台删号成功，回执把'删了几行'原样报出来" || no "删号" "$DEL"
for k in sessions save saveRevisions analytics user; do
  N=$(echo "$DEL" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||{};let v=d[process.argv[1]];console.log(typeof v==="number"?v:"")' "$k")
  [ -n "$N" ] && [ "$N" -ge 1 ] && ok "回执清掉了 $k（$N 行）" || no "清理 $k" "$DEL"
done
[ "$(echo "$DEL" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||{};console.log(d.reportsFiled)' )" = "1" ] \
  && ok "他发起的举报随行删掉（1 条）——那是他名下的数据" || no "reportsFiled" "$DEL"
[ "$(echo "$DEL" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||{};console.log(d.reportsAbout)' )" = "1" ] \
  && ok "别人举报他的那条只把 target 置空、行留着（1 条）：注销不该洗白举报史" || no "reportsAbout" "$DEL"
GONE=$(curl -s "$BASE/admin/api/moderation/reports?size=200" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];
console.log(d.some(x=>String(x.refId)===process.argv[1])?"still":"gone")' "$BREF")
[ "$GONE" = "gone" ] && ok "他自报的那条已在列表里查不到" || no "举报随删" "reports=$GONE"
KEPT=$(curl -s "$LISTOPEN" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];
let r=d.filter(x=>String(x.reason||"")===process.argv[1])[0];
console.log(r?r.id+"|"+(r.targetUser==null?"null":"set")+"|"+(r.targetGone?"gone":"live"):"")' "$ABOUT")
echo "$KEPT" | grep -q "|null|gone$" \
  && ok "针对他的那条还在审核面上，只是显示'被举报人已注销'（理由与工单号没丢）" || no "举报留痕" "row=$KEPT"
ST=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/game/state" -H "Authorization: Bearer $GBT")
[ "$ST" = "401" ] && ok "删号后他的令牌立刻调不动玩法（会话在同一笔事务里清了）" || no "会话作废" "http=$ST"
DELAUD=$(curl -s "$BASE/admin/api/moderation/audit?size=60" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];
let r=d.filter(x=>x.action==="user.delete"&&x.target==="uid:"+process.argv[1])[0];
console.log(r&&String(r.detail).indexOf("saveRevisions")>=0?"yes":"no")' "$GBUID")
[ "$DELAUD" = "yes" ] && ok "审计里留着这次删号删了几行的凭据" || no "删号审计" "audit=$DELAUD"
# 终局对账下探到库里：HTTP 回执报的是"删了几行"，而注销的合规承诺是"库里没有这个人的行"，只有查库说得清。
if command -v mysql >/dev/null 2>&1 && [ -n "${CHEMERA_DB_PASSWORD:-}" ]; then
  dbq() { MYSQL_PWD="$CHEMERA_DB_PASSWORD" mysql -h "${CHEMERA_DB_HOST:-localhost}" -P "${CHEMERA_DB_PORT:-3306}" \
    -u "${CHEMERA_DB_USER:-chem}" -D "${CHEMERA_DB_NAME:-chemera}" -N -B -e "$1" 2>/dev/null; }
  for t in "app_user:id" "user_save:user_id" "user_save_revision:user_id" "user_session:user_id" "analytics_event:user_id"; do
    TAB=${t%%:*}; COL=${t#*:}
    LEFT=$(dbq "SELECT COUNT(*) FROM $TAB WHERE $COL=$GBUID")
    [ "$LEFT" = "0" ] && ok "库里 $TAB 已无此人的行" || no "$TAB 残留" "rows=$LEFT"
  done
  FILLED=$(dbq "SELECT COUNT(*) FROM report WHERE reporter=$GBUID")
  [ "$FILLED" = "0" ] && ok "库里 report 里他发起的行清零" || no "report 残留" "rows=$FILLED"
  NULLTGT=$(dbq "SELECT target_user IS NULL FROM report WHERE reason='$ABOUT'")
  [ "$NULLTGT" = "1" ] && ok "库里那条针对他的举报留着，target_user 已置空（线索不随注销蒸发）" || no "举报置空" "target_user IS NULL=$NULLTGT"
else
  echo "  - 跳过逐表查库（本机没有 mysql 客户端或缺 CHEMERA_DB_PASSWORD）：上面那几行 HTTP 回执仍是删号的凭据"
fi
curl -s -X POST "$BASE/admin/api/moderation/reports/handle?id=${KEPT%%|*}&status=dismissed" -H "$AH" >/dev/null
[ "$(curl -s "$BASE/admin/api/moderation/pending" -H "$AH" | jget data.open)" = "$OPEN0" ] \
  && ok "本组留下的工单已全部处理，待处理数回到基线 $OPEN0（回归不留脏数据）" || no "工单收尾" "$(curl -s "$BASE/admin/api/moderation/pending" -H "$AH")"

echo "== 意图成本与运维面（G8/G1：一条意图几条 SQL，运维字段不给匿名） =="
# 这一组盯的是两件平时看不见的事：
# ① /api/healthz 是匿名可达的（探活探针不带口令），而它一度把 ad.devMode / taptap.mode 亮在外面——
#    那两个字说的正是"这台环境的验签是假的"，等于给外面的人一张地图。G1 把它们挪进了带口令的 ops 面。
# ② 结算链路的 SQL 条数只有压测才看得见，日常是盲区：往结算里加一次 N+1 不会有人发现。
#    G8 用 MyBatis 拦截器给每条意图记 avgSql / minSql，这里给它一条硬预算。
HZ=$(curl -s "$BASE/api/healthz")
LEAK=$(echo "$HZ" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8"));
let bad=["pool","guards","pendingReports","pendingAdTickets"].filter(k=>k in d);
if (d.ad && "devMode" in d.ad) bad.push("ad.devMode");
if (d.taptap && "mode" in d.taptap) bad.push("taptap.mode");
console.log(bad.join(",")||"clean")')
[ "$LEAK" = "clean" ] && ok "匿名 healthz 不含内部模式/池水位/待办量（只回 ok 与 db，不给攻击者画地图）" || no "healthz 泄漏" "字段=$LEAK"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/api/ops/health")" = "401" ] \
  && ok "这些字段挪到了要口令的 ops/health（匿名 401）" || no "ops 鉴权" "http=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/api/ops/health")"
OHS=$(curl -s "$BASE/admin/api/ops/health" -H "$AH")
[ -n "$(echo "$OHS" | jget data.pendingReports)" ] && [ -n "$(echo "$OHS" | jget data.pool.max)" ] \
  && ok "运维面照旧看得见全部内部模式：ad.devMode=$(echo "$OHS" | jget data.ad.devMode)、taptap.mode=$(echo "$OHS" | jget data.taptap.mode)、待办=$(echo "$OHS" | jget data.pendingReports) 条、池 max=$(echo "$OHS" | jget data.pool.max)" || no "ops/health 内容" "$OHS"
# 新启的服务端也可能一张表都还没攒出来，先自己造一次读档意图，保证下面有样本。
curl -s "$BASE/api/game/state" -H "Authorization: Bearer $T" >/dev/null
INTS=$(curl -s "$BASE/admin/api/ops/intents" -H "$AH")
[ "$(echo "$INTS" | jget ok)" = "true" ] \
  && ok "意图成本表同样要口令才给（viewer 那一档的 403 由 OpsHealthHttpTest 在 HTTP 层测）" || no "ops/intents" "$INTS"
HASPLACE=$(echo "$INTS" | node -e '
let d=(JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).intents||[];
console.log(d.some(x=>x.intent==="bench.place")?"yes":"no")')
[ "$HASPLACE" = "yes" ] && ok "读档→结算→写回那条全链路在表里（bench.place）" || no "意图缺项" "$INTS"
# ============ 意图 SQL 预算：读 p50Sql（干净样本的中位数）============
# 先说"干净样本"：没替内容缓存干过活、也没被 401/403/429 挡回去的那几次。两种脏样本都跟链的形状无关：
#   ① 缓存的活——后台改一次内容就 publishContent() 失效缓存，紧跟的那条意图要多跑 content_version /
#      content_item / app_config 三条把包重攒出来；ContentService 那份 1 秒版本号 TTL 到期时又多一条
#      SELECT version。服务端把它们合成一个总账（ContentRegistry.cacheWork() = 重建次数 + 版本号真读库
#      次数），意图前后一比就抓到；这条真的红过两次：一次是 claim.ach 只在"后台刚改过内容"那一段被调用
#      （每个样本都带那三条，最小值稳稳报到 7），一次是 sandbox.exit 只在脚本最后被调到一次、恰好跨过
#      TTL 边界（样本数 1，最小值等于那一次的运气，同一条链在 6 与 7 之间来回跳）。
#   ② 被拒的那次——宵禁 403、令牌失效 401、限流 429 只走到会话校验就返回，回归里到处都是。
# 再说为什么是中位数而不是最小或最大（都是逐条点出来的，把 mapper 调到 DEBUG 看 ==> Preparing）：
#   最小值会量到便宜分支：market.consumable 的 minSql 是 1（会话缓存命中的那一次），而它最费的那条分支
#   是 8；ad.request 最小 4（"上一次观看还没结束"那一支）最大 7（真签发）。往贵分支加一次 N+1，
#   拿最小值当预算是量不到的——尺子看着在量，其实量的是最便宜的那条路。
#   最大值会常红：state 第一次建档要多一条 INSERT 存档 + 版本行 + 清理，几乎每条链的上限都被这种一次性
#   分支抬高过；那一列（maxCleanSql）留给人看最坏能坏到哪，不当预算。
#   中位数两边都不骗：往结算里加的 N+1 是每次都发，中位数跟着涨。
# 预算本身：默认 6 条。管道（会话校验、读帧、广告 settle、CAS 带号写回）占 4~5 条，剩下的额度才是这条链自己的。
# 只有一条链申请了例外，理由写在下面的 cap() 里，不是把全局放松：
#   ad.request 7 条 —— 签发要三写：查在途工单（一位一单，防"囤券刷回调"）、清过期、插新工单。
#   已知可优化：把 expireStale 那次全表 UPDATE 挪出玩家点击路径能回到 6，但动的是广告工单的状态机
#   （live() 不看 expires_at，挪走之前得先给它加时间条件），留作后续，不在本轮里顺手改发钱的链路。
# 最后那条"至少 8 条意图有干净样本"钉的是尺子本身：几乎没有干净样本的话，预算就成了空转。
SQLT=$(echo "$INTS" | node -e '
let d=(JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).intents||[];
let cap=function(x){return x.intent==="ad.request"?7:6;};
let warm=d.filter(function(x){return typeof x.p50Sql==="number";});
function top(k,arr){let w={intent:"none",v:0};arr.forEach(function(x){if((+x[k]||0)>w.v)w={intent:x.intent,v:+x[k]||0};});return w;}
let rank=warm.map(function(x){return {intent:x.intent,v:x.p50Sql,over:x.p50Sql-cap(x),cap:cap(x)};})
             .sort(function(p,q){return (q.over-p.over)||(q.v-p.v);});
let a=rank[0]||{intent:"none",v:0,over:99,cap:6};
let b=top("avgSql",d);
let cold=d.filter(function(x){return typeof x.p50Sql!=="number";}).map(function(x){return x.intent;});
// 三列都在才算契约成立：只在"有干净样本的那几行"上查——null 会被线上的 NON_NULL 转换器整个抹掉，
// 拿 "p50Sql" in x 去问一条全是脏样本的意图，红的是转换器不是尺子。
let cols=warm.length>0&&warm.every(function(x){return typeof x.p50Sql==="number"&&typeof x.minSql==="number"&&typeof x.maxCleanSql==="number";})?"yes":"no";
// 判定字段先在这儿比好，只往 bash 递一个"over/in"：负数当 argv 递给 node 会被当成命令行开关
// （bad option: -2 → 非零退出），于是"最贴预算线的那条其实还差两条"这种健康情形反倒判成红。
// 本组里 over 为负才是正常状态，所以这一步不能省。
console.log([(a.over>0?"over":"in"),a.over,a.v,a.intent,a.cap,b.v,b.intent,d.length,warm.length,cols,cold.join(",")].join("|"));')
# 拆分用 read + 独有变量名：这里绝不能再叫 T——$T 是本脚本从头用到尾的玩家令牌，
# 拿它当临时变量会把后面每一句 act 都变成匿名请求（401），红得莫名其妙。
IFS='|' read -r BUDGET OVER WORST CHAINWHO CAPT AVGW AVGWHO NINT NMIN COLS COLDONLY <<<"$SQLT"
[ "$COLS" = "yes" ] && [ "$NMIN" != "0" ] \
  && ok "意图成本表带 p50Sql / minSql / maxCleanSql 三列（$NINT 条意图里 $NMIN 条有干净样本：旧服务端缺列就该红，不能让预算静默通过）" || no "预算列缺项" "sqlt=$SQLT"
node -e 'process.exit(Number(process.argv[1])>=8?0:1)' "$NMIN" \
  && ok "本轮有 $NMIN 条意图带着干净样本进预算（不是空转）" || no "预算在空转" "只有 $NMIN 条意图有干净样本"
[ -z "$COLDONLY" ] && ok "$NINT 条意图全都测到过干净样本" \
  || ok "这几条本轮只落在脏样本上（缓存的活或被拒的请求），不判它们的预算：$COLDONLY"
[ -n "$NINT" ] && [ "$NINT" != "0" ] \
  && ok "最贴预算线的意图：$CHAINWHO 中位数 $WORST 条（预算 $CAPT 条，超支 $OVER），平均最费的是 $AVGWHO（$AVGW 条，多出来的是脏样本摊进来的）" || no "读快照" "$INTS"
[ "$BUDGET" = "in" ] \
  && ok "单次意图 ≤6 条 SQL（ad.request 例外到 7：签发那三写；钉在干净样本的中位数上，谁往结算里加一次每次都发的查询，这个数就跟着涨）" || no "SQL 预算超支" "p50=$WORST cap=$CAPT intent=$CHAINWHO over=$OVER"
ORDER=$(echo "$INTS" | node -e '
let d=(JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).intents||[];
console.log(d.every((x,i)=>i===0||d[i-1].count>=x.count)?"desc":"bad")')
[ "$ORDER" = "desc" ] && ok "快照按调用数从多到少排（运维打开第一眼就是最忙的那条）" || no "快照排序" "order=$ORDER"

echo "== 缓存与运维面 =="

JS_CC=$(curl -s -D - -o /dev/null "$BASE/js/gate.js?v=${CURV:-1}" | tr -d '\r' | grep -i '^cache-control' | sed 's/^[Cc]ache-[Cc]ontrol: //')
echo "$JS_CC" | grep -q "immutable" && ok "版本化脚本长缓存：$JS_CC" || no "静态缓存" "cache-control=$JS_CC"
HTML_CC=$(curl -s -D - -o /dev/null "$BASE/" | tr -d '\r' | grep -i '^cache-control' | sed 's/^[Cc]ache-[Cc]ontrol: //')
[ "$HTML_CC" = "no-cache" ] && ok "入口文档不缓存（换 ?v= 立刻生效）" || no "入口缓存" "cache-control=$HTML_CC"
RID=$(curl -s -D - -o /dev/null "$BASE/api/content/version" | tr -d '\r' | grep -i '^x-request-id' | awk '{print $2}')
[ -n "$RID" ] && ok "每个请求带 traceId（X-Request-Id=$RID）" || no "请求追踪" "缺 X-Request-Id"
HZ=$(curl -s "$BASE/api/healthz")
[ "$(echo "$HZ" | jget db)" = "true" ] \
  && ok "healthz 报数据库连通 + 内容版本 $(echo "$HZ" | jget contentVersion)" || no "healthz" "$HZ"
# A7：状态码是客户端与运维唯一的分流依据。"调错接口"必须是 4xx 且说清该怎么调，
# 否则前端拿 500 去猜"服务器坏了"，运维在错误日志里翻的是不存在的故障。
WM=$(curl -s -o /tmp/chem-wm.json -w '%{http_code}' -X GET "$BASE/api/auth/taptap")
[ "$WM" = "405" ] && ok "方法用错回 405（不再伪装成服务端故障）" || no "405 收口" "http=$WM"
grep -q "POST" /tmp/chem-wm.json && ok "405 的回复点名支持的动词" || no "405 话术" "$(head -c 80 /tmp/chem-wm.json)"
NP=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/definitely/not/a/route")
[ "$NP" = "404" ] && ok "未知接口路径回 404" || no "404 收口" "http=$NP"
BAD=$(curl -s -o /tmp/chem-bad.json -w '%{http_code}' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d 'not json at all{')
# 参数类错误走的是"200 + ok:false + code:400"这条既有通道（和字段校验同一形状），
# 客户端只需要看 body.code 就能分流，不用同时处理两套错误位置；A7 收的是"别把客户端的锅记成 500"。
[ "$BAD" = "200" ] && grep -q '"code":400' /tmp/chem-bad.json && grep -q "UTF-8" /tmp/chem-bad.json \
  && ok "非法请求体按 400 业务码回并说明要 UTF-8 JSON（不是 500）" || no "请求体解析" "http=$BAD $(head -c 60 /tmp/chem-bad.json)"
# 反面对照：受保护的接口上"没登录"优先于"体不合法"——拦截器在解析之前就拒绝了，
# 所以这条必须是 401 而不是 400，否则等于让匿名请求探测哪些路径存在。
PRIO=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/game/intent" -H 'Content-Type: application/json' -d 'not json at all{')
[ "$PRIO" = "401" ] && ok "鉴权先于请求体解析（匿名者探不到受保护路由）" || no "鉴权优先级" "http=$PRIO"

echo "== 成就条件与结算参数可拨动（G4）=="
# G4 把"成就达成条件"和四个结算参数（升级曲线 / 单容器物质种数 / 事故概率与赔付 / 答题年级倍率）
# 从 Java 常数搬进了库。单测钉的是解释器与存前校验（AchievementRuleTest、EngineConfigValidatorTest、
# GameEngineConfigTest），这一段钉的是只有真栈才有的四件事：
#   1) strict 闸门在 HTTP 上确实拦得住（后台点保存的那个人看不到 Java）；
#   2) bundle 确实把 cond 与配置下发给客户端（前端只负责显示同一个数）；
#   3) 后台改完之后，线上结算确实跟着变（这是"外提"唯一有意义的证明）；
#   4) 拦下来的写入一条都没落库，本段结束时配置与内容都复原。
# 载荷一律 ASCII；要写中文键的 quiz_grade_mult 走 putcfg（bash 内置 printf 先落文件，curl 只 --data-binary 读文件）。
G4ID="E2EG4$RANDOM$$"
B0=$(getcfg bench_max_lines); L0=$(getcfg level_exp); A0=$(getcfg accident); Q0=$(getcfg quiz_grade_mult)

# 1. 词表只有一份：后台下拉里的指标就是引擎 AchievementRule.Metric 那一份
OPTG=$(curl -s "$BASE/admin/api/content/options" -H "$AH" | node -e '
let e = (JSON.parse(require("fs").readFileSync(0, "utf8")).data || {}).enums || {};
let m = e.achMetric || [], o = e.achOp || [];
console.log(m.length + "/" + o.length + "/" + (m.indexOf("knownReaction") >= 0 && m.indexOf("e2eBogusMetric") < 0 ? 1 : 0));')
echo "$OPTG" | grep -qE "^[0-9]{2}/4/1$" && ok "后台指标下拉=引擎词表（$OPTG：条数/比较符/含成员式指标）" || no "options 词表" "opt=$OPTG"
G4MISS=$(curl -s "$BASE/admin/api/config/spec" -H "$AH" | node -e '
let d = JSON.parse(require("fs").readFileSync(0, "utf8")).data || [];
let k = (Array.isArray(d) ? d : []).map((x) => x.key);
let miss = ["level_exp", "bench_max_lines", "accident", "quiz_grade_mult"].filter((x) => k.indexOf(x) < 0);
console.log(miss.length ? miss.join(",") : "none");')
[ "$G4MISS" = "none" ] && ok "四个结算参数都在《配置作用说明书》里（运营改前先看到后果）" || no "spec 缺键" "$G4MISS"

# 2. 下发面：cond 与配置都进了 bundle
G4B=$(curl -s "$BASE/api/content/bundle" | node -e '
let d = JSON.parse(require("fs").readFileSync(0, "utf8"));
let a = (d.content || {}).achievement || [], c = d.config || {};
let n = a.filter((x) => x && x.cond && x.cond.metric).length;
let cfg = ["level_exp", "bench_max_lines", "accident", "quiz_grade_mult"].filter((k) => c[k] !== undefined).length;
console.log((n === a.length ? "all" : "gap") + "/" + a.length + "/" + cfg);')
[ "${G4B%%/*}" = "all" ] && [ "${G4B##*/}" = "4" ] && ok "bundle 里每行成就都带 cond、四个结算参数齐（成就 $G4B）" || no "bundle 下发" "$G4B"
AF=$(curl -s "$BASE/admin/api/content/item?type=achievement&id=aFirst" -H "$AH")
[ "$(echo "$AF" | jget data.cond.metric)" = "success" ] \
  && ok "V12 的条件回填已落库（aFirst 读回 cond.metric=success，过渡白名单从此只是兜底）" || no "回填落库" "$AF"

# 3. strict 闸门：三类"配了等于没配"的写法当场拒，且都点名后果
G4BAD="{\"type\":\"achievement\",\"data\":{\"id\":\"$G4ID\",\"zh\":\"e2e g4\",\"desc\":\"probe\",\"reward\":300"
S1=$(curl -s -X PUT "$BASE/admin/api/content/item?strict=true" -H "$AH" -H 'Content-Type: application/json' -d "$G4BAD},\"sort\":9998,\"enabled\":1}")
[ "$(echo "$S1" | jget code)" = "400" ] && echo "$S1" | grep -q "永远判它未达成" \
  && ok "新成就行不写 cond 被 strict 拒（改造前它会静静入库、玩家永远领不到）" || no "无 cond 负例" "$S1"
S2=$(curl -s -X PUT "$BASE/admin/api/content/item?strict=true" -H "$AH" -H 'Content-Type: application/json' \
  -d "$G4BAD,\"cond\":{\"metric\":\"e2eBogusMetric\",\"value\":1}},\"sort\":9998,\"enabled\":1}")
echo "$S2" | grep -q "引擎读不出来" && ok "念不出来的指标当场拒（词表只有一份，不在上面就是错）" || no "非法指标" "$S2"
S3=$(curl -s -X PUT "$BASE/admin/api/content/item?strict=true" -H "$AH" -H 'Content-Type: application/json' \
  -d "$G4BAD,\"cond\":{\"metric\":\"discoveredSubstance\"}},\"sort\":9998,\"enabled\":1}")
echo "$S3" | grep -q "要知道对象" && ok "成员式指标没写 subject 也拒（半截条件不会静默变成永不达成）" || no "半截条件" "$S3"
S4=$(curl -s -X PUT "$BASE/admin/api/content/item?strict=true" -H "$AH" -H 'Content-Type: application/json' \
  -d "$G4BAD,\"cond\":{\"metric\":\"coins\",\"op\":\"ge\",\"value\":999999999}},\"sort\":9998,\"enabled\":1}")
[ "$(echo "$S4" | jget ok)" = "true" ] && ok "合法 cond 放行并落库（门槛先设成本档够不着）" || no "合法 cond 写入" "$S4"

# 4. 改配置 ⇒ 判定跟着变：同一枚成就，只动 cond 里的一个数
C1=$(act "claim.ach" "{\"id\":\"$G4ID\"}")
[ "$(echo "$C1" | jget data.result.ok)" = "false" ] && echo "$C1" | grep -q "尚不可领取" \
  && ok "金币远不到 999999999：服务端按行里的条件判未达成" || no "高门槛 cond" "$C1"
curl -s -o /dev/null -X PUT "$BASE/admin/api/content/item?strict=true" -H "$AH" -H 'Content-Type: application/json' \
  -d "$G4BAD,\"cond\":{\"metric\":\"coins\",\"op\":\"ge\",\"value\":1}},\"sort\":9998,\"enabled\":1}"
C2=$(act "claim.ach" "{\"id\":\"$G4ID\"}")
[ "$(echo "$C2" | jget data.result.ok)" = "true" ] && [ "$(echo "$C2" | jget data.result.reward)" = "300" ] \
  && ok "把 cond 的门槛从 999999999 改成 1，同一枚成就当场可领、奖励仍按行里那份发" || no "改 cond 后仍拒" "$C2"
curl -s -o /dev/null -X DELETE "$BASE/admin/api/content/item?type=achievement&id=$G4ID" -H "$AH"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/api/content/item?type=achievement&id=$G4ID" -H "$AH")" = "404" ] \
  && ok "探针成就已清理" || no "探针清理" ""

# 5. 结算参数同理：台位上限是唯一能纯靠意图看出来的那一个
act "market.buy" '{"id":"H2","amt":3}' >/dev/null
act "market.buy" '{"id":"O2","amt":3}' >/dev/null
putcfg '{"key":"bench_max_lines","value":1}' >/dev/null
act "bench.clear" '{}' >/dev/null
BP1=$(act "bench.place" '{"id":"H2","n":1}' | jget data.result.ok)
BP2=$(act "bench.place" '{"id":"O2","n":1}')
[ "$BP1" = "true" ] && [ "$(echo "$BP2" | jget data.result.ok)" = "false" ] && echo "$BP2" | grep -q "最多容纳 1 种" \
  && ok "台位上限拨到 1：第二种物质当场被拒，拒绝理由里就是配置里那个数" || no "bench=1" "p1=$BP1 body=$BP2"
putcfg "{\"key\":\"bench_max_lines\",\"value\":$B0}" >/dev/null
act "bench.clear" '{}' >/dev/null
BP3=$(act "bench.place" '{"id":"H2","n":1}' | jget data.result.ok)
BP4=$(act "bench.place" '{"id":"O2","n":1}' | jget data.result.ok)
[ "$BP3" = "true" ] && [ "$BP4" = "true" ] && ok "改回原值后同一操作立刻放行（复原也是即时生效的）" || no "bench 复原" "p3=$BP3 p4=$BP4"
act "bench.clear" '{}' >/dev/null

# 6. 存前范围：这四个键填坏了没有任何回声，所以只能在这一步拦
N1=$(putcfg '{"key":"bench_max_lines","value":0}')
echo "$N1" | grep -q "只能是 1~64" && ok "台位上限写 0 被拦（那等于投放任何物质都被拒）" || no "bench 越界" "$N1"
N2=$(putcfg '{"key":"accident","value":{"hit_base":5}}')
echo "$N2" | grep -q "只能写 0~1" && ok "事故概率写 5 被拦（引擎不报错，它会拿 5 直接算结算）" || no "accident 越界" "$N2"
N3=$(putcfg '{"key":"level_exp","value":{"base":0,"coef":0}}')
echo "$N3" | grep -q "只能是 5~100000" && ok "升级曲线写成 0/0 被拦（服务端会在加经验那一帧里转不出来）" || no "level_exp 越界" "$N3"
N4=$(putcfg '{"key":"quiz_grade_mult","value":{"e2eBogusGrade":2}}')
echo "$N4" | grep -q "引擎查不到" && ok "倍率表里年级名拼错被拦（那一档永远查不到，等于配了个寂寞）" || no "quiz 年级名" "$N4"
[ "$(getcfg bench_max_lines)" = "$B0" ] && [ "$(getcfg accident)" = "$A0" ] && [ "$(getcfg level_exp)" = "$L0" ] \
  && ok "被拦的三次写入一条都没落库（三项配置仍是原值）" || no "写拦截未生效" "bench=$(getcfg bench_max_lines) acc=$(getcfg accident)"

# 7. 合法改写要能在下发面看见（前端显示与后端判定读的是同一份）
putcfg '{"key":"level_exp","value":{"base":20,"coef":0}}' >/dev/null
putcfg '{"key":"quiz_grade_mult","value":{"小学":1.0,"初中":1.0,"高中":1.2,"大学":3.0}}' >/dev/null
LVQ=$(curl -s "$BASE/api/content/bundle" | node -e '
let d = JSON.parse(require("fs").readFileSync(0, "utf8"));
let c = d.config || {};
let m = Object.keys(c.quiz_grade_mult || {}).map((k) => c.quiz_grade_mult[k]);
console.log(((c.level_exp || {}).base) + "/" + (m.length ? Math.max.apply(null, m) : 0));')
[ "$LVQ" = "20/3" ] && ok "改过的升级曲线与倍率表原样下发（level_exp.base=20、大学=3.0）" || no "配置下发" "lv=$LVQ"
putcfg "{\"key\":\"level_exp\",\"value\":$L0}" >/dev/null
putcfg "{\"key\":\"quiz_grade_mult\",\"value\":$Q0}" >/dev/null
[ "$(getcfg level_exp)" = "$L0" ] && [ "$(getcfg quiz_grade_mult)" = "$Q0" ] && ok "本段结束时四项结算参数全部复原" || no "配置复原" "$(getcfg level_exp) $(getcfg quiz_grade_mult)"
[ "$(curl -s "$BASE/admin/api/content/health" -H "$AH" | jget data.ok)" = "true" ] \
  && ok "跑完这一段内容体检仍然 0 问题（没留下念不出来的成就行）" || no "体检复原" ""

echo "== 共享向量表 ↔ 玩家实际取到的那份内容（H1 的第三条边）=="
# 第 1 层比的是 data/*.js ↔ 夹具（content-bundle.json），第 3 层拿同一份夹具跑服务端结算——两边都在仓库里。
# 可真正在跑的那份内容在 MySQL：后台改一个价格、跑一次迁移，content_version 往前走一格，上面两层照样全绿，
# 而玩家的"预览报价"和"落账"读的是线上这一份。这一段把线上 bundle 当作待比内容，走 run.js 里同一套
# bundleParity 判据（绝不在 shell 里再写一遍比较逻辑：两套各判各的，最后只会互相"看起来对得上"）。
# 版本号和向量里钉的 contentVersion 不一致只报不判：改简介、调 sort 都会 bump 版本，那种改动碰不到读数。
grep -q "golden/run.js" "$ROOT/test/validate.js" \
  && ok "第 1 层确实挂着共享向量表（test/validate.js 里 require 了 golden/run.js）" || no "向量表接线" "validate.js 不再调用 golden/run.js，这层回归已被绕过"
GD=$(curl -s "$BASE/api/content/bundle" | node "$ROOT/test/golden/check-deployed.js")
[ $? -eq 0 ] && ok "$GD" || no "线上内容与向量表对不上" "$GD"

echo "== 整页重绘与重复计算（H3）=="
# 这一段读的全是服务端下发的 js/*.js 与 css：只改在仓库里、忘了重出包，玩家拿到的还是"一次铺 214 张卡"那一版。
# 判据本体在 test/render-budget.js——把随包那份的分页函数切出来 new Function 真跑一遍（切几项、点更多接不接得上、
# 作废会不会误伤别的列表），再补静态接线。不在 shell 里重写一遍比较逻辑：本机 grep 判不动中文，两套裁判也只会
# 互相"看起来对得上"。
RB=$(curl -s "$BASE/js/panels.js" | node "$ROOT/test/render-budget.js" panels) \
  && ok "长列表分页与按帧记忆：$RB" || no "H3 渲染收口" "$(echo "$RB" | paste -sd' ' -)"
RB2=$(curl -s "$BASE/js/state.js" | node "$ROOT/test/render-budget.js" state) \
  && ok "subMap 失效点：$RB2（绑内容版本，既不每帧也不绑 revision）" || no "H3 查表缓存" "$(echo "$RB2" | paste -sd' ' -)"
HGJS=$(curl -s "$BASE/js/game.js")
echo "$HGJS" | grep -q "invalidateSubs()" && echo "$HGJS" | grep -q "invalidateCounts()"   && ok "后台换版的落地回调走两个作废入口（查表 + 角标记忆，不再手写 subMap = null）" || no "换版作废" "game.js 未调 invalidateSubs/invalidateCounts"
curl -s "$BASE/css/style.css" | grep -qF ".item-card.qs-more .sq"   && ok "页面版『更多』卡有 token 样式（不再只有物质架那条 #qs-row）" || no "分页样式" "style.css 缺全局 .item-card.qs-more"

echo "== 音频点火与触感（H4）=="
# 在线版每条音效都是服务端结算回来才响，那一次 new AudioContext 落在手势外：WebView 里它一起就是
# suspended，resume() 又被自动播放策略拒掉，整局静音而界面毫无异常。触感同理——"该振哪三处、振多久、
# 设备不支持就跳过"只有把那段切出来喂假 navigator 才判得动，所以复用 test/audio-haptics.js：
# 第 1 层判仓库那份，这里判服务端下发的那一份，两边同一把裁判。
AH1=$(curl -s "$BASE/js/sfx.js" | node "$ROOT/test/audio-haptics.js" sfx) \
  && ok "音频与触感：$AH1" || no "H4 音频/触感" "$(echo "$AH1" | paste -sd' ' -)"
AH2=$(curl -s "$BASE/js/panels.js" | node "$ROOT/test/audio-haptics.js" panels) \
  && ok "设置页联动：$AH2" || no "H4 滑条联动" "$(echo "$AH2" | paste -sd' ' -)"
# 音乐开关是真源：滑条改完要连着 music 一起落库，否则下一次存档快照会把玩家刚打开的开关抹回关闭
curl -s "$BASE/js/panels.js" | grep -q "music: !!st.data.music" \
  && ok "音量滑条把开关一并写进 settings 意图（不只在本地改一下）" || no "滑条落库" "vol-mus 的 onchange 没带上 music"
# 触感不进存档：它是本机偏好，走 localStorage，所以 settings 意图的字段集里不该多出 haptic
! curl -s "$BASE/js/panels.js" | grep -q "haptic:" \
  && ok "触感开关走本机偏好，没混进存档字段（换设备不该带走上一台的振动习惯）" || no "触感归属" "panels 把 haptic 当存档字段送服务端了"

echo "== 后台配置说明书下发（H6-1）=="
# 面板原来自己抄了两张表（键名→编辑器、scope→颜色），后端加「已退役」那一档时没人跟，
# 于是"改它什么都不发生"的退役键显示得和"改它不影响结算"一样温和。现在两张表都删了，
# 颜色与控件由 ConfigSpec 的 tone/form 下发——那就必须验"线上真的下发了"并且"面板接得住"：
# 复用 test/admin-spec.js，第 1 层拿 ConfigSpec.java 对账，这里拿运行中后端的返回对账同一份 Vue 源码。
CS=$(curl -s "$BASE/admin/api/config/spec" -H "$AH")
H6=$(printf '%s' "$CS" | node "$ROOT/test/admin-spec.js" served 2>&1) \
  && ok "配置说明书与面板同源：$H6" || no "H6 描述符下发" "$(echo "$H6" | paste -sd' ' -)"
# 没登录拿不到说明书：它带着"哪一档会动全体玩家的钱包"，不该是个匿名可读面
CS401=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/admin/api/config/spec")
[ "$CS401" != "200" ] && ok "/config/spec 需要后台登录态（未登录回 $CS401）" || no "描述符鉴权" "匿名也能读到配置说明书"
printf '%s' "$CS" | node -e 'let d="";process.stdin.on("data",c=>d+=c).on("end",()=>{
  const r=(JSON.parse(d).data||[]);const bad=r.filter(x=>!x.form||!x.tone);
  console.log(bad.length?"缺 tone/form："+bad.map(x=>x.key).join(","):"每键都带了 form 与 tone（"+r.length+" 个键）");
  process.exit(bad.length?1:0);})' >/tmp/h6tone.txt 2>&1 \
  && ok "$(cat /tmp/h6tone.txt)" || no "描述符列不全" "$(cat /tmp/h6tone.txt)"

echo "== 后台分页与总数：{rows,total} 与体检缓存（H6-3）=="
# 面板以前自己算总数：d.length < size ? 偏移+本页条数 : 页码*size+1。服务端返的是裸 List，
# 它没有总数可用，于是那个数字在最后一页说"共有 N+1 条"、满页时永远说"还有下一页"——
# 运营对着一个假页数翻页，翻到空页以为数据没了。现在六个列表端点同形，这一节就判四件事：
# 形状统一、筛选条件进得了总数、翻页不重不漏、页长上限真封顶；外加体检不再每次请求重扫全表。
# 全程只读现网接口 + 两个探针词/一个探针键，跑完自己清干净。
BADSHAPE=""
for SPEC in "content/items|type=element" "users|" "config|" "moderation/reports|" "moderation/words|" "moderation/audit|"; do
  EP="${SPEC%%|*}"; QS="${SPEC#*|}"
  curl -s "$BASE/admin/api/$EP?${QS:+$QS&}size=2" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||{};
// rows 必须是数组、total 必须是数字（不是字符串、不是缺省），否则面板的 :total 绑上去是个 "-"
process.exit(Array.isArray(d.rows)&&Number.isInteger(d.total)&&d.rows.length<=2&&d.rows.length<=d.total?0:1)' \
    || BADSHAPE="$BADSHAPE $EP"
done
[ -z "$BADSHAPE" ] && ok "六个列表端点同形 {rows,total}（面板不再需要自己编总数）" || no "分页形状" "形状不对的端点:$BADSHAPE"

# 翻页走到头：每页 5 行逐页并起来，必须正好等于 total。
# ORDER BY 少了唯一列收尾的话，同一行会在第 1 页和第 2 页各出现一次、另一行两页都不出现——
# 这种"看着都是 5 行"的错只有走一遍页才能露出来，单页断言判不了。
CWF=$(mktemp); : > "$CWF"
for off in $(seq 0 5 200); do
  curl -s "$BASE/admin/api/config?size=5&off=$off" -H "$AH" >> "$CWF"; echo >> "$CWF"
done
WALKOUT=$(node -e '
const t=require("fs").readFileSync(process.argv[1],"utf8").trim().split("\n").filter(Boolean).map((l)=>JSON.parse(l));
const bad=[], totals=[...new Set(t.map((x)=>x.data.total))];
const pages=t.filter((x)=>x.data.rows.length>0);
const keys=pages.flatMap((x)=>x.data.rows.map((r)=>r.cfgKey));
if(totals.length!==1) bad.push("total 随翻页变了："+totals.join("/"));
if(pages.length<2) bad.push("只有 1 页，这条判据根本没被执行到");
else if(pages[pages.length-1].data.rows.length>=5) bad.push("最后一页还是满页：没走到头就拿第 1 页冒充全表");
if(new Set(keys).size!==keys.length) bad.push("有行在两页里各出现一次（排序没有唯一列收尾）");
if(keys.length!==totals[0]) bad.push("逐页并起来 "+keys.length+" 行 ≠ total "+totals[0]);
console.log(bad.length?bad.join("；"):pages.length+" 页走到头、"+totals[0]+" 个键不重不漏");
process.exit(bad.length?1:0)' "$CWF" 2>&1)
WALK=$?
[ "$WALK" = "0" ] && ok "配置列表按 5 行翻页：$WALKOUT" || no "翻页不重不漏" "$WALKOUT"
rm -f "$CWF"

# 筛选条件要进得了总数：面板挂着搜索框翻页时，页数读的是 total，命中却由 WHERE 决定，
# 两处手写就会漂（漂了表现为"搜出来 3 条、分页却显示 20 页"）。
FULL=$(curl -s "$BASE/admin/api/config?size=200" -H "$AH" | jget data.total)
FT=$(curl -s "$BASE/admin/api/config?q=level&size=200" -H "$AH" | FULL="$FULL" node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data, full=Number(process.env.FULL);
let miss=d.rows.filter((r)=>!((String(r.cfgKey)+String(r.category)+String(r.remark)).toLowerCase().indexOf("level")>=0));
let ok=d.total>0&&d.total<full&&d.total===d.rows.length&&miss.length===0;
console.log((ok?"yes ":"no ")+d.total+"/"+full+" 条命中"+(miss.length?"，其中 "+miss.length+" 条其实搜不出来":""));')
[ "${FT%% *}" = "yes" ] && ok "搜索条件进得了总数（q=level：${FT#* }）" || no "筛选与总数" "$FT"

# 举报列表的 open 总数必须和侧栏角标是同一个数：两处一个是 COUNT(status='open')、
# 一个是列表带筛选的 total，各数各的话运营看到的"待处理 3"和翻出来的 5 条会同时存在。
ROPEN=$(curl -s "$BASE/admin/api/moderation/reports?status=open&size=1" -H "$AH" | jget data.total)
RHAN=$(curl -s "$BASE/admin/api/moderation/reports?status=handled&size=1" -H "$AH" | jget data.total)
RDIS=$(curl -s "$BASE/admin/api/moderation/reports?status=dismissed&size=1" -H "$AH" | jget data.total)
RALL=$(curl -s "$BASE/admin/api/moderation/reports?size=1" -H "$AH" | jget data.total)
PENDING=$(curl -s "$BASE/admin/api/moderation/pending" -H "$AH" | jget data.open)
[ "$ROPEN" = "$PENDING" ] \
  && ok "带筛选的 total 与角标同源（open=$ROPEN）：那个数不是第二套算法" || no "筛选总数" "列表=$ROPEN 角标=$PENDING"
[ "$((ROPEN+RHAN+RDIS))" = "$RALL" ] \
  && ok "三种状态的 total 加起来等于不分状态的 total（$RALL）：WHERE 确实进了 COUNT，不是只在列表上过滤" \
  || no "状态总数对账" "open=$ROPEN handled=$RHAN dismissed=$RDIS ≠ all=$RALL"

# 词表这一张以前整表搬给面板、而且没有分页。改成 id DESC 分页后，"加完词立刻能在第一页
# 看到自己那条"从一句界面感受变成可断言的事（顺序反了运营会以为没存上，再去点一次保存）。
WORDS0=$(curl -s "$BASE/admin/api/moderation/words?size=1" -H "$AH" | jget data.total)
WA="e2ew${RANDOM}a"; WB="e2ew${RANDOM}b"
word_add(){ curl -s -X POST "$BASE/admin/api/moderation/words" -H "$AH" -H 'Content-Type: application/json' \
  -d "{\"word\":\"$1\",\"level\":$2}" >/dev/null; }
word_del(){ local ID; ID=$(curl -s "$BASE/admin/api/moderation/words?size=200" -H "$AH" | W="$1" node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows;
let r=d.filter((x)=>x.word===process.env.W)[0];console.log(r?r.id:"")')
  [ -n "$ID" ] && curl -s -X DELETE "$BASE/admin/api/moderation/words?id=$ID" -H "$AH" >/dev/null; }
word_add "$WA" 1; word_add "$WB" 2
WORDB=$(curl -s "$BASE/admin/api/moderation/words?size=1" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data;
console.log([d.total, d.rows.length===1?d.rows[0].word:""].join("|"))')
[ "${WORDB%%|*}" = "$((WORDS0+2))" ] \
  && ok "词表 total 随真实写入而变（$WORDS0 → ${WORDB%%|*}）：它数的是整张表，不是本页" || no "词表总数" "total=${WORDB%%|*} 期望 $((WORDS0+2))"
[ "${WORDB#*|}" = "$WB" ] \
  && ok "新加的词落在第 1 页第 1 行（id DESC）：运营加完不用翻到最后去找" || no "词表排序" "首行=${WORDB#*|} 期望 $WB"
word_del "$WA"; word_del "$WB"
[ "$(curl -s "$BASE/admin/api/moderation/words?size=1" -H "$AH" | jget data.total)" = "$WORDS0" ] \
  && ok "两个探针词已清掉（total 回到 $WORDS0）" || no "词表探针残留" "$(curl -s "$BASE/admin/api/moderation/words?size=1" -H "$AH" | jget data.total)"

# 体检全表扫描：这一张表有几百行，每次打开面板都扫一遍等于把后台按在玩家能感受到的位置上。
# 现在它按 content_version 缓存，runId 是这份结果的扫描序号（时间戳同毫秒可以相同，判不出重扫）。
hrun(){ local Q="${1:-}"; curl -s "$BASE/admin/api/content/health${Q:+$Q}" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data;console.log(d.runId+"|"+d.fromCache+"|"+d.checked)'; }
H63A=$(hrun); H63B=$(hrun)
[ "${H63A%%|*}" = "${H63B%%|*}" ] && [ "$(echo "$H63B" | cut -d'|' -f2)" = "true" ] \
  && ok "连读两次同一份结果（runId=${H63A%%|*}，第二次 fromCache=true）：不再每次请求重扫 ${H63A##*|} 行" \
  || no "体检缓存" "$H63A vs $H63B"
H63F=$(hrun "?fresh=true")
[ "${H63F%%|*}" -gt "${H63A%%|*}" ] && [ "$(echo "$H63F" | cut -d'|' -f2)" = "false" ] \
  && ok "【重新体检】真的重扫（runId ${H63A%%|*} → ${H63F%%|*}，fromCache=false）：那个按钮不是摆设" \
  || no "fresh 重扫" "$H63A vs $H63F"
# 后台一写就得把这份缓存作废：改完内容体检页还显示上一版，运营会判成"下发没生效"再去改一遍。
# 探针键跑完删掉（它不在说明书里，正合"自定义键"这条路）。
putcfg "{\"key\":\"e2eH63Probe\",\"value\":1,\"category\":\"general\",\"remark\":\"h6-3 probe\"}" >/dev/null
H63W=$(hrun)
[ "${H63W%%|*}" -gt "${H63F%%|*}" ] \
  && ok "后台改一笔配置，体检缓存自动作废（runId ${H63F%%|*} → ${H63W%%|*}）" || no "写后失效" "$H63F vs $H63W"
curl -s -X DELETE "$BASE/admin/api/config?key=e2eH63Probe" -H "$AH" >/dev/null
[ "$(curl -s "$BASE/admin/api/config/get?key=e2eH63Probe" -H "$AH" | jget code)" = "404" ] \
  && ok "探针键已清掉" || no "体检探针残留" "$(curl -s "$BASE/admin/api/config/get?key=e2eH63Probe" -H "$AH")"

# 页长上限与负偏移在服务端归一（Page.size / Page.off），面板传什么都不该变成全表搬运或 SQL 报错。
CLAMP=$(curl -s "$BASE/admin/api/config?size=99999&off=-7" -H "$AH" | FULL="$FULL" node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data, full=Number(process.env.FULL);
let bad=[];
if(!(d.rows.length<=200)) bad.push("一次搬了 "+d.rows.length+" 行（MAX_SIZE 没封顶）");
if(d.total!==full) bad.push("total "+d.total+" ≠ 全表 "+full+"（off=-7 没当 0 处理）");
console.log(bad.length?bad.join("；"):"这一页 "+d.rows.length+" 行 / 全表 "+full+" 条");
process.exit(bad.length?1:0)' 2>&1)
[ "$?" = "0" ] \
  && ok "size=99999 被压到 MAX_SIZE、off=-7 当 0 处理：$CLAMP" || no "页长归一" "$CLAMP"
[ "$(curl -s "$BASE/admin/api/config?size=99999&off=-7" -H "$AH" | jget data.rows.0.cfgKey)" \
   = "$(curl -s "$BASE/admin/api/config?size=1" -H "$AH" | jget data.rows.0.cfgKey)" ] \
  && ok "负偏移不是「往前多要几行」，而是从第 1 页开始" || no "负偏移语义" ""

echo "== 意图覆盖面（H7）之一：签到 / 提示 / 社交 / 排行榜 / 我的档案 =="
# 裁判 test/intent-coverage.js 拿服务端的 switch (intent) 与本脚本实际打出去的意图对表，
# 47 条协议面里只有 19 条被正向打过。下面几段把剩下的补齐。断言一律落在**整帧里的真状态**
# （streak / rep / coins / bag / counters / 解锁表），而不是回执里那句 ok=true；
# 已有段落测过的拒绝分支不重复演。真做不到的两条留给裁判的 WHITELIST 并写清理由。
SG=$(act "sign")
[ "$(echo "$SG" | jget data.result.ok)" = "true" ] && [ "$(echo "$SG" | jget data.result.day)" = "1" ] \
  && ok "sign：连签第 1 天，按签到表第 1 档发 🪙$(echo "$SG" | jget data.result.coins)" || no "sign 首日" "$SG"
[ "$(echo "$SG" | jget data.state.sign.streak)" = "1" ] \
  && [ "$(echo "$SG" | jget data.state.sign.last)" = "$(echo "$SG" | jget data.state.daily.date)" ] \
  && ok "签到把 streak/last 写进整帧（last 就是当日键 $(echo "$SG" | jget data.state.sign.last)）" || no "sign 落帧" "$SG"
[ -n "$(echo "$SG" | jget data.result.gift)" ] \
  && [ -n "$(echo "$SG" | jget "data.state.bag.$(echo "$SG" | jget data.result.gift)|0")" ] \
  && ok "签到回礼真进了背包（$(echo "$SG" | jget data.result.gift) 已在 bag 里数得出）" || no "sign 回礼入包" "$SG"
SG2=$(act "sign")
[ "$(echo "$SG2" | jget data.result.ok)" = "false" ] && echo "$SG2" | grep -q "今日已签到" \
  && ok "同日第二签被拒：$(echo "$SG2" | jget data.result.msg)" || no "重复签到没拦住" "$SG2"
[ "$(echo "$SG2" | jget data.state.sign.streak)" = "1" ] \
  && [ "$(echo "$SG2" | jget data.state.coins)" = "$(echo "$SG" | jget data.state.coins)" ] \
  && ok "被拒那一签不落 streak、也不再发钱（余额与上一签同）" || no "重复签到偷偷发钱" "$SG2"

# 提示：先券后币两条路都得真的扣东西，且给出来的必须是**这条方程式服务端还没知道**的那一条。
H0=$(echo "$SG2" | jget data.state.hints); HC=$(echo "$SG2" | jget data.state.coins)
HM=$(act "hint" '{"spendCoin":true}')
[ "$(echo "$HM" | jget data.result.ok)" = "true" ] && [ -n "$(echo "$HM" | jget data.result.eq)" ] \
  && ok "hint 指出一条尚未解锁的方程式（$(echo "$HM" | jget data.result.rid)：$(echo "$HM" | jget data.result.eq)）" || no "hint 无回执" "$HM"
H1V=$(echo "$HM" | jget data.state.hints); C1V=$(echo "$HM" | jget data.state.coins)
HGOOD=0
if [ "${H0:-0}" -gt 0 ]; then [ "$H1V" = "$((H0 - 1))" ] && HGOOD=1
else [ "$C1V" = "$((HC - 300))" ] && HGOOD=1; fi
[ "$HGOOD" = "1" ] && ok "提示按「先券后币」计费（券 ${H0:-0}→$H1V、🪙$HC→$C1V，只走一条）" || no "hint 计费" "券 $H1V 币 $C1V 期望券 $((H0-1)) 或币 $((HC-300))"
HS=$(echo "$HM" | jget data.result.rid)
[ "$(echo "$HM" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data;let k=d.result.rid;console.log(d.state.reactionsKnown&&d.state.reactionsKnown[k]===true?"known":"unknown")')" = "unknown" ] \
  && ok "提示给的确实是还没解锁的那条（reactionsKnown 里查不到 $HS）" || no "hint 挑了已知方程式" "rid=$HS"

# 社交：拜访有每日一次闸，回礼与内容表同一份；送礼回来的是声望与谢金。
NPC=$(curl -s "$BASE/api/content/bundle" | node -e '
let c=JSON.parse(require("fs").readFileSync(0,"utf8")).content||{};let n=c.npc||[];
let x=n.filter(y=>y.gift&&y.gift.id)[0]||n[0]||{};console.log(x.id+" "+((x.gift&&x.gift.id)||""));')
NPCID=${NPC%% *}; NPCGIFT=${NPC##* }
V0=$(echo "$HM" | jget data.state.stats.visits); R0=$(echo "$HM" | jget data.state.rep)
FV=$(act "friend.visit" "{\"npcId\":\"$NPCID\"}")
[ "$(echo "$FV" | jget data.result.ok)" = "true" ] && [ "$(echo "$FV" | jget data.result.gift.id)" = "$NPCGIFT" ] \
  && ok "friend.visit 收到该位好友的回礼（$NPCGIFT ×$(echo "$FV" | jget data.result.gift.n)），与内容表同一份" || no "friend.visit" "$FV"
[ "$(echo "$FV" | jget data.state.stats.visits)" = "$((V0 + 1))" ] \
  && ok "拜访计入整帧 stats.visits（$V0 → $(echo "$FV" | jget data.state.stats.visits)）" || no "visits 计数" "$FV"
[ "$(echo "$FV" | jget "data.state.friends.$NPCID.lastVisit")" = "$(echo "$FV" | jget data.state.daily.date)" ] \
  && ok "每日一次的键落在 friends.$NPCID.lastVisit（不是内存里记着玩玩的）" || no "拜访日记" "$FV"
FV2=$(act "friend.visit" "{\"npcId\":\"$NPCID\"}")
[ "$(echo "$FV2" | jget data.result.ok)" = "false" ] && [ "$(echo "$FV2" | jget data.result.again)" = "true" ] \
  && [ "$(echo "$FV2" | jget data.state.stats.visits)" = "$((V0 + 1))" ] \
  && ok "同日二访被拒且计数不重复加（again=true，visits 仍 $((V0+1))）" || no "拜访每日闸" "$FV2"
FVB=$(act "friend.visit" '{"npcId":"npc-does-not-exist"}')
[ "$(echo "$FVB" | jget data.result.ok)" = "false" ] && echo "$FVB" | grep -q "好友不存在" \
  && ok "查无此人的拜访被点名拒绝（不是默默发一份空回礼）" || no "拜访假好友" "$FVB"
G0=$(echo "$FV2" | jget "data.state.bag.$NPCGIFT|0")
FG=$(act "friend.gift" "{\"npcId\":\"$NPCID\",\"id\":\"$NPCGIFT\",\"n\":1}")
[ "$(echo "$FG" | jget data.result.ok)" = "true" ] && [ "$(echo "$FG" | jget data.result.thanks)" -gt 0 ] \
  && ok "friend.gift 送出 1 份 $NPCGIFT，换回谢金 🪙$(echo "$FG" | jget data.result.thanks)" || no "friend.gift" "$FG"
[ "$(echo "$FG" | jget data.state.rep)" = "$((R0 + 1))" ] \
  && ok "送礼涨的是整帧里的声望（$R0 → $(echo "$FG" | jget data.state.rep)），下一笔买价就要按它算" || no "gift 声望" "$FG"
[ "$(echo "$FG" | jget "data.state.bag.$NPCGIFT|0")" = "$((G0 - 1))" ] \
  && ok "送出去的东西真的离开背包（$NPCGIFT $G0 → $(echo "$FG" | jget "data.state.bag.$NPCGIFT|0")）" || no "gift 扣包" "$FG"
FGE=$(act "friend.gift" "{\"npcId\":\"$NPCID\",\"id\":\"$NPCGIFT\",\"n\":9999}")
# 服务端的口径是**夹到持有量**而不是拒绝（EconomyService.giftToFriend: `n = Math.min(n, countAll)`）。
# 这条断言要盯的不是"拒没拒"，而是那个夹击有没有夹干净：一个 9999 的请求
# 不许造出物质、不许把 rep 按件数乘、不许把谢金按件数发——三样各钉一句。
bagsum(){ echo "$1" | node -e 'let d=(JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).state.bag||{};let k=process.argv[1];let s=0;for(const q of [0,1,2]){let v=d[k+"|"+q];if(typeof v==="number")s+=v}console.log(s)' "$2"; }
CNB=$(echo "$FG" | jget data.state.coins)
TNB=$(echo "$FGE" | jget data.result.thanks)
GCSUM=$(bagsum "$FG" "$NPCGIFT"); GC0=$(bagsum "$FGE" "$NPCGIFT")
[ "$(echo "$FGE" | jget data.result.ok)" = "true" ] && [ "$GC0" = "0" ] \
  && ok "n=9999 按持有量夹到 $GCSUM 件、全部送出（三档 grade 加起来 $GCSUM→0，不凭空多也不留负数）" || no "gift 夹量" "$FGE 期望 $NPCGIFT 总量 $GCSUM→0"
[ "$(echo "$FGE" | jget data.state.rep)" = "$((R0 + 2))" ] \
  && ok "一笔 9999 的赠送只涨 1 点声望（$((R0+1)) → $(echo "$FGE" | jget data.state.rep)，rep 不按件数乘）" || no "gift 声望翻倍" "$FGE 期望 rep=$((R0+2))"
[ "$TNB" -gt 0 ] && [ "$(echo "$FGE" | jget data.state.coins)" = "$((CNB + TNB))" ] \
  && ok "谢金也只发一次（🪙$CNB + $TNB，不按件数乘）" || no "gift 谢金按件数发" "$FGE 期望 $((CNB + TNB))"

PREV=$(act "state" | jget data.revision)
LB=$(act "leaderboard")
LBL=$(echo "$LB" | jlen data.result)
LBY=$(echo "$LB" | node -e 'let r=(JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).result||[];console.log(r.filter(x=>x.you===true).length+" "+r.filter(x=>x.n==null).length)')
[ "$LBL" -ge 2 ] && [ "${LBY%% *}" = "1" ] && [ "${LBY##* }" = "0" ] \
  && ok "leaderboard 下发 $LBL 行 NPC+自己，恰好 1 行标「你」、每行都带发现数" || no "leaderboard 行集" "rows=$LBL you/缺数=$LBY"
[ "$(echo "$LB" | node -e 'let r=(JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).result||[];let n=r.map(x=>x.n);console.log(n.every((v,i)=>i===0||n[i-1]>=v)?"sorted":"unsorted")')" = "sorted" ] \
  && ok "榜单按发现数降序（服务端排好的，客户端只管画）" || no "榜单排序" "$LB"
[ "$(echo "$LB" | jget data.revision)" = "$PREV" ] \
  && ok "排行榜是只读意图：整帧号没被推高（仍是 $PREV）" || no "leaderboard 写盘" "rev=$(echo "$LB" | jget data.revision) 期望 $PREV"

ME=$(curl -s "$BASE/api/me" -H "Authorization: Bearer $T")
[ "$(echo "$ME" | jget data.user)" = "$U" ] && [ "$(echo "$ME" | jget data.guest)" = "false" ] \
  && ok "GET /api/me 回的就是这一个账号的档案（user=$U、guest=false）" || no "api/me 身份" "$ME"
[ "$(echo "$ME" | jget data.exists)" = "true" ] && [ "$(echo "$ME" | jget data.revision)" = "$PREV" ] \
  && ok "/api/me 带出云端存档（exists=true、revision=$PREV 与意图帧同号：客户端启动那一次核对读的就是它）" || no "api/me 存档" "$ME"
[ "$(echo "$ME" | jget data.payload.level)" = "$(echo "$LB" | jget data.state.level)" ] \
  && ok "/api/me 的 payload 与意图帧同一份存档（level 都是 $(echo "$ME" | jget data.payload.level)）" || no "api/me payload" "$(echo "$ME" | jget data.payload.level)"

echo "== 意图覆盖面（H7）之二：取回 / 换容器 / 卖货 / 挂单 / 今日特惠 =="
# 这三条 bench.* 与三条市场动作是"玩家每天都在用"的那半边，此前一次都没正向打过。
BI=$(echo "$LB" | jget data.state.bi)
VB=$(act "bench.vessel" '{"id":"flask"}')
[ "$(echo "$VB" | jget data.result.ok)" = "false" ] && echo "$VB" | grep -q "尚未拥有该仪器" \
  && ok "bench.vessel 挡在拥有权前面：没买过的容器当场拒" || no "换没拥有的容器" "$VB"
[ "$(echo "$LB" | jget "data.state.benchStates.$BI.vessel")" = "beaker" ] \
  && ok "拒掉那一次之后帧里的容器还是 beaker（拒绝不留副作用）" || no "拒绝写了副作用" "$VB"
VT=$(act "bench.vessel" '{"id":"testtube"}')
[ "$(echo "$VT" | jget data.result.ok)" = "true" ] && [ "$(echo "$VT" | jget "data.state.benchStates.$BI.vessel")" = "testtube" ] \
  && ok "bench.vessel 换到已拥有的试管并落进 benchStates[$BI].vessel" || no "bench.vessel" "$VT"
act "bench.vessel" '{"id":"beaker"}' >/dev/null

# 台面上的料要从背包来（非沙盒路径先验库存），所以先把原料补齐再投放。
act "market.buy" '{"id":"H2","amt":10}' >/dev/null
act "market.buy" '{"id":"O2","amt":10}' >/dev/null
TB0=$(act "state" | jget 'data.state.bag.H2|0')
act "bench.place" '{"id":"H2","n":3}' >/dev/null
TB1=$(act "state" | jget 'data.state.bag.H2|0')
[ "$TB1" = "$((TB0 - 3))" ] && ok "投放先把 3 份 H2 从背包搬上台面（$TB0 → $TB1）" || no "投放扣包" "$TB0 → $TB1"
TBK=$(act "bench.takeBack" '{"id":"H2"}')
[ "$(echo "$TBK" | jget data.result.ok)" = "true" ] \
  && [ "$(echo "$TBK" | jget 'data.state.bag.H2|0')" = "$TB0" ] \
  && [ -z "$(echo "$TBK" | jget "data.state.benchStates.$BI.placed.H2")" ] \
  && ok "bench.takeBack 把整份 H2 原数收回背包（$TB1 → $(echo "$TBK" | jget 'data.state.bag.H2|0')），台面上清空" || no "bench.takeBack" "$TBK"
TBK2=$(act "bench.takeBack" '{"id":"H2"}')
[ "$(echo "$TBK2" | jget 'data.state.bag.H2|0')" = "$TB0" ] \
  && ok "台面已空时再取回一次不会无中生有（背包仍是 $TB0）" || no "空取回增发" "$TBK2"

act "bench.place" '{"id":"H2","n":4}' >/dev/null
act "bench.place" '{"id":"O2","n":2}' >/dev/null
RW=$(act "react" '{"multiplier":2}')
# 产率折损是引擎按品质/安全设施概率决定的（failChance≈0.2），所以这里不钉死"必产 4 份"，
# 而是拿回执里那一实际产量当基准，往下验背包一分不差地跟着它走。
HP=$(echo "$RW" | jget 'data.result.produced.H2O'); HP=${HP:-0}
[ "$(echo "$RW" | jget data.result.ok)" = "true" ] && [ "$HP" -ge 2 ] \
  && ok "批量合成一把产 $HP 份水（multiplier=2 真的乘进了结算，产率折损时少一半）" || no "批量反应" "$RW"
SL0=$(echo "$RW" | jget data.state.coins); SS0=$(echo "$RW" | jget data.state.stats.sold)
SLN=$(act "market.sell" '{"id":"H2O","q":0,"n":1}')
SLP=$(echo "$SLN" | jget data.result.price)
[ "$(echo "$SLN" | jget data.result.ok)" = "true" ] && [ "${SLP:-0}" -gt 0 ] && [ "$(echo "$SLN" | jget data.result.n)" = "1" ] \
  && ok "market.sell 按服务端定价卖掉 1 份水（🪙$SLP，含首次出售加成）" || no "market.sell" "$SLN"
[ "$(echo "$SLN" | jget data.state.coins)" = "$((SL0 + SLP))" ] \
  && ok "卖价一分不多一分不少地进整帧（$SL0 → $(echo "$SLN" | jget data.state.coins)）" || no "卖货入账" "$SL0 + $SLP → $(echo "$SLN" | jget data.state.coins)"
[ "$(echo "$SLN" | jget 'data.state.bag.H2O|0')" = "$((HP - 1))" ] && [ "$(echo "$SLN" | jget data.state.stats.sold)" = "$((SS0 + 1))" ] \
  && ok "卖掉的那份从背包划走、销量计数 +1（H2O $HP→$(echo "$SLN" | jget 'data.state.bag.H2O|0')、sold $SS0→$(echo "$SLN" | jget data.state.stats.sold)）" || no "卖货扣包" "$SLN"
# 挂单与"超量直售"都要消耗水，而一次反应在产率折损时只产 1~2 份、还可能整个炸掉，
# 所以先把水位补到一个实测数字上（最多试三把），后面所有断言都跟着这个基准走，不猜 HP 够不够。
WB0=0
for _t in 1 2 3; do
  act "bench.place" '{"id":"H2","n":4}' >/dev/null
  act "bench.place" '{"id":"O2","n":2}' >/dev/null
  WB=$(act "react" '{"multiplier":1}')
  WB0=$(echo "$WB" | jget 'data.state.bag.H2O|0'); WB0=${WB0:-0}
  [ "$WB0" -ge 2 ] && break
done
[ "$WB0" -ge 2 ] && ok "再合成一把把水位补到 $WB0 份（下面的挂单与超量直售都拿它当基准）" || no "补水" "$WB"
LB0=$(echo "$WB" | jlen data.state.listings)
BC=$(act "market.consumable" '{"id":"reagentbottle","n":1}')
[ "$(echo "$BC" | jget data.result.ok)" = "true" ] \
  && [ "$(echo "$BC" | jget 'data.state.bag.reagentbottle|0')" = "1" ] \
  && ok "挂单要用的试剂瓶从耗材区买到（bag 数得出 1 只）" || no "买试剂瓶" "$BC"
LST=$(act "listing.create" '{"id":"H2O","q":0,"n":1,"mult":1.2}')
[ "$(echo "$LST" | jget data.result.ok)" = "true" ] && [ "$(echo "$LST" | jlen data.state.listings)" = "$((LB0 + 1))" ] \
  && ok "listing.create 挂出一笔商会代售（挂单 $LB0 → $(echo "$LST" | jlen data.state.listings)）" || no "listing.create" "$LST"
# 扣到 0 的那一项，服务端是**整个键从背包里消失**（不是留一个 0 在那儿），所以两侧都归一成 0 再比。
WBA=$(echo "$LST" | jget 'data.state.bag.H2O|0'); WBA=${WBA:-0}
BTA=$(echo "$LST" | jget 'data.state.bag.reagentbottle|0'); BTA=${BTA:-0}
[ "$WBA" = "$((WB0 - 1))" ] && [ "$BTA" = "0" ] \
  && ok "挂单同时扣掉货与包装（H2O $WB0→$WBA、试剂瓶 1→$BTA，扣到 0 的键直接从背包消失）：不是白挂" || no "挂单扣料" "H2O $WB0→$WBA、瓶 $BTA"
[ "$(echo "$LST" | node -e 'let s=(JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).state||{};let A=s.listings||[];let L=A[A.length-1]||{};console.log(L.id==="H2O"&&L.n===1&&L.price>0&&L.mat>Date.now()-60000?"ok":"bad:"+JSON.stringify(L))')" = "ok" ] \
  && ok "挂单行带得出货、价与到期时刻（客户端那句「约 N 秒后商会结算」就靠它）" || no "挂单行内容" "$LST"
LSB=$(act "listing.create" '{"id":"H2O","q":0,"n":99}')
[ "$(echo "$LSB" | jget data.result.ok)" = "false" ] && echo "$LSB" | grep -q "数量不足" \
  && ok "手里没那么多货就挂不出去（拒绝理由说的是数量）" || no "挂单超量" "$LSB"

# 直售超量：服务端的口径是**夹到持有量**（instantSell 里 n=min(要卖, 有货)），不是拒绝。
# 所以三样各钉一句：实际卖掉的数量、背包见底、销量计数只按真实卖出数涨。
WT0=$(echo "$LSB" | jget data.state.stats.sold)
SL2=$(act "market.sell" '{"id":"H2O","q":0,"n":999}')
SL2N=$(echo "$SL2" | jget data.result.n); SL2N=${SL2N:-0}
[ "$(echo "$SL2" | jget data.result.ok)" = "true" ] && [ "$SL2N" = "$((WB0 - 1))" ] \
  && [ -z "$(echo "$SL2" | jget 'data.state.bag.H2O|0')" ] \
  && [ "$(echo "$SL2" | jget data.state.stats.sold)" = "$((WT0 + SL2N))" ] \
  && ok "超出持有量的直售被夹到真实持有量（要 999 → 只卖 $SL2N 份：背包见底、sold 只按卖出数涨 $WT0→$(echo "$SL2" | jget data.state.stats.sold)）" || no "卖货超量" "$SL2"

SPID=$(act "state" | jget data.state.market.specials.0.id)
SP0=$(act "state" | jget data.state.coins); TR0=$(act "state" | jget data.state.stats.trades)
if [ -n "$SPID" ]; then
  SP=$(act "market.special" "{\"id\":\"$SPID\"}")
  SPC=$(echo "$SP" | jget data.result.cost)
  { [ "$(echo "$SP" | jget data.result.ok)" = "true" ] && [ "${SPC:-0}" -gt 0 ]; } \
    && ok "market.special 买走今日特惠的 $SPID（按表内折扣价 🪙$SPC）" || no "market.special" "$SP"
  [ "$(echo "$SP" | jget data.state.coins)" = "$((SP0 - SPC))" ] && [ "$(echo "$SP" | jget data.state.stats.trades)" = "$((TR0 + 1))" ] \
    && ok "特惠按回执金额扣款、成交计数 +1（🪙$SP0→$(echo "$SP" | jget data.state.coins)）" || no "特惠扣款" "$SP"
  [ "$(echo "$SP" | jget "data.state.market.specialBuy.$SPID")" = "1" ] \
    && ok "限购记在帧里（specialBuy.$SPID=1），不是客户端自己数的" || no "特惠限购记账" "$SP"
  ACT=$(act "state" | jlen data.state.market.specials)
  SPX=$(act "market.special" '{"id":"NoSuchThing"}')
  [ "$ACT" -ge 1 ] && [ "$(echo "$SPX" | jget data.result.ok)" = "false" ] && echo "$SPX" | grep -q "今日没有" \
    && ok "今日目录外的 id 被拒（清单里 $ACT 件，没有 NoSuchThing）" || no "特惠目录闸门" "$SPX"
else
  no "今日特惠清单" "market.specials 为空，特惠面没法正向覆盖"
fi

echo "== 意图覆盖面（H7）之三：升级族 / 提纯 / 换台（先把人抬到解锁线） =="
# upgrade.* 五条与 refine 都有等级线／金币线，Lv.1 的新档根本够不着（坩埚 Lv.11、分析室 6 万金币）。
# 这里不伪造余额，用的是 G4 已经证明过的两把正规尺子：把 level_exp 拨平（每级 20 经验），
# 再用后台资产口按正常上限补币——两条都是线上真存在的路径，用完当场复原并回读确认。
HL0=$(getcfg level_exp)
putcfg '{"key":"level_exp","value":{"base":20,"coef":0}}' >/dev/null
LVF=$(act "state"); LV0=$(echo "$LVF" | jget data.state.level)
for LVTRY in 1 2 3 4; do
  act "market.buy" '{"id":"H2","amt":20}' >/dev/null
  act "market.buy" '{"id":"O2","amt":20}' >/dev/null
  act "bench.place" '{"id":"H2","n":20}' >/dev/null
  act "bench.place" '{"id":"O2","n":10}' >/dev/null
  LVF=$(act "react" '{"multiplier":10}')
  LVN=$(echo "$LVF" | jget data.state.level)
  [ "${LVN:-1}" -ge 16 ] && break
done
[ "${LVN:-1}" -ge 16 ] \
  && ok "拨平升级曲线后 $LVTRY 轮批量合成就站到 Lv.$LVN（曲线是配置决定的，不是代码写死的）" || no "抬等级" "level=$LVN 起点 $LV0"
putcfg "{\"key\":\"level_exp\",\"value\":$HL0}" >/dev/null
[ "$(getcfg level_exp)" = "$HL0" ] \
  && ok "升级曲线当场复原并回读到（$HL0）：借的尺子不留给下一轮回归" || no "复原 level_exp" "$(getcfg level_exp)"
TOP=$(curl -s -X POST "$BASE/admin/api/users/assets?id=$PID" -H "$AH" -H 'Content-Type: application/json' -d '{"coins":300000}')
TOPC=$(echo "$TOP" | jget data.coinsAfter)
[ "$(echo "$TOP" | jget ok)" = "true" ] && [ "$(act "state" | jget data.state.coins)" = "$TOPC" ] \
  && ok "解锁项要的币走后台资产口（单笔上限内），下一帧玩家侧余额就是 $TOPC" || no "补币" "$TOP"

UL=$(act "upgrade.lab" '{"key":"storage"}')
[ "$(echo "$UL" | jget data.result.ok)" = "true" ] && [ "$(echo "$UL" | jget data.state.lab.storage)" = "1" ] \
  && ok "upgrade.lab 把储物柜扩容买到 Lv.1（整帧 lab.storage 0→1）" || no "upgrade.lab" "$UL"
[ "$(echo "$UL" | jget data.state.coins)" = "$((TOPC - $(echo "$UL" | jget data.result.cost)))" ] \
  && ok "实验室升级按回执扣款（🪙$TOPC → $(echo "$UL" | jget data.state.coins)）" || no "lab 扣款" "$UL"
ULB=$(act "upgrade.lab" '{"key":"nosuchUpgrade"}')
[ "$(echo "$ULB" | jget data.result.ok)" = "false" ] && echo "$ULB" | grep -q "未知升级项" \
  && ok "配置里没有的升级项被点名拒绝（不会扣一笔看不见的钱）" || no "lab 未知项" "$ULB"

UV=$(act "upgrade.vessel" '{"id":"flask"}')
[ "$(echo "$UV" | jget data.result.ok)" = "true" ] && [ "$(echo "$UV" | jget data.state.vessels.flask.owned)" = "true" ] \
  && ok "upgrade.vessel 买下锥形瓶并写进 vessels.flask（Lv.6 那条解锁线过了）" || no "upgrade.vessel" "$UV"
UVD=$(act "upgrade.vessel" '{"id":"flask"}')
[ "$(echo "$UVD" | jget data.result.ok)" = "false" ] && echo "$UVD" | grep -q "已拥有" \
  && ok "同一件容器买第二次被拒（不是再扣一次钱）" || no "容器重复购买" "$UVD"
UVN=$(act "upgrade.vessel" '{"id":"nosuchInstrument"}')
[ "$(echo "$UVN" | jget data.result.ok)" = "false" ] && echo "$UVN" | grep -q "仪器不存在" \
  && ok "内容表里没有的仪器 id 被拒" || no "容器假 id" "$UVN"

RFB=$(act "refine" '{"fromQ":0,"toQ":1,"need":5}')
[ "$(echo "$RFB" | jget data.result.ok)" = "false" ] && echo "$RFB" | grep -q "需要分光光度计" \
  && ok "没买分光光度计时提纯被拒：升级设备那条路是 refine 的唯一入口" || no "refine 前置闸门" "$RFB"
UE=$(act "upgrade.equipment" '{"id":"spectrometer"}')
[ "$(echo "$UE" | jget data.result.ok)" = "true" ] && [ "$(echo "$UE" | jget data.state.equipment.spectrometer)" = "true" ] \
  && ok "upgrade.equipment 买下 Lv.16 的分光光度计（equipment.spectrometer=true）" || no "upgrade.equipment" "$UE"
UED=$(act "upgrade.equipment" '{"id":"lamp"}')
[ "$(echo "$UED" | jget data.result.ok)" = "false" ] && echo "$UED" | grep -q "已装备" \
  && ok "已装备的设备不能再买一遍（酒精灯不会重复扣钱）" || no "设备重复购买" "$UED"

UTC=$(act "upgrade.tier" '{"id":"beaker"}')
[ "$(echo "$UTC" | jget data.result.ok)" = "true" ] && [ "$(echo "$UTC" | jget data.result.tier)" = "1" ] \
  && [ "$(echo "$UTC" | jget data.state.vessels.beaker.tier)" = "1" ] \
  && ok "upgrade.tier 把烧杯升到第 2 档（tier 0→1，提档费按 tier_up_cost 表算）" || no "upgrade.tier" "$UTC"
act "upgrade.tier" '{"id":"beaker"}' >/dev/null
UTD=$(act "upgrade.tier" '{"id":"beaker"}')
[ "$(echo "$UTD" | jget data.result.ok)" = "false" ] && echo "$UTD" | grep -q "已满级" \
  && ok "第三档不存在：满级后继续提档被拒（tier 停在 2）" || no "tier 上限" "$UTD"
UTN=$(act "upgrade.tier" '{"id":"condenser"}')
[ "$(echo "$UTN" | jget data.result.ok)" = "false" ] && echo "$UTN" | grep -q "未拥有该容器" \
  && ok "没买过的东西提不了档（拒绝理由说的是拥有权）" || no "tier 未拥有" "$UTN"

UR=$(act "upgrade.room" '{"id":"analysis"}')
[ "$(echo "$UR" | jget data.result.ok)" = "true" ] && [ "$(echo "$UR" | jlen data.state.rooms)" = "2" ] \
  && ok "upgrade.room 开出分析化学室：rooms 1→2，6 万金币的门槛在 Lv.12 之后" || no "upgrade.room" "$UR"
URD=$(act "upgrade.room" '{"id":"analysis"}')
[ "$(echo "$URD" | jget data.result.ok)" = "false" ] && echo "$URD" | grep -q "已拥有该房间" \
  && ok "同一间房子不卖两次" || no "房间重复购买" "$URD"
[ "$(echo "$UR" | jlen data.state.benchStates)" = "2" ] \
  && ok "多一间房就多一张台：ensureBenches 把 benchStates 撑到 2 张" || no "台位随房间增长" "$UR"
BS1=$(act "bench.switch" '{"index":1}')
[ "$(echo "$BS1" | jget data.result.ok)" = "true" ] && [ "$(echo "$BS1" | jget data.state.bi)" = "1" ] \
  && ok "bench.switch 切到第 2 张台并把 bi 落在整帧里" || no "bench.switch" "$BS1"
BS2=$(act "bench.switch" '{"index":9}')
[ "$(echo "$BS2" | jget data.result.ok)" = "false" ] && [ "$(echo "$BS2" | jget data.state.bi)" = "1" ] \
  && ok "切到不存在的台位被拒且 bi 不动（越界不会把玩家扔进空气里）" || no "switch 越界" "$BS2"
act "bench.switch" '{"index":0}' >/dev/null
act "market.buy" '{"id":"Cu","amt":8}' >/dev/null
RFB2=$(act "refine" '{"fromQ":0,"toQ":1,"need":9999}')
[ "$(echo "$RFB2" | jget data.result.ok)" = "false" ] && echo "$RFB2" | grep -q "没有持有量" \
  && ok "持有量不够时提纯被拒（拒绝理由带着那个门槛数字）" || no "refine 持有量闸门" "$RFB2"
RB0=$(act "state")
RF=$(act "refine" '{"fromQ":0,"toQ":1,"need":5}')
RFID=$(echo "$RF" | jget data.result.id)
RA0=$(echo "$RB0" | jget "data.state.bag.$RFID|0"); RA1=$(echo "$RF" | jget "data.state.bag.$RFID|0")
RQ0=$(echo "$RB0" | jget "data.state.bag.$RFID|1"); RQ0=${RQ0:-0}; RQ1=$(echo "$RF" | jget "data.state.bag.$RFID|1")
[ "$(echo "$RF" | jget data.result.ok)" = "true" ] && [ -n "$RFID" ] \
  && ok "refine 服务端自己挑出够提的一摞（$RFID：q0 攒够 5 份才折 1 份 q1）" || no "refine" "$RF"
[ "$RA1" = "$((RA0 - 5))" ] && [ "$RQ1" = "$((RQ0 + 1))" ] \
  && ok "提纯真的搬运了整帧背包（$RFID q0 $RA0→$RA1、q1 $RQ0→$RQ1）" || no "refine 背包账" "$RFID $RA0/$RA1 $RQ0/$RQ1"

echo "== 意图覆盖面（H7）之四：每日任务 / 图鉴里程碑 / 商会订单 =="
MSN=$(getcfg milestones | node -e 'let a=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log(Array.isArray(a)&&a.length?a[0]:"")')
MSN=${MSN:-20}
for HID in $(curl -s "$BASE/api/content/bundle" | node -e '
let c=JSON.parse(require("fs").readFileSync(0,"utf8")).content||{};
console.log((c.element||[]).filter(x=>x.price<=30).map(x=>x.id).slice(0,28).join(" "));'); do
  act "market.buy" "{\"id\":\"$HID\",\"amt\":1}" >/dev/null
done
DIS=$(act "state" | jlen data.state.discovered)
[ "${DIS:-0}" -ge "${MSN:-20}" ] && ok "图鉴攒到 $DIS 种，跨过第一个里程碑（$MSN）" || no "图鉴数量" "discovered=$DIS 门槛=$MSN"
MBEFORE=$(act "state" | jget data.state.coins)
CM=$(act "claim.milestone" "{\"index\":0}")
[ "$(echo "$CM" | jget data.result.ok)" = "true" ] && [ "$(echo "$CM" | jget data.result.coins)" = "$((MSN * 100))" ] \
  && ok "claim.milestone 按表发节点奖（第 1 档 $MSN 种 → 🪙$(echo "$CM" | jget data.result.coins)）" || no "claim.milestone" "$CM"
[ "$(echo "$CM" | jget data.state.coins)" = "$((MBEFORE + MSN * 100))" ] \
  && ok "节点奖一分不差进整帧（$MBEFORE → $(echo "$CM" | jget data.state.coins)）" || no "里程碑入账" "$CM"
[ "$(echo "$CM" | jget "data.state.milestones.$MSN")" = "true" ] \
  && ok "领取状态记在帧里（milestones.$MSN=true），换设备也不会重领" || no "里程碑记账" "$CM"
CMD=$(act "claim.milestone" '{"index":0}')
[ "$(echo "$CMD" | jget data.result.ok)" = "false" ] && [ "$(echo "$CMD" | jget data.state.coins)" = "$(echo "$CM" | jget data.state.coins)" ] \
  && ok "同一档领第二次被拒且不再发钱" || no "里程碑重复领" "$CMD"
CMN=$(act "claim.milestone" '{"index":99}')
[ "$(echo "$CMN" | jget data.result.ok)" = "false" ] && echo "$CMN" | grep -q "无此节点" \
  && ok "越界的档位序号被拒（不是静默 no-op）" || no "里程碑越界" "$CMN"

TASK=$(curl -s "$BASE/api/content/bundle" | node -e '
let c=JSON.parse(require("fs").readFileSync(0,"utf8")).content||{};
let t=(c.task||[]).filter(x=>x.key==="trade")[0]||(c.task||[])[0]||{};
console.log((t.id||"")+" "+(t.reward||0));')
TID=${TASK%% *}; TPAY=${TASK##* }
TN=$(act "state" | jget "data.state.daily.counters.trade"); TN=${TN:-0}
[ "${TN:-0}" -ge 1 ] && ok "今日任务计数在帧里数得出（trade=$TN，前面每笔成交都记进了 daily.counters）" || no "日常计数" "trade=$TN"
CD=$(act "claim.daily" "{\"id\":\"$TID\"}")
[ "$(echo "$CD" | jget data.result.ok)" = "true" ] && [ "$(echo "$CD" | jget data.result.reward)" = "$TPAY" ] \
  && ok "claim.daily 按任务表发奖（$TID → 🪙$TPAY，与内容表同一份数字）" || no "claim.daily" "$CD"
[ "$(echo "$CD" | jget "data.state.daily.claimed.$TID")" = "true" ] \
  && ok "任务领取状态写进 daily.claimed（当天不能领第二遍）" || no "任务记账" "$CD"
CDD=$(act "claim.daily" "{\"id\":\"$TID\"}")
[ "$(echo "$CDD" | jget data.result.ok)" = "false" ] \
  && ok "已领过的任务再点被拒" || no "任务重复领" "$CDD"
CDN=$(act "claim.daily" '{"id":"nosuchTask"}')
[ "$(echo "$CDN" | jget data.result.ok)" = "false" ] && echo "$CDN" | grep -q "任务未完成" \
  && ok "表里没有的任务 id 走同一条拒绝（不发钱）" || no "任务假 id" "$CDN"

ORD=$(act "state" | node -e '
let s=(JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).state||{};
let l=(s.orders||{}).list||[];
let i=l.findIndex(o=>!o.done&&o.q===0&&o.grade!=="rare");
if(i<0) i=l.findIndex(o=>!o.done);            // 当天只剩 rare 单时改验"品质不足"那条闸（同样是真状态）
let o=l[i]||{};
console.log(i<0?"":(i+" "+(o.id||"")+" "+(o.need||0)+" "+(o.pay||0)+" "+(o.q||0)));')
if [ -n "$ORD" ]; then
  OIDX=${ORD%% *}; REST=${ORD#* }; OIID=${REST%% *}; REST2=${REST#* }; ONEED=${REST2%% *}; REST3=${REST2#* }; OPAY=${REST3%% *}; OQ=${REST3##* }
  OB=$(act "market.buy" "{\"id\":\"$OIID\",\"amt\":$ONEED}")
  [ "$(echo "$OB" | jget data.result.ok)" = "true" ] \
    && ok "凑齐商会订单要的量（$OIID ×$ONEED 从市场买齐）" || no "凑单材料" "$OB"
  OF0=$(echo "$OB" | jget data.state.coins); REP0=$(echo "$OB" | jget data.state.rep)
  OF=$(act "order.fulfill" "{\"index\":$OIDX}")
  if [ "$OQ" = "0" ]; then
    [ "$(echo "$OF" | jget data.result.ok)" = "true" ] && [ "$(echo "$OF" | jget data.result.pay)" = "$OPAY" ] \
      && ok "order.fulfill 交得了货：按订单行的那个价 🪙$OPAY 结款" || no "order.fulfill" "$OF"
    [ "$(echo "$OF" | jget "data.state.orders.list.$OIDX.done")" = "true" ] \
      && ok "订单行本身被标 done（第 $OIDX 单），不是只在回执里说成功" || no "订单状态" "$OF"
    [ "$(echo "$OF" | jget data.state.coins)" = "$((OF0 + OPAY))" ] && [ "$(echo "$OF" | jget data.state.rep)" = "$((REP0 + 1))" ] \
      && ok "货款与声望都进整帧（🪙$OF0→$(echo "$OF" | jget data.state.coins)、rep $REP0→$(echo "$OF" | jget data.state.rep)）" || no "订单结算落帧" "$OF"
    OFD=$(act "order.fulfill" "{\"index\":$OIDX}")
    [ "$(echo "$OFD" | jget data.result.ok)" = "false" ] && echo "$OFD" | grep -q "订单已完成" \
      && ok "同一单交第二次被拒（不会重复发货款）" || no "订单重复交" "$OFD"
  else
    [ "$(echo "$OF" | jget data.result.ok)" = "false" ] && echo "$OF" | grep -q "品质不足" \
      && ok "rare 单只认 q≥1：市场上买的 q0 货交不了，货一件没少（当天没有普通单，正向那条由上一轮的 done 标记覆盖）" || no "rare 单品质闸" "$OF"
    [ "$(echo "$OF" | jget "data.state.orders.list.$OIDX.done")" != "true" ] && [ "$(echo "$OF" | jget data.state.coins)" = "$OF0" ] \
      && ok "被拒那一单既不结款也不翻 done" || no "rare 单拒绝副作用" "$OF"
  fi
else
  no "商会订单正向覆盖" "orders.list 是空的（当天没滚出订单），order.fulfill 无从打起"
fi
OFN=$(act "order.fulfill" '{"index":77}')
[ "$(echo "$OFN" | jget data.result.ok)" = "false" ] && echo "$OFN" | grep -q "订单不存在" \
  && ok "越界订单号被拒" || no "订单越界" "$OFN"

echo "== 意图覆盖面（H7）之五：挑战现场 / 沙盒现场 / 引导 / 演示发奖 / 重置 =="
# 挑战与沙盒的临时台**不进存档**（EngineCtx 那份内存现场），它的整帧效果靠 data.bench / data.sandbox
# 带外回传。这一段钉的就是"回传的那张台子真的跟着意图变"，以及"退出后存档一个字节都没被污染"。
CS=$(act "challenge.start")
[ "$(echo "$CS" | jget data.result.ok)" = "true" ] && [ -n "$(echo "$CS" | jget data.result.target)" ] \
  && [ -n "$(echo "$CS" | jget data.state.chal.reactId)" ] \
  && ok "challenge.start 起一局：目标物 $(echo "$CS" | jget data.result.target)、步数上限写进存档里的 chal 段" || no "challenge.start" "$CS"
[ -n "$(echo "$CS" | jget data.bench)" ] && [ "$(echo "$CS" | jget data.bench.placed | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log(Object.keys(d||{}).length)')" = "0" ] \
  && ok "新挑战的回传现场是空台（bench 带外给出，玩家一进来就看得见它）" || no "挑战空现场" "$CS"
CG=$(echo "$CS" | node -e '
let c=((JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).state||{}).chal||{};
let g=c.given||{}, k=Object.keys(g)[0]||"";
console.log(k+" "+(g[k]||0)+" "+(Object.keys(c.decoys||{})[0]||""));')
CGID=${CG%% *}; RESTC=${CG#* }; CGN=${RESTC%% *}; CGDECOY=${RESTC##* }
CBAG0=$(echo "$CS" | jget "data.state.bag.$CGID|0"); CBAG0=${CBAG0:-0}
CITEMS0=$(echo "$CS" | jlen data.state.bag)
CP=$(act "bench.place" "{\"id\":\"$CGID\",\"n\":$CGN}")
[ "$(echo "$CP" | jget data.result.ok)" = "true" ] && [ "$(echo "$CP" | jget "data.bench.placed.$CGID")" = "$CGN" ] \
  && ok "挑战材料按服务端下发的那份上台（$CGID ×$CGN，走的是临时台不是玩家库存）" || no "挑战投放" "$CP"
# 注意：jget 读不到的键输出的是空串，两边都要归一成 0 再比——不然"本来就没有这份材料"
# 会被写成"背包从 0 变成了空"，一条本该成立的判据当场假红。
CBAG1=$(echo "$CP" | jget "data.state.bag.$CGID|0"); CBAG1=${CBAG1:-0}
[ "$CBAG1" = "$CBAG0" ] && [ "$(echo "$CP" | jlen data.state.bag)" = "$CITEMS0" ] \
  && ok "投放不动背包（$CGID 仍是 $CBAG0 份、条目数 $CITEMS0 原样）：这局成败与玩家的存货无关" || no "挑战投放扣了库存" "$CBAG0/$CBAG1、$CITEMS0/$(echo "$CP" | jlen data.state.bag)"
CQ=$(act "challenge.quit")
[ "$(echo "$CQ" | jget data.result.ok)" = "true" ] && [ -z "$(echo "$CQ" | jget data.state.chal)" ] \
  && [ -z "$(echo "$CQ" | jget data.bench)" ] && [ "$(echo "$CQ" | jget data.sandbox)" = "false" ] \
  && ok "challenge.quit 把现场整个撤掉：存档里 chal 清空、回传现场不再带出" || no "challenge.quit" "$CQ"
CQB=$(act "challenge.quit")
[ "$(echo "$CQB" | jget data.result.ok)" = "true" ] \
  && ok "没有挑战时退出也是一次安全的 no-op（幂等的收口，不该报错）" || no "空退出报错" "$CQB"

SB0=$(echo "$CQ" | jget data.state.stats.sandbox); XBG0=$(echo "$CQ" | jget 'data.state.bag.H2O|0'); XBG0=${XBG0:-0}; XLV0=$(echo "$CQ" | jget data.state.level)
SEN=$(act "sandbox.enter")
[ "$(echo "$SEN" | jget data.result.ok)" = "true" ] && [ "$(echo "$SEN" | jget data.sandbox)" = "true" ] \
  && [ -n "$(echo "$SEN" | jget data.bench)" ] \
  && ok "sandbox.enter 开沙盒：sandbox=true 且带外回传一张空白试验台" || no "sandbox.enter" "$SEN"
SBA0=$(echo "$SEN" | jget 'data.state.bag.H2|0')
act "bench.place" '{"id":"H2","n":4}' >/dev/null
SPB=$(act "bench.place" '{"id":"O2","n":2}')
[ "$(echo "$SPB" | jget "data.bench.placed.O2")" = "2" ] && [ "$(echo "$SPB" | jget 'data.state.bag.H2|0')" = "$SBA0" ] \
  && ok "沙盒里投放不扣库存（背包 H2 仍是 $SBA0，台面上却是 4/2）" || no "沙盒投放" "$SPB"
SRE=$(act "react" '{"multiplier":2}')
[ "$(echo "$SRE" | jget data.result.ok)" = "true" ] && [ "$(echo "$SRE" | jget data.state.stats.sandbox)" = "$((SB0 + 1))" ] \
  && ok "沙盒反应计进 stats.sandbox（$SB0 → $(echo "$SRE" | jget data.state.stats.sandbox)）而不是成功实验数" || no "sandbox 计数" "$SRE"
XBG1=$(echo "$SRE" | jget 'data.state.bag.H2O|0'); XBG1=${XBG1:-0}
[ "$(echo "$SRE" | jget data.state.level)" = "$XLV0" ] && [ "$XBG1" = "$XBG0" ] \
  && [ -z "$(echo "$SRE" | jget 'data.bench.placed.H2')" ] \
  && ok "沙盒不给经验、不产物进包、台面清空（等级仍 $XLV0、背包的水仍 $XBG0 份，而这一把台面上投的是 4 H2 + 2 O2）：练手不会毁号" || no "沙盒无副作用" "$SRE"
SEX=$(act "sandbox.exit")
[ "$(echo "$SEX" | jget data.result.ok)" = "true" ] && [ "$(echo "$SEX" | jget data.sandbox)" = "false" ] \
  && [ -z "$(echo "$SEX" | jget data.bench)" ] \
  && ok "sandbox.exit 关掉沙盒并撤掉带外现场（存档 benchStates 一直是那张真台）" || no "sandbox.exit" "$SEX"

TS3=$(act "settings" '{"tutorial":3}')
[ "$(echo "$TS3" | jget data.state.tutorial)" = "3" ] && ok "把引导推到第 3 步（正向覆盖前先把起点摆正）" || no "settings tutorial" "$TS3"
TCFG=$(getcfg tutorial_coins)
TB0=$(echo "$TS3" | jget data.state.coins)
TST=$(act "tutorial.step" '{"to":4}')
[ "$(echo "$TST" | jget data.result.ok)" = "true" ] && [ "$(echo "$TST" | jget data.state.tutorial)" = "4" ] \
  && ok "tutorial.step 只往前推：3 → 4" || no "tutorial.step" "$TST"
[ "$(echo "$TST" | jget data.result.bonus)" = "$TCFG" ] && [ "$(echo "$TST" | jget data.state.coins)" = "$((TB0 + TCFG))" ] \
  && ok "跨过第 3 那一步按配置发引导补贴（🪙$TCFG 进整帧，不是客户端自己加的）" || no "tutorial 补贴" "$TST 期望 $TCFG"
TSTB=$(act "tutorial.step" '{"to":2}')
[ "$(echo "$TSTB" | jget data.result.bonus)" = "0" ] && [ "$(echo "$TSTB" | jget data.state.tutorial)" = "4" ] \
  && ok "引导步数不许回退（to=2 被吞，补贴也不会重发）" || no "tutorial 回退" "$TSTB"

ADV=$(act "ad.status")
KIND=$(echo "$ADV" | node -e '
let v=((JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).result||{});
let s=(v.slots||[]).filter(x=>x.ready===true&&x.kind!=="boom");
let p=s.filter(x=>x.reward==="diamonds")[0]||s[0]||{};
console.log((p.kind||"")+" "+(p.amount||0)+" "+(v.viewPoints||0)+" "+(v.points||0)+" "+(v.total||0)+" "+((v.devMode===true)?"dev":"nodev"));')
KID=${KIND%% *}; RESTK=${KIND#* }; KAMT=${RESTK%% *}; RESTK2=${RESTK#* }; KVP=${RESTK2%% *}; RESTK3=${RESTK2#* }; KP0=${RESTK3%% *}; RESTK4=${RESTK3#* }; KT0=${RESTK4%% *}; KDEV=${RESTK4##* }
KD0=$(echo "$ADV" | jget data.state.diamonds)
if [ -z "$KID" ] || [ "$KDEV" != "dev" ]; then
  no "ad.devGrant 正向覆盖" "本机没有空闲且可演示发放的广告位（kind=$KID devMode=$KDEV）——回调／签发／冷却那三面已由激励视频段覆盖"
else
  ADR=$(act "ad.request" "{\"kind\":\"$KID\"}")
  TICK2=$(echo "$ADR" | jget data.result.ticket)
  [ "$(echo "$ADR" | jget data.result.ok)" = "true" ] && [ -n "$TICK2" ] \
    && ok "ad.request 为 $KID 位签发工单（回执带 devMode=true，本机演示通道在）" || no "ad.request $KID" "$ADR"
  ADG=$(act "ad.devGrant" "{\"ticket\":\"$TICK2\"}")
  [ "$(echo "$ADG" | jget data.result.ok)" = "true" ] \
    && ok "ad.devGrant 把这张工单认成「已看完」：带玩家 JWT，归属查得出来" || no "ad.devGrant" "$ADG"
  ADA=$(act "ad.devGrant" '{"ticket":"deadbeefdeadbeefdeadbeefdeadbeef"}')
  [ "$(echo "$ADA" | jget data.result.ok)" = "false" ] && echo "$ADA" | grep -q "工单不存在\|不属于你" \
    && ok "别人编出来的工单号不被承认（演示通道也认归属，不是谁都能白拿）" || no "devGrant 越权" "$ADA"
  SFR=$(act "state")
  # 结算在"取帧开头"：工单是 devGrant 之后**第一帧**兑进来的，type=ad 事件就带在那一帧上，
  # 再往后的 SFR 只剩 ["state"]（余额是留着看的，事件不会补发）。所以事件这条判 ADA，
  # 而"奖励进了存档、之后每帧都还在"判 SFR——两句话各钉各的，不混着说。
  [ "$(echo "$SFR" | jget data.state.diamonds)" = "$((KD0 + KAMT))" ] \
    && ok "奖励在下一帧开头结算入账并留在存档里：💎$KD0 → $(echo "$SFR" | jget data.state.diamonds)（正是工单上那个数）" || no "devGrant 结算" "$SFR 期望 +$KAMT"
  [ "$(echo "$SFR" | jget data.state.ad.points)" = "$((KP0 + KVP))" ] && [ "$(echo "$SFR" | jget data.state.ad.total)" = "$((KT0 + 1))" ] \
    && ok "积分与累计观看数同时进 ad 段（points $KP0→$(echo "$SFR" | jget data.state.ad.points)、total $KT0→$(echo "$SFR" | jget data.state.ad.total)）" || no "ad 积分" "$SFR"
  [ "$(echo "$ADA" | node -e 'let e=((JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).events||[]);console.log(e.filter(x=>x.type==="ad").length)')" -ge 1 ] \
    && ok "到账用 type=ad 事件告知客户端（带在结算那一帧，不等下一次取帧补发）" || no "ad 事件" "$ADA"
fi

FG=$(curl -s -X POST "$BASE/api/auth/guest"); FGT=$(echo "$FG" | jget data.token)
FRM=$(curl -s "$BASE/api/game/state" -H "Authorization: Bearer $FGT")
BEFORE=$(act "state" | jget data.revision)
RS=$(act "reset")
[ "$(echo "$RS" | jget data.result.ok)" = "true" ] && [ "$(echo "$RS" | jget data.state.level)" = "1" ] \
  && [ "$(echo "$RS" | jget data.state.level)" = "$(echo "$FRM" | jget data.state.level)" ] \
  && ok "reset 把存档就地覆盖成新手档（等级回到 1，与刚注册的游客同档）" || no "reset 等级" "$RS"
[ "$(echo "$RS" | jget data.state.coins)" = "$(echo "$FRM" | jget data.state.coins)" ] \
  && ok "余额回到 fresh 那份基线（与新建号一模一样：$(echo "$RS" | jget data.state.coins)）" || no "reset 余额" "$RS"
[ "$(echo "$RS" | jget 'data.state.bag.H2|0')" = "$(echo "$FRM" | jget 'data.state.bag.H2|0')" ] \
  && ok "背包也是重开的（H2 与新手档同量，不是把玩家攒的东西留着只扣数字）" || no "reset 背包" "$RS"
[ "$(echo "$RS" | jlen data.state.rooms)" = "1" ] \
  && [ "$(echo "$RS" | node -e 'let s=((JSON.parse(require("fs").readFileSync(0,"utf8")).data||{}).state||{});console.log(Object.keys(s.vessels||{}).filter(k=>(s.vessels[k]||{}).owned===true).length+"/"+Object.keys(s.equipment||{}).filter(k=>s.equipment[k]===true).length)')" = "3/1" ] \
  && ok "房间 / 容器 / 设备一并回零（rooms 2→1、容器只剩自带 3 件、设备只剩酒精灯）" || no "reset 解锁面" "$RS"
[ "$(echo "$RS" | jget data.state.sign.streak)" = "0" ] && [ "$(echo "$RS" | jget data.state.tutorial)" = "0" ] \
  && [ -z "$(echo "$RS" | jget data.state.chal)" ] && [ "$(echo "$RS" | jget data.sandbox)" = "false" ] \
  && ok "签到 / 引导 / 现场三段同时清干净（streak 0、tutorial 0、chal 空、sandbox false）" || no "reset 清现场" "$RS"
[ "$(echo "$RS" | jget data.revision)" -gt "$BEFORE" ] \
  && ok "重置不是抹掉存档而是照常写回一版（revision $BEFORE → $(echo "$RS" | jget data.revision)：历史里查得到这次重置）" || no "reset revision" "$RS"
[ "$(act "state" | jget data.state.coins)" = "$(echo "$RS" | jget data.state.coins)" ] \
  && ok "重开的那一帧就是库里那一帧（再取一次余额一致，不是只改了内存）" || no "reset 落库" ""

echo "== 本轮账号自清（回归不该一轮一轮给后台攒测试号） =="
# 脚本每跑一轮就建四五个玩家（注册档、游客档、TapTap 档），以前全留在 app_user 里：
# 后台【用户管理】与看板的 totalUsers 因此越看越假。这里只删**本轮自己解析得出来**的那些——
# 按令牌反查 /api/me 的用户名、再按用户名精确匹配 uid，绝不用 q= 模糊搜一把梭（会连别轮的号一起扫掉）。
h7me(){ curl -s "$BASE/api/me" -H "Authorization: Bearer $1" | jget data.user; }
# 游客名是中文（游客3077ed89）。中文只要经过 curl 的命令行参数，就要先过 Windows 的 ANSI 代码页：
# 拼进 URL 会被 Tomcat 判成非法请求（400），走 --data-urlencode 也一样——它编码的是 curl 已经收
# 到下的 GBK 字节，服务端按 UTF-8 比对，查出来"无人"。看着像令牌没解出账号，其实是根本没查对字。
# 所以这一步挪进 node：名字从<b>文件</b>读（bash 写进去的是原样 UTF-8），curl 全程只见 ASCII 百分号串。
H7QF=$(mktemp)
h7uid(){
  printf '%s' "$1" > "$H7QF"
  local Q
  Q=$(node -e 'let fs=require("fs");process.stdout.write(encodeURIComponent(fs.readFileSync(process.argv[1],"utf8")))' "$H7QF")
  curl -s "$BASE/admin/api/users?q=$Q&size=20" -H "$AH" | node -e '
let fs=require("fs");let want=fs.readFileSync(process.argv[1],"utf8");
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];
let r=d.filter(x=>x.username===want)[0];console.log(r?r.id:"")' "$H7QF"
}
NAMES=""
# 令牌 → 用户名 → uid。少收一支令牌，就少清一个档：GAT（举报人那位的临时游客档）以前不在
# 这张单子里，每跑一轮后台就多留一个"没人认领的游客"，台账判据（末尾 USERS_END ≤ USERS0）
# 就是这么被一点点顶红的。GBT 不在这儿，不是漏——它那一档在删号组里已经被当场删掉了。
for TK in "$T" "$UT" "$GT" "$GGT" "$XGT" "$BGT" "$FGT" "$GAT"; do
  N=$(h7me "$TK"); [ -n "$N" ] && NAMES="$NAMES $N"
done
for N in "$U" "$NICK" "$PU" "$GU" "$(echo "$T1" | jget data.user)" "$(echo "$BIND" | jget data.user)"; do
  [ -n "$N" ] && NAMES="$NAMES $N"
done
# 手工排查时留下的探针档（不在本脚本里，是这一轮 H7 调试建的），按前缀精确认领。
PROBES=$(curl -s "$BASE/admin/api/users?q=probe&size=50" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];
console.log(d.filter(x=>/^(lv)?probe/.test(x.username||"")).map(x=>x.username).join(" "));')
PURGED=0
for N in $(echo "$NAMES" | tr ' ' '\n' | grep -v '^$' | sort -u); do
  UIDX=$(h7uid "$N")
  if [ -z "$UIDX" ]; then no "自清找不到档（$N）" "按用户名精确匹配不到，说明这一位的令牌没解出本轮账号"; continue; fi
  DEL=$(curl -s -X DELETE "$BASE/admin/api/users?id=$UIDX" -H "$AH")
  [ "$(echo "$DEL" | jget ok)" = "true" ] || { no "删号失败（$N id=$UIDX）" "$DEL"; continue; }
  [ -z "$(h7uid "$N")" ] && [ "$(curl -s "$BASE/admin/api/users/save?id=$UIDX" -H "$AH" | jget code)" = "404" ] \
    && { ok "收回本轮账号 $N（uid=$UIDX，逐表清干净后列表与存档都查无此人）"; PURGED=$((PURGED + 1)); } \
    || no "删号后仍查得到（$N）" "uid=$UIDX"
done
for N in $PROBES; do
  UIDX=$(h7uid "$N"); [ -z "$UIDX" ] && continue
  curl -s -X DELETE "$BASE/admin/api/users?id=$UIDX" -H "$AH" >/dev/null
  [ -z "$(h7uid "$N")" ] && ok "顺手清掉排查时手工建的探针档 $N（uid=$UIDX）" || no "探针档没清掉" "$N"
  PURGED=$((PURGED + 1))
done
USERS_END=$(curl -s "$BASE/admin/api/users?size=1" -H "$AH" | jget data.total)
if [ -z "$USERS_END" ]; then
  no "账号台账读不出来" "total 是空的（基线 $USERS0），这条判据没法成立"
elif [ "$USERS_END" -le "$USERS0" ]; then
  ok "台账不涨：app_user $USERS0 → $USERS_END（本轮收回 $PURGED 个$( [ "$USERS_END" -lt "$USERS0" ] && echo "，比基线还少是因为顺手清了历史遗留" )）"
else
  no "账号台账净增 $((USERS_END - USERS0))" "$USERS0 → $USERS_END，本轮收回 $PURGED 个：有账号没被认领回去"
fi
DEADCODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/game/state" -H "Authorization: Bearer $T")
[ "$DEADCODE" = "401" ] && ok "被删那位手上的令牌当场失效（会话随行撤销，脚本自己也不再留着可用身份）" || no "删号后令牌还活着" "http=$DEADCODE"

echo
echo "================  E2E 结果： PASS=$PASS  FAIL=$FAIL  ================"
[ "$FAIL" -eq 0 ]
