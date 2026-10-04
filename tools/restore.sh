#!/usr/bin/env bash
# tools/restore.sh —— 从 tools/backup.sh 的快照恢复。
#
#   bash tools/restore.sh backups/chemera-20260925-153000.sql.gz
#       恢复到快照里的原库（同名表会被整张覆盖，因此执行前要输入库名二次确认）
#
#   bash tools/restore.sh backups/xxx.sql.gz --into chemera_drill
#       恢复到另一张库：做恢复演练用，平时就确认备份真的可用，而不是出事那天才发现
#
#   末尾加 --root 则用 MYSQL_ROOT_PASSWORD 连库（应用账号通常没有建库权限）。
# 口令只从 server/.env 读，脚本不含字面量。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/server/.env" ] && set -a && . "$ROOT/server/.env" && set +a

FILE=""
INTO=""
ASROOT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --into) shift; INTO="${1:?--into 后面要跟库名}" ;;
    --into=*) INTO="${1#--into=}" ;;
    --root) ASROOT=1 ;;
    -*) echo "未知参数: $1" >&2; exit 1 ;;
    *) FILE="$1" ;;
  esac
  shift
done
[ -n "$FILE" ] || { echo "用法: bash tools/restore.sh <快照.sql.gz> [--into <库名>] [--root]" >&2; exit 1; }
[ -f "$FILE" ] || { echo "快照文件不存在: $FILE" >&2; exit 1; }
gzip -t "$FILE" || { echo "快照不是完整的 gzip" >&2; exit 1; }

MYSQL="${MYSQL_BIN:-mysql}"
if ! command -v "$MYSQL" >/dev/null 2>&1; then
  for c in "/c/Program Files/MySQL/MySQL Server 9.5/bin/mysql" \
           "/c/Program Files/MySQL/MySQL Server 8.4/bin/mysql"; do
    [ -x "$c" ] && MYSQL="$c" && break
  done
fi
command -v "$MYSQL" >/dev/null 2>&1 || { echo "找不到 mysql 客户端，请设 MYSQL_BIN=<路径>" >&2; exit 1; }

if [ "$ASROOT" = "1" ]; then
  USER="root"; PW="${MYSQL_ROOT_PASSWORD:?--root 需要先在 server/.env 里给 MYSQL_ROOT_PASSWORD}"
else
  USER="${CHEMERA_DB_USER:-chem}"; PW="${CHEMERA_DB_PASSWORD:?缺少 CHEMERA_DB_PASSWORD}"
fi
HOST="${CHEMERA_DB_HOST:-localhost}"; PORT="${CHEMERA_DB_PORT:-3306}"
connect() { MYSQL_PWD="$PW" "$MYSQL" -h "$HOST" -P "$PORT" -u "$USER" --default-character-set=utf8mb4 "$@"; }

# grep -m1/head 提前收工会让上游 gzip 吃 SIGPIPE，pipefail 下要先兜住退出码
SRC_DB="$(gzip -dc "$FILE" | sed -nE 's/^CREATE DATABASE.*`([^`]+)`.*/\1/p' | head -1)" || SRC_DB=""
if [ -z "$SRC_DB" ]; then
  SRC_DB="${CHEMERA_DB_NAME:-chemera}"
  echo "快照里读不到库名，按 .env 的 $SRC_DB 处理"
fi
echo "快照: $FILE"
echo "内含库: $SRC_DB"

if [ -n "$INTO" ]; then
  case "$INTO" in
    *[!A-Za-z0-9_]*|"") echo "目标库名只允许字母/数字/下划线：$INTO" >&2; exit 1 ;;
  esac
  echo "→ 恢复进 $INTO（原库 $SRC_DB 不动）"
  # 快照里的 CREATE DATABASE / USE 都指向原库；这里整行改写，库名已在上面白名单校验过，不必再加反引号
  gzip -dc "$FILE" | sed -E "s#^CREATE DATABASE.*#CREATE DATABASE IF NOT EXISTS $INTO DEFAULT CHARACTER SET utf8mb4;#; s#^USE .*#USE $INTO;#" | connect
  echo "✓ 已恢复到 $INTO"
  exit 0
fi

echo
echo "!! 这会覆盖库 $SRC_DB 下的同名表。确认无误后输入库名继续。"
printf '输入库名: '; read -r CONFIRM
[ "$CONFIRM" = "$SRC_DB" ] || { echo "输入不匹配，已取消"; exit 1; }
gzip -dc "$FILE" | connect
echo "✓ 已恢复 $SRC_DB"
