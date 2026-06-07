#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
BRANCH="${BRANCH:-main}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/.env.trading.secrets}"
PORT_FILE="${PORT_FILE:-$APP_DIR/app.port}"
PID_FILE="${PID_FILE:-$APP_DIR/app.pid}"
COMMIT_FILE="${COMMIT_FILE:-$APP_DIR/app.commit}"
DEFAULT_PORT="${PORT:-8084}"
PORT_A="${PORT_A:-8084}"
PORT_B="${PORT_B:-8085}"
AGORA_MARKET_HEALTH_URL="${AGORA_MARKET_HEALTH_URL:-http://127.0.0.1:8080/api/actuator/health}"
PUBLIC_TRADING_HEALTH_URL="${PUBLIC_TRADING_HEALTH_URL:-}"
NGINX_CONF_GLOB="${NGINX_CONF_GLOB:-/etc/nginx/sites-enabled/*}"
INTERNAL_CLIENT_POM="${INTERNAL_CLIENT_POM:-/home/ubuntu/AgoraMarketAPI/internal-client/pom.xml}"
RUN_PREFLIGHT="${RUN_PREFLIGHT:-1}"
VERIFY_GIT_CURRENT="${VERIFY_GIT_CURRENT:-1}"
REQUIRE_NGINX_TRADING_PATH="${REQUIRE_NGINX_TRADING_PATH:-1}"
REQUIRE_NGINX_SERVICE="${REQUIRE_NGINX_SERVICE:-1}"
REQUIRE_DEPLOY_METADATA="${REQUIRE_DEPLOY_METADATA:-1}"
RUN_SCHEMA_BASELINE_COMPARE="${RUN_SCHEMA_BASELINE_COMPARE:-0}"
EXPECTED_AGORA_MARKET_BASE_URL="${EXPECTED_AGORA_MARKET_BASE_URL:-http://127.0.0.1:8080}"
REQUIRE_AGORA_MARKET_HEALTH="${REQUIRE_AGORA_MARKET_HEALTH:-1}"
EXPECTED_TRADING_DATABASE="${EXPECTED_TRADING_DATABASE:-agora_market}"
SCHEMA_COMPARE_MODE="${SCHEMA_COMPARE_MODE:-shared}"

fail() {
  echo "[server-verify] FAIL: $*" >&2
  exit 1
}

warn() {
  echo "[server-verify] WARN: $*" >&2
}

