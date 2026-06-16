#!/usr/bin/env bash
set -euo pipefail

APP_NAME="${APP_NAME:-agora-trading-api}"
APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
BRANCH="${BRANCH:-main}"
PORT_A="${PORT_A:-8084}"
PORT_B="${PORT_B:-8085}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g -Duser.timezone=UTC}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/.env.trading.secrets}"
NGINX_CONF="${NGINX_CONF:-/etc/nginx/sites-enabled/agoramarketapi}"
UPDATE_NGINX="${UPDATE_NGINX:-1}"
RUN_POST_DEPLOY_VERIFY="${RUN_POST_DEPLOY_VERIFY:-1}"
POST_DEPLOY_VERIFIED=0
DEFAULT_PUBLIC_TRADING_HEALTH_URL="${DEFAULT_PUBLIC_TRADING_HEALTH_URL:-https://agoratradingapi.purrtechllc.com/api/actuator/health}"
DEFAULT_PUBLIC_TRADING_MCP_BLOCKED_URL="${DEFAULT_PUBLIC_TRADING_MCP_BLOCKED_URL:-https://agoratradingapi.purrtechllc.com/api/mcp}"
DEFAULT_PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL="${DEFAULT_PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL:-https://agoramarketapi.purrtechllc.com/api/trading/mcp}"
INTERNAL_CLIENT_POM="${INTERNAL_CLIENT_POM:-/home/ubuntu/AgoraMarketAPI/internal-client/pom.xml}"
EXPECTED_AGORA_MARKET_BASE_URL="${EXPECTED_AGORA_MARKET_BASE_URL:-https://agoramarketapi.purrtechllc.com}"
AGORA_MARKET_HEALTH_URL="${AGORA_MARKET_HEALTH_URL:-https://agoramarketapi.purrtechllc.com/api/actuator/health}"
EXPECTED_TRADING_DATABASE="${EXPECTED_TRADING_DATABASE:-agora_market}"

cd "$APP_DIR"

load_env_file() {
  local file="$1"
  local line key value
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      ''|\#*) continue ;;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in
      [A-Za-z_][A-Za-z0-9_]*) export "$key=$value" ;;
      *) echo "[deploy] ignoring invalid env key in $file: $key" >&2 ;;
    esac
  done < "$file"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "[deploy] missing command: $1" >&2
    exit 1
  }
}

require_env_key() {
  local key="$1"
  local value="${!key:-}"
  if [ -z "$value" ]; then
    echo "[deploy] required env key missing or empty: $key" >&2
    exit 1
  fi
  echo "[deploy] required env key present: $key"
}

require_env_value() {
  local key="$1"
  local expected="$2"
  require_env_key "$key"
  local value="${!key:-}"
  if [ "$value" != "$expected" ]; then
    echo "[deploy] $key must be $expected" >&2
    exit 1
  fi
  echo "[deploy] $key matches expected value: $expected"
}

cleanup_new_instance() {
  if [ -n "${NEW_PID:-}" ]; then
    kill "$NEW_PID" 2>/dev/null || true
  fi
  if [ -n "${NEW_PORT:-}" ]; then
    rm -f "app.pid.$NEW_PORT"
  fi
  if [ -n "${tmp_nginx:-}" ]; then
    rm -f "$tmp_nginx"
  fi
}

rollback_after_failed_verify() {
  echo "[deploy] post-deploy verification failed; rolling back active metadata" >&2

  if [ -n "${CURRENT_PORT:-}" ] && [ -f "app.pid.$CURRENT_PORT" ]; then
    echo "$CURRENT_PORT" > app.port
    cat "app.pid.$CURRENT_PORT" > app.pid
  else
    rm -f app.port app.pid
  fi

  if [ -n "${PREVIOUS_COMMIT:-}" ]; then
    printf '%s\n' "$PREVIOUS_COMMIT" > app.commit
  else
    rm -f app.commit
  fi

  if [ "${UPDATE_NGINX:-0}" = "1" ] && [ -f "$NGINX_CONF.bak-trading" ]; then
    echo "[deploy] restoring nginx trading upstream after failed verification" >&2
    sudo mv "$NGINX_CONF.bak-trading" "$NGINX_CONF"
    if sudo nginx -t >/dev/null 2>&1; then
      sudo systemctl reload nginx
    else
      echo "[deploy] nginx rollback config failed validation; manual intervention required" >&2
    fi
  fi

  cleanup_new_instance
}

