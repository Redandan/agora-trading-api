#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
PORT_FILE="${PORT_FILE:-$APP_DIR/app.port}"
RUN_LOG_FILE="${RUN_LOG_FILE:-}"
LOG_TAIL_LINES="${LOG_TAIL_LINES:-3000}"
ALLOW_UNKNOWN_WARN="${ALLOW_UNKNOWN_WARN:-0}"
ALLOW_RUNTIME_ERROR="${ALLOW_RUNTIME_ERROR:-0}"
ALLOW_HIGH_RISK_LOG="${ALLOW_HIGH_RISK_LOG:-0}"
MAX_OKX_WS_CONNECTION_RESET_WARN="${MAX_OKX_WS_CONNECTION_RESET_WARN:-3}"
MAX_OKX_WS_TRANSIENT_WARN="${MAX_OKX_WS_TRANSIENT_WARN:-10}"
MAX_OKX_PRIVATE_WS_TRANSIENT_WARN="${MAX_OKX_PRIVATE_WS_TRANSIENT_WARN:-10}"
MAX_PYTH_NETWORK_WARN="${MAX_PYTH_NETWORK_WARN:-3}"
MAX_ETHERSCAN_TOKEN_SUPPLY_WARN="${MAX_ETHERSCAN_TOKEN_SUPPLY_WARN:-5}"
MAX_MCP_AUTH_DENIED_WARN="${MAX_MCP_AUTH_DENIED_WARN:-20}"
MAX_HTTP_METHOD_NOT_SUPPORTED_WARN="${MAX_HTTP_METHOD_NOT_SUPPORTED_WARN:-10}"
MAX_KLINE_GAP_WARN="${MAX_KLINE_GAP_WARN:-3}"
MAX_INDICATOR_FETCH_TIMEOUT_WARN="${MAX_INDICATOR_FETCH_TIMEOUT_WARN:-5}"
MAX_AGING_POSITION_WARN="${MAX_AGING_POSITION_WARN:-10}"
MAX_MCP_UNKNOWN_TOOL_WARN="${MAX_MCP_UNKNOWN_TOOL_WARN:-5}"
LOG_LEVEL_PREFIX='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[^[:space:]]+[[:space:]]+'
ERROR_LOG_PATTERN="${LOG_LEVEL_PREFIX}ERROR[[:space:]]+"
WARN_LOG_PATTERN="${LOG_LEVEL_PREFIX}WARN[[:space:]]+"

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

ERROR_COUNT="$(grep -cE "$ERROR_LOG_PATTERN" "$RUN_LOG_FILE" || true)"
if [ "$ERROR_COUNT" -gt 0 ]; then
  ERROR_TELEGRAM_SERVICE_PATTERN='TelegramServiceImpl.*Failed to send Telegram'
  KNOWN_ERROR_PATTERN="${ERROR_TELEGRAM_SERVICE_PATTERN}"
  ERROR_TELEGRAM_SERVICE_COUNT="$(grep -cE "${ERROR_LOG_PATTERN}.*(${ERROR_TELEGRAM_SERVICE_PATTERN})" "$RUN_LOG_FILE" || true)"
  UNKNOWN_ERROR_LINES="$(grep -nE "$ERROR_LOG_PATTERN" "$RUN_LOG_FILE" | grep -Ev "$KNOWN_ERROR_PATTERN" || true)"
  UNKNOWN_ERROR_COUNT="$(printf '%s\n' "$UNKNOWN_ERROR_LINES" | sed '/^[[:space:]]*$/d' | wc -l | tr -d '[:space:]')"
  echo "[runtime-log] ERROR category telegram_service=$ERROR_TELEGRAM_SERVICE_COUNT unknown=$UNKNOWN_ERROR_COUNT" >&2
  if [ "$ERROR_TELEGRAM_SERVICE_COUNT" -gt 0 ]; then
    echo "[runtime-log] ERROR rca=TELEGRAM_NOTIFICATION_PATH review Telegram send health before live review" >&2
  fi
  if [ "$ALLOW_RUNTIME_ERROR" = "1" ]; then
    warn "runtime ERROR lines present but allowed: count=$ERROR_COUNT"
  else
    grep -nE "$ERROR_LOG_PATTERN" "$RUN_LOG_FILE" | tail -n 20 >&2 || true
    fail "runtime ERROR lines present: count=$ERROR_COUNT"
  fi
