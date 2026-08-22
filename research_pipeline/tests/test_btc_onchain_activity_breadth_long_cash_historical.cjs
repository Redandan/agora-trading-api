"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const sourceProbe = require("../../research/coinmetrics_btc_onchain_activity_source_probe.cjs");
const runner = require("../../research/btc_onchain_activity_breadth_long_cash_historical.cjs");

const REPO_ROOT = path.resolve(__dirname, "../..");
const MANIFEST = path.join(REPO_ROOT, "research_pipeline/examples/btc-onchain-activity-breadth-long-cash-historical.v1.manifest.json");
const ACTIVITY = path.join(REPO_ROOT, ".research-state/experiments/btc-onchain-activity-breadth-long-cash-historical-v1/inputs/coinmetrics-btc-onchain-activity-2018-2024.csv");
const DAY_MS = 24 * 60 * 60 * 1000;

function syntheticPayload() {
  const data = [];
  const start = Date.parse("2018-01-01T00:00:00Z");
  for (let index = 0; index < 2557; index += 1) {
    data.push({
      asset: "btc",
      time: new Date(start + index * DAY_MS).toISOString().replace(".000Z", ".000000000Z"),
      AdrActCnt: String(100000 + index),
      TxCnt: String(200000 + index),
    });
  }
  return { data };
}

test("source parser accepts one exact consecutive 2018-2024 response", () => {
  const rows = sourceProbe.parseRows(Buffer.from(JSON.stringify(syntheticPayload())));
  assert.equal(rows.length, 2557);
  assert.equal(rows[0].date, "2018-01-01");
  assert.equal(rows.at(-1).date, "2024-12-31");
  const feasibility = sourceProbe.featureFeasibility(rows);
  assert.equal(feasibility.first_evaluable_observation_day, "2019-01-27");
  assert.equal(feasibility.first_effective_time, "2019-01-29T00:00:00Z");
  assert.equal(feasibility.variants.PRIMARY_BOTH.evaluations, 2166);
  assert.equal(feasibility.variants.PRIMARY_BOTH.positive_days, 2166);
  assert.equal(feasibility.variants.PRIMARY_BOTH.transitions, 0);
});

test("source parser rejects pagination residue and daily gaps", () => {
  const paginated = syntheticPayload();
  paginated.next_page_url = "https://example.invalid/next";
  assert.throws(() => sourceProbe.parseRows(Buffer.from(JSON.stringify(paginated))), /ENVELOPE_OR_PAGINATION/);
  const gap = syntheticPayload();
  gap.data[100].time = gap.data[99].time;
  assert.throws(() => sourceProbe.parseRows(Buffer.from(JSON.stringify(gap))), /DAILY_CONTINUITY/);
});

test("sealed activity source and frozen target clocks are exact", () => {
  const parsed = runner.parseActivityRows(ACTIVITY);
  assert.equal(parsed.rows.length, 2557);
  const both = runner.targetsByEffectiveTime(parsed.rows, "BOTH");
  const tx = runner.targetsByEffectiveTime(parsed.rows, "TXCNT_ONLY");
  const address = runner.targetsByEffectiveTime(parsed.rows, "ADRACTCNT_ONLY");
  const first = Date.parse("2019-01-29T00:00:00Z");
  assert.equal(both.size, 2166);
  assert.equal(both.has(first), true);
  assert.equal(tx.size, both.size);
  assert.equal(address.size, both.size);
});

test("manifest accepts only the frozen 28-day 364-day three-variant policy", () => {
  const manifest = JSON.parse(fs.readFileSync(MANIFEST, "utf8"));
  assert.doesNotThrow(() => runner.validateManifest(manifest));
  const changed = structuredClone(manifest);
  changed.strategy_policy.mean_days = 29;
  assert.throws(() => runner.validateManifest(changed), /MANIFEST_REJECT:POLICY/);
});

test("activity parser rejects changed sealed bytes", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "agora-onchain-test-"));
  const file = path.join(directory, "activity.csv");
  fs.writeFileSync(file, "date,active_address_count,transaction_count\n2018-01-01,1,1\n");
  assert.throws(() => runner.parseActivityRows(file), /ACTIVITY_REJECT:SHA256/);
  fs.rmSync(directory, { recursive: true, force: true });
});
