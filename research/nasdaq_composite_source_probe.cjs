#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..");
const SOURCE_URL = "https://fred.stlouisfed.org/graph/fredgraph.csv?id=NASDAQCOM&cosd=2018-01-01&coed=2024-12-31";
const MAX_RESPONSE_BYTES = 256 * 1024;
const REQUEST_TIMEOUT_MS = 20000;
const EXPECTED_RAW_ROWS = 1826;
const EXPECTED_VALID_ROWS = 1762;

class SourceReject extends Error {}

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

function parseRows(text) {
  const lines = text.replace(/^\uFEFF/, "").trimEnd().split(/\r?\n/);
  if (lines[0] !== "observation_date,NASDAQCOM") throw new SourceReject("SOURCE_REJECT:HEADER");
  if (lines.length - 1 !== EXPECTED_RAW_ROWS) throw new SourceReject(`SOURCE_REJECT:RAW_ROWS:${lines.length - 1}`);
  const rows = [];
  const missingDates = [];
  for (let index = 1; index < lines.length; index += 1) {
    const fields = lines[index].split(",");
    if (fields.length !== 2 || !/^\d{4}-\d{2}-\d{2}$/.test(fields[0])) {
      throw new SourceReject(`SOURCE_REJECT:ROW:${index}`);
    }
    const [date, valueText] = fields;
    if (!valueText) {
      missingDates.push(date);
      continue;
    }
    if (!/^\d+(?:\.\d+)?$/.test(valueText)) throw new SourceReject(`SOURCE_REJECT:VALUE:${date}`);
    const close = Number(valueText);
    if (!Number.isFinite(close) || close <= 0 || close > 100000) throw new SourceReject(`SOURCE_REJECT:RANGE:${date}`);
    rows.push({ date, close_text: valueText });
  }
  if (rows.length !== EXPECTED_VALID_ROWS || missingDates.length !== EXPECTED_RAW_ROWS - EXPECTED_VALID_ROWS) {
    throw new SourceReject(`SOURCE_REJECT:VALID_MISSING_ROWS:${rows.length}:${missingDates.length}`);
  }
  if (rows[0].date !== "2018-01-02" || rows.at(-1).date !== "2024-12-31") {
    throw new SourceReject("SOURCE_REJECT:BOUNDARY");
  }
  for (let index = 1; index < rows.length; index += 1) {
    if (rows[index].date <= rows[index - 1].date) throw new SourceReject(`SOURCE_REJECT:ORDER:${index}`);
  }
  return { rows, missingDates };
}

function requireOutputPath(file) {
  const resolved = path.resolve(file);
  const stateRoot = path.join(REPO_ROOT, ".research-state");
  if (!resolved.startsWith(`${stateRoot}${path.sep}`)) throw new SourceReject(`OUTPUT_PATH_REJECT:${resolved}`);
  if (fs.existsSync(resolved)) throw new SourceReject(`SEALED_OUTPUT_EXISTS:${resolved}`);
  return resolved;
}

function writeCreateOnce(file, bytes) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, bytes, { flag: "wx" });
}

function argumentsByName(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) values[argv[index]] = argv[index + 1];
  for (const name of ["--bundle", "--raw", "--normalized"]) {
    if (!values[name]) throw new SourceReject(`ARGUMENT_REJECT:${name}`);
  }
  return values;
}