else
  ok "runtime ERROR count is 0"
fi

WARN_COUNT="$(grep -cE "$WARN_LOG_PATTERN" "$RUN_LOG_FILE" || true)"
WARN_FLYWAY_MYSQL_PATTERN='Using MySQL .* newer than the version Flyway has been verified with'
WARN_STARTUP_TIMING_PATTERN='StartupBeanTiming'
WARN_CGLIB_PROXY_PATTERN='CglibAopProxy.*Unable to proxy'
WARN_OPEN_IN_VIEW_PATTERN='spring.jpa.open-in-view is enabled by default'
WARN_THEGRAPH_PATTERN='external[.]thegraph[.]api-key not configured'
WARN_AUTONOMOUS_DIGEST_SEVERE_PATTERN='DailyAutonomousTradingDigest.*severe notification sent'
WARN_OKX_WS_CONNECTION_RESET_PATTERN='OkxWsKlineService.*\[OkxWS\] WS failure .*Connection reset'
WARN_OKX_WS_TRANSIENT_PATTERN='OkxWsKlineService.*\[OkxWS\] WS failure .*(: null|timeout|timed out|EOF|closed|reset by peer)'
WARN_OKX_PRIVATE_WS_TRANSIENT_PATTERN='OkxPrivateWsService.*\[OkxPrivateWs\] Connection failure: (null|timeout|timed out|EOF|closed|Broken pipe|Connection reset|reset by peer)'
WARN_SCOREBUY_ML_SCHEMA_MISMATCH_PATTERN='ScoreBuyV2Strategy.*\[ScoreBuyV2\] predict failed v[0-9]+: .*ML003011: Columns of provided data need to match those used for training'
WARN_PYTH_NETWORK_TRANSIENT_PATTERN='PythNetworkService.*\[Pyth\] feed=.*(HTTP [0-9]+|empty response|unparseable price|error: .*)'
WARN_ETHERSCAN_TOKEN_SUPPLY_PATTERN='EtherscanService.*\[Etherscan\] tokenSupply chainid=[0-9]+ error .*: Error retrieving value'
WARN_MCP_AUTH_DENIED_PATTERN='McpApiKeyFilter.*\[McpAuth\] DENIED MCP method=.*reason=(metadata key missing|API key missing|invalid API key|metadata key invalid)'
WARN_HTTP_METHOD_NOT_SUPPORTED_PATTERN='DefaultHandlerExceptionResolver.*HttpRequestMethodNotSupportedException: Request method '\''GET'\'' is not supported'
WARN_KLINE_GAP_PATTERN='KlineGapDetector.*\[KlineGap\] [A-Z0-9_-]+@[A-Za-z0-9]+ missing [0-9]+ bars'
WARN_INDICATOR_FETCH_TIMEOUT_PATTERN='MarketIndicatorHistoryCollector.*\[IndicatorHistory\] parallel fetch timed out after 30s, some indicators may be missing'
WARN_AGING_POSITION_PATTERN='PositionAgingMonitor.*\[OcoPoll\] Aging position: id=[0-9]+ symbol=[A-Z0-9_-]+ daysOpen=[0-9]+'
WARN_MCP_UNKNOWN_TOOL_PATTERN='McpStreamableHttpController.*\[McpHttp\] Bad request method=tools/call: Unknown tool: getDbRuntimeStatus[[:space:]]*$'
KNOWN_WARN_PATTERN="${WARN_FLYWAY_MYSQL_PATTERN}|${WARN_STARTUP_TIMING_PATTERN}|${WARN_CGLIB_PROXY_PATTERN}|${WARN_OPEN_IN_VIEW_PATTERN}|${WARN_THEGRAPH_PATTERN}|${WARN_AUTONOMOUS_DIGEST_SEVERE_PATTERN}|${WARN_OKX_WS_CONNECTION_RESET_PATTERN}|${WARN_OKX_WS_TRANSIENT_PATTERN}|${WARN_OKX_PRIVATE_WS_TRANSIENT_PATTERN}|${WARN_SCOREBUY_ML_SCHEMA_MISMATCH_PATTERN}|${WARN_PYTH_NETWORK_TRANSIENT_PATTERN}|${WARN_ETHERSCAN_TOKEN_SUPPLY_PATTERN}|${WARN_MCP_AUTH_DENIED_PATTERN}|${WARN_HTTP_METHOD_NOT_SUPPORTED_PATTERN}|${WARN_KLINE_GAP_PATTERN}|${WARN_INDICATOR_FETCH_TIMEOUT_PATTERN}|${WARN_AGING_POSITION_PATTERN}|${WARN_MCP_UNKNOWN_TOOL_PATTERN}"

