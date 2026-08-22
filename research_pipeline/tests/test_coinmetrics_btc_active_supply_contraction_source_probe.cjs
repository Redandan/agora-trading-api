"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const sourceProbe = require("../../research/coinmetrics_btc_active_supply_contraction_source_probe.cjs");

const DAY_MS = 24 * 60 * 60 * 1000;

function syntheticPayload() {
  const data = [];
  const start = Date.parse("2018-01-01T00:00:00Z");
  for (let index = 0; index < 2557; index += 1) {
    data.push({
      asset: "btc",
      time: new Date(start + index * DAY_MS).toISOString().replace(".000Z", ".000000000Z"),
      SplyActPct1yr: (40 + index / 10000).toFixed(6),
    });
  }
  return { data };
}

test("source parser accepts the exact daily sample and freezes the adjacent-window clock", () => {
  const rows = sourceProbe.parseRows(Buffer.from(JSON.stringify(syntheticPayload())));
  assert.equal(rows.length, 2557);
  assert.equal(rows[0].date, "2018-01-01");
  assert.equal(rows.at(-1).date, "2024-12-31");

  const feasibility = sourceProbe.featureFeasibility(rows);
  assert.equal(feasibility.evaluations, 358);
  assert.equal(feasibility.contraction_weeks, 0);
  assert.equal(feasibility.noncontraction_weeks, 358);
  assert.equal(feasibility.transitions, 0);
  assert.equal(feasibility.complete_week_count, 365);
  assert.equal(feasibility.excluded_incomplete_tail_days, 2);
  assert.equal(feasibility.first_evaluable_week_end, "2018-02-25");
  assert.equal(feasibility.first_effective_time, "2018-02-28T00:00:00Z");
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
  changed.data[100].SplyActPct1yr = null;
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(changed))),
    /ACTIVE_SUPPLY_DECIMAL/,
  );
});
