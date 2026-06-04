#!/usr/bin/env bash
set -euo pipefail

NGINX_CONF="${NGINX_CONF:-/etc/nginx/sites-enabled/agoramarketapi}"
TRADING_PORT="${TRADING_PORT:-8084}"

fail() {
  echo "[nginx-trading] FAIL: $*" >&2
  exit 1
}

ok() {
  echo "[nginx-trading] OK: $*"
}

[ -f "$NGINX_CONF" ] || fail "nginx config missing: $NGINX_CONF"

if sudo grep -q "location[[:space:]]*/api/trading/" "$NGINX_CONF"; then
  sudo sed -E -i.bak-trading "s#127\\.0\\.0\\.1:(8084|8085)#127.0.0.1:${TRADING_PORT}#g" "$NGINX_CONF"
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