warn_category_count() {
  local pattern="$1"
  grep -cE "${WARN_LOG_PATTERN}.*(${pattern})" "$RUN_LOG_FILE" || true
}

okx_recovered_after_latest_warning() {
  local pattern="$1"
  local latest_warn_line
  latest_warn_line="$(grep -nE "${WARN_LOG_PATTERN}.*(${pattern})" "$RUN_LOG_FILE" | tail -n 1 | cut -d: -f1 || true)"
  case "$latest_warn_line" in
    ''|*[!0-9]*) return 1 ;;
  esac

  local recovered_count
  recovered_count="$(tail -n +"$((latest_warn_line + 1))" "$RUN_LOG_FILE" | grep -cE 'OkxWsKlineService.*\[OkxWS\] Persisted ' || true)"
  [ "$recovered_count" -gt 0 ]
}

okx_private_ws_recovered_after_latest_warning() {
  local latest_warn_line
  latest_warn_line="$(grep -nE "${WARN_LOG_PATTERN}.*(${WARN_OKX_PRIVATE_WS_TRANSIENT_PATTERN})" "$RUN_LOG_FILE" | tail -n 1 | cut -d: -f1 || true)"
  case "$latest_warn_line" in
    ''|*[!0-9]*) return 1 ;;
  esac

  local recovered_count
  recovered_count="$(tail -n +"$((latest_warn_line + 1))" "$RUN_LOG_FILE" | grep -cE 'OkxPrivateWsService.*\[OkxPrivateWs\] Subscription confirmed' || true)"
  [ "$recovered_count" -gt 0 ]
}

kline_gaps_all_recovered() {
  local warn_line warn_text scope recovered_count
  while IFS=: read -r warn_line warn_text; do
    [ -n "$warn_line" ] || continue
    case "$warn_line" in
      *[!0-9]*) return 1 ;;
    esac
    scope="$(printf '%s\n' "$warn_text" | sed -nE 's/.*\[KlineGap\] ([A-Z0-9_-]+@[A-Za-z0-9]+) missing.*/\1/p')"
    [ -n "$scope" ] || return 1
    recovered_count="$(tail -n +"$((warn_line + 1))" "$RUN_LOG_FILE" | grep -cE "KlineGapDetector.*\[KlineGap\] ${scope} backfilled [1-9][0-9]* bars from OKX" || true)"
    [ "$recovered_count" -gt 0 ] || return 1
  done <<EOF
$(grep -nE "${WARN_LOG_PATTERN}.*(${WARN_KLINE_GAP_PATTERN})" "$RUN_LOG_FILE" || true)
EOF
  return 0
}

