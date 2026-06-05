#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
BRANCH="${BRANCH:-main}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/.env.trading.secrets}"
PORT_FILE="${PORT_FILE:-$APP_DIR/app.port}"
COMMIT_FILE="${COMMIT_FILE:-$APP_DIR/app.commit}"
DEFAULT_PORT="${PORT:-8084}"
PORT_A="${PORT_A:-8084}"
PORT_B="${PORT_B:-8085}"
AGORA_MARKET_HEALTH_URL="${AGORA_MARKET_HEALTH_URL:-https://agoramarketapi.purrtechllc.com/api/actuator/health}"
PUBLIC_TRADING_HEALTH_URL="${PUBLIC_TRADING_HEALTH_URL:-}"
NGINX_CONF_GLOB="${NGINX_CONF_GLOB:-/etc/nginx/sites-enabled/*}"
INTERNAL_CLIENT_POM="${INTERNAL_CLIENT_POM:-/home/ubuntu/AgoraMarketAPI/internal-client/pom.xml}"
RUN_PREFLIGHT="${RUN_PREFLIGHT:-1}"
VERIFY_GIT_CURRENT="${VERIFY_GIT_CURRENT:-1}"
REQUIRE_NGINX_TRADING_PATH="${REQUIRE_NGINX_TRADING_PATH:-1}"
RUN_SCHEMA_BASELINE_COMPARE="${RUN_SCHEMA_BASELINE_COMPARE:-0}"

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

require_cmd bash
require_cmd curl
require_cmd git
require_cmd java
require_cmd mvn

[ -d "$APP_DIR" ] || fail "app dir missing: $APP_DIR"

if [ "$RUN_PREFLIGHT" = "1" ]; then
  PREFLIGHT_SCRIPT="$APP_DIR/scripts/preflight_server.sh"
  [ -f "$PREFLIGHT_SCRIPT" ] || fail "preflight script missing: $PREFLIGHT_SCRIPT"
  APP_DIR="$APP_DIR" \
    ENV_FILE="$ENV_FILE" \
    INTERNAL_CLIENT_POM="$INTERNAL_CLIENT_POM" \
    AGORA_MARKET_HEALTH_URL="$AGORA_MARKET_HEALTH_URL" \
    NGINX_CONF_GLOB="$NGINX_CONF_GLOB" \
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
else
  warn "deploy commit file missing: $COMMIT_FILE"
fi

require_env_key TRADING_ADMIN_KEY
require_env_key TRADING_MCP_KEY
require_env_key AGORA_MARKET_BASE_URL
require_env_key AGORA_MARKET_INTERNAL_API_KEY
require_env_key SPRING_DATASOURCE_URL
require_env_key SPRING_DATASOURCE_USERNAME
require_env_key SPRING_DATASOURCE_PASSWORD

if [ "$RUN_SCHEMA_BASELINE_COMPARE" = "1" ]; then
  SCHEMA_COMPARE_SCRIPT="$APP_DIR/scripts/schema_baseline_compare_server.sh"
  [ -f "$SCHEMA_COMPARE_SCRIPT" ] || fail "schema baseline compare script missing: $SCHEMA_COMPARE_SCRIPT"
  APP_DIR="$APP_DIR" \
    ENV_FILE="$ENV_FILE" \
    bash "$SCHEMA_COMPARE_SCRIPT"
  ok "schema baseline database comparison passed"
else
  ok "schema baseline database comparison skipped; set RUN_SCHEMA_BASELINE_COMPARE=1 before Flyway baseline generation"
fi

if [ -f "$PORT_FILE" ]; then
  ACTIVE_PORT="$(tr -d '[:space:]' < "$PORT_FILE")"
else
  ACTIVE_PORT="$DEFAULT_PORT"
  warn "port file missing: $PORT_FILE; using PORT/default $ACTIVE_PORT"
fi

case "$ACTIVE_PORT" in
  ''|*[!0-9]*) fail "invalid active port: $ACTIVE_PORT" ;;
esac
case "$ACTIVE_PORT" in
  "$PORT_A"|"$PORT_B") ;;
  *) fail "unknown active port: $ACTIVE_PORT (expected $PORT_A or $PORT_B)" ;;
esac

LOCAL_HEALTH_URL="http://127.0.0.1:${ACTIVE_PORT}/api/trading/actuator/health"
curl -fsS "$LOCAL_HEALTH_URL" >/dev/null || fail "local trading health failed: $LOCAL_HEALTH_URL"
ok "local trading health passed: $LOCAL_HEALTH_URL"

curl -fsS "$AGORA_MARKET_HEALTH_URL" >/dev/null || fail "AgoraMarket exchange-rate dependency health failed: $AGORA_MARKET_HEALTH_URL"
ok "AgoraMarket exchange-rate dependency health passed: $AGORA_MARKET_HEALTH_URL"

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

if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet nginx; then
  ok "nginx service is active"
else
  warn "nginx service is not active or systemctl is unavailable"
fi

ok "server verification complete"