require_cmd bash
require_cmd cat
require_cmd curl
require_cmd date
require_cmd env
require_cmd grep
require_cmd git
require_cmd java
require_cmd kill
require_cmd lsof
require_cmd mkdir
require_cmd mvn
require_cmd nohup
require_cmd ps
require_cmd rm
require_cmd seq
require_cmd sleep
require_cmd tail
if [ "$UPDATE_NGINX" = "1" ]; then
  require_cmd awk
  require_cmd cp
  require_cmd mktemp
  require_cmd mv
  require_cmd nginx
  require_cmd sudo
  require_cmd systemctl
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "[deploy] env file missing: $ENV_FILE" >&2
  exit 1
fi
load_env_file "$ENV_FILE"

require_env_key TRADING_MCP_KEY
require_env_key AGORA_MARKET_BASE_URL
require_env_key AGORA_MARKET_INTERNAL_API_KEY
require_env_value AGORA_MARKET_INTERNAL_TIMEOUT_MS 3000
require_env_key SPRING_DATASOURCE_URL
require_env_key SPRING_DATASOURCE_USERNAME
require_env_key SPRING_DATASOURCE_PASSWORD
require_env_value SPRING_JPA_HIBERNATE_DDL_AUTO validate
require_env_value SPRING_FLYWAY_ENABLED true
require_env_value SPRING_FLYWAY_TABLE trading_flyway_schema_history
require_env_value SPRING_FLYWAY_BASELINE_ON_MIGRATE true
require_env_value SPRING_FLYWAY_BASELINE_VERSION 1

case "$SPRING_DATASOURCE_URL" in
  jdbc:mysql://*/"$EXPECTED_TRADING_DATABASE"|jdbc:mysql://*/"$EXPECTED_TRADING_DATABASE"\?*) ;;
  *)
    echo "[deploy] SPRING_DATASOURCE_URL must point at expected shared database: $EXPECTED_TRADING_DATABASE" >&2
    exit 1
    ;;
esac
echo "[deploy] SPRING_DATASOURCE_URL points at expected shared database: $EXPECTED_TRADING_DATABASE"

if [ "$AGORA_MARKET_BASE_URL" != "$EXPECTED_AGORA_MARKET_BASE_URL" ]; then
  echo "[deploy] AGORA_MARKET_BASE_URL must point at stable AgoraMarketAPI dependency: expected $EXPECTED_AGORA_MARKET_BASE_URL" >&2
  exit 1
fi
echo "[deploy] AGORA_MARKET_BASE_URL points at stable AgoraMarketAPI dependency"

curl -fsS "$AGORA_MARKET_HEALTH_URL" >/dev/null || {
  echo "[deploy] AgoraMarket exchange-rate dependency health failed: $AGORA_MARKET_HEALTH_URL" >&2
  exit 1
}
echo "[deploy] AgoraMarket exchange-rate dependency health passed: $AGORA_MARKET_HEALTH_URL"

CURRENT_PORT=""
if [ -f app.port ]; then
  CURRENT_PORT="$(cat app.port)"
fi
PREVIOUS_COMMIT=""
if [ -f app.commit ]; then
  PREVIOUS_COMMIT="$(cat app.commit)"
fi

case "$CURRENT_PORT" in
  ""|"$PORT_A"|"$PORT_B") ;;
  *)
    echo "[deploy] invalid app.port value: $CURRENT_PORT (expected $PORT_A or $PORT_B)" >&2
    exit 1
    ;;
esac

if [ "$CURRENT_PORT" = "$PORT_A" ]; then
  NEW_PORT="$PORT_B"
else
  NEW_PORT="$PORT_A"
fi

echo "[deploy] $APP_NAME blue-green: ${CURRENT_PORT:-none} -> $NEW_PORT"

git rev-parse --is-inside-work-tree >/dev/null || {
  echo "[deploy] $APP_DIR is not a git worktree" >&2
  exit 1
}
git diff --quiet || {
  echo "[deploy] $APP_DIR has unstaged changes; refusing to overwrite during deploy" >&2
  exit 1
}
git diff --cached --quiet || {
  echo "[deploy] $APP_DIR has staged changes; refusing to overwrite during deploy" >&2
  exit 1
}

git fetch origin "$BRANCH" --quiet
git reset --hard "origin/$BRANCH"

if [ ! -f "$INTERNAL_CLIENT_POM" ]; then
  echo "[deploy] AgoraMarket internal-client pom missing: $INTERNAL_CLIENT_POM" >&2
  exit 1
fi
echo "[deploy] installing AgoraMarket internal-client SDK"
mvn -f "$INTERNAL_CLIENT_POM" install -DskipTests -q

mvn clean package -DskipTests -q

