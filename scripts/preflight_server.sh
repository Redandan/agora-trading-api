#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/.env.trading.secrets}"
INTERNAL_CLIENT_POM="${INTERNAL_CLIENT_POM:-/home/ubuntu/AgoraMarketAPI/internal-client/pom.xml}"
AGORA_MARKET_HEALTH_URL="${AGORA_MARKET_HEALTH_URL:-http://127.0.0.1:8080/api/actuator/health}"
NGINX_CONF_GLOB="${NGINX_CONF_GLOB:-/etc/nginx/sites-enabled/*}"
EXPECTED_AGORA_MARKET_BASE_URL="${EXPECTED_AGORA_MARKET_BASE_URL:-http://127.0.0.1:8080}"
REQUIRE_AGORA_MARKET_HEALTH="${REQUIRE_AGORA_MARKET_HEALTH:-1}"
EXPECTED_TRADING_DATABASE="${EXPECTED_TRADING_DATABASE:-agora_market}"

fail() {
  echo "[server-preflight] FAIL: $*" >&2
  exit 1
}

warn() {
  echo "[server-preflight] WARN: $*" >&2
}

ok() {
  echo "[server-preflight] OK: $*"
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
require_cmd awk
require_cmd curl
require_cmd date
require_cmd env
require_cmd grep
require_cmd git
require_cmd java
require_cmd lsof
require_cmd ls
require_cmd mktemp
require_cmd mvn
require_cmd nohup
require_cmd ps
require_cmd seq
require_cmd sleep
require_cmd sudo
require_cmd tail

[ -d "$APP_DIR" ] || fail "app dir missing: $APP_DIR"
cd "$APP_DIR"
ok "app dir exists: $APP_DIR"

git rev-parse --is-inside-work-tree >/dev/null || fail "$APP_DIR is not a git worktree"
ok "git worktree detected: $(git rev-parse --short HEAD)"

git diff --quiet || fail "$APP_DIR has unstaged changes"
git diff --cached --quiet || fail "$APP_DIR has staged changes"
ok "git worktree is clean"

bash -n deploy.sh scripts/*.sh
ok "shell syntax passed: deploy.sh scripts/*.sh"

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

if [ "$(env_value AGORA_MARKET_BASE_URL)" != "$EXPECTED_AGORA_MARKET_BASE_URL" ]; then
  fail "AGORA_MARKET_BASE_URL must point at local AgoraMarketAPI dependency: expected $EXPECTED_AGORA_MARKET_BASE_URL"
fi
ok "AGORA_MARKET_BASE_URL points at local AgoraMarketAPI dependency"

[ -f "$INTERNAL_CLIENT_POM" ] || fail "AgoraMarket internal-client pom missing: $INTERNAL_CLIENT_POM"
ok "AgoraMarket internal-client pom found"

if curl -fsS "$AGORA_MARKET_HEALTH_URL" >/dev/null; then
  ok "AgoraMarket exchange-rate dependency local health passed: $AGORA_MARKET_HEALTH_URL"
elif [ "$REQUIRE_AGORA_MARKET_HEALTH" = "1" ]; then
  fail "AgoraMarket exchange-rate dependency local health failed: $AGORA_MARKET_HEALTH_URL"
else
  warn "AgoraMarket exchange-rate dependency local health failed: $AGORA_MARKET_HEALTH_URL; REQUIRE_AGORA_MARKET_HEALTH=$REQUIRE_AGORA_MARKET_HEALTH"
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

ok "server preflight complete; no deploy or runtime mutation performed"
