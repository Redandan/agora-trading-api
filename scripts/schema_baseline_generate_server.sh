#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/.env.trading.secrets}"
OUTPUT_DIR="${OUTPUT_DIR:-$APP_DIR/target/schema-baseline}"
MIGRATION_DIR="${MIGRATION_DIR:-$APP_DIR/src/main/resources/db/migration}"
BASELINE_FILE="${BASELINE_FILE:-$MIGRATION_DIR/V1__baseline.sql}"
EXPECTED_TRADING_DATABASE="${EXPECTED_TRADING_DATABASE:-agora_market}"
SCHEMA_COMPARE_MODE="${SCHEMA_COMPARE_MODE:-shared}"

fail() {
  echo "[schema-baseline-generate] FAIL: $*" >&2
  exit 1
}

ok() {
  echo "[schema-baseline-generate] OK: $*"
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

require_cmd bash
require_cmd cat
require_cmd date
require_cmd grep
require_cmd mkdir
require_cmd mv
require_cmd mysqldump
require_cmd tail
require_cmd tr
require_cmd wc

[ -d "$APP_DIR" ] || fail "app dir missing: $APP_DIR"
cd "$APP_DIR"

case "$SCHEMA_COMPARE_MODE" in
  shared) ;;
  standalone) fail "baseline generation is currently guarded for shared mode only; use the existing standalone cleanup workflow first" ;;
  *) fail "SCHEMA_COMPARE_MODE must be shared or standalone" ;;
esac

RUN_SCHEMA_BASELINE_COMPARE=1 \
SCHEMA_COMPARE_MODE="$SCHEMA_COMPARE_MODE" \
EXPECTED_TRADING_DATABASE="$EXPECTED_TRADING_DATABASE" \
APP_DIR="$APP_DIR" \
ENV_FILE="$ENV_FILE" \
OUTPUT_DIR="$OUTPUT_DIR" \
  bash "$APP_DIR/scripts/schema_baseline_compare_server.sh"

source_tables="$OUTPUT_DIR/server-source-entity-tables.txt"
missing_tables="$OUTPUT_DIR/missing-in-db.txt"
[ -s "$source_tables" ] || fail "source entity table inventory is missing or empty: $source_tables"
if [ -s "$missing_tables" ]; then
  fail "database is missing trading entity tables; inspect $missing_tables before baseline generation"
fi

SPRING_DATASOURCE_URL="$(read_env_key SPRING_DATASOURCE_URL)"
SPRING_DATASOURCE_USERNAME="$(read_env_key SPRING_DATASOURCE_USERNAME)"
SPRING_DATASOURCE_PASSWORD="$(read_env_key SPRING_DATASOURCE_PASSWORD)"

jdbc_without_prefix="${SPRING_DATASOURCE_URL#jdbc:mysql://}"
jdbc_without_query="${jdbc_without_prefix%%\?*}"
host_port="${jdbc_without_query%%/*}"
database="${jdbc_without_query#*/}"

[ "$database" = "$EXPECTED_TRADING_DATABASE" ] || fail "datasource must point at $EXPECTED_TRADING_DATABASE"

if printf '%s\n' "$host_port" | grep -q ':'; then
  host="${host_port%%:*}"
  port="${host_port##*:}"
else
  host="$host_port"
  port="3306"
fi

table_count="$(wc -l < "$source_tables" | tr -d '[:space:]')"
[ "$table_count" != "0" ] || fail "no source tables to dump"

mkdir -p "$MIGRATION_DIR"
tmp_file="$BASELINE_FILE.tmp"

{
  echo "-- Flyway baseline for standalone Trading service."
  echo "-- Generated from shared database '$EXPECTED_TRADING_DATABASE' on $(date -u +%Y-%m-%dT%H:%M:%SZ)."
  echo "-- Contains only tables mapped by agora-trading-api JPA entities."
  echo "-- Shared marketplace tables are intentionally excluded."
  echo "-- Do not edit production env or enable Flyway until this file is reviewed."
  echo ""
} > "$tmp_file"

MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD" mysqldump \
  --host="$host" \
  --port="$port" \
  --user="$SPRING_DATASOURCE_USERNAME" \
  --no-data \
  --skip-comments \
  --skip-add-drop-table \
  --set-gtid-purged=OFF \
  "$database" \
  $(tr '\n' ' ' < "$source_tables") >> "$tmp_file"

mv "$tmp_file" "$BASELINE_FILE"
ok "wrote baseline DDL for $table_count trading entity tables -> $BASELINE_FILE"
ok "review baseline before enabling Flyway; do not run extra-table cleanup in shared DB mode"
