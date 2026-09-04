#!/usr/bin/env bash
# Round 17 #2 Pg 备份脚本
# 周期(cron 建议):每天凌晨 03:00
# 保留策略:本地 7 天 + S3/OSS 长期
#
# 用法:
#   PGHOST=localhost PGUSER=agentgateway ./scripts/db/backup.sh
# 或在 docker-compose 内:
#   docker exec agent-gateway-pg /scripts/db/backup.sh
#
# 环境变量:
#   PGHOST      默认 localhost
#   PGPORT      默认 5432
#   PGUSER      默认 agentgateway
#   PGPASSWORD  默认 agentgateway(可从 ~/.pgpass 读)
#   PGDATABASE  默认 agentgateway
#   BACKUP_DIR  默认 /var/backups/agent-gateway
#   RETAIN_DAYS 默认 7

set -euo pipefail

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-agentgateway}"
PGPASSWORD="${PGPASSWORD:-agentgateway}"
PGDATABASE="${PGDATABASE:-agentgateway}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/agent-gateway}"
RETAIN_DAYS="${RETAIN_DAYS:-7}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="${BACKUP_DIR}/${PGDATABASE}_${TIMESTAMP}.sql.gz"

mkdir -p "$BACKUP_DIR"

echo "[$(date)] backing up ${PGDATABASE}@${PGHOST}:${PGPORT} to ${BACKUP_FILE}"

# pg_dump 自定义格式(-Fc)支持并行恢复 + 选择性恢复
# 自定义格式压缩更高,适合中大型数据库
pg_dump \
  -h "$PGHOST" \
  -p "$PGPORT" \
  -U "$PGUSER" \
  -d "$PGDATABASE" \
  -Fc \
  -Z 6 \
  --no-owner \
  --no-privileges \
  -f "$BACKUP_FILE.tmp" \
  && mv "$BACKUP_FILE.tmp" "$BACKUP_FILE"

# 校验备份完整性(自定义格式可被 pg_restore -l 列出)
if ! pg_restore -l "$BACKUP_FILE" > /dev/null 2>&1; then
  echo "[$(date)] ERROR: backup file $BACKUP_FILE is invalid" >&2
  exit 2
fi

BACKUP_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
echo "[$(date)] backup OK: $BACKUP_FILE ($BACKUP_SIZE)"

# 清理过期备份
find "$BACKUP_DIR" -name "${PGDATABASE}_*.sql.gz" -mtime "+${RETAIN_DAYS}" -delete
echo "[$(date)] cleaned backups older than ${RETAIN_DAYS} days"

# 可选:同步到 S3(取消注释 + 安装 awscli)
# if command -v aws >/dev/null 2>&1; then
#   aws s3 cp "$BACKUP_FILE" "s3://my-bucket/agent-gateway/$TIMESTAMP.sql.gz"
# fi
