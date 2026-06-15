#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
PORT_FILE="${PORT_FILE:-$APP_DIR/app.port}"
RUN_LOG_FILE="${RUN_LOG_FILE:-}"
LOG_TAIL_LINES="${LOG_TAIL_LINES:-3000}"
ALLOW_UNKNOWN_WARN="${ALLOW_UNKNOWN_WARN:-0}"
ALLOW_RUNTIME_ERROR="${ALLOW_RUNTIME_ERROR:-0}"
ALLOW_HIGH_RISK_LOG="${ALLOW_HIGH_RISK_LOG:-0}"

fail() {
  echo "[runtime-log] FAIL: $*" >&2
  exit 1
}

warn() {
  echo "[runtime-log] WARN: $*" >&2
}

ok() {
  echo "[runtime-log] OK: $*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

require_cmd cut
require_cmd find
require_cmd grep
require_cmd ls
require_cmd sed
require_cmd sort
require_cmd tail
require_cmd tr
require_cmd wc

[ -d "$APP_DIR" ] || fail "app dir missing: $APP_DIR"
[ -f "$PORT_FILE" ] || fail "port file missing: $PORT_FILE"

ACTIVE_PORT="$(tr -d '[:space:]' < "$PORT_FILE")"
case "$ACTIVE_PORT" in
  ''|*[!0-9]*) fail "invalid active port: $ACTIVE_PORT" ;;
esac

if [ -z "$RUN_LOG_FILE" ]; then
  RUN_LOG_FILE="$(find "$APP_DIR/logs/runs" -maxdepth 1 -type f -name "app-*-port${ACTIVE_PORT}.log" -printf '%T@ %p\n' 2>/dev/null | sort -n | tail -n 1 | cut -d' ' -f2-)"
fi

[ -n "$RUN_LOG_FILE" ] || fail "active run log not found for port $ACTIVE_PORT under $APP_DIR/logs/runs"
[ -f "$RUN_LOG_FILE" ] || fail "run log file missing: $RUN_LOG_FILE"

ok "checking active run log: $RUN_LOG_FILE"

ERROR_COUNT="$(grep -cE ' ERROR ' "$RUN_LOG_FILE" || true)"
if [ "$ERROR_COUNT" -gt 0 ]; then
  if [ "$ALLOW_RUNTIME_ERROR" = "1" ]; then
    warn "runtime ERROR lines present but allowed: count=$ERROR_COUNT"
  else
    grep -nE ' ERROR ' "$RUN_LOG_FILE" | tail -n 20 >&2 || true
    fail "runtime ERROR lines present: count=$ERROR_COUNT"
  fi
else
  ok "runtime ERROR count is 0"
fi

WARN_COUNT="$(grep -cE ' WARN ' "$RUN_LOG_FILE" || true)"
UNKNOWN_WARN_LINES="$(grep -nE ' WARN ' "$RUN_LOG_FILE" | grep -Ev 'Using MySQL .* newer than the version Flyway has been verified with|StartupBeanTiming|CglibAopProxy.*Unable to proxy|spring.jpa.open-in-view is enabled by default|external[.]thegraph[.]api-key not configured' || true)"
UNKNOWN_WARN_COUNT="$(printf '%s\n' "$UNKNOWN_WARN_LINES" | sed '/^[[:space:]]*$/d' | wc -l | tr -d '[:space:]')"
if [ "$UNKNOWN_WARN_COUNT" -gt 0 ]; then
  if [ "$ALLOW_UNKNOWN_WARN" = "1" ]; then
    warn "unknown WARN lines present but allowed: count=$UNKNOWN_WARN_COUNT total_warn=$WARN_COUNT"
  else
    printf '%s\n' "$UNKNOWN_WARN_LINES" | tail -n 40 >&2 || true
    fail "unknown runtime WARN lines present: count=$UNKNOWN_WARN_COUNT total_warn=$WARN_COUNT"
  fi
else
  ok "runtime WARN lines match known baseline: total_warn=$WARN_COUNT"
fi

HIGH_RISK_LINES="$(tail -n "$LOG_TAIL_LINES" "$RUN_LOG_FILE" | grep -nEi 'Auto-trade enabled[[:space:]]*:[[:space:]]*true|(order|okx).*(placed|submitted|filled|executed)|(modifyOco|createGrid|closeGrid|redeemEarn|subscribeEarn|forceClosePosition|cancelHardOco|retryOco).*(executed|success|submitted|placed|complete)' || true)"
HIGH_RISK_COUNT="$(printf '%s\n' "$HIGH_RISK_LINES" | sed '/^[[:space:]]*$/d' | wc -l | tr -d '[:space:]')"
if [ "$HIGH_RISK_COUNT" -gt 0 ]; then
  if [ "$ALLOW_HIGH_RISK_LOG" = "1" ]; then
    warn "high-risk operation-like log lines present but allowed: count=$HIGH_RISK_COUNT"
  else
    printf '%s\n' "$HIGH_RISK_LINES" | tail -n 40 >&2 || true
    fail "high-risk operation-like log lines present in last $LOG_TAIL_LINES lines: count=$HIGH_RISK_COUNT"
  fi
else
  ok "no high-risk trading/OCO/grid/Earn/fund operation-like lines in last $LOG_TAIL_LINES lines"
fi

ok "runtime log smoke complete"