async function main() {
  const args = argumentsByName(process.argv.slice(2));
  const bundleFile = requireOutputPath(args["--bundle"]);
  const rawFile = requireOutputPath(args["--raw"]);
  const normalizedFile = requireOutputPath(args["--normalized"]);
  if (new Set([bundleFile, rawFile, normalizedFile]).size !== 3) throw new SourceReject("OUTPUT_PATH_REJECT:DUPLICATE");

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  let response;
  try {
    response = await fetch(SOURCE_URL, {
      method: "GET",
      redirect: "error",
      signal: controller.signal,
      headers: { Accept: "text/csv", "User-Agent": "AgoraResearchNasdaqSourceProbe/1.0" },
    });
  } finally {
    clearTimeout(timeout);
  }
  if (response.status !== 200) throw new SourceReject(`SOURCE_REJECT:HTTP:${response.status}`);
  const rawBytes = Buffer.from(await response.arrayBuffer());
  if (!rawBytes.length || rawBytes.length > MAX_RESPONSE_BYTES) throw new SourceReject(`SOURCE_REJECT:BYTES:${rawBytes.length}`);
  const { rows, missingDates } = parseRows(rawBytes.toString("utf8"));
  const normalizedBytes = Buffer.from([
    "date,nasdaq_composite_close",
    ...rows.map((row) => `${row.date},${row.close_text}`),
  ].join("\n") + "\n", "utf8");
  const bundle = {
    schema_version: "1",
    document_type: "NASDAQ_COMPOSITE_DAILY_SOURCE_BUNDLE_V1",
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    status: "SEALED_SOURCE_ONLY_NO_BTC_OUTCOME_ACCESS",
    publisher: "Nasdaq, Inc., redistributed by Federal Reserve Bank of St. Louis FRED",
    captured_at: new Date().toISOString().replace(".000Z", "Z"),
    request_contract: {
      method: "GET",
      url: SOURCE_URL,
      credentials: "DENY",
      redirect: "DENY",
      retry: "DENY",
      maximum_response_bytes: MAX_RESPONSE_BYTES,
    },
    raw_response: {
      path: path.relative(REPO_ROOT, rawFile).replaceAll("\\", "/"),
      status: response.status,
      content_type: response.headers.get("content-type"),
      etag: response.headers.get("etag"),
      last_modified: response.headers.get("last-modified"),
      bytes: rawBytes.length,
      sha256: sha256(rawBytes),
      rows: EXPECTED_RAW_ROWS,
    },
    normalized_subset: {
      path: path.relative(REPO_ROOT, normalizedFile).replaceAll("\\", "/"),
      sha256: sha256(normalizedBytes),
      rows: rows.length,
      first_date: rows[0].date,
      last_date: rows.at(-1).date,
      columns: ["date", "nasdaq_composite_close"],
    },
    explicit_missing_market_dates: missingDates,
    publication_timing_boundary: "Treat each Nasdaq trading-date close as usable no earlier than 00:00 UTC on the following calendar day; persist the latest valid state across weekends, holidays and explicit missing rows.",
    revision_boundary: "The exact FRED response is a sealed present-vintage historical research input. It is not original-vintage proof and a later download cannot replace these bytes after BTC outcome access.",
    copyright_boundary: "NASDAQCOM is copyrighted. Raw and normalized bytes remain under untracked .research-state for internal research and are not added to the Git-versioned reusable catalog.",
    scope_note: "Free source only. No BTC outcome, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
  };
  const bundleBytes = Buffer.from(`${JSON.stringify(canonical(bundle))}\n`, "utf8");

  writeCreateOnce(rawFile, rawBytes);
  try {
    writeCreateOnce(normalizedFile, normalizedBytes);
    writeCreateOnce(bundleFile, bundleBytes);
  } catch (error) {
    fs.rmSync(rawFile, { force: true });
    fs.rmSync(normalizedFile, { force: true });
    throw error;
  }
  process.stdout.write(`${JSON.stringify({
    status: bundle.status,
    bundle: path.relative(REPO_ROOT, bundleFile).replaceAll("\\", "/"),
    bundle_sha256: sha256(bundleBytes),
    raw: bundle.raw_response,
    normalized: bundle.normalized_subset,
    explicit_missing_count: missingDates.length,
  })}\n`);
}

module.exports = { SourceReject, parseRows };

if (require.main === module) {
  main().catch((error) => {
    process.stderr.write(`${error.name}:${error.message}\n`);
    process.exitCode = 1;
  });
}
