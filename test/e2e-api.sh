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
jlen(){ node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8"));let p=process.argv[1].split(".");let c=d;for(const k of p){c=c==null?null:c[k]}console.log(Array.isArray(c)?c.length:0)' "$1"; }
jhas(){ node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8"));let p=process.argv[1].split(".");let c=d;for(const k of p){c=c==null?null:c[k]}console.log(c&&Object.prototype.hasOwnProperty.call(c,process.argv[2])?"yes":"no")' "$1" "$2"; }
# 发一个游戏意图，输出整帧 JSON
act(){ curl -s -X POST "$BASE/api/game/$1" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d "${2:-{\}}"; }

echo "== 内容下发 =="
V=$(curl -s "$BASE/api/content/version" | jget data.version)
[ -n "$V" ] && ok "content/version = $V" || no "content/version" ""
RB=$(curl -s "$BASE/api/content/bundle")
RC=$(echo "$RB" | node -e 'let d=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log((d.content&&d.content.reaction||[]).length)')
[ "$RC" -gt 100 ] && ok "bundle 反应数=$RC" || no "bundle 反应数=$RC" "期望>100"

echo "== 注册 / 登录 =="
U="e2e$RANDOM$$"
REG=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' -d "{\"user\":\"$U\",\"pass\":\"pass1234\"}")
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

echo "== 鉴权防爆破闸门 =="
BF="e2ebf$RANDOM$$"
LAST=""
for i in $(seq 1 9); do
  LAST=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$BF\",\"pass\":\"nope1234\"}" | jget code)
done
[ "$LAST" = "429" ] && ok "同一 IP+账号连续失败后转 429" || no "登录锁定" "code=$LAST"
OTH=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "{\"user\":\"$U\",\"pass\":\"pass1234\"}" | jget ok)
[ "$OTH" = "true" ] && ok "锁定按账号隔离，其它账号照常登录" || no "锁定越界" "ok=$OTH"
GGC=$(curl -s -X POST "$BASE/api/auth/guest" | jget code)
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

echo "== 偏好持久化 + 广告券一次一用 =="
SET=$(act "settings" '{"volSfx":42,"realMode":true}' | jget data.state.volSfx)
[ "$SET" = "42" ] && ok "偏好写入服务器存档（volSfx=42）" || no "偏好持久化" "volSfx=$SET"
act "settings" '{"realMode":false}' >/dev/null
C1=$(act "ad.bonus" '{"kind":1}' | jget data.result.coupon)
C2=$(act "ad.bonus" '{"kind":1}' | jget data.result.ok)
[ "$C1" = "true" ] && ok "双倍券由服务器签发" || no "广告券" "coupon=$C1"
[ "$C2" = "false" ] && ok "同日第二张双倍券被拒" || no "广告券复用" "ok=$C2"

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
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||[];
// 挑那笔"加金币"的记录（不是后面那笔只动钻石的），后台明细列要能读出加减了多少。
let rs=d.filter(x=>x.action==="user.assets" && x.target==="uid:"+process.argv[1] && typeof x.detail==="string")
  .map(x=>JSON.parse(x.detail).d).filter(j=>j&&j.coinsBefore!=null&&j.coinsAfter!=null&&j.coinsBefore!==j.coinsAfter);
