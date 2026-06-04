#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
REPO_URL="${REPO_URL:-https://github.com/Redandan/agora-trading-api.git}"
BRANCH="${BRANCH:-main}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/.env.trading.secrets}"
AGORA_MARKET_HEALTH_URL="${AGORA_MARKET_HEALTH_URL:-http://127.0.0.1:8082/api/actuator/health}"
NGINX_CONF_GLOB="${NGINX_CONF_GLOB:-/etc/nginx/sites-enabled/*}"

fail() {
  echo "[server-bootstrap] FAIL: $*" >&2
  exit 1
}

warn() {
  echo "[server-bootstrap] WARN: $*" >&2
}

ok() {
  echo "[server-bootstrap] OK: $*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

require_cmd curl
require_cmd git
require_cmd java
require_cmd mvn

if [ -d "$APP_DIR/.git" ]; then
  ok "repo already exists: $APP_DIR"
  git -C "$APP_DIR" fetch origin "$BRANCH" --quiet
  ok "origin/$BRANCH is reachable"
elif [ -e "$APP_DIR" ]; then
  fail "$APP_DIR exists but is not a git worktree"
else
  ok "cloning $REPO_URL into $APP_DIR"
  git clone --branch "$BRANCH" "$REPO_URL" "$APP_DIR"
fi

cd "$APP_DIR"
git rev-parse --is-inside-work-tree >/dev/null
ok "current commit: $(git rev-parse --short HEAD)"

cat > .env.trading.secrets.example <<'EOF'
# Copy to /home/ubuntu/.env.trading.secrets and fill real values on the server.
# Do not commit or print the real secret file.
TRADING_ADMIN_KEY=
TRADING_MCP_KEY=
AGORA_MARKET_BASE_URL=http://127.0.0.1:8082
AGORA_MARKET_INTERNAL_API_KEY=
AGORA_MARKET_INTERNAL_TIMEOUT_MS=3000
PORT=8084
EOF
ok "wrote env template: $APP_DIR/.env.trading.secrets.example"

if [ -f "$ENV_FILE" ]; then
  ok "env file exists: $ENV_FILE"
else
  warn "env file missing: $ENV_FILE"
  warn "create it from $APP_DIR/.env.trading.secrets.example and fill secret values"
fi

curl -fsS "$AGORA_MARKET_HEALTH_URL" >/dev/null \
  && ok "AgoraMarket local health passed: $AGORA_MARKET_HEALTH_URL" \
  || warn "AgoraMarket local health failed: $AGORA_MARKET_HEALTH_URL"

if ls $NGINX_CONF_GLOB >/dev/null 2>&1; then
  if grep -R "location[[:space:]]*/api/trading/" $NGINX_CONF_GLOB >/dev/null 2>&1; then
    ok "nginx /api/trading/ location found"
  else
    warn "nginx /api/trading/ location not found under $NGINX_CONF_GLOB"
  fi
else
  warn "nginx config glob has no matches: $NGINX_CONF_GLOB"
fi

ok "bootstrap preflight complete"
