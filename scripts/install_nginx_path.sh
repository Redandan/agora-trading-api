#!/usr/bin/env bash
set -euo pipefail

NGINX_CONF="${NGINX_CONF:-/etc/nginx/sites-enabled/agoramarketapi}"
TRADING_PORT="${TRADING_PORT:-8084}"
PORT_A="${PORT_A:-8084}"
PORT_B="${PORT_B:-8085}"

fail() {
  echo "[nginx-trading] FAIL: $*" >&2
  exit 1
}

ok() {
  echo "[nginx-trading] OK: $*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

require_cmd awk
require_cmd cp
require_cmd grep
require_cmd mktemp
require_cmd mv
require_cmd nginx
require_cmd rm
require_cmd sudo
require_cmd systemctl

[ -f "$NGINX_CONF" ] || fail "nginx config missing: $NGINX_CONF"

case "$TRADING_PORT" in
  "$PORT_A"|"$PORT_B") ;;
  *) fail "invalid TRADING_PORT: $TRADING_PORT (expected $PORT_A or $PORT_B)" ;;
esac

if sudo grep -q "location[[:space:]]*/api/trading/" "$NGINX_CONF"; then
  tmp_file="$(mktemp)"
  awk -v port="$TRADING_PORT" '
    /^[[:space:]]*location[[:space:]]+\/api\/trading\/[[:space:]]*\{/ {
      in_trading = 1
    }
    in_trading || /proxy_pass[[:space:]]+http:\/\/127\.0\.0\.1:(8084|8085)\/api\/trading/ {
      gsub(/127\.0\.0\.1:(8084|8085)/, "127.0.0.1:" port)
    }
    { print }
    in_trading && /^[[:space:]]*}/ {
      in_trading = 0
    }
  ' "$NGINX_CONF" > "$tmp_file"
  sudo cp "$NGINX_CONF" "$NGINX_CONF.bak-trading"
  sudo cp "$tmp_file" "$NGINX_CONF"
  rm -f "$tmp_file"
  ok "updated existing /api/trading/ upstream to $TRADING_PORT"
else
  tmp_file="$(mktemp)"
  awk -v port="$TRADING_PORT" '
    !inserted && /^[[:space:]]*location[[:space:]]+\/api\/[[:space:]]*\{/ {
      print "    # Standalone trading service. Keep this before the generic /api/ fallback.";
      print "    location /api/trading/ {";
      print "        proxy_pass http://127.0.0.1:" port ";";
      print "        proxy_http_version 1.1;";
      print "        proxy_set_header Host $host;";
      print "        proxy_set_header X-Real-IP $remote_addr;";
      print "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;";
      print "        proxy_set_header X-Forwarded-Proto $scheme;";
      print "    }";
      print "";
      inserted=1;
    }
    { print }
    END {
      if (!inserted) {
        exit 42
      }
    }
  ' "$NGINX_CONF" > "$tmp_file" || {
    rm -f "$tmp_file"
    fail "could not find generic /api/ location insertion point"
  }
  sudo cp "$NGINX_CONF" "$NGINX_CONF.bak-trading"
  sudo cp "$tmp_file" "$NGINX_CONF"
  rm -f "$tmp_file"
  ok "inserted /api/trading/ upstream to $TRADING_PORT"
fi

if ! sudo nginx -t >/dev/null 2>&1; then
  [ -f "$NGINX_CONF.bak-trading" ] && sudo mv "$NGINX_CONF.bak-trading" "$NGINX_CONF"
  fail "nginx config invalid after update; rolled back"
fi

sudo rm -f "$NGINX_CONF.bak-trading"
sudo systemctl reload nginx
ok "nginx reloaded"
