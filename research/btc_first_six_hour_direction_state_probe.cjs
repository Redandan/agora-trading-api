#!/usr/bin/env node
"use strict";

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const engine = require("./btc_treasury_term_spread_long_cash_historical.cjs");

const REPO_ROOT = path.resolve(__dirname, "..");
const EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd";
const EXPECTED_BTC_ROWS = 52608;
const HOUR_MS = 60 * 60 * 1000;
const DAY_MS = 24 * HOUR_MS;

class SourceReject extends Error {}

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  return value;
}

function statesFromBars(bars) {
  if (bars.length !== EXPECTED_BTC_ROWS || bars.length % 24 !== 0) throw new SourceReject("STATE_REJECT:ROW_COUNT");
  const states = [];
  for (let index = 0; index < bars.length; index += 24) {
    const day = bars.slice(index, index + 24);
    if (day.length !== 24 || day[0].openTime % DAY_MS !== 0) throw new SourceReject(`STATE_REJECT:DAY_BOUNDARY:${index}`);
    for (let hour = 0; hour < 24; hour += 1) {
      if (day[hour].openTime !== day[0].openTime + hour * HOUR_MS) throw new SourceReject(`STATE_REJECT:HOUR_LATTICE:${index}:${hour}`);
    }
    states.push({
      date: new Date(day[0].openTime).toISOString().slice(0, 10),
      effectiveTime: day[0].openTime + 6 * HOUR_MS,
      target: day[5].close > day[0].open,
    });
  }
  return states;
}

function summarize(states, start, end) {
  const selected = states.filter((state) => Date.parse(start) <= state.effectiveTime && state.effectiveTime < Date.parse(end));
  let transitions = 0;
  for (let index = 1; index < selected.length; index += 1) if (selected[index].target !== selected[index - 1].target) transitions += 1;
  return {
    evaluations: selected.length,
    positive: selected.filter((state) => state.target).length,
    nonpositive: selected.filter((state) => !state.target).length,
    transitions,
    first_observation_date: selected[0].date,
    first_effective_at: new Date(selected[0].effectiveTime).toISOString().replace(".000Z", "Z"),
    last_observation_date: selected.at(-1).date,
    last_effective_at: new Date(selected.at(-1).effectiveTime).toISOString().replace(".000Z", "Z"),
  };
}

function argumentsByName(argv) {
  const values = {};
  for (let index = 0; index < argv.length; index += 2) values[argv[index]] = argv[index + 1];
  for (const name of ["--input", "--output"]) if (!values[name]) throw new SourceReject(`ARGUMENT_REJECT:${name}`);
  return values;
}

function main() {
  const args = argumentsByName(process.argv.slice(2));
  const inputFile = path.resolve(args["--input"]);
  const outputFile = path.resolve(args["--output"]);
  if (!inputFile.startsWith(`${REPO_ROOT}${path.sep}`)) throw new SourceReject(`INPUT_PATH_REJECT:${inputFile}`);
  if (!outputFile.startsWith(`${path.join(REPO_ROOT, ".research-state")}${path.sep}`)) throw new SourceReject(`OUTPUT_PATH_REJECT:${outputFile}`);
  if (fs.existsSync(outputFile)) throw new SourceReject(`SEALED_OUTPUT_EXISTS:${outputFile}`);
  const btc = engine.parseBtcRows(inputFile);
  if (btc.digest !== EXPECTED_BTC_SHA256 || btc.bars.length !== EXPECTED_BTC_ROWS) throw new SourceReject("BTC_REJECT:BINDING");
  const states = statesFromBars(btc.bars);
  const result = {
    schema_version: "1",
    document_type: "BTC_FIRST_SIX_HOUR_DIRECTION_STATE_FEASIBILITY_V1",
    authorization: "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
    status: "PASS_BOTH_STATES_AND_TRANSITIONS_PRESENT_NO_POST_0600_RETURN_ACCESSED",
    source: {
      path: path.relative(REPO_ROOT, inputFile).replaceAll("\\", "/"),
      sha256: btc.digest,
      rows: btc.bars.length,
      days: states.length,
    },
    feature: {
      observation: "EACH_UTC_DAY_FIRST_SIX_COMPLETE_HOURLY_BARS_0000_THROUGH_0559",
      relation: "CLOSE_AT_0600_UTC_STRICTLY_ABOVE_OPEN_AT_0000_UTC",
      effective_time: "0600_UTC",
      validity_hours: 24,
    },
    windows: {
      design: summarize(states, "2019-01-01T00:00:00Z", "2023-01-01T00:00:00Z"),
      validation: summarize(states, "2023-01-01T00:00:00Z", "2025-01-01T00:00:00Z"),
    },
    probe: {
      path: path.relative(REPO_ROOT, __filename).replaceAll("\\", "/"),
      sha256: sha256(fs.readFileSync(__filename)),
    },
    outcome_boundary: "Only each day's 00:00 open and 05:59 close were compared. No bar at or after the 06:00 effective time was used for feasibility.",
    scope_note: "No strategy outcome, hypothesis, manifest, candidate, OOS, paid API, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
  };
  const bytes = Buffer.from(`${JSON.stringify(canonical(result))}\n`, "utf8");
  fs.mkdirSync(path.dirname(outputFile), { recursive: true });
  fs.writeFileSync(outputFile, bytes, { flag: "wx" });
  process.stdout.write(`${JSON.stringify({ status: result.status, output: path.relative(REPO_ROOT, outputFile).replaceAll("\\", "/"), sha256: sha256(bytes), windows: result.windows })}\n`);
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`${error.name}:${error.message}\n`);
    process.exitCode = 1;
  }
}

module.exports = { SourceReject, statesFromBars, summarize };
