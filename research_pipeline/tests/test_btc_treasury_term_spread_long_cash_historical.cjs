"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const repoRoot = path.resolve(__dirname, "../..");
const sourceProbe = require(path.join(repoRoot, "research/treasury_yield_curve_source_probe.cjs"));
const runner = require(path.join(repoRoot, "research/btc_treasury_term_spread_long_cash_historical.cjs"));

test("source parser ignores out-of-window archive gaps before numeric validation", () => {
  const csv = [
    "date,3 mo,1 yr,2 yr,10 yr",
    "10/11/10,,0.20,0.40,2.50",
    "01/02/2018,1.44,1.83,1.92,2.46",
  ].join("\n");
  assert.deepEqual(sourceProbe.parseResponseRows(csv, "fixture"), [
    {
      date: "2018-01-02",
      three_month_pct: "1.44",
      one_year_pct: "1.83",
      two_year_pct: "1.92",
      ten_year_pct: "2.46",
    },
  ]);
});

test("source parser fails closed on an in-window required-field gap", () => {
  const csv = [
    "Date,3 Mo,1 Yr,2 Yr,10 Yr",
    "01/02/2018,,1.83,1.92,2.46",
  ].join("\n");
  assert.throws(
    () => sourceProbe.parseResponseRows(csv, "fixture"),
    /SOURCE_REJECT:NUMERIC:2018-01-02:3 Mo/,
  );
});

test("term-spread state is effective only on the following UTC calendar day", () => {
  const rows = [
    {
      date: "2024-01-02",
      dateTime: Date.parse("2024-01-02T00:00:00Z"),
      three_month_pct: 5.4,
      one_year_pct: 4.8,
      two_year_pct: 4.3,
      ten_year_pct: 4.0,
    },
    {
      date: "2024-01-03",
      dateTime: Date.parse("2024-01-03T00:00:00Z"),
      three_month_pct: 3.9,
      one_year_pct: 3.8,
      two_year_pct: 3.7,
      ten_year_pct: 4.0,
    },
  ];
  const targets = runner.targetsByEffectiveTime(rows, "three_month_pct");
  assert.equal(targets.has(Date.parse("2024-01-02T00:00:00Z")), false);
  assert.equal(targets.get(Date.parse("2024-01-03T00:00:00Z")), false);
  assert.equal(targets.get(Date.parse("2024-01-04T00:00:00Z")), true);
});

test("fair-reset windows seed the latest causally available target", () => {
  const targets = new Map([
    [Date.parse("2023-12-30T00:00:00Z"), false],
    [Date.parse("2024-01-03T00:00:00Z"), true],
  ]);
  assert.equal(
    runner.latestTargetAtOrBefore(targets, Date.parse("2024-01-01T00:00:00Z")),
    false,
  );
});

test("frozen manifest validates and sealed reproducibility runs are byte-identical", () => {
  const manifest = JSON.parse(
    fs.readFileSync(
      path.join(repoRoot, "research_pipeline/examples/btc-treasury-term-spread-long-cash-historical.v1.manifest.json"),
      "utf8",
    ),
  );
  runner.validateManifest(manifest);
  const run1 = fs.readFileSync(
    path.join(repoRoot, ".research-state/experiments/btc-treasury-term-spread-long-cash-historical-v1/artifacts/run1.json"),
  );
  const run2 = fs.readFileSync(
    path.join(repoRoot, ".research-state/experiments/btc-treasury-term-spread-long-cash-historical-v1/artifacts/run2.json"),
  );
  assert.deepEqual(run1, run2);
});
