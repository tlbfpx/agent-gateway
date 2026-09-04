#!/usr/bin/env bash
# Round 17 #2 Pg 恢复脚本
# ⚠️ 危险:会覆盖目标数据库
#
# 用法:
#   ./scripts/db/restore.sh /var/backups/agent-gateway/agentgateway_20260903_030000.sql.gz
#
# 环境变量同 backup.sh
# 额外:
#   CONFIRM=yes  必须设此环境变量才会执行(防误操作)

set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 <backup-file>" >&2
  echo "Set CONFIRM=yes to actually run." >&2
  exit 1
fi

if [ "${CONFIRM:-}" != "yes" ]; then
  echo "Refusing to run without CONFIRM=yes" >&2
  exit 1
fi

BACKUP_FILE="$1"
if [ ! -f "$BACKUP_FILE" ]; then
  echo "Backup file not found: $BACKUP_FILE" >&2
  exit 2
fi

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-agentgateway}"
PGPASSWORD="${PGPASSWORD:-agentgateway}"
PGDATABASE="${PGDATABASE:-agentgateway}"

echo "[$(date)] WARNING: dropping and recreating ${PGDATABASE}@${PGHOST}:${PGPORT}"
read -p "Type 'yes' to continue: " confirm
if [ "$confirm" != "yes" ]; then
  echo "Aborted"
  exit 1
fi

echo "[$(date)] dropping existing connections"
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -c "
  SELECT pg_terminate_backend(pid)
  FROM pg_stat_activity
  WHERE datname = '$PGDATABASE' AND pid <> pg_backend_pid()" >/dev/null

echo "[$(date)] dropping database $PGDATABASE"
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -c "DROP DATABASE IF EXISTS $PGDATABASE" >/dev/null
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d postgres -c "CREATE DATABASE $PGDATABASE" >/dev/null

echo "[$(date)] restoring from $BACKUP_FILE"
pg_restore \
  -h "$PGHOST" \
  -p "$PGPORT" \
  -U "$PGUSER" \
  -d "$PGDATABASE" \
  --no-owner \
  --no-privileges \
  --clean \
  --if-exists \
  "$BACKUP_FILE" 2>&1 | grep -v "^WARNING\|errors ignored on restore" || true

echo "[$(date)] restore done. Verify with:"
echo "  psql -h $PGHOST -U $PGUSER -d $PGDATABASE -c '\\dt'"
