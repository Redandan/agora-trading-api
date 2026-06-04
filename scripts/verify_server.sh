#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/.env.trading.secrets}"
PORT_FILE="${PORT_FILE:-$APP_DIR/app.port}"
DEFAULT_PORT="${PORT:-8084}"
AGORA_MARKET_HEALTH_URL="${AGORA_MARKET_HEALTH_URL:-https://agoramarketapi.purrtechllc.com/api/actuator/health}"
PUBLIC_TRADING_HEALTH_URL="${PUBLIC_TRADING_HEALTH_URL:-}"
NGINX_CONF_GLOB="${NGINX_CONF_GLOB:-/etc/nginx/sites-enabled/*}"

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
  if [ ! -f "$ENV_FILE" ]; then
    fail "env file missing: $ENV_FILE"
  fi
  if ! grep -Eq "^[[:space:]]*${key}=" "$ENV_FILE"; then
    fail "missing $key in $ENV_FILE"
  fi
  ok "$key is present in env file"
}

require_cmd curl
require_cmd git
require_cmd java
require_cmd mvn

[ -d "$APP_DIR" ] || fail "app dir missing: $APP_DIR"
cd "$APP_DIR"
ok "app dir exists: $APP_DIR"

git rev-parse --is-inside-work-tree >/dev/null || fail "$APP_DIR is not a git worktree"
ok "git worktree detected: $(git rev-parse --short HEAD)"

require_env_key TRADING_ADMIN_KEY
require_env_key TRADING_MCP_KEY
require_env_key AGORA_MARKET_BASE_URL
require_env_key AGORA_MARKET_INTERNAL_API_KEY
require_env_key SPRING_DATASOURCE_URL
require_env_key SPRING_DATASOURCE_USERNAME
require_env_key SPRING_DATASOURCE_PASSWORD

if [ -f "$PORT_FILE" ]; then
  ACTIVE_PORT="$(tr -d '[:space:]' < "$PORT_FILE")"
else
  ACTIVE_PORT="$DEFAULT_PORT"
  warn "port file missing: $PORT_FILE; using PORT/default $ACTIVE_PORT"
fi

case "$ACTIVE_PORT" in
  ''|*[!0-9]*) fail "invalid active port: $ACTIVE_PORT" ;;
esac

LOCAL_HEALTH_URL="http://127.0.0.1:${ACTIVE_PORT}/api/trading/actuator/health"
curl -fsS "$LOCAL_HEALTH_URL" >/dev/null || fail "local trading health failed: $LOCAL_HEALTH_URL"
ok "local trading health passed: $LOCAL_HEALTH_URL"

curl -fsS "$AGORA_MARKET_HEALTH_URL" >/dev/null || fail "AgoraMarket health failed: $AGORA_MARKET_HEALTH_URL"
ok "AgoraMarket health passed: $AGORA_MARKET_HEALTH_URL"

if [ -n "$PUBLIC_TRADING_HEALTH_URL" ]; then
  curl -fsS "$PUBLIC_TRADING_HEALTH_URL" >/dev/null || fail "public trading health failed: $PUBLIC_TRADING_HEALTH_URL"
  ok "public trading health passed: $PUBLIC_TRADING_HEALTH_URL"
fi

if ls $NGINX_CONF_GLOB >/dev/null 2>&1; then
  if grep -R "location[[:space:]]*/api/trading/" $NGINX_CONF_GLOB >/dev/null 2>&1; then
    ok "nginx /api/trading/ location found"
  else
    warn "nginx /api/trading/ location not found under $NGINX_CONF_GLOB"
  fi
else
  warn "nginx config glob has no matches: $NGINX_CONF_GLOB"
fi

if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet nginx; then
  ok "nginx service is active"
else
  warn "nginx service is not active or systemctl is unavailable"
fi

ok "server verification complete"
