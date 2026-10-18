#!/bin/bash
# 每日任务脚本：采集 → 选股 → 模拟调仓（顺序执行，失败告警）
#
# 用法: daily.sh [collect|select|paper|all] (默认 all)
#
# 生产 cron 示例（服务器 crontab -e，交易日）：
# 30 15 * * 1-5 docker compose -f /opt/quant-src/deploy/docker-compose.yml exec -T api /opt/quant/bin/daily.sh collect >> /opt/quant-src/data/logs/cron.log 2>&1
# 0 16 * * 1-5 docker compose -f /opt/quant-src/deploy/docker-compose.yml exec -T api /opt/quant/bin/daily.sh select >> /opt/quant-src/data/logs/cron.log 2>&1
# 10 16 * * 1-5 docker compose -f /opt/quant-src/deploy/docker-compose.yml exec -T api /opt/quant/bin/daily.sh paper >> /opt/quant-src/data/logs/cron.log 2>&1
#
# 说明：
# - 三步拆开是为了让"选股"在 16:00 产出候选清单（给早报用），"模拟调仓"在 16:10 执行。
# PaperTrader.runDay 内部会复用选股引擎再算一次（交易决策用），与 select 步结果一致，
# select_runs 会多一行记录，属预期行为。
# - 非交易日：采集无新增、paper 自动跳过（M3 已实现），属正常。
# - 简化替代：也可以只调一次 POST /api/jobs/daily（API 内置采集→选股→调仓链路），
# 见 backend/quant-api/README.md。

set -u

QUANT_HOME="${QUANT_HOME:-/opt/quant}"
DB_PATH="${DB_PATH:-/data/quant.db}"
LOG_DIR="${LOG_DIR:-/data/logs}"
VENV_PYTHON="${VENV_PYTHON:-$QUANT_HOME/venv/bin/python}"
COLLECTOR="${COLLECTOR:-$QUANT_HOME/collector/collect.py}"
ENGINE_JAR="${ENGINE_JAR:-$QUANT_HOME/cli/quant-engine-1.0.0.jar}"
PAPER_JAR="${PAPER_JAR:-$QUANT_HOME/cli/quant-paper-1.0.0.jar}"
# 可选：企业微信/钉钉群机器人 webhook，失败时推送告警（为空则只记日志）
ALERT_WEBHOOK="${ALERT_WEBHOOK:-}"

TRADE_DATE="${TRADE_DATE:-$(TZ=Asia/Shanghai date +%F)}"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/daily-$(TZ=Asia/Shanghai date +%F).log"

log() { echo "[$(TZ=Asia/Shanghai date '+%F %T')] $*" | tee -a "$LOG";}
alert() {
log "$*"
if [ -n "$ALERT_WEBHOOK"]; then
curl -s -m 10 -X POST "$ALERT_WEBHOOK" -H 'Content-Type: application/json' \
-d "{\"msgtype\":\"text\",\"text\":{\"content\":\"$*\"}}" >>"$LOG" 2>&1 || true
fi
}

# 防重入：上一次还没跑完则跳过
exec 9>"$LOG_DIR/daily.lock" || exit 1
if ! flock -n 9; then
log "上一次任务仍在运行，本次跳过"
exit 0
fi

run_step() {
local name="$1"; shift
log "== 开始 [$name] =="
if "$@" >>"$LOG" 2>&1; then
log "== [$name] 成功 =="
return 0
else
local code=$?
alert "[$name] 失败（exit=$code），日期=$TRADE_DATE，详见 $LOG"
return $code
fi
}

case "${1:-all}" in
collect)
run_step "采集" "$VENV_PYTHON" "$COLLECTOR" --db "$DB_PATH"
;;
select)
run_step "选股" java -jar "$ENGINE_JAR" --date "$TRADE_DATE" --db "$DB_PATH"
;;
paper)
run_step "模拟调仓" java -jar "$PAPER_JAR" --db "$DB_PATH" --date "$TRADE_DATE"
;;
all)
run_step "采集" "$VENV_PYTHON" "$COLLECTOR" --db "$DB_PATH" || exit 1
run_step "选股" java -jar "$ENGINE_JAR" --date "$TRADE_DATE" --db "$DB_PATH" || exit 1
run_step "模拟调仓" java -jar "$PAPER_JAR" --db "$DB_PATH" --date "$TRADE_DATE" || exit 1
log "今日任务全部完成"
;;
*)
echo "用法: $0 [collect|select|paper|all]"
exit 2
;;
esac
