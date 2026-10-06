#!/usr/bin/env bash
# test/ci.sh —— 一键全量回归：数据一致性 → 后台随包产物 → 后端单测 → （后端在跑的话）端到端 API → （有 APK 的话）安卓包自检。
# 用法：bash test/ci.sh [BASE_URL]     BASE 默认 http://localhost:8080
# 退出码非 0 即任一层失败，可直接挂 CI/提交前钩子。
#
# 为什么安卓那一步是"有包才跑"：apk 要 gradle + Android SDK，跑回归的机器不一定都有；
# 但**这台机器上产出过包就必须跑**——APK 是发出去改不了的产物，签名/档位/接口地址这些错，
# 商店只会回一句"不符合要求"。没有包时它打印一行说明再跳，不装作通过。
#
# 为什么后台产物也单独一层：jar 里那份 SPA 是"入库的产物"而不是"构建时生成的产物"，
# 所以它有一个独立的失真方向——改了 admin/src 忘了重出（或重出了忘了入库）。
# 这一层就是拿一次真正的 vite build 去对账随包那 21 个文件（迭代 4 的 F6）。
#
# pipefail 不是风格问题：每层都是 `cmd | grep | tail`，关掉 pipefail 时管道的退出码是最后
# 那个 tail 的——它永远成功。也就是说 mvn 编译炸了、e2e 断言红了，这个脚本照样打"全部通过"，
# 挂到 CI 上就是一台只会亮绿灯的机器。带上 pipefail，下面那句 `|| FAIL=1` 才真的接到失败。
set -uo pipefail
cd "$(dirname "$0")/.."
BASE="${1:-http://localhost:8080}"
# `-o`（离线）是这台开发机的便利：本地 .m2 早就满了，离线能省掉每次几分钟的解析。
# CI 上冷缓存跑 `-o` 只会在第一个依赖上失败，所以留个口子：CI 传 MVN_FLAGS= 走在线解析。
MVN_FLAGS="${MVN_FLAGS:--o}"
# ADMIN_CHECK=skip 给"只改了后端"的本地快跑用：那一次 vite build 要十几秒，而产物没动过的话它对不出新信息
ADMIN_CHECK="${ADMIN_CHECK:-run}"
FAIL=0

echo "### 1/5 前端数据一致性 (test/validate.js)"
node test/validate.js || FAIL=1

echo
echo "### 2/5 后台随包产物与源码同源 (test/admin-dist-check.sh)"
if [ "$ADMIN_CHECK" = "skip" ]; then
  echo "  （跳过：ADMIN_CHECK=skip。改了 admin/src 必须重出并入库，别把这条带走。）"
else
  bash test/admin-dist-check.sh || FAIL=1
fi

echo
echo "### 3/5 后端单元测试 (mvn $MVN_FLAGS test)"
(cd server && mvn $MVN_FLAGS test | grep -E "Tests run:.*Failures|BUILD" | tail -3) || FAIL=1

echo
echo "### 4/5 端到端 API (test/e2e-api.sh)"
if curl -s -o /dev/null -m 3 "$BASE/api/healthz"; then
  # 屏上只给结论，全文落到 server/logs/e2e-latest.log（*.log 已被 gitignore）。
  # 以前这里是 `e2e | tail -8`：一次红跑只留下最后八行，谁也说不清是哪条断言红的，
  # 而端到端最难查的恰好是"偶发的那一条"——没有全文就只能重跑碰运气。
  E2E_LOG="server/logs/e2e-latest.log"
  mkdir -p "$(dirname "$E2E_LOG")"
  bash test/e2e-api.sh "$BASE" > "$E2E_LOG" 2>&1
  E2E=$?
  grep -E "^  ✗" "$E2E_LOG" | sed 's/^/    红在 /' || echo "  （无失败行）"
  tail -2 "$E2E_LOG"
  # 退出码 2 是"本机 IP 的注册配额用尽"：限流按预期生效，不是代码坏了，但这一层确实没跑完，
  # 依然算失败——否则一次 429 会让人以为 260 条断言全绿过。
  case "$E2E" in
    0) ;;
    2) echo "  ! e2e 以 429 退出（注册/游客限流窗口已满）：等窗口重置，或重启后端清零后重跑。"; FAIL=1 ;;
    *) echo "  ! e2e 退出码 $E2E（全文见 $E2E_LOG）"; FAIL=1 ;;
  esac
else
  echo "  （跳过：$BASE 未响应。先启动后端再跑完整回归。）"
fi

echo
echo "### 5/5 安卓包自检 (test/android-check.sh)"
APK=""
for c in android/app/build/outputs/apk/release/app-release.apk \
         android/app/build/outputs/apk/release/app-release-signed.apk \
         android/app/build/outputs/apk/debug/app-debug.apk; do
  [ -f "$c" ] && APK="$c" && break
done
if [ -n "$APK" ]; then
  # 默认验 debug 包（结构/产物断言都成立；未签名的 release 包留在提审那一步单独跑）。
  # 提审前请显式执行：bash test/android-check.sh <发布包> <线上 BASE>
  bash test/android-check.sh "$APK" "$BASE" | tail -12 || FAIL=1
else
  echo "  （跳过：还没构建过 APK。cd android && ./gradlew assembleDebug）"
fi

echo
if [ "$FAIL" -eq 0 ]; then echo "===========  全部通过  ==========="; else echo "===========  存在失败，见上文  ==========="; fi
exit $FAIL
