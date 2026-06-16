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
AGORA_MARKET_HEALTH_URL="${AGORA_MARKET_HEALTH_URL:-https://agoramarketapi.purrtechllc.com/api/actuator/health}"
PUBLIC_TRADING_HEALTH_URL="${PUBLIC_TRADING_HEALTH_URL:-}"
PUBLIC_TRADING_MCP_BLOCKED_URL="${PUBLIC_TRADING_MCP_BLOCKED_URL:-}"
PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL="${PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL:-}"
PUBLIC_TRADING_MCP_BLOCKED_STATUSES="${PUBLIC_TRADING_MCP_BLOCKED_STATUSES:-401 403 404 405}"
NGINX_CONF_GLOB="${NGINX_CONF_GLOB:-/etc/nginx/sites-enabled/*}"
INTERNAL_CLIENT_POM="${INTERNAL_CLIENT_POM:-/home/ubuntu/AgoraMarketAPI/internal-client/pom.xml}"
RUN_PREFLIGHT="${RUN_PREFLIGHT:-1}"
VERIFY_GIT_CURRENT="${VERIFY_GIT_CURRENT:-1}"
REQUIRE_NGINX_TRADING_PATH="${REQUIRE_NGINX_TRADING_PATH:-1}"
REQUIRE_NGINX_DEDICATED_API="${REQUIRE_NGINX_DEDICATED_API:-1}"
REQUIRE_NGINX_SERVICE="${REQUIRE_NGINX_SERVICE:-1}"
REQUIRE_DEPLOY_METADATA="${REQUIRE_DEPLOY_METADATA:-1}"
RUN_SCHEMA_BASELINE_COMPARE="${RUN_SCHEMA_BASELINE_COMPARE:-0}"
EXPECTED_AGORA_MARKET_BASE_URL="${EXPECTED_AGORA_MARKET_BASE_URL:-https://agoramarketapi.purrtechllc.com}"
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

verify_public_mcp_blocked() {
  local url="$1"
  local label="$2"
  [ -n "$url" ] || return 0

  local status
  status="$(curl -sS -o /dev/null -w '%{http_code}' \
    --max-time 30 \
    -H "Content-Type: application/json" \
    --data '{"jsonrpc":"2.0","id":"server-verify-public-mcp-blocked","method":"tools/list","params":{}}' \
    "$url" || true)"
  case " $PUBLIC_TRADING_MCP_BLOCKED_STATUSES " in
    *" $status "*)
      ok "$label is blocked: $url status=$status"
      ;;
    *)
      fail "$label must be blocked at $url; got HTTP $status expected one of: $PUBLIC_TRADING_MCP_BLOCKED_STATUSES"
      ;;
  esac
}

