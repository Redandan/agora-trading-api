#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "..");
const START_DATE = "2018-01-01";
const END_DATE_EXCLUSIVE = "2025-01-01";
const MAX_RESPONSE_BYTES = 1024 * 1024;
const REQUEST_TIMEOUT_MS = 20000;

const ARCHIVE_SOURCES = [
  {
    id: "treasury-par-yield-2010-2019",
    url: "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rate-archives/par-yield-curve-rates-2010-2019.csv",
  },
  {
    id: "treasury-par-yield-2020-2023",
    url: "https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rate-archives/par-yield-curve-rates-2020-2023.csv",
  },
];

const MONTHLY_2024_SOURCES = Array.from({ length: 12 }, (_, index) => {
  const month = String(index + 1).padStart(2, "0");
  const period = `2024${month}`;
  return {
    id: `treasury-par-yield-${period}`,
    url: `https://home.treasury.gov/resource-center/data-chart-center/interest-rates/daily-treasury-rates.csv/all/${period}?_format=csv&field_tdr_date_value_month=${period}&page=&type=daily_treasury_yield_curve`,
  };
});

const SOURCES = [...ARCHIVE_SOURCES, ...MONTHLY_2024_SOURCES];

class SourceReject extends Error {}

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.keys(value).sort().map((key) => [key, canonical(value[key])]),
    );
  }
  return value;
}

function parseCsvLine(line) {
  const fields = [];
  let current = "";
  let quoted = false;
  for (let index = 0; index < line.length; index += 1) {
    const character = line[index];
    if (character === '"') {
      if (quoted && line[index + 1] === '"') {
        current += '"';
        index += 1;
      } else {
        quoted = !quoted;
      }
    } else if (character === "," && !quoted) {
      fields.push(current.trim());
      current = "";
    } else {
      current += character;
    }
  }
  if (quoted) throw new SourceReject("SOURCE_REJECT:UNTERMINATED_CSV_QUOTE");
  fields.push(current.trim());
  return fields;
}

