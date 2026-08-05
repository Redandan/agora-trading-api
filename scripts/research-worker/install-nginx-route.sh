#!/usr/bin/env bash
set -euo pipefail

SITE_FILE="${SITE_FILE:-/etc/nginx/sites-enabled/agoramarketapi}"
SNIPPET_SOURCE="${SNIPPET_SOURCE:-/opt/agora-research-worker/current/scripts/research-worker/nginx-research-mcp.conf}"
SNIPPET_TARGET="/etc/nginx/snippets/research-mcp.conf"
INCLUDE_LINE="    include /etc/nginx/snippets/research-mcp.conf;"

[ "$SITE_FILE" = "/etc/nginx/sites-enabled/agoramarketapi" ] || {
  echo "unsupported nginx site path" >&2
  exit 1
}
[ -f "$SITE_FILE" ] || { echo "nginx site missing" >&2; exit 1; }
[ -f "$SNIPPET_SOURCE" ] || { echo "research MCP snippet missing" >&2; exit 1; }

sudo install -o root -g root -m 0644 "$SNIPPET_SOURCE" "$SNIPPET_TARGET"
if ! sudo grep -Fqx "$INCLUDE_LINE" "$SITE_FILE"; then
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  backup="/etc/nginx/backups/sites-enabled/agoramarketapi.pre-research-mcp-$stamp"
  sudo install -d -o root -g root -m 0700 /etc/nginx/backups/sites-enabled
  sudo cp -a "$SITE_FILE" "$backup"
  sudo python3 - "$SITE_FILE" "$INCLUDE_LINE" <<'PY'
import os
import sys
from pathlib import Path

path = Path(sys.argv[1])
include = sys.argv[2]
content = path.read_text(encoding="utf-8")
needle = "    server_name agoratradingapi.purrtechllc.com;\n"
positions = []
offset = 0
while True:
    found = content.find(needle, offset)
    if found < 0:
        break
    positions.append(found)
    offset = found + len(needle)
if len(positions) != 2:
    raise SystemExit("expected dedicated Trading HTTP and HTTPS servers")
target = positions[-1]
updated = content[:target] + content[target:].replace(needle, needle + include + "\n", 1)
temporary = path.with_name(path.name + ".research-mcp.tmp")
temporary.write_text(updated, encoding="utf-8")
os.chmod(temporary, path.stat().st_mode)
os.replace(temporary, path)
PY
fi
sudo nginx -t
sudo systemctl reload nginx