PIDS_ON_NEW_PORT="$(lsof -ti ":$NEW_PORT" 2>/dev/null || true)"
if [ -n "$PIDS_ON_NEW_PORT" ]; then
  for pid in $PIDS_ON_NEW_PORT; do
    if ps -p "$pid" -o args= 2>/dev/null | grep -q "agora-trading-api"; then
      echo "[deploy] killing stale $APP_NAME PID=$pid on port=$NEW_PORT"
      kill "$pid" 2>/dev/null || true
      sleep 2
    else
      echo "[deploy] port $NEW_PORT is held by non-$APP_NAME PID=$pid" >&2
      exit 1
    fi
  done
fi

mkdir -p logs/runs
RUN_LOG="logs/runs/app-$(date -u +%Y%m%dT%H%M%SZ)-port${NEW_PORT}.log"

PORT="$NEW_PORT" nohup java $JAVA_OPTS -jar target/agora-trading-api-1.0-SNAPSHOT.jar > "$RUN_LOG" 2>&1 &
NEW_PID="$!"
echo "$NEW_PID" > "app.pid.$NEW_PORT"
echo "[deploy] new instance PID=$NEW_PID port=$NEW_PORT log=$RUN_LOG"

for attempt in $(seq 1 120); do
  if curl -fsS "http://127.0.0.1:${NEW_PORT}/api/actuator/health" >/dev/null 2>&1; then
    echo "[deploy] READY in ${attempt}s"
    break
  fi
  if ! kill -0 "$NEW_PID" 2>/dev/null; then
    echo "[deploy] new instance exited before ready; tailing log" >&2
    tail -80 "$RUN_LOG" >&2 || true
    cleanup_new_instance
    exit 1
  fi
  sleep 1
done

if ! curl -fsS "http://127.0.0.1:${NEW_PORT}/api/actuator/health" >/dev/null 2>&1; then
  echo "[deploy] health check timed out; tailing log" >&2
  tail -80 "$RUN_LOG" >&2 || true
  cleanup_new_instance
  exit 1
fi

if [ "$UPDATE_NGINX" = "1" ]; then
  if [ ! -f "$NGINX_CONF" ]; then
    echo "[deploy] nginx config missing: $NGINX_CONF" >&2
    cleanup_new_instance
    exit 1
  fi
  if ! sudo grep -q "location[[:space:]]*/api/trading/" "$NGINX_CONF"; then
    echo "[deploy] nginx /api/trading/ location missing in $NGINX_CONF" >&2
    cleanup_new_instance
    exit 1
  fi

  tmp_nginx="$(mktemp)"
  awk -v port="$NEW_PORT" -f scripts/rewrite_nginx_trading_routes.awk "$NGINX_CONF" > "$tmp_nginx"

  sudo cp "$NGINX_CONF" "$NGINX_CONF.bak-trading"
  sudo cp "$tmp_nginx" "$NGINX_CONF"
  rm -f "$tmp_nginx"
  if ! sudo nginx -t >/dev/null 2>&1; then
    echo "[deploy] nginx config invalid after trading upstream swap; rolling back" >&2
    sudo mv "$NGINX_CONF.bak-trading" "$NGINX_CONF"
    cleanup_new_instance
    exit 1
  fi
  sudo systemctl reload nginx
  echo "[deploy] nginx /api/trading/ switched to port $NEW_PORT"
fi

echo "$NEW_PORT" > app.port
echo "$NEW_PID" > app.pid
git rev-parse HEAD > app.commit

if [ "$RUN_POST_DEPLOY_VERIFY" = "1" ]; then
  VERIFY_SCRIPT="$APP_DIR/scripts/verify_server.sh"
  if [ ! -f "$VERIFY_SCRIPT" ]; then
    echo "[deploy] post-deploy verifier missing: $VERIFY_SCRIPT" >&2
    rollback_after_failed_verify
    exit 1
  fi
  echo "[deploy] running post-deploy server verification"
  VERIFY_ENV=(
    APP_DIR="$APP_DIR"
    ENV_FILE="$ENV_FILE"
    PORT_A="$PORT_A"
    PORT_B="$PORT_B"
    INTERNAL_CLIENT_POM="$INTERNAL_CLIENT_POM"
    AGORA_MARKET_HEALTH_URL="$AGORA_MARKET_HEALTH_URL"
    EXPECTED_AGORA_MARKET_BASE_URL="$EXPECTED_AGORA_MARKET_BASE_URL"
    EXPECTED_TRADING_DATABASE="$EXPECTED_TRADING_DATABASE"
    NGINX_CONF_GLOB="$NGINX_CONF"
    RUN_SCHEMA_BASELINE_COMPARE="${RUN_SCHEMA_BASELINE_COMPARE:-0}"
    RUN_PREFLIGHT=0
    ALLOW_INACTIVE_PORT_LISTENER=1
  )
  if [ "$UPDATE_NGINX" = "1" ]; then
    if ! env "${VERIFY_ENV[@]}" \
        PUBLIC_TRADING_HEALTH_URL="${PUBLIC_TRADING_HEALTH_URL:-$DEFAULT_PUBLIC_TRADING_HEALTH_URL}" \
        PUBLIC_TRADING_MCP_BLOCKED_URL="${PUBLIC_TRADING_MCP_BLOCKED_URL:-$DEFAULT_PUBLIC_TRADING_MCP_BLOCKED_URL}" \
        PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL="${PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL:-$DEFAULT_PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL}" \
        bash "$VERIFY_SCRIPT"; then
      rollback_after_failed_verify
      exit 1
    fi
  else
    if ! env "${VERIFY_ENV[@]}" bash "$VERIFY_SCRIPT"; then
      rollback_after_failed_verify
      exit 1
    fi
  fi
  POST_DEPLOY_VERIFIED=1
