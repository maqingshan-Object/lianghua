#!/bin/bash
# 每日备份 quant.db（SQLite 在线安全备份），保留 30 天
#
# 生产 cron 示例（每天凌晨 2 点）：
# 0 2 * * * docker compose -f /opt/quant-src/deploy/docker-compose.yml exec -T api /opt/quant/bin/backup.sh >> /opt/quant-src/data/logs/backup.log 2>&1
#
# 恢复方法：停掉 api 服务后，把备份文件拷回 /data/quant.db 即可
# docker compose -f deploy/docker-compose.yml stop api
# cp /opt/quant-src/data/backup/quant-2026-10-20.db /opt/quant-src/data/quant.db
# docker compose -f deploy/docker-compose.yml start api

set -u

DB_PATH="${DB_PATH:-/data/quant.db}"
BACKUP_DIR="${BACKUP_DIR:-/data/backup}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"

mkdir -p "$BACKUP_DIR"

if [! -f "$DB_PATH"]; then
echo "库文件不存在：$DB_PATH" >&2
exit 1
fi

TS="$(TZ=Asia/Shanghai date +%F)"
DEST="$BACKUP_DIR/quant-$TS.db"

if command -v sqlite3 >/dev/null 2>&1; then
#.backup 是 SQLite 官方在线备份命令，运行时读写不受影响
sqlite3 "$DB_PATH" ".backup '$DEST'" \
&& gzip -f "$DEST" \
&& echo "备份完成：$DEST.gz ($(du -h "$DEST.gz" | cut -f1))"
else
echo "sqlite3 不可用，跳过备份" >&2
exit 1
fi

# 清理过期备份
find "$BACKUP_DIR" -name 'quant-*.db.gz' -mtime +"$RETENTION_DAYS" -delete
echo "已清理 ${RETENTION_DAYS} 天前的备份，当前保留：$(ls "$BACKUP_DIR"/quant-*.db.gz 2>/dev/null | wc -l) 份"