WARN_FLYWAY_MYSQL_COUNT="$(warn_category_count "$WARN_FLYWAY_MYSQL_PATTERN")"
WARN_STARTUP_TIMING_COUNT="$(warn_category_count "$WARN_STARTUP_TIMING_PATTERN")"
WARN_CGLIB_PROXY_COUNT="$(warn_category_count "$WARN_CGLIB_PROXY_PATTERN")"
WARN_OPEN_IN_VIEW_COUNT="$(warn_category_count "$WARN_OPEN_IN_VIEW_PATTERN")"
WARN_THEGRAPH_COUNT="$(warn_category_count "$WARN_THEGRAPH_PATTERN")"
WARN_AUTONOMOUS_DIGEST_SEVERE_COUNT="$(warn_category_count "$WARN_AUTONOMOUS_DIGEST_SEVERE_PATTERN")"
WARN_OKX_WS_CONNECTION_RESET_COUNT="$(warn_category_count "$WARN_OKX_WS_CONNECTION_RESET_PATTERN")"
WARN_OKX_WS_TRANSIENT_COUNT="$(warn_category_count "$WARN_OKX_WS_TRANSIENT_PATTERN")"
WARN_OKX_PRIVATE_WS_TRANSIENT_COUNT="$(warn_category_count "$WARN_OKX_PRIVATE_WS_TRANSIENT_PATTERN")"
WARN_SCOREBUY_ML_SCHEMA_MISMATCH_COUNT="$(warn_category_count "$WARN_SCOREBUY_ML_SCHEMA_MISMATCH_PATTERN")"
WARN_PYTH_NETWORK_TRANSIENT_COUNT="$(warn_category_count "$WARN_PYTH_NETWORK_TRANSIENT_PATTERN")"
WARN_ETHERSCAN_TOKEN_SUPPLY_COUNT="$(warn_category_count "$WARN_ETHERSCAN_TOKEN_SUPPLY_PATTERN")"
WARN_MCP_AUTH_DENIED_COUNT="$(warn_category_count "$WARN_MCP_AUTH_DENIED_PATTERN")"
WARN_HTTP_METHOD_NOT_SUPPORTED_COUNT="$(warn_category_count "$WARN_HTTP_METHOD_NOT_SUPPORTED_PATTERN")"
WARN_KLINE_GAP_COUNT="$(warn_category_count "$WARN_KLINE_GAP_PATTERN")"
WARN_INDICATOR_FETCH_TIMEOUT_COUNT="$(warn_category_count "$WARN_INDICATOR_FETCH_TIMEOUT_PATTERN")"
WARN_AGING_POSITION_COUNT="$(warn_category_count "$WARN_AGING_POSITION_PATTERN")"
WARN_MCP_UNKNOWN_TOOL_COUNT="$(warn_category_count "$WARN_MCP_UNKNOWN_TOOL_PATTERN")"

case "$MAX_OKX_WS_CONNECTION_RESET_WARN" in
  ''|*[!0-9]*) fail "invalid MAX_OKX_WS_CONNECTION_RESET_WARN: $MAX_OKX_WS_CONNECTION_RESET_WARN" ;;
esac
case "$MAX_OKX_WS_TRANSIENT_WARN" in
  ''|*[!0-9]*) fail "invalid MAX_OKX_WS_TRANSIENT_WARN: $MAX_OKX_WS_TRANSIENT_WARN" ;;
esac
case "$MAX_OKX_PRIVATE_WS_TRANSIENT_WARN" in
  ''|*[!0-9]*) fail "invalid MAX_OKX_PRIVATE_WS_TRANSIENT_WARN: $MAX_OKX_PRIVATE_WS_TRANSIENT_WARN" ;;
esac
case "$MAX_PYTH_NETWORK_WARN" in
  ''|*[!0-9]*) fail "invalid MAX_PYTH_NETWORK_WARN: $MAX_PYTH_NETWORK_WARN" ;;
esac
case "$MAX_ETHERSCAN_TOKEN_SUPPLY_WARN" in
  ''|*[!0-9]*) fail "invalid MAX_ETHERSCAN_TOKEN_SUPPLY_WARN: $MAX_ETHERSCAN_TOKEN_SUPPLY_WARN" ;;
esac
case "$MAX_MCP_AUTH_DENIED_WARN" in
  ''|*[!0-9]*) fail "invalid MAX_MCP_AUTH_DENIED_WARN: $MAX_MCP_AUTH_DENIED_WARN" ;;
esac
case "$MAX_HTTP_METHOD_NOT_SUPPORTED_WARN" in
  ''|*[!0-9]*) fail "invalid MAX_HTTP_METHOD_NOT_SUPPORTED_WARN: $MAX_HTTP_METHOD_NOT_SUPPORTED_WARN" ;;
