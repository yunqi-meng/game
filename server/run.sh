#!/usr/bin/env bash
# 启动后端（Linux/macOS/Git Bash）：环境变量来自 .env 或调用方，本脚本不含口令。
set -euo pipefail
cd "$(dirname "$0")"
[ -f .env ] && set -a && . ./.env && set +a
export CHEMERA_DB_HOST="${CHEMERA_DB_HOST:-localhost}"
export CHEMERA_DB_PORT="${CHEMERA_DB_PORT:-3306}"
export CHEMERA_DB_NAME="${CHEMERA_DB_NAME:-chemera}"
export CHEMERA_DB_USER="${CHEMERA_DB_USER:-chem}"
if [ -z "${CHEMERA_DB_PASSWORD:-}" ]; then
  echo "缺少 CHEMERA_DB_PASSWORD：请复制 .env.example 为 .env 并填入本地口令（.env 已被 gitignore）。" >&2
  exit 1
fi
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-mysql}"
# 本机开发默认补上 dev profile：广告与 TapTap 的自证通道由它打开（application.yml 里两条默认都是关的，
# 因为"照抄仓库配置就能启动的实例"绝不能带着发钱总闸和身份自证后门）。
# 已经写了 prod 或 dev 的都不动，所以上线用的 mysql,prod 永远不会被这里带上 dev。
case ",$SPRING_PROFILES_ACTIVE," in
  *,prod,*|*,dev,*) ;;
  *) SPRING_PROFILES_ACTIVE="$SPRING_PROFILES_ACTIVE,dev" ;;
esac
echo "SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE"
exec mvn -q spring-boot:run
