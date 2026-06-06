#!/usr/bin/env bash
set -euo pipefail

APP_DIR="${APP_DIR:-/home/ubuntu/agora-trading-api}"
ENV_FILE="${ENV_FILE:-/home/ubuntu/.env.trading.secrets}"
OUTPUT_DIR="${OUTPUT_DIR:-$APP_DIR/target/schema-baseline}"
MARKETPLACE_TABLE_PATTERN='^(cart|cart_item|carts|delivery_order|order|order_item|orders|product|products|store|stores|user|user_address|user_wallet|users|wallet|wallets)$'
KNOWN_SYSTEM_TABLE_PATTERN='^(flyway_schema_history)$'

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
  if [ -z "$line" ] || ! printf '%s\n' "$line" | grep -Eq "^[[:space:]]*${key}=[^[:space:]#]"; then
    fail "missing or empty $key in $ENV_FILE"
  fi
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
implicit_entities="$OUTPUT_DIR/server-implicit-entities.txt"
db_tables="$OUTPUT_DIR/server-db-tables.txt"
missing_tables="$OUTPUT_DIR/missing-in-db.txt"
extra_tables="$OUTPUT_DIR/extra-in-db.txt"
forbidden_tables="$OUTPUT_DIR/server-forbidden-marketplace-tables.txt"
db_forbidden_tables="$OUTPUT_DIR/server-db-forbidden-marketplace-tables.txt"
known_system_tables="$OUTPUT_DIR/server-db-known-system-tables.txt"

find src/main/java/com/agora/model -name '*.java' -print0 |
  xargs -0 perl -0ne 'if (/@Entity\b/ && /@Table\s*\(\s*name\s*=\s*"([^"]+)"/s) { print "$1\n" }' |
  sort -u > "$source_tables"

find src/main/java/com/agora/model -name '*.java' -print0 |
  xargs -0 perl -0ne 'if (/@Entity\b/ && !/@Table\s*\(\s*name\s*=\s*"([^"]+)"/s) { print "$ARGV\n" }' |
  sort -u > "$implicit_entities"

grep -E "$MARKETPLACE_TABLE_PATTERN" "$source_tables" \
  > "$forbidden_tables" || true

MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD" mysql \
  --batch \
  --skip-column-names \
  -h "$host" \
  -P "$port" \
  -u "$SPRING_DATASOURCE_USERNAME" \
  "$database" \
  -e "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name" |
  sort -u > "$db_tables"

grep -E "$MARKETPLACE_TABLE_PATTERN" "$db_tables" \
  > "$db_forbidden_tables" || true

grep -E "$KNOWN_SYSTEM_TABLE_PATTERN" "$db_tables" \
  > "$known_system_tables" || true

comm -23 "$source_tables" "$db_tables" > "$missing_tables"
comm -13 "$source_tables" "$db_tables" > "$extra_tables"

source_count="$(wc -l < "$source_tables" | tr -d '[:space:]')"
implicit_count="$(wc -l < "$implicit_entities" | tr -d '[:space:]')"
forbidden_count="$(wc -l < "$forbidden_tables" | tr -d '[:space:]')"
db_forbidden_count="$(wc -l < "$db_forbidden_tables" | tr -d '[:space:]')"
known_system_count="$(wc -l < "$known_system_tables" | tr -d '[:space:]')"
db_count="$(wc -l < "$db_tables" | tr -d '[:space:]')"
missing_count="$(wc -l < "$missing_tables" | tr -d '[:space:]')"
extra_count="$(wc -l < "$extra_tables" | tr -d '[:space:]')"

echo "[schema-compare] source entity tables: $source_count -> $source_tables"
echo "[schema-compare] implicit entity names: $implicit_count -> $implicit_entities"
echo "[schema-compare] forbidden marketplace tables: $forbidden_count -> $forbidden_tables"
echo "[schema-compare] database tables: $db_count -> $db_tables"
echo "[schema-compare] database marketplace tables: $db_forbidden_count -> $db_forbidden_tables"
echo "[schema-compare] database known system tables: $known_system_count -> $known_system_tables"
echo "[schema-compare] missing in database: $missing_count -> $missing_tables"
echo "[schema-compare] extra in database: $extra_count -> $extra_tables"

if [ "$implicit_count" != "0" ]; then
  fail "schema baseline source inventory found entity class(es) without explicit @Table(name=...); inspect $implicit_entities"
fi

if [ "$forbidden_count" != "0" ]; then
  fail "schema baseline source inventory found marketplace-owned table mapping(s); inspect $forbidden_tables"
fi

if [ "$db_forbidden_count" != "0" ]; then
  fail "schema baseline database contains obvious marketplace-owned table(s); inspect $db_forbidden_tables"
fi

if [ "$missing_count" != "0" ] || [ "$extra_count" != "0" ]; then
  fail "schema baseline table inventory differs; inspect $missing_tables and $extra_tables before generating Flyway baseline"
fi

ok "schema baseline source inventory matches database table list; read-only compare complete"
