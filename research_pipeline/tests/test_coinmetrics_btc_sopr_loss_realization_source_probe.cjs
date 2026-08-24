"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const sourceProbe = require("../../research/coinmetrics_btc_sopr_loss_realization_source_probe.cjs");

const DAY_MS = 24 * 60 * 60 * 1000;
const REPO_ROOT = path.resolve(__dirname, "../..");

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function syntheticPayload() {
  const data = [];
  const start = Date.parse("2018-01-01T00:00:00Z");
  for (let index = 0; index < sourceProbe.EXPECTED_ROWS; index += 1) {
    const lossRealization = Math.floor(index / 45) % 2 === 0;
    data.push({
      asset: "btc",
      time: new Date(start + index * DAY_MS).toISOString().replace(".000Z", ".000000000Z"),
      SOPR: lossRealization ? "0.980000" : "1.020000",
    });
  }
  return { data };
}

test("source contract is exact and does not request credentials or reviewed entitlement", () => {
  assert.equal(
    sourceProbe.SOURCE_URL,
    "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&metrics=SOPR&frequency=1d&start_time=2018-01-01&end_time=2024-12-31&page_size=10000",
  );
  assert.equal(sourceProbe.AVAILABILITY_LAG_DAYS, 3);
  assert.equal(sourceProbe.SUPPORT_GATES.DESIGN.minimum_loss_realization_days, 30);
  assert.equal(sourceProbe.SUPPORT_GATES.VALIDATION.minimum_loss_realization_days, 10);
});

test("frozen source specification matches the adapter and prior bytes", () => {
  const spec = JSON.parse(fs.readFileSync(
    path.join(REPO_ROOT, "research_pipeline/examples/btc-coinmetrics-sopr-loss-realization-source-feasibility.v1.spec.json"),
    "utf8",
  ));
  assert.equal(spec.source_contract.url, sourceProbe.SOURCE_URL);
  assert.equal(spec.source_contract.expected_rows, sourceProbe.EXPECTED_ROWS);
  assert.equal(spec.feature_contract.variants, 1);
  assert.equal(spec.pre_outcome_support_gates.design.minimum_loss_realization_days, 30);
  assert.equal(spec.pre_outcome_support_gates.validation.minimum_loss_realization_days, 10);
  const bindings = Object.fromEntries(spec.bindings.map((binding) => [binding.path, binding.sha256]));
  for (const [relative, expected] of Object.entries(bindings)) {
    if (relative.endsWith("test_coinmetrics_btc_sopr_loss_realization_source_probe.cjs")) continue;
    assert.equal(sha256(path.join(REPO_ROOT, relative)), expected, relative);
  }
});

test("exact daily SOPR sample passes frozen state support with broad synthetic states", () => {
  const rows = sourceProbe.parseRows(Buffer.from(JSON.stringify(syntheticPayload())));
  assert.equal(rows.length, 2557);
  assert.equal(rows[0].date, "2018-01-01");
  assert.equal(rows.at(-1).date, "2024-12-31");

  const feasibility = sourceProbe.featureFeasibility(rows);
  assert.equal(feasibility.threshold, "STRICTLY_BELOW_1");
  assert.equal(feasibility.design.evaluations, 1096);
  assert.equal(feasibility.validation.evaluations, 731);
  assert.equal(feasibility.design.support_pass, true);
  assert.equal(feasibility.validation.support_pass, true);
  assert.equal(feasibility.all_support_gates_pass, true);
});

test("SOPR equality belongs to profit or break-even rather than loss realization", () => {
  const payload = syntheticPayload();
  for (const row of payload.data) row.SOPR = "1.000000";
  const rows = sourceProbe.parseRows(Buffer.from(JSON.stringify(payload)));
  const design = sourceProbe.windowSupport(rows, sourceProbe.SUPPORT_GATES.DESIGN);
  assert.equal(design.loss_realization_days, 0);
  assert.equal(design.profit_or_breakeven_days, 1096);
  assert.equal(design.support_pass, false);
});

test("parser rejects pagination, gaps and changed metric identity", () => {
  const paginated = syntheticPayload();
  paginated.next_page_url = "https://example.invalid/next";
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(paginated))),
    /ENVELOPE_OR_PAGINATION/,
  );

  const gap = syntheticPayload();
  gap.data[100].time = gap.data[99].time;
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(gap))),
    /DAILY_CONTINUITY/,
  );

  const changed = syntheticPayload();
  changed.data[100].SOPR = null;
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(changed))),
    /SOPR_DECIMAL/,
  );
});
