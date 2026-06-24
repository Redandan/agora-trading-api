function brace_delta(line, copy, opens, closes) {
  copy = line
  opens = gsub(/\{/, "{", copy)
  copy = line
  closes = gsub(/\}/, "}", copy)
  return opens - closes
}

function update_server_depth() {
  if (in_server) {
    server_depth += brace_delta($0)
    if (server_depth <= 0) {
      in_server = 0
      dedicated = 0
      shared = 0
      server_depth = 0
    }
  }
}

function print_dedicated_mcp_proxy() {
  print "    # Dedicated Trading MCP endpoint. Authentication remains enforced by the app.";
  print "    location = /api/mcp {";
  print "        proxy_pass http://127.0.0.1:" port "/api/mcp;";
  print "        proxy_http_version 1.1;";
  print "        proxy_set_header Host $host;";
  print "        proxy_set_header X-Real-IP $remote_addr;";
  print "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;";
  print "        proxy_set_header X-Forwarded-Proto $scheme;";
  print "        proxy_read_timeout 3600;";
  print "    }";
}

function print_shared_mcp_block() {
  print "    # Trading MCP is internal-only. Public shared host must not expose /api/trading/mcp.";
  print "    location = /api/trading/mcp {";
  print "        return 404;";
  print "    }";
}

function print_trading_path_block() {
  print "    # Standalone trading service. Keep this before the generic /api/ fallback.";
  print "    location /api/trading/ {";
  print "        proxy_pass http://127.0.0.1:" port ";";
  print "        proxy_http_version 1.1;";
  print "        proxy_set_header Host $host;";
  print "        proxy_set_header X-Real-IP $remote_addr;";
  print "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;";
  print "        proxy_set_header X-Forwarded-Proto $scheme;";
  print "    }";
}

/^[[:space:]]*server[[:space:]]*\{/ {
  in_server = 1
  dedicated = 0
  shared = 0
  server_depth = 0
}

in_server && /^[[:space:]]*server_name[[:space:]]+agoratradingapi[.]purrtechllc[.]com;/ {
  dedicated = 1
}

in_server && /^[[:space:]]*server_name[[:space:]]+agoramarketapi[.]purrtechllc[.]com;/ {
  shared = 1
}

dedicated && /^[[:space:]]*location[[:space:]]*=[[:space:]]*\/api\/mcp[[:space:]]*\{/ {
  print_dedicated_mcp_proxy()
  skip_block = 1
  next
}

shared && /^[[:space:]]*location[[:space:]]*=[[:space:]]*\/api\/trading\/mcp[[:space:]]*\{/ {
  if (!inserted_trading_mcp_block) {
    print_shared_mcp_block()
    inserted_trading_mcp_block = 1
  }
  skip_block = 1
  next
}

skip_block && /^[[:space:]]*}/ {
  skip_block = 0
  next
}

skip_block {
  next
}

shared && /^[[:space:]]*location[[:space:]]+\/api\/trading\/[[:space:]]*\{/ {
  if (!inserted_trading_mcp_block) {
    print_shared_mcp_block()
    print ""
    inserted_trading_mcp_block = 1
  }
  inserted_trading_path = 1
  in_trading = 1
}

dedicated && /^[[:space:]]*location[[:space:]]+\/api\/[[:space:]]*\{/ {
  in_dedicated_api = 1
}

shared && insert_trading_path && !inserted_trading_path && /^[[:space:]]*location[[:space:]]+\/api\/[[:space:]]*\{/ {
  if (!inserted_trading_mcp_block) {
    print_shared_mcp_block()
    print ""
    inserted_trading_mcp_block = 1
  }
  print_trading_path_block()
  print ""
  inserted_trading_path = 1
}

in_trading || /proxy_pass[[:space:]]+http:\/\/127\.0\.0\.1:(8084|8085)\/api\/trading/ {
  gsub(/127\.0\.0\.1:(8084|8085)/, "127.0.0.1:" port)
}

in_dedicated_api && /proxy_pass[[:space:]]+http:\/\/127\.0\.0\.1:(8084|8085)\/api\/trading\// {
  sub(/proxy_pass[[:space:]]+http:\/\/127\.0\.0\.1:(8084|8085)\/api\/trading\//,
      "proxy_pass http://127.0.0.1:" port "/api/")
}

in_dedicated_api && /proxy_pass[[:space:]]+http:\/\/127\.0\.0\.1:(8084|8085)\/api\// {
  sub(/proxy_pass[[:space:]]+http:\/\/127\.0\.0\.1:(8084|8085)\/api\//,
      "proxy_pass http://127.0.0.1:" port "/api/")
}

{ print }

in_trading && /^[[:space:]]*}/ {
  in_trading = 0
}

in_dedicated_api && /^[[:space:]]*}/ {
  in_dedicated_api = 0
}

{ update_server_depth() }

END {
  if (insert_trading_path && !inserted_trading_path) {
    exit 42
  }
}