ok() {
  echo "[server-verify] OK: $*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

require_env_key() {
  local key="$1"
  local line
  if [ ! -f "$ENV_FILE" ]; then
    fail "env file missing: $ENV_FILE"
  fi
  line="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)"
  if [ -z "$line" ] || ! printf '%s\n' "$line" | grep -Eq "^[[:space:]]*${key}=[^[:space:]#]"; then
    fail "missing or empty $key in $ENV_FILE"
  fi
  ok "$key is present and non-empty in env file"
}

env_value() {
  local key="$1"
  local line
  if [ ! -f "$ENV_FILE" ]; then
    fail "env file missing: $ENV_FILE"
  fi
  line="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)"
  if [ -z "$line" ]; then
    return 1
  fi
  printf '%s' "${line#*=}"
}

require_env_value() {
  local key="$1"
  local expected="$2"
  require_env_key "$key"
  if [ "$(env_value "$key")" != "$expected" ]; then
    fail "$key must be $expected"
  fi
  ok "$key matches expected value: $expected"
}

require_cmd bash
require_cmd curl
require_cmd git
require_cmd grep
require_cmd java
require_cmd lsof
require_cmd ls
require_cmd mvn
require_cmd ps
require_cmd systemctl
require_cmd tail
require_cmd tr

[ -d "$APP_DIR" ] || fail "app dir missing: $APP_DIR"

if [ "$RUN_PREFLIGHT" = "1" ]; then
  PREFLIGHT_SCRIPT="$APP_DIR/scripts/preflight_server.sh"
  [ -f "$PREFLIGHT_SCRIPT" ] || fail "preflight script missing: $PREFLIGHT_SCRIPT"
  APP_DIR="$APP_DIR" \
    ENV_FILE="$ENV_FILE" \
    INTERNAL_CLIENT_POM="$INTERNAL_CLIENT_POM" \
    AGORA_MARKET_HEALTH_URL="$AGORA_MARKET_HEALTH_URL" \
    EXPECTED_AGORA_MARKET_BASE_URL="$EXPECTED_AGORA_MARKET_BASE_URL" \
    EXPECTED_TRADING_DATABASE="$EXPECTED_TRADING_DATABASE" \
    NGINX_CONF_GLOB="$NGINX_CONF_GLOB" \
    REQUIRE_AGORA_MARKET_HEALTH="$REQUIRE_AGORA_MARKET_HEALTH" \
    bash "$PREFLIGHT_SCRIPT"
fi

cd "$APP_DIR"
ok "app dir exists: $APP_DIR"

git rev-parse --is-inside-work-tree >/dev/null || fail "$APP_DIR is not a git worktree"
ok "git worktree detected: $(git rev-parse --short HEAD)"

if [ "$VERIFY_GIT_CURRENT" = "1" ]; then
  git fetch origin "$BRANCH" --quiet
  HEAD_COMMIT="$(git rev-parse HEAD)"
  ORIGIN_COMMIT="$(git rev-parse "origin/$BRANCH")"
  if [ "$HEAD_COMMIT" != "$ORIGIN_COMMIT" ]; then
    fail "worktree commit $(git rev-parse --short HEAD) does not match origin/$BRANCH $(git rev-parse --short "origin/$BRANCH")"
  fi
  ok "worktree commit matches origin/$BRANCH: $(git rev-parse --short HEAD)"
else
  ok "git currentness check skipped; VERIFY_GIT_CURRENT=$VERIFY_GIT_CURRENT"
fi

if [ -f "$COMMIT_FILE" ]; then
  DEPLOYED_COMMIT="$(tr -d '[:space:]' < "$COMMIT_FILE")"
  HEAD_COMMIT="$(git rev-parse HEAD)"
  if [ "$DEPLOYED_COMMIT" != "$HEAD_COMMIT" ]; then
    fail "deployed app.commit ${DEPLOYED_COMMIT:-empty} does not match worktree HEAD $(git rev-parse --short HEAD)"
  fi
  ok "deployed app.commit matches worktree HEAD: $(git rev-parse --short HEAD)"
elif [ "$REQUIRE_DEPLOY_METADATA" = "1" ]; then
  fail "deploy commit file missing: $COMMIT_FILE"
else
  warn "deploy commit file missing: $COMMIT_FILE; REQUIRE_DEPLOY_METADATA=$REQUIRE_DEPLOY_METADATA"
fi

require_env_key TRADING_MCP_KEY
require_env_key AGORA_MARKET_BASE_URL
require_env_key AGORA_MARKET_INTERNAL_API_KEY
require_env_value AGORA_MARKET_INTERNAL_TIMEOUT_MS 3000
require_env_key SPRING_DATASOURCE_URL
require_env_key SPRING_DATASOURCE_USERNAME
require_env_key SPRING_DATASOURCE_PASSWORD
require_env_value SPRING_JPA_HIBERNATE_DDL_AUTO update
require_env_value SPRING_FLYWAY_ENABLED false

case "$(env_value SPRING_DATASOURCE_URL)" in
  jdbc:mysql://*/"$EXPECTED_TRADING_DATABASE"|jdbc:mysql://*/"$EXPECTED_TRADING_DATABASE"\?*) ;;
  *) fail "SPRING_DATASOURCE_URL must point at expected shared database: $EXPECTED_TRADING_DATABASE" ;;
esac
ok "SPRING_DATASOURCE_URL points at expected shared database: $EXPECTED_TRADING_DATABASE"

[ -f "$INTERNAL_CLIENT_POM" ] || fail "AgoraMarket internal-client pom missing: $INTERNAL_CLIENT_POM"
ok "AgoraMarket internal-client pom found"