esac
case "$MAX_KLINE_GAP_WARN" in
  ''|*[!0-9]*) fail "invalid MAX_KLINE_GAP_WARN: $MAX_KLINE_GAP_WARN" ;;
esac
case "$MAX_INDICATOR_FETCH_TIMEOUT_WARN" in
  ''|*[!0-9]*) fail "invalid MAX_INDICATOR_FETCH_TIMEOUT_WARN: $MAX_INDICATOR_FETCH_TIMEOUT_WARN" ;;
esac
case "$MAX_AGING_POSITION_WARN" in
  ''|*[!0-9]*) fail "invalid MAX_AGING_POSITION_WARN: $MAX_AGING_POSITION_WARN" ;;
esac
case "$MAX_MCP_UNKNOWN_TOOL_WARN" in
  ''|*[!0-9]*) fail "invalid MAX_MCP_UNKNOWN_TOOL_WARN: $MAX_MCP_UNKNOWN_TOOL_WARN" ;;
esac

if [ "$WARN_OKX_WS_CONNECTION_RESET_COUNT" -gt "$MAX_OKX_WS_CONNECTION_RESET_WARN" ]; then
  if okx_recovered_after_latest_warning "$WARN_OKX_WS_CONNECTION_RESET_PATTERN"; then
    warn "OKX WS connection reset warnings exceeded threshold but recovered with persisted K-line rows after latest warning: count=$WARN_OKX_WS_CONNECTION_RESET_COUNT max=$MAX_OKX_WS_CONNECTION_RESET_WARN"
  else
    grep -nE "${WARN_LOG_PATTERN}.*(${WARN_OKX_WS_CONNECTION_RESET_PATTERN})" "$RUN_LOG_FILE" | tail -n 40 >&2 || true
    fail "OKX WS connection reset warnings exceeded threshold without persisted K-line recovery: count=$WARN_OKX_WS_CONNECTION_RESET_COUNT max=$MAX_OKX_WS_CONNECTION_RESET_WARN"
  fi
fi
if [ "$WARN_OKX_WS_TRANSIENT_COUNT" -gt "$MAX_OKX_WS_TRANSIENT_WARN" ]; then
  if okx_recovered_after_latest_warning "$WARN_OKX_WS_TRANSIENT_PATTERN"; then
    warn "OKX WS transient warnings exceeded threshold but recovered with persisted K-line rows after latest warning: count=$WARN_OKX_WS_TRANSIENT_COUNT max=$MAX_OKX_WS_TRANSIENT_WARN"
  else
    grep -nE "${WARN_LOG_PATTERN}.*(${WARN_OKX_WS_TRANSIENT_PATTERN})" "$RUN_LOG_FILE" | tail -n 40 >&2 || true
    fail "OKX WS transient warnings exceeded threshold without persisted K-line recovery: count=$WARN_OKX_WS_TRANSIENT_COUNT max=$MAX_OKX_WS_TRANSIENT_WARN"
  fi
fi
if [ "$WARN_OKX_PRIVATE_WS_TRANSIENT_COUNT" -gt 0 ] && ! okx_private_ws_recovered_after_latest_warning; then
  grep -nE "${WARN_LOG_PATTERN}.*(${WARN_OKX_PRIVATE_WS_TRANSIENT_PATTERN})" "$RUN_LOG_FILE" | tail -n 40 >&2 || true
  fail "OKX private WS warnings lack subscription recovery after latest warning: count=$WARN_OKX_PRIVATE_WS_TRANSIENT_COUNT"
fi
if [ "$WARN_OKX_PRIVATE_WS_TRANSIENT_COUNT" -gt "$MAX_OKX_PRIVATE_WS_TRANSIENT_WARN" ]; then
  grep -nE "${WARN_LOG_PATTERN}.*(${WARN_OKX_PRIVATE_WS_TRANSIENT_PATTERN})" "$RUN_LOG_FILE" | tail -n 40 >&2 || true
  fail "OKX private WS warnings exceeded threshold: count=$WARN_OKX_PRIVATE_WS_TRANSIENT_COUNT max=$MAX_OKX_PRIVATE_WS_TRANSIENT_WARN"
