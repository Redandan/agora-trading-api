#!/usr/bin/env bash
set -euo pipefail

APP_NAME="${APP_NAME:-agora-trading-api}"
APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
BRANCH="${BRANCH:-main}"
PORT_A="${PORT_A:-8084}"
PORT_B="${PORT_B:-8085}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g -Duser.timezone=UTC}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/.env.trading.secrets}"

cd "$APP_DIR"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

CURRENT_PORT=""
if [ -f app.port ]; then
  CURRENT_PORT="$(cat app.port)"
fi

if [ "$CURRENT_PORT" = "$PORT_A" ]; then
  NEW_PORT="$PORT_B"
else
  NEW_PORT="$PORT_A"
fi

echo "[deploy] $APP_NAME blue-green: ${CURRENT_PORT:-none} -> $NEW_PORT"

git fetch origin "$BRANCH" --quiet
git reset --hard "origin/$BRANCH"

mvn clean package -DskipTests -q

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
    exit 1
  fi
  sleep 1
done

if ! curl -fsS "http://127.0.0.1:${NEW_PORT}/api/trading/actuator/health" >/dev/null; then
  echo "[deploy] health check timed out; tailing log" >&2
  tail -80 "$RUN_LOG" >&2 || true
  exit 1
fi

echo "$NEW_PORT" > app.port
echo "$NEW_PID" > app.pid

if [ -n "$CURRENT_PORT" ] && [ -f "app.pid.$CURRENT_PORT" ]; then
  OLD_PID="$(cat "app.pid.$CURRENT_PORT")"
  echo "[deploy] draining old instance PID=$OLD_PID port=$CURRENT_PORT"
  sleep "${DRAIN_SECONDS:-30}"
  kill "$OLD_PID" 2>/dev/null || true
  rm -f "app.pid.$CURRENT_PORT"
fi

echo "[deploy] complete: $APP_NAME running on port $NEW_PORT"