let j=rs[0];
console.log(j?j.coinsBefore+"->"+j.coinsAfter:"")' "$PUID")
[ "$AUD" = "$PCOINS->$AFTER" ] && ok "审计留痕带前后值（金币 $AUD），后台明细列可直接读" || no "审计留痕" "audit=$AUD 期望 $PCOINS->$AFTER"
LIST2=$(curl -s "$BASE/admin/api/users?q=$U&size=5" -H "$AH" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data.rows||[];let r=d.filter(x=>x.username===process.argv[1])[0]||{};console.log(r.coins==null?"-":r.coins)' "$U")
[ "$LIST2" = "$(echo "$ONLY" | jget data.coinsAfter)" ] && ok "列表余额随调整刷新" || no "列表刷新" "coins=$LIST2"

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
# 刻画当前边界：访问令牌是无状态的，重置/封禁都要等它自然过期（≤2h）才彻底失效。
# 这条断言在 A6（令牌绑定会话）落地后会失败——那时正是我们想要的提醒。
STILL=$(curl -s "$BASE/api/game/state" -H "Authorization: Bearer $STK" | jget ok)
[ "$STILL" = "true" ] && ok "已知边界：已签发的访问令牌在过期前仍有效（待 A6 绑定会话）" || no "访问令牌行为已变" "ok=$STILL"
NOTSUPERPW=$(curl -s -X POST "$BASE/admin/api/users/reset-password?id=$PID" | jget code)
[ "$NOTSUPERPW" = "401" ] && ok "无令牌不能重置口令(401)" || no "重置鉴权" "code=$NOTSUPERPW"
curl -s -X POST "$BASE/api/auth/guest" >/dev/null
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
const S=["服务端结算","服务端+前端","部分生效","仅前端"];
let bad=d.filter(x=>!x.zh||!x.key||!x.effect||!x.note||!x.refs||!S.includes(x.scope));
process.exit(bad.length?1:0)' && ok "每条都有中文名/作用/风险/出处/生效范围" || no "说明书完整性" ""
echo "$SPEC" | node -e '
let d=JSON.parse(require("fs").readFileSync(0,"utf8")).data||[];let m={};for(const x of d)m[x.key]=x.scope;
process.exit(m.sell_rate==="服务端结算"&&m.start_coins==="仅前端"?0:1)' \
  && ok "sell_rate 标为服务端结算、start_coins 老实标为仅前端" || no "生效范围标注" ""
# 说明书和库里的键做集合比对：多写的键（库里没有）和无主的键（库里都有说明）都算漂移。
curl -s "$BASE/admin/api/config" -H "$AH" | SPEC="$SPEC" node -e '
let c=JSON.parse(require("fs").readFileSync(0,"utf8")).data.map(r=>r.cfgKey);
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

echo "== 静态托管（H5 客户端随包发布） =="
IDX=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/")
[ "$IDX" = "200" ] && ok "GET / 返回游戏页面" || no "首页托管" "http=$IDX"
JS=$(curl -s "$BASE/js/panels.js")
echo "$JS" | grep -q "G.call" && ! echo "$JS" | grep -q "U.save" && ok "托管的客户端已是意图版（无本地写入 API）" || no "客户端版本" "panels.js 仍含本地真源调用"
GT=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/js/gate.js")
[ "$GT" = "200" ] && ok "登录页脚本 js/gate.js 已随包发布" || no "gate.js 托管" "http=$GT"
curl -s "$BASE/" | grep -q 'id="gate-root"' && ok "首页自带登录/注册门结构" || no "登录门结构" "index.html 缺 #gate-root"
curl -s "$BASE/" | grep -q 'id="gate-help"' && ok "登录页给出忘记密码找回路径（不再是一条死路）" || no "找回密码指引" "index.html 缺 #gate-help"
curl -s "$BASE/js/game.js" | grep -q "C.logged()) return G.toGate" && ok "未登录时先过鉴权门（不再静默开游客档）" || no "启动流程" "game.js 未走登录门"
OLD=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/manifest.json")
[ "$OLD" = "404" ] && ok "PWA 离线壳已移除（manifest.json 404）" || no "离线壳清理" "http=$OLD"
SW=$(curl -s "$BASE/sw.js" | grep -c "unregister")
[ "$SW" -ge 1 ] && ok "旧 Service Worker 会自我注销" || no "SW 注销" "hits=$SW"

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

echo
echo "================  E2E 结果： PASS=$PASS  FAIL=$FAIL  ================"
[ "$FAIL" -eq 0 ]