fi
if [ "$WARN_PYTH_NETWORK_TRANSIENT_COUNT" -gt "$MAX_PYTH_NETWORK_WARN" ]; then
  grep -nE "${WARN_LOG_PATTERN}.*(${WARN_PYTH_NETWORK_TRANSIENT_PATTERN})" "$RUN_LOG_FILE" | tail -n 40 >&2 || true
  fail "Pyth network warnings exceeded threshold: count=$WARN_PYTH_NETWORK_TRANSIENT_COUNT max=$MAX_PYTH_NETWORK_WARN"
fi
if [ "$WARN_ETHERSCAN_TOKEN_SUPPLY_COUNT" -gt "$MAX_ETHERSCAN_TOKEN_SUPPLY_WARN" ]; then
  grep -nE "${WARN_LOG_PATTERN}.*(${WARN_ETHERSCAN_TOKEN_SUPPLY_PATTERN})" "$RUN_LOG_FILE" | tail -n 40 >&2 || true
  fail "Etherscan tokenSupply warnings exceeded threshold: count=$WARN_ETHERSCAN_TOKEN_SUPPLY_COUNT max=$MAX_ETHERSCAN_TOKEN_SUPPLY_WARN"
fi
if [ "$WARN_MCP_AUTH_DENIED_COUNT" -gt "$MAX_MCP_AUTH_DENIED_WARN" ]; then
  grep -nE "${WARN_LOG_PATTERN}.*(${WARN_MCP_AUTH_DENIED_PATTERN})" "$RUN_LOG_FILE" | tail -n 40 >&2 || true
  fail "MCP auth denied warnings exceeded threshold: count=$WARN_MCP_AUTH_DENIED_COUNT max=$MAX_MCP_AUTH_DENIED_WARN"
fi
if [ "$WARN_HTTP_METHOD_NOT_SUPPORTED_COUNT" -gt "$MAX_HTTP_METHOD_NOT_SUPPORTED_WARN" ]; then
  grep -nE "${WARN_LOG_PATTERN}.*(${WARN_HTTP_METHOD_NOT_SUPPORTED_PATTERN})" "$RUN_LOG_FILE" | tail -n 40 >&2 || true
  fail "HTTP method-not-supported warnings exceeded threshold: count=$WARN_HTTP_METHOD_NOT_SUPPORTED_COUNT max=$MAX_HTTP_METHOD_NOT_SUPPORTED_WARN"
fi
if [ "$WARN_KLINE_GAP_COUNT" -gt 0 ]; then
  if ! kline_gaps_all_recovered; then
    grep -nE "${WARN_LOG_PATTERN}.*(${WARN_KLINE_GAP_PATTERN})" "$RUN_LOG_FILE" | tail -n 40 >&2 || true
    fail "one or more K-line gap warnings lack same-run same-scope OKX backfill recovery: count=$WARN_KLINE_GAP_COUNT"
  fi
  if [ "$WARN_KLINE_GAP_COUNT" -gt "$MAX_KLINE_GAP_WARN" ]; then
    grep -nE "${WARN_LOG_PATTERN}.*(${WARN_KLINE_GAP_PATTERN})" "$RUN_LOG_FILE" | tail -n 40 >&2 || true
    fail "K-line gap warnings exceeded threshold: count=$WARN_KLINE_GAP_COUNT max=$MAX_KLINE_GAP_WARN"
  fi
fi
if [ "$WARN_INDICATOR_FETCH_TIMEOUT_COUNT" -gt "$MAX_INDICATOR_FETCH_TIMEOUT_WARN" ]; then
  grep -nE "${WARN_LOG_PATTERN}.*(${WARN_INDICATOR_FETCH_TIMEOUT_PATTERN})" "$RUN_LOG_FILE" | tail -n 40 >&2 || true
  fail "indicator fetch timeout warnings exceeded threshold: count=$WARN_INDICATOR_FETCH_TIMEOUT_COUNT max=$MAX_INDICATOR_FETCH_TIMEOUT_WARN"