verify_nginx_mcp_block_bodies() {
  if ! awk '
    function brace_delta(line, copy, opens, closes) {
      copy = line
      opens = gsub(/\{/, "{", copy)
      copy = line
      closes = gsub(/\}/, "}", copy)
      return opens - closes
    }
    function finish_location() {
      if (location_kind == "dedicated") {
        dedicated_seen = 1
        if (!location_return_404 || location_proxy_pass) {
          dedicated_bad = 1
        }
      } else if (location_kind == "shared") {
        shared_seen = 1
        if (!location_return_404 || location_proxy_pass) {
          shared_bad = 1
        }
      }
      location_kind = ""
      location_depth = 0
      location_return_404 = 0
      location_proxy_pass = 0
    }
    function update_server_depth() {
      if (in_server) {
        server_depth += brace_delta($0)
        if (server_depth <= 0) {
          in_server = 0
          dedicated_server = 0
          server_depth = 0
        }
      }
    }
    /^[[:space:]]*server[[:space:]]*\{/ {
      in_server = 1
      dedicated_server = 0
      server_depth = 0
    }
    in_server && /^[[:space:]]*server_name[[:space:]]+agoratradingapi[.]purrtechllc[.]com;/ {
      dedicated_server = 1
    }
    location_kind != "" {
      if ($0 ~ /return[[:space:]]+404[[:space:]]*;/) {
        location_return_404 = 1
      }
      if ($0 ~ /proxy_pass[[:space:]]+/) {
        location_proxy_pass = 1
      }
      location_depth += brace_delta($0)
      if (location_depth <= 0) {
        finish_location()
      }
      update_server_depth()
      next
    }
    dedicated_server && /^[[:space:]]*location[[:space:]]*=[[:space:]]*\/api\/mcp[[:space:]]*\{/ {
      location_kind = "dedicated"
      location_depth = brace_delta($0)
      location_return_404 = ($0 ~ /return[[:space:]]+404[[:space:]]*;/)
      location_proxy_pass = ($0 ~ /proxy_pass[[:space:]]+/)
      if (location_depth <= 0) {
        finish_location()
      }
      update_server_depth()
      next
    }
    /^[[:space:]]*location[[:space:]]*=[[:space:]]*\/api\/trading\/mcp[[:space:]]*\{/ {
      location_kind = "shared"
      location_depth = brace_delta($0)
      location_return_404 = ($0 ~ /return[[:space:]]+404[[:space:]]*;/)
      location_proxy_pass = ($0 ~ /proxy_pass[[:space:]]+/)
      if (location_depth <= 0) {
        finish_location()
      }
      update_server_depth()
      next
    }
    { update_server_depth() }
    END {
      if (!dedicated_seen) {
        print "dedicated /api/mcp block missing" > "/dev/stderr"
        exit 11
      }
      if (!shared_seen) {
        print "shared /api/trading/mcp block missing" > "/dev/stderr"
        exit 12
      }
      if (dedicated_bad) {
        print "dedicated /api/mcp block must return 404 and must not proxy_pass" > "/dev/stderr"
        exit 13
      }
      if (shared_bad) {
        print "shared /api/trading/mcp block must return 404 and must not proxy_pass" > "/dev/stderr"
        exit 14
      }
    }
  ' $NGINX_CONF_GLOB; then
    fail "nginx public Trading MCP exact blocks must return 404 and must not proxy_pass under $NGINX_CONF_GLOB"
  fi
  ok "nginx public Trading MCP exact blocks return 404 with no proxy_pass"
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

classify_deployed_delta_path() {
  local path="$1"
  case "$path" in
    .gitattributes|.gitignore|AGENTS.md|INTERNAL_API_TODO.md|README.md|SERVICE_BOUNDARY.md|SPLIT_PROGRESS.md|docs/*|deploy.sh|scripts/deploy_ssh.ps1|scripts/install_nginx_path.sh|scripts/rewrite_nginx_trading_routes.awk|scripts/test_nginx_route_rewrite.ps1|scripts/check_server_runtime_log.sh|scripts/verify_local.ps1|scripts/verify_server.sh|scripts/verify_server_ssh.ps1|scripts/verify_split_acceptance_ssh.ps1)
      echo "docs-tooling"
      ;;
    *)
      echo "runtime"
      ;;
  esac
}

require_cmd bash
require_cmd awk
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
    git cat-file -e "${DEPLOYED_COMMIT}^{commit}" 2>/dev/null || fail "deployed app.commit ${DEPLOYED_COMMIT:-empty} is not a known git commit"
    mapfile -t deployed_delta < <(git diff --name-only "$DEPLOYED_COMMIT"..HEAD || true)
    runtime_delta=0
    docs_tooling_delta=0
    for delta_path in "${deployed_delta[@]}"; do
      case "$(classify_deployed_delta_path "$delta_path")" in
        docs-tooling) docs_tooling_delta=$((docs_tooling_delta + 1)) ;;
        *) runtime_delta=$((runtime_delta + 1)) ;;
      esac
    done
    if [ "$runtime_delta" -gt 0 ]; then
      fail "runtime files differ from deployed app.commit ${DEPLOYED_COMMIT:-empty}; deploy required before server verification can pass"
    fi
    ok "deployed app.commit differs from worktree HEAD only by docs/tooling files: deployed=$(git rev-parse --short "$DEPLOYED_COMMIT") head=$(git rev-parse --short HEAD) docs_tooling_files=$docs_tooling_delta"
  else
    ok "deployed app.commit matches worktree HEAD: $(git rev-parse --short HEAD)"
  fi
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
require_env_value SPRING_JPA_HIBERNATE_DDL_AUTO validate
require_env_value SPRING_FLYWAY_ENABLED true
require_env_value SPRING_FLYWAY_TABLE trading_flyway_schema_history
require_env_value SPRING_FLYWAY_BASELINE_ON_MIGRATE true
require_env_value SPRING_FLYWAY_BASELINE_VERSION 1

case "$(env_value SPRING_DATASOURCE_URL)" in
  jdbc:mysql://*/"$EXPECTED_TRADING_DATABASE"|jdbc:mysql://*/"$EXPECTED_TRADING_DATABASE"\?*) ;;
  *) fail "SPRING_DATASOURCE_URL must point at expected shared database: $EXPECTED_TRADING_DATABASE" ;;
esac
ok "SPRING_DATASOURCE_URL points at expected shared database: $EXPECTED_TRADING_DATABASE"

[ -f "$INTERNAL_CLIENT_POM" ] || fail "AgoraMarket internal-client pom missing: $INTERNAL_CLIENT_POM"
ok "AgoraMarket internal-client pom found"

if [ "$(env_value AGORA_MARKET_BASE_URL)" != "$EXPECTED_AGORA_MARKET_BASE_URL" ]; then
  fail "AGORA_MARKET_BASE_URL must point at stable AgoraMarketAPI dependency: expected $EXPECTED_AGORA_MARKET_BASE_URL"
fi
ok "AGORA_MARKET_BASE_URL points at stable AgoraMarketAPI dependency"

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

if [ "$ACTIVE_PORT" = "$PORT_A" ]; then
  INACTIVE_PORT="$PORT_B"
else
  INACTIVE_PORT="$PORT_A"
fi

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

INACTIVE_PORT_PIDS="$(lsof -ti ":$INACTIVE_PORT" 2>/dev/null || true)"
if [ -n "$INACTIVE_PORT_PIDS" ]; then
  fail "non-active blue-green port $INACTIVE_PORT still has listener pid(s): $INACTIVE_PORT_PIDS"
fi
ok "non-active blue-green port $INACTIVE_PORT has no listener"

LOCAL_HEALTH_URL="http://127.0.0.1:${ACTIVE_PORT}/api/actuator/health"
curl -fsS "$LOCAL_HEALTH_URL" >/dev/null || fail "local trading health failed: $LOCAL_HEALTH_URL"
ok "local trading health passed: $LOCAL_HEALTH_URL"

MCP_KEY="$(env_value TRADING_MCP_KEY)"
MCP_URL="http://127.0.0.1:${ACTIVE_PORT}/api/mcp"
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

verify_public_mcp_blocked "$PUBLIC_TRADING_MCP_BLOCKED_URL" "public dedicated Trading MCP"
verify_public_mcp_blocked "$PUBLIC_TRADING_CONTEXT_MCP_BLOCKED_URL" "public shared-host Trading MCP"

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

if ls $NGINX_CONF_GLOB >/dev/null 2>&1; then
  DEDICATED_API_PASS="proxy_pass[[:space:]]+http://127[.]0[.]0[.]1:${ACTIVE_PORT}/api/"
  DEDICATED_MCP_PASS="proxy_pass[[:space:]]+http://127[.]0[.]0[.]1:${ACTIVE_PORT}/api/trading/mcp"
  SHARED_TRADING_PASS="proxy_pass[[:space:]]+http://127[.]0[.]0[.]1:${ACTIVE_PORT}([[:space:];]|$)"
  if grep -RE "$DEDICATED_MCP_PASS" $NGINX_CONF_GLOB >/dev/null 2>&1; then
    fail "nginx dedicated host must not proxy public /api/mcp to Trading MCP under $NGINX_CONF_GLOB"
  fi
  verify_nginx_mcp_block_bodies
  if grep -RE "$DEDICATED_API_PASS" $NGINX_CONF_GLOB >/dev/null 2>&1 \
      && grep -RE "$SHARED_TRADING_PASS" $NGINX_CONF_GLOB >/dev/null 2>&1; then
    ok "nginx shared/dedicated trading upstreams point at active port $ACTIVE_PORT and public MCP is blocked"
  else
    if [ "$REQUIRE_NGINX_DEDICATED_API" = "1" ]; then
      fail "nginx shared/dedicated trading upstreams or public MCP block are not correct for active port $ACTIVE_PORT under $NGINX_CONF_GLOB"
    fi
    warn "nginx shared/dedicated trading upstreams or public MCP block are not correct for active port $ACTIVE_PORT under $NGINX_CONF_GLOB; REQUIRE_NGINX_DEDICATED_API=$REQUIRE_NGINX_DEDICATED_API"
  fi
else
  if [ "$REQUIRE_NGINX_DEDICATED_API" = "1" ]; then
    fail "nginx config glob has no matches for dedicated trading check: $NGINX_CONF_GLOB"
  fi
  warn "nginx config glob has no matches for dedicated trading check: $NGINX_CONF_GLOB; REQUIRE_NGINX_DEDICATED_API=$REQUIRE_NGINX_DEDICATED_API"
fi

if systemctl is-active --quiet nginx; then
  ok "nginx service is active"
elif [ "$REQUIRE_NGINX_SERVICE" = "1" ]; then
  fail "nginx service is not active"
else
  warn "nginx service is not active; REQUIRE_NGINX_SERVICE=$REQUIRE_NGINX_SERVICE"
fi

ok "server verification complete"
