#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/.env.trading.secrets}"
OUTPUT_DIR="${OUTPUT_DIR:-$APP_DIR/target/schema-baseline}"

fail() {
  echo "[schema-compare] FAIL: $*" >&2
  exit 1
}

ok() {
  echo "[schema-compare] OK: $*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

read_env_key() {
  local key="$1"
  local line
  [ -f "$ENV_FILE" ] || fail "env file missing: $ENV_FILE"
  line="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)"
  [ -n "$line" ] || fail "missing $key in $ENV_FILE"
  printf '%s\n' "${line#*=}"
}

require_cmd comm
require_cmd grep
require_cmd mysql
require_cmd perl
require_cmd sort

[ -d "$APP_DIR" ] || fail "app dir missing: $APP_DIR"
cd "$APP_DIR"

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

mkdir -p "$OUTPUT_DIR"

source_tables="$OUTPUT_DIR/server-source-entity-tables.txt"
db_tables="$OUTPUT_DIR/server-db-tables.txt"
missing_tables="$OUTPUT_DIR/missing-in-db.txt"
extra_tables="$OUTPUT_DIR/extra-in-db.txt"

find src/main/java/com/agora/model -name '*.java' -print0 |
  xargs -0 perl -0ne 'while (/@Entity\b.*?@Table\s*\(\s*name\s*=\s*"([^"]+)"/sg) { print "$1\n" }' |
  sort -u > "$source_tables"

MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD" mysql \
  --batch \
  --skip-column-names \
  -h "$host" \
  -P "$port" \
  -u "$SPRING_DATASOURCE_USERNAME" \
  "$database" \
  -e "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name" |
  sort -u > "$db_tables"

comm -23 "$source_tables" "$db_tables" > "$missing_tables"
comm -13 "$source_tables" "$db_tables" > "$extra_tables"

source_count="$(wc -l < "$source_tables" | tr -d '[:space:]')"
db_count="$(wc -l < "$db_tables" | tr -d '[:space:]')"
missing_count="$(wc -l < "$missing_tables" | tr -d '[:space:]')"
extra_count="$(wc -l < "$extra_tables" | tr -d '[:space:]')"

echo "[schema-compare] source entity tables: $source_count -> $source_tables"
echo "[schema-compare] database tables: $db_count -> $db_tables"
echo "[schema-compare] missing in database: $missing_count -> $missing_tables"
echo "[schema-compare] extra in database: $extra_count -> $extra_tables"

if [ "$missing_count" != "0" ] || [ "$extra_count" != "0" ]; then
  fail "schema baseline table inventory differs; inspect $missing_tables and $extra_tables before generating Flyway baseline"
fi

ok "schema baseline source inventory matches database table list; read-only compare complete"