fi
if [ "$WARN_AGING_POSITION_COUNT" -gt "$MAX_AGING_POSITION_WARN" ]; then
  grep -nE "${WARN_LOG_PATTERN}.*(${WARN_AGING_POSITION_PATTERN})" "$RUN_LOG_FILE" | tail -n 40 >&2 || true
  fail "aging position warnings exceeded threshold: count=$WARN_AGING_POSITION_COUNT max=$MAX_AGING_POSITION_WARN"
fi
if [ "$WARN_MCP_UNKNOWN_TOOL_COUNT" -gt "$MAX_MCP_UNKNOWN_TOOL_WARN" ]; then
  grep -nE "${WARN_LOG_PATTERN}.*(${WARN_MCP_UNKNOWN_TOOL_PATTERN})" "$RUN_LOG_FILE" | tail -n 40 >&2 || true
  fail "wrong-service getDbRuntimeStatus probes exceeded threshold: count=$WARN_MCP_UNKNOWN_TOOL_COUNT max=$MAX_MCP_UNKNOWN_TOOL_WARN"
fi

UNKNOWN_WARN_LINES="$(grep -nE "$WARN_LOG_PATTERN" "$RUN_LOG_FILE" | grep -Ev "$KNOWN_WARN_PATTERN" || true)"
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
  ok "WARN baseline category flyway_mysql_version=$WARN_FLYWAY_MYSQL_COUNT startup_bean_timing=$WARN_STARTUP_TIMING_COUNT cglib_proxy=$WARN_CGLIB_PROXY_COUNT open_in_view=$WARN_OPEN_IN_VIEW_COUNT thegraph_optional_key=$WARN_THEGRAPH_COUNT autonomous_digest_severe=$WARN_AUTONOMOUS_DIGEST_SEVERE_COUNT okx_ws_connection_reset=$WARN_OKX_WS_CONNECTION_RESET_COUNT okx_ws_transient=$WARN_OKX_WS_TRANSIENT_COUNT okx_private_ws_transient=$WARN_OKX_PRIVATE_WS_TRANSIENT_COUNT scorebuy_ml_schema_mismatch=$WARN_SCOREBUY_ML_SCHEMA_MISMATCH_COUNT pyth_network_transient=$WARN_PYTH_NETWORK_TRANSIENT_COUNT etherscan_token_supply=$WARN_ETHERSCAN_TOKEN_SUPPLY_COUNT mcp_auth_denied=$WARN_MCP_AUTH_DENIED_COUNT http_method_not_supported=$WARN_HTTP_METHOD_NOT_SUPPORTED_COUNT kline_gap=$WARN_KLINE_GAP_COUNT indicator_fetch_timeout=$WARN_INDICATOR_FETCH_TIMEOUT_COUNT aging_position=$WARN_AGING_POSITION_COUNT mcp_unknown_tool=$WARN_MCP_UNKNOWN_TOOL_COUNT unknown=0"
fi

TAIL_LOG_LINES="$(tail -n "$LOG_TAIL_LINES" "$RUN_LOG_FILE")"
OKX_AUTO_TRADE_CONFIG_ECHO_LINES="$(printf '%s\n' "$TAIL_LOG_LINES" | grep -nE 'OkxTradingService.*\[OKX\] Auto-trade enabled[[:space:]]*:[[:space:]]*true' || true)"
OKX_AUTO_TRADE_CONFIG_ECHO_COUNT="$(printf '%s\n' "$OKX_AUTO_TRADE_CONFIG_ECHO_LINES" | sed '/^[[:space:]]*$/d' | wc -l | tr -d '[:space:]')"
if [ "$OKX_AUTO_TRADE_CONFIG_ECHO_COUNT" -gt 0 ]; then
  ok "OKX auto-trade enabled startup config echo present: count=$OKX_AUTO_TRADE_CONFIG_ECHO_COUNT"
fi

HIGH_RISK_LINES="$(printf '%s\n' "$TAIL_LOG_LINES" | grep -nEi '(order|okx).*(placed|submitted|filled|executed)|(modifyOco|createGrid|closeGrid|redeemEarn|subscribeEarn|forceClosePosition|cancelHardOco|retryOco).*(executed|success|submitted|placed|complete)' || true)"
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
