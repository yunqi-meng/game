#!/usr/bin/env bash
# tools/backup.sh —— 整库快照备份（玩家进度只存在于 user_save / user_save_revision，
# 这是全项目唯一的"丢了就真没了"的面，所以每次备份都要验完整性）。
#
#   bash tools/backup.sh                  # 备份到 backups/，保留 14 天
#   KEEP_DAYS=30 bash tools/backup.sh     # 自定义保留期
#   bash tools/backup.sh /data/chemera    # 自定义输出目录
#
# 口令只从 server/.env 读（CHEMERA_DB_PASSWORD），脚本不含任何字面量。
# 恢复见 tools/restore.sh 与 README「备份与恢复」。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/server/.env" ] && set -a && . "$ROOT/server/.env" && set +a

OUT="${1:-$ROOT/backups}"
KEEP="${KEEP_DAYS:-14}"
DB="${CHEMERA_DB_NAME:-chemera}"
# 备份账号与业务账号分开：mysqldump 取一致性快照要 FLUSH_TABLES，不该把这个权限发给应用账号
BK_USER="${CHEMERA_BACKUP_USER:-chem_bak}"
: "${CHEMERA_BACKUP_PASSWORD:?缺少 CHEMERA_BACKUP_PASSWORD（备份专用账号，写在 server/.env）}"

# Windows 上 mysqldump 常不在 PATH，可用 MYSQLDUMP_BIN 指到安装目录
DUMP="${MYSQLDUMP_BIN:-mysqldump}"
if ! command -v "$DUMP" >/dev/null 2>&1; then
  for c in "/c/Program Files/MySQL/MySQL Server 9.5/bin/mysqldump" \
           "/c/Program Files/MySQL/MySQL Server 8.4/bin/mysqldump"; do
    [ -x "$c" ] && DUMP="$c" && break
  done
fi
command -v "$DUMP" >/dev/null 2>&1 || { echo "找不到 mysqldump，请设 MYSQLDUMP_BIN=<路径>" >&2; exit 1; }

mkdir -p "$OUT"
STAMP="$(date +%Y%m%d-%H%M%S)"
FILE="$OUT/chemera-$STAMP.sql.gz"

# --single-transaction：InnoDB 一致性快照，备份期间不锁玩法表（在线游戏不能停服备份）
#   —— 取快照时 mysqldump 会执行 FLUSH TABLES，这正是备份账号要 FLUSH_TABLES 权限、
#      而应用账号 chem 不该有的原因（9.5 已无 --locking-mode 可关这一步）
# --databases：带 CREATE DATABASE IF NOT EXISTS + USE，恢复时不必再指定库名
# --no-tablespaces + --set-gtid-purged=OFF：避开需要 PROCESS/RELOAD 的元数据查询与 GTID 噪声
# 刻意不带 --routines/--triggers：本库无存储过程（触发器有 TRIGGER 权限即可导出）
TMP="$FILE.tmp"
MYSQL_PWD="$CHEMERA_BACKUP_PASSWORD" "$DUMP" \
  -h "${CHEMERA_DB_HOST:-localhost}" -P "${CHEMERA_DB_PORT:-3306}" -u "$BK_USER" \
  --default-character-set=utf8mb4 --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF \
  --databases "$DB" > "$TMP"
gzip -9 < "$TMP" > "$FILE" && rm -f "$TMP"

# 半截 dump 比没有 dump 更危险（恢复时才发现）。gzip 完整性 + mysqldump 的收尾标记都要过。
# 这里刻意不用 grep -q：它提前退出会让上游 gzip 吃 SIGPIPE，pipefail 下反而误判成"不完整"。
gzip -t "$FILE"
TAIL="$(gzip -dc "$FILE" | tail -2)"
case "$TAIL" in
  *"Dump completed"*) ;;
  *) echo "✗ dump 不完整，已保留 $FILE 供排查" >&2; exit 1 ;;
esac

# 只删自己产出的文件，且必须先有更新的一份才允许清理
find "$OUT" -maxdepth 1 -name 'chemera-*.sql.gz' -type f -mtime +"$KEEP" -print -delete | sed 's/^/  过期清理: /'

ROWS=$(du -h "$FILE" | cut -f1)
LEFT=$(find "$OUT" -maxdepth 1 -name 'chemera-*.sql.gz' | wc -l)
echo "✓ 备份完成 $FILE ($ROWS)，当前保留 $LEFT 份（${KEEP} 天）"