if [ "$(env_value AGORA_MARKET_BASE_URL)" != "$EXPECTED_AGORA_MARKET_BASE_URL" ]; then
  fail "AGORA_MARKET_BASE_URL must point at local AgoraMarketAPI dependency: expected $EXPECTED_AGORA_MARKET_BASE_URL"
fi
ok "AGORA_MARKET_BASE_URL points at local AgoraMarketAPI dependency"

if [ "$RUN_SCHEMA_BASELINE_COMPARE" = "1" ]; then
  SCHEMA_COMPARE_SCRIPT="$APP_DIR/scripts/schema_baseline_compare_server.sh"
  [ -f "$SCHEMA_COMPARE_SCRIPT" ] || fail "schema baseline compare script missing: $SCHEMA_COMPARE_SCRIPT"
  APP_DIR="$APP_DIR" \
    ENV_FILE="$ENV_FILE" \
    EXPECTED_TRADING_DATABASE="$EXPECTED_TRADING_DATABASE" \
    SCHEMA_COMPARE_MODE="$SCHEMA_COMPARE_MODE" \
    bash "$SCHEMA_COMPARE_SCRIPT"
  ok "schema baseline database comparison passed"
else
  ok "schema baseline database comparison skipped; set RUN_SCHEMA_BASELINE_COMPARE=1 before Flyway baseline generation"
fi

if [ -f "$PORT_FILE" ]; then
  ACTIVE_PORT="$(tr -d '[:space:]' < "$PORT_FILE")"
elif [ "$REQUIRE_DEPLOY_METADATA" = "1" ]; then
  fail "deploy port file missing: $PORT_FILE"
else
  ACTIVE_PORT="$DEFAULT_PORT"
  warn "port file missing: $PORT_FILE; using PORT/default $ACTIVE_PORT; REQUIRE_DEPLOY_METADATA=$REQUIRE_DEPLOY_METADATA"
fi

case "$ACTIVE_PORT" in
  ''|*[!0-9]*) fail "invalid active port: $ACTIVE_PORT" ;;
esac
case "$ACTIVE_PORT" in
  "$PORT_A"|"$PORT_B") ;;
  *) fail "unknown active port: $ACTIVE_PORT (expected $PORT_A or $PORT_B)" ;;
esac

if [ -f "$PID_FILE" ]; then
  ACTIVE_PID="$(tr -d '[:space:]' < "$PID_FILE")"
  case "$ACTIVE_PID" in
    ''|*[!0-9]*) fail "invalid deployed app.pid: $ACTIVE_PID" ;;
  esac
  ACTIVE_PORT_PID_FILE="$APP_DIR/app.pid.$ACTIVE_PORT"
  if [ -f "$ACTIVE_PORT_PID_FILE" ]; then
    ACTIVE_PORT_PID="$(tr -d '[:space:]' < "$ACTIVE_PORT_PID_FILE")"
    if [ "$ACTIVE_PORT_PID" != "$ACTIVE_PID" ]; then
      fail "active per-port pid metadata $ACTIVE_PORT_PID_FILE value ${ACTIVE_PORT_PID:-empty} does not match app.pid $ACTIVE_PID"
    fi
    ok "active per-port pid metadata matches app.pid: $ACTIVE_PORT_PID_FILE"
  elif [ "$REQUIRE_DEPLOY_METADATA" = "1" ]; then
    fail "active per-port pid metadata missing: $ACTIVE_PORT_PID_FILE"
  else
    warn "active per-port pid metadata missing: $ACTIVE_PORT_PID_FILE; REQUIRE_DEPLOY_METADATA=$REQUIRE_DEPLOY_METADATA"
  fi
  ps -p "$ACTIVE_PID" >/dev/null || fail "deployed app.pid $ACTIVE_PID is not running"
  if lsof -ti ":$ACTIVE_PORT" 2>/dev/null | grep -qx "$ACTIVE_PID"; then
    ok "deployed app.pid $ACTIVE_PID is listening on active port $ACTIVE_PORT"
  else
    fail "deployed app.pid $ACTIVE_PID is not listening on active port $ACTIVE_PORT"
  fi
