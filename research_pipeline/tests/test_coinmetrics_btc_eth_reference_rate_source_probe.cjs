"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const sourceProbe = require("../../research/coinmetrics_btc_eth_reference_rate_source_probe.cjs");

const DAY_MS = 24 * 60 * 60 * 1000;

function syntheticPayload() {
  const data = [];
  const start = Date.parse("2018-01-01T00:00:00Z");
  for (const asset of ["btc", "eth"]) {
    for (let index = 0; index < 2557; index += 1) {
      const base = asset === "btc" ? 10000 : 1000;
      const growth = asset === "btc" ? 1.0001 : 1.0002;
      data.push({
        asset,
        time: new Date(start + index * DAY_MS).toISOString().replace(".000Z", ".000000000Z"),
        ReferenceRateUSD: (base * growth ** index).toFixed(8),
      });
    }
  }
  return { data };
}

test("source parser accepts exact paired daily rates and freezes the relative-return clock", () => {
  const byAsset = sourceProbe.parseRows(Buffer.from(JSON.stringify(syntheticPayload())));
  assert.equal(byAsset.btc.length, 2557);
  assert.equal(byAsset.eth.length, 2557);
  assert.equal(byAsset.btc[0].date, "2018-01-01");
  assert.equal(byAsset.eth.at(-1).date, "2024-12-31");

  const feasibility = sourceProbe.featureFeasibility(byAsset);
  assert.equal(feasibility.evaluations, 361);
  assert.equal(feasibility.complete_week_count, 365);
  assert.equal(feasibility.excluded_incomplete_tail_days, 2);
  assert.equal(feasibility.first_evaluable_week_end, "2018-02-04");
  assert.equal(feasibility.first_effective_time, "2018-02-05T00:00:00Z");
  assert.deepEqual(Object.keys(feasibility.threshold_diagnostics), ["-5.00", "0.00", "5.00"]);
  for (const diagnostic of Object.values(feasibility.threshold_diagnostics)) {
    assert.equal(diagnostic.at_or_above + diagnostic.below, 361);
  }
});

test("source parser rejects pagination, asset gaps and changed metric identity", () => {
  const paginated = syntheticPayload();
  paginated.next_page_url = "https://example.invalid/next";
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(paginated))),
    /ENVELOPE_OR_PAGINATION/,
  );

  const gap = syntheticPayload();
  gap.data[2557 + 100].time = gap.data[2557 + 99].time;
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(gap))),
    /ETH_DAILY_CONTINUITY/,
  );

  const changed = syntheticPayload();
  changed.data[100].ReferenceRateUSD = null;
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(changed))),
    /REFERENCE_RATE_DECIMAL/,
  );
});