else
  echo "[deploy] post-deploy server verification skipped: RUN_POST_DEPLOY_VERIFY=$RUN_POST_DEPLOY_VERIFY"
fi

if [ "$UPDATE_NGINX" = "1" ] && [ "$POST_DEPLOY_VERIFIED" = "1" ]; then
  sudo rm -f "$NGINX_CONF.bak-trading"
elif [ "$UPDATE_NGINX" = "1" ] && [ -f "$NGINX_CONF.bak-trading" ]; then
  echo "[deploy] keeping nginx backup because post-deploy verification was not proven: $NGINX_CONF.bak-trading" >&2
fi

# Keep the previous instance alive until post-deploy verification has proven the
# new active metadata, nginx path, and health checks.
if [ "$POST_DEPLOY_VERIFIED" = "1" ] && [ -n "$CURRENT_PORT" ] && [ -f "app.pid.$CURRENT_PORT" ]; then
  OLD_PID="$(cat "app.pid.$CURRENT_PORT")"
  echo "[deploy] draining old instance after verification PID=$OLD_PID port=$CURRENT_PORT"
  sleep "${DRAIN_SECONDS:-30}"
  kill "$OLD_PID" 2>/dev/null || true
  rm -f "app.pid.$CURRENT_PORT"
elif [ -n "$CURRENT_PORT" ] && [ -f "app.pid.$CURRENT_PORT" ]; then
  echo "[deploy] keeping old instance because post-deploy verification was not proven PID=$(cat "app.pid.$CURRENT_PORT") port=$CURRENT_PORT" >&2
fi

if [ "$RUN_POST_DEPLOY_VERIFY" = "1" ] && [ "$POST_DEPLOY_VERIFIED" = "1" ]; then
  echo "[deploy] running strict post-drain server verification"
  STRICT_VERIFY_ENV=(
    APP_DIR="$APP_DIR"
    ENV_FILE="$ENV_FILE"
    PORT_A="$PORT_A"
    PORT_B="$PORT_B"
    INTERNAL_CLIENT_POM="$INTERNAL_CLIENT_POM"
    AGORA_MARKET_HEALTH_URL="$AGORA_MARKET_HEALTH_URL"
    EXPECTED_AGORA_MARKET_BASE_URL="$EXPECTED_AGORA_MARKET_BASE_URL"
    EXPECTED_TRADING_DATABASE="$EXPECTED_TRADING_DATABASE"
    NGINX_CONF_GLOB="$NGINX_CONF"
    RUN_SCHEMA_BASELINE_COMPARE="${RUN_SCHEMA_BASELINE_COMPARE:-0}"
    RUN_PREFLIGHT=0
  )
  if [ "$UPDATE_NGINX" = "1" ]; then
    env "${STRICT_VERIFY_ENV[@]}" \
      PUBLIC_TRADING_HEALTH_URL="${PUBLIC_TRADING_HEALTH_URL:-$DEFAULT_PUBLIC_TRADING_HEALTH_URL}" \
      PUBLIC_TRADING_MCP_BLOCKED_URL="${PUBLIC_TRADING_MCP_BLOCKED_URL:-$DEFAULT_PUBLIC_TRADING_MCP_BLOCKED_URL}" \
      PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL="${PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL:-$DEFAULT_PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL}" \
      bash "$VERIFY_SCRIPT"
  else
    env "${STRICT_VERIFY_ENV[@]}" bash "$VERIFY_SCRIPT"
  fi
fi

echo "[deploy] complete: $APP_NAME running on port $NEW_PORT"
