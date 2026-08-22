"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const sourceProbe = require("../../research/coinmetrics_btc_nvtadj_source_probe.cjs");

const DAY_MS = 24 * 60 * 60 * 1000;

function syntheticPayload() {
  const data = [];
  const start = Date.parse("2018-01-01T00:00:00Z");
  for (let index = 0; index < 2557; index += 1) {
    data.push({
      asset: "btc",
      time: new Date(start + index * DAY_MS).toISOString().replace(".000Z", ".000000000Z"),
      NVTAdj: (40 + (index % 400) / 10).toFixed(6),
    });
  }
  return { data };
}

test("source parser accepts the exact daily sample and freezes the ratio clock", () => {
  const rows = sourceProbe.parseRows(Buffer.from(JSON.stringify(syntheticPayload())));
  assert.equal(rows.length, 2557);
  assert.equal(rows[0].date, "2018-01-01");
  assert.equal(rows.at(-1).date, "2024-12-31");

  const feasibility = sourceProbe.featureFeasibility(rows);
  assert.equal(feasibility.evaluations, 313);
  assert.equal(feasibility.complete_week_count, 365);
  assert.equal(feasibility.excluded_incomplete_tail_days, 2);
  assert.equal(feasibility.first_evaluable_week_end, "2019-01-06");
  assert.equal(feasibility.first_effective_time, "2019-01-09T00:00:00Z");
  assert.deepEqual(Object.keys(feasibility.threshold_diagnostics), ["0.80", "1.00", "1.20"]);
  for (const diagnostic of Object.values(feasibility.threshold_diagnostics)) {
    assert.equal(diagnostic.at_or_below + diagnostic.above, 313);
  }
});

test("source parser rejects pagination, gaps and changed metric identity", () => {
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
  changed.data[100].NVTAdj = null;
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(changed))),
    /NVTADJ_DECIMAL/,
  );
});