function parseDate(text) {
  const match = /^(\d{1,2})\/(\d{1,2})\/(\d{2}|\d{4})$/.exec(text);
  if (!match) throw new SourceReject(`SOURCE_REJECT:DATE_FORMAT:${text}`);
  const [, monthText, dayText, rawYear] = match;
  const year = rawYear.length === 2 ? 2000 + Number(rawYear) : Number(rawYear);
  const month = Number(monthText);
  const day = Number(dayText);
  const value = new Date(Date.UTC(year, month - 1, day));
  if (
    value.getUTCFullYear() !== year ||
    value.getUTCMonth() !== month - 1 ||
    value.getUTCDate() !== day
  ) {
    throw new SourceReject(`SOURCE_REJECT:INVALID_DATE:${text}`);
  }
  return `${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

function normalizeNumeric(text, date, field) {
  if (!/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(text)) {
    throw new SourceReject(`SOURCE_REJECT:NUMERIC:${date}:${field}:${text}`);
  }
  const value = Number(text);
  if (!Number.isFinite(value) || value < 0 || value > 25) {
    throw new SourceReject(`SOURCE_REJECT:RANGE:${date}:${field}:${text}`);
  }
  return text;
}

function parseResponseRows(text, sourceId) {
  const lines = text.replace(/^\uFEFF/, "").trim().split(/\r?\n/);
  if (lines.length < 2) throw new SourceReject(`SOURCE_REJECT:EMPTY:${sourceId}`);
  const header = parseCsvLine(lines[0]);
  const indexByName = Object.fromEntries(
    header.map((name, index) => [name.trim().toLowerCase(), index]),
  );
  for (const field of ["date", "3 mo", "1 yr", "2 yr", "10 yr"]) {
    if (!(field in indexByName)) {
      throw new SourceReject(`SOURCE_REJECT:MISSING_FIELD:${sourceId}:${field}`);
    }
  }
  return lines.slice(1).filter(Boolean).map((line, index) => {
    const fields = parseCsvLine(line);
    if (fields.length !== header.length) {
      throw new SourceReject(`SOURCE_REJECT:FIELD_COUNT:${sourceId}:${index + 2}`);
    }
    const date = parseDate(fields[indexByName.date]);
    if (date < START_DATE || date >= END_DATE_EXCLUSIVE) return null;
    return {
      date,
      three_month_pct: normalizeNumeric(fields[indexByName["3 mo"]], date, "3 Mo"),
      one_year_pct: normalizeNumeric(fields[indexByName["1 yr"]], date, "1 Yr"),
      two_year_pct: normalizeNumeric(fields[indexByName["2 yr"]], date, "2 Yr"),
      ten_year_pct: normalizeNumeric(fields[indexByName["10 yr"]], date, "10 Yr"),
    };
  }).filter(Boolean);
}

async function fetchSource(source) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  let response;
  try {
    response = await fetch(source.url, {
      method: "GET",
      redirect: "error",
      signal: controller.signal,
      headers: {
        Accept: "text/csv",
        "User-Agent": "AgoraResearchTreasurySourceProbe/1.0",
      },
    });
  } finally {
    clearTimeout(timeout);
  }
  if (response.status !== 200) {
    throw new SourceReject(`SOURCE_REJECT:HTTP:${source.id}:${response.status}`);
  }
  const contentType = response.headers.get("content-type") || "";
  if (!contentType.toLowerCase().includes("text/csv") && !contentType.toLowerCase().includes("text/plain")) {
    throw new SourceReject(`SOURCE_REJECT:CONTENT_TYPE:${source.id}:${contentType}`);
  }
  const bytes = Buffer.from(await response.arrayBuffer());
  if (!bytes.length || bytes.length > MAX_RESPONSE_BYTES) {
    throw new SourceReject(`SOURCE_REJECT:BYTES:${source.id}:${bytes.length}`);
  }
  const text = bytes.toString("utf8");
  const rows = parseResponseRows(text, source.id);
  return {
    receipt: {
      id: source.id,
      url: source.url,
      status: response.status,
      content_type: contentType,
      etag: response.headers.get("etag"),
      last_modified: response.headers.get("last-modified"),
      bytes: bytes.length,
      sha256: sha256(bytes),
      body_base64: bytes.toString("base64"),
    },
    rows,
  };
}

function requireOutputPath(file) {
  const resolved = path.resolve(file);
  const stateRoot = path.join(REPO_ROOT, ".research-state");
  if (!resolved.startsWith(`${stateRoot}${path.sep}`)) {
    throw new SourceReject(`OUTPUT_PATH_REJECT:${resolved}`);
  }
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
  for (const name of ["--bundle", "--normalized"]) {
    if (!values[name]) throw new SourceReject(`ARGUMENT_REJECT:${name}`);
  }
  return values;
}

async function main() {
  const args = argumentsByName(process.argv.slice(2));
  const bundleFile = requireOutputPath(args["--bundle"]);
  const normalizedFile = requireOutputPath(args["--normalized"]);
  if (bundleFile === normalizedFile) throw new SourceReject("OUTPUT_PATH_REJECT:DUPLICATE");

  const fetched = [];
  for (const source of SOURCES) fetched.push(await fetchSource(source));

  const byDate = new Map();
  for (const item of fetched) {
    for (const row of item.rows) {
      if (row.date < START_DATE || row.date >= END_DATE_EXCLUSIVE) continue;
      if (byDate.has(row.date)) throw new SourceReject(`SOURCE_REJECT:DUPLICATE_DATE:${row.date}`);
      byDate.set(row.date, row);
    }
  }
  const rows = [...byDate.values()].sort((left, right) => left.date.localeCompare(right.date));
  if (!rows.length || rows[0].date !== "2018-01-02" || rows.at(-1).date !== "2024-12-31") {
    throw new SourceReject("SOURCE_REJECT:BOUNDARY");
  }
  for (let index = 1; index < rows.length; index += 1) {
    if (rows[index].date <= rows[index - 1].date) {
      throw new SourceReject(`SOURCE_REJECT:ORDER:${rows[index].date}`);
    }
  }

  const normalized = [
    "date,three_month_pct,one_year_pct,two_year_pct,ten_year_pct",
    ...rows.map((row) => [
      row.date,
      row.three_month_pct,
      row.one_year_pct,
      row.two_year_pct,
      row.ten_year_pct,
    ].join(",")),
  ].join("\n") + "\n";
  const normalizedBytes = Buffer.from(normalized, "utf8");
  const bundle = {
    schema_version: "1",
    document_type: "US_TREASURY_PAR_YIELD_CURVE_SOURCE_BUNDLE_V1",
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    status: "SEALED_SOURCE_ONLY_NO_BTC_OUTCOME_ACCESS",
    publisher: "U.S. Department of the Treasury",
    captured_at: new Date().toISOString().replace(".000Z", "Z"),
    request_contract: {
      method: "GET",
      origin: "https://home.treasury.gov",
      request_count: SOURCES.length,
      credentials: "DENY",
      redirect: "DENY",
      retry: "DENY",
      maximum_response_bytes_each: MAX_RESPONSE_BYTES,
    },
    raw_responses: fetched.map((item) => item.receipt),
    normalized_subset: {
      path: path.relative(REPO_ROOT, normalizedFile).replaceAll("\\", "/"),
      sha256: sha256(normalizedBytes),
      rows: rows.length,
      first_date: rows[0].date,
      last_date: rows.at(-1).date,
      columns: ["date", "three_month_pct", "one_year_pct", "two_year_pct", "ten_year_pct"],
    },
    publication_timing_boundary: "Treat each Treasury trading-date observation as usable no earlier than 00:00 UTC on the following calendar day; persist the latest state across weekends and holidays.",
    revision_boundary: "These exact raw response bytes and the normalized subset are the sole present-vintage historical source for the audit. A later download is a different artifact and cannot replace this bundle after BTC outcome access.",
    scope_note: "Official free historical source only. No BTC outcome, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
  };
  const bundleBytes = Buffer.from(`${JSON.stringify(canonical(bundle))}\n`, "utf8");

  writeCreateOnce(normalizedFile, normalizedBytes);
  try {
    writeCreateOnce(bundleFile, bundleBytes);
  } catch (error) {
    fs.rmSync(normalizedFile, { force: true });
    throw error;
  }
  process.stdout.write(`${JSON.stringify({
    status: bundle.status,
    bundle: path.relative(REPO_ROOT, bundleFile).replaceAll("\\", "/"),
    bundle_sha256: sha256(bundleBytes),
    normalized: bundle.normalized_subset,
  })}\n`);
}

module.exports = {
  SourceReject,
  parseCsvLine,
  parseDate,
  parseResponseRows,
};

if (require.main === module) {
  main().catch((error) => {
    process.stderr.write(`${error.name}:${error.message}\n`);
    process.exitCode = 1;
  });
}
