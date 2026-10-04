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
exec mvn -q spring-boot:run
