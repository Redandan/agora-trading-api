#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/.env.trading.secrets}"
INTERNAL_CLIENT_POM="${INTERNAL_CLIENT_POM:-/home/ubuntu/AgoraMarketAPI/internal-client/pom.xml}"
AGORA_MARKET_HEALTH_URL="${AGORA_MARKET_HEALTH_URL:-http://127.0.0.1:8082/api/actuator/health}"
NGINX_CONF_GLOB="${NGINX_CONF_GLOB:-/etc/nginx/sites-enabled/*}"

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

require_cmd bash
require_cmd curl
require_cmd git
require_cmd java
require_cmd mvn

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

require_env_key TRADING_ADMIN_KEY
require_env_key TRADING_MCP_KEY
require_env_key AGORA_MARKET_BASE_URL
require_env_key AGORA_MARKET_INTERNAL_API_KEY
require_env_key SPRING_DATASOURCE_URL
require_env_key SPRING_DATASOURCE_USERNAME
require_env_key SPRING_DATASOURCE_PASSWORD

[ -f "$INTERNAL_CLIENT_POM" ] || fail "AgoraMarket internal-client pom missing: $INTERNAL_CLIENT_POM"
ok "AgoraMarket internal-client pom found"

curl -fsS "$AGORA_MARKET_HEALTH_URL" >/dev/null \
  && ok "AgoraMarket exchange-rate dependency local health passed: $AGORA_MARKET_HEALTH_URL" \
  || warn "AgoraMarket exchange-rate dependency local health failed: $AGORA_MARKET_HEALTH_URL"

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
