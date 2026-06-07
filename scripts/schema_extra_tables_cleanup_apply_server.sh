#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/.env.trading.secrets}"
OUTPUT_DIR="${OUTPUT_DIR:-$APP_DIR/target/schema-baseline}"
EXTRA_TABLES_FILE="${EXTRA_TABLES_FILE:-$OUTPUT_DIR/extra-in-db.txt}"
COUNTS_FILE="${COUNTS_FILE:-$OUTPUT_DIR/extra-table-row-counts.tsv}"
EXPECTED_TRADING_DATABASE="${EXPECTED_TRADING_DATABASE:-agora_trading}"
APPLY_SCHEMA_EXTRA_TABLE_CLEANUP="${APPLY_SCHEMA_EXTRA_TABLE_CLEANUP:-0}"
BACKUP_DIR="${BACKUP_DIR:-/home/ubuntu/backups/agora-trading-api-schema-cleanup}"

fail() {
  echo "[schema-cleanup-apply] FAIL: $*" >&2
  exit 1
}

ok() {
  echo "[schema-cleanup-apply] OK: $*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

read_env_key() {
  local key="$1"
  local line
  [ -f "$ENV_FILE" ] || fail "env file missing: $ENV_FILE"
  line="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)"
  if [ -z "$line" ] || ! printf '%s\n' "$line" | grep -Eq "^[[:space:]]*${key}=[^[:space:]#]"; then
    fail "missing or empty $key in $ENV_FILE"
  fi
  printf '%s\n' "${line#*=}"
}

require_cmd awk
require_cmd date
require_cmd grep
require_cmd mkdir
require_cmd mysql
require_cmd mysqldump
require_cmd tail
require_cmd tr

[ -d "$APP_DIR" ] || fail "app dir missing: $APP_DIR"
[ -f "$EXTRA_TABLES_FILE" ] || fail "extra table list missing: $EXTRA_TABLES_FILE; run RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh first"

SPRING_DATASOURCE_URL="$(read_env_key SPRING_DATASOURCE_URL)"
SPRING_DATASOURCE_USERNAME="$(read_env_key SPRING_DATASOURCE_USERNAME)"
SPRING_DATASOURCE_PASSWORD="$(read_env_key SPRING_DATASOURCE_PASSWORD)"

case "$SPRING_DATASOURCE_URL" in
  jdbc:mysql://*) ;;
  *) fail "SPRING_DATASOURCE_URL must be a jdbc:mysql URL" ;;
esac

jdbc_without_prefix="${SPRING_DATASOURCE_URL#jdbc:mysql://}"
jdbc_without_query="${jdbc_without_prefix%%\?*}"
host_port="${jdbc_without_query%%/*}"
database="${jdbc_without_query#*/}"

[ -n "$database" ] && [ "$database" != "$jdbc_without_query" ] || fail "database name missing in SPRING_DATASOURCE_URL"
if [ "$database" != "$EXPECTED_TRADING_DATABASE" ]; then
  fail "SPRING_DATASOURCE_URL must point at standalone trading database: $EXPECTED_TRADING_DATABASE"
fi
ok "SPRING_DATASOURCE_URL points at standalone trading database: $EXPECTED_TRADING_DATABASE"

if printf '%s\n' "$host_port" | grep -q ':'; then
  host="${host_port%%:*}"
  port="${host_port##*:}"
else
  host="$host_port"
  port="3306"
fi

[ -n "$host" ] || fail "database host missing in SPRING_DATASOURCE_URL"
case "$port" in
  ''|*[!0-9]*) fail "database port is invalid in SPRING_DATASOURCE_URL: $port" ;;
esac

cd "$APP_DIR"
bash scripts/schema_extra_tables_cleanup_plan_server.sh

[ -f "$COUNTS_FILE" ] || fail "row-count file missing after planner run: $COUNTS_FILE"
non_empty="$(awk 'NR > 1 && $2 != 0 { count++ } END { print count + 0 }' "$COUNTS_FILE")"
if [ "$non_empty" != "0" ]; then
  fail "$non_empty extra table(s) are not empty; refusing cleanup"
fi

table_count="$(awk 'NF { count++ } END { print count + 0 }' "$EXTRA_TABLES_FILE")"
if [ "$table_count" = "0" ]; then
  ok "no extra tables listed; nothing to clean"
  exit 0
fi

mkdir -p "$BACKUP_DIR"
backup_file="$BACKUP_DIR/${database}-before-extra-table-cleanup-$(date -u +%Y%m%dT%H%M%SZ).sql"

echo "[schema-cleanup-apply] backup target: $backup_file"
MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD" mysqldump \
  --single-transaction \
  --routines \
  --triggers \
  -h "$host" \
  -P "$port" \
  -u "$SPRING_DATASOURCE_USERNAME" \
  "$database" > "$backup_file"
ok "backup completed"

if [ "$APPLY_SCHEMA_EXTRA_TABLE_CLEANUP" != "1" ]; then
  ok "dry-run complete; set APPLY_SCHEMA_EXTRA_TABLE_CLEANUP=1 to drop the empty extra tables after review"
  exit 0
fi

while IFS= read -r table || [ -n "$table" ]; do
  table="$(printf '%s' "$table" | tr -d '[:space:]')"
  [ -n "$table" ] || continue
  case "$table" in
    *[!A-Za-z0-9_]*)
      fail "unsafe table name in $EXTRA_TABLES_FILE: $table"
      ;;
  esac
  echo "[schema-cleanup-apply] dropping empty extra table: $table"
  MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD" mysql \
    -h "$host" \
    -P "$port" \
    -u "$SPRING_DATASOURCE_USERNAME" \
    "$database" \
    -e "DROP TABLE \`$table\`;"
done < "$EXTRA_TABLES_FILE"

ok "empty extra table cleanup applied; re-run RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh"
