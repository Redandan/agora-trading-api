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
INTERNAL_CLIENT_POM="${INTERNAL_CLIENT_POM:-/home/ubuntu/AgoraMarketAPI/internal-client/pom.xml}"

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

require_env_key() {
  local key="$1"
  local value="${!key:-}"
  if [ -z "$value" ]; then
    echo "[deploy] required env key missing or empty: $key" >&2
    exit 1
  fi
  echo "[deploy] required env key present: $key"
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

if [ ! -f "$ENV_FILE" ]; then
  echo "[deploy] env file missing: $ENV_FILE" >&2
  exit 1
fi
load_env_file "$ENV_FILE"

require_env_key TRADING_ADMIN_KEY
require_env_key TRADING_MCP_KEY
require_env_key AGORA_MARKET_BASE_URL
require_env_key AGORA_MARKET_INTERNAL_API_KEY
require_env_key SPRING_DATASOURCE_URL
require_env_key SPRING_DATASOURCE_USERNAME
require_env_key SPRING_DATASOURCE_PASSWORD

CURRENT_PORT=""
if [ -f app.port ]; then
  CURRENT_PORT="$(cat app.port)"
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
  if curl -fsS "http://127.0.0.1:${NEW_PORT}/api/trading/actuator/health" >/dev/null; then
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

if ! curl -fsS "http://127.0.0.1:${NEW_PORT}/api/trading/actuator/health" >/dev/null; then
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
  awk -v port="$NEW_PORT" '
    /^[[:space:]]*location[[:space:]]+\/api\/trading\/[[:space:]]*\{/ {
      in_trading = 1
    }
    in_trading {
      gsub(/127\.0\.0\.1:(8084|8085)/, "127.0.0.1:" port)
    }
    { print }
    in_trading && /^[[:space:]]*}/ {
      in_trading = 0
    }
  ' "$NGINX_CONF" > "$tmp_nginx"

  sudo cp "$NGINX_CONF" "$NGINX_CONF.bak-trading"
  sudo cp "$tmp_nginx" "$NGINX_CONF"
  rm -f "$tmp_nginx"
  if ! sudo nginx -t >/dev/null 2>&1; then
    echo "[deploy] nginx config invalid after trading upstream swap; rolling back" >&2
    sudo mv "$NGINX_CONF.bak-trading" "$NGINX_CONF"
    cleanup_new_instance
    exit 1
  fi
  sudo rm -f "$NGINX_CONF.bak-trading"
  sudo systemctl reload nginx
  echo "[deploy] nginx /api/trading/ switched to port $NEW_PORT"
fi

if [ -n "$CURRENT_PORT" ] && [ -f "app.pid.$CURRENT_PORT" ]; then
  OLD_PID="$(cat "app.pid.$CURRENT_PORT")"
  echo "[deploy] draining old instance PID=$OLD_PID port=$CURRENT_PORT"
  sleep "${DRAIN_SECONDS:-30}"
  kill "$OLD_PID" 2>/dev/null || true
  rm -f "app.pid.$CURRENT_PORT"
fi

echo "$NEW_PORT" > app.port
echo "$NEW_PID" > app.pid

echo "[deploy] complete: $APP_NAME running on port $NEW_PORT"
