#!/usr/bin/env bash
# test/ci.sh —— 一键全量回归：数据一致性 → 后端单测 → （后端在跑的话）端到端 API。
# 用法：bash test/ci.sh [BASE_URL]     BASE 默认 http://localhost:8080
# 退出码非 0 即任一层失败，可直接挂 CI/提交前钩子。
set -u
cd "$(dirname "$0")/.."
BASE="${1:-http://localhost:8080}"
FAIL=0

echo "### 1/3 前端数据一致性 (test/validate.js)"
node test/validate.js || FAIL=1

echo
echo "### 2/3 后端单元测试 (mvn -o test)"
(cd server && mvn -o test | grep -E "Tests run:.*Failures|BUILD" | tail -3) || FAIL=1

echo
echo "### 3/3 端到端 API (test/e2e-api.sh)"
if curl -s -o /dev/null -m 3 "$BASE/api/healthz"; then
  bash test/e2e-api.sh "$BASE" | tail -6 || FAIL=1
else
  echo "  （跳过：$BASE 未响应。先启动后端再跑完整回归。）"
fi

echo
if [ "$FAIL" -eq 0 ]; then echo "===========  全部通过  ==========="; else echo "===========  存在失败，见上文  ==========="; fi
exit $FAIL