elif [ "$REQUIRE_DEPLOY_METADATA" = "1" ]; then
  fail "deploy pid file missing: $PID_FILE"
else
  warn "deploy pid file missing: $PID_FILE; REQUIRE_DEPLOY_METADATA=$REQUIRE_DEPLOY_METADATA"
fi

LOCAL_HEALTH_URL="http://127.0.0.1:${ACTIVE_PORT}/api/trading/actuator/health"
curl -fsS "$LOCAL_HEALTH_URL" >/dev/null || fail "local trading health failed: $LOCAL_HEALTH_URL"
ok "local trading health passed: $LOCAL_HEALTH_URL"

MCP_KEY="$(env_value TRADING_MCP_KEY)"
MCP_URL="http://127.0.0.1:${ACTIVE_PORT}/api/trading/mcp"
MCP_RESPONSE="$(curl -fsS \
  --max-time 30 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${MCP_KEY}" \
  --data '{"jsonrpc":"2.0","id":"server-verify-registry-version","method":"tools/call","params":{"name":"getMcpRegistryVersion","arguments":{}}}' \
  "$MCP_URL")" || fail "local MCP getMcpRegistryVersion failed: $MCP_URL"
printf '%s' "$MCP_RESPONSE" | grep -q '"content"' || fail "local MCP getMcpRegistryVersion response missing content array: $MCP_URL"
ok "local MCP getMcpRegistryVersion passed: $MCP_URL"

if curl -fsS "$AGORA_MARKET_HEALTH_URL" >/dev/null; then
  ok "AgoraMarket exchange-rate dependency health passed: $AGORA_MARKET_HEALTH_URL"
elif [ "$REQUIRE_AGORA_MARKET_HEALTH" = "1" ]; then
  fail "AgoraMarket exchange-rate dependency health failed: $AGORA_MARKET_HEALTH_URL"
else
  warn "AgoraMarket exchange-rate dependency health failed: $AGORA_MARKET_HEALTH_URL; REQUIRE_AGORA_MARKET_HEALTH=$REQUIRE_AGORA_MARKET_HEALTH"
fi

if [ -n "$PUBLIC_TRADING_HEALTH_URL" ]; then
  curl -fsS "$PUBLIC_TRADING_HEALTH_URL" >/dev/null || fail "public trading health failed: $PUBLIC_TRADING_HEALTH_URL"
  ok "public trading health passed: $PUBLIC_TRADING_HEALTH_URL"
fi

if ls $NGINX_CONF_GLOB >/dev/null 2>&1; then
  if grep -R "location[[:space:]]*/api/trading/" $NGINX_CONF_GLOB >/dev/null 2>&1; then
    ok "nginx /api/trading/ location found"
  else
    if [ "$REQUIRE_NGINX_TRADING_PATH" = "1" ]; then
      fail "nginx /api/trading/ location not found under $NGINX_CONF_GLOB"
    fi
    warn "nginx /api/trading/ location not found under $NGINX_CONF_GLOB; REQUIRE_NGINX_TRADING_PATH=$REQUIRE_NGINX_TRADING_PATH"
  fi
else
  if [ "$REQUIRE_NGINX_TRADING_PATH" = "1" ]; then
    fail "nginx config glob has no matches: $NGINX_CONF_GLOB"
  fi
  warn "nginx config glob has no matches: $NGINX_CONF_GLOB; REQUIRE_NGINX_TRADING_PATH=$REQUIRE_NGINX_TRADING_PATH"
fi

if systemctl is-active --quiet nginx; then
  ok "nginx service is active"
elif [ "$REQUIRE_NGINX_SERVICE" = "1" ]; then
  fail "nginx service is not active"
else
  warn "nginx service is not active; REQUIRE_NGINX_SERVICE=$REQUIRE_NGINX_SERVICE"
fi

ok "server verification complete"
