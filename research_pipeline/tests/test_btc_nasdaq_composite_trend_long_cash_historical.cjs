"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const repoRoot = path.resolve(__dirname, "../..");
const sourceProbe = require(path.join(repoRoot, "research/nasdaq_composite_source_probe.cjs"));
const runner = require(path.join(repoRoot, "research/btc_nasdaq_composite_trend_long_cash_historical.cjs"));

test("source parser preserves explicit holiday gaps without interpolation", () => {
  const raw = fs.readFileSync(
    path.join(repoRoot, ".research-state/experiments/btc-nasdaq-composite-trend-long-cash-historical-v1/inputs/fred-nasdaqcom-2018-2024-raw.csv"),
    "utf8",
  );
  assert.throws(
    () => sourceProbe.parseRows(raw.replace("2018-01-02,7006.900", "2018-01-02,")),
    /SOURCE_REJECT:VALID_MISSING_ROWS:1761:65/,
  );

  const actual = sourceProbe.parseRows(raw);
  assert.equal(actual.rows.length, 1762);
  assert.equal(actual.missingDates.length, 64);
  assert.equal(actual.rows[0].date, "2018-01-02");
  assert.equal(actual.rows.at(-1).date, "2024-12-31");
});

test("Nasdaq trend excludes the current close and becomes effective next UTC day", () => {
  const rows = Array.from({ length: 202 }, (_, index) => ({
    date: new Date(Date.UTC(2023, 0, 1 + index)).toISOString().slice(0, 10),
    dateTime: Date.UTC(2023, 0, 1 + index),
    close: index < 200 ? 100 : index === 200 ? 101 : 99,
  }));
  const targets = runner.targetsByEffectiveTime(rows, 200);
  assert.equal(targets.size, 2);
  assert.equal(targets.has(rows[200].dateTime), false);
  assert.equal(targets.get(rows[200].dateTime + 86400000), true);
  assert.equal(targets.get(rows[201].dateTime + 86400000), false);
});

test("unregistered lookback fails closed", () => {
  assert.throws(() => runner.targetsByEffectiveTime([], 199), /FORMULA_REJECT:LOOKBACK:199/);
});

test("frozen manifest validates and the economic decision remains closed", () => {
  const manifest = JSON.parse(
    fs.readFileSync(
      path.join(repoRoot, "research_pipeline/examples/btc-nasdaq-composite-trend-long-cash-historical.v1.manifest.json"),
      "utf8",
    ),
  );
  runner.validateManifest(manifest);
  const result = JSON.parse(
    fs.readFileSync(
      path.join(repoRoot, ".research-state/experiments/btc-nasdaq-composite-trend-long-cash-historical-v1/artifacts/run1.json"),
      "utf8",
    ),
  );
  assert.equal(result.status, "NO_CANDIDATE_CLOSE_BTC_NASDAQ_COMPOSITE_TREND_LONG_CASH_FAMILY");
  assert.equal(result.all_gates_pass, false);
  assert.equal(result.oos_opened, false);
  assert.ok(result.failed_gates.includes("primary_validation_normal_upside_capture_at_least_60pct"));
});

test("sealed reproducibility runs are byte-identical", () => {
  const run1 = fs.readFileSync(
    path.join(repoRoot, ".research-state/experiments/btc-nasdaq-composite-trend-long-cash-historical-v1/artifacts/run1.json"),
  );
  const run2 = fs.readFileSync(
    path.join(repoRoot, ".research-state/experiments/btc-nasdaq-composite-trend-long-cash-historical-v1/artifacts/run2.json"),
  );
  assert.deepEqual(run1, run2);
});
