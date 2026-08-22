"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const sourceProbe = require("../../research/coinmetrics_btc_exchange_net_flow_source_probe.cjs");

const DAY_MS = 24 * 60 * 60 * 1000;

function syntheticPayload(metric, valueForIndex) {
  const data = [];
  const start = Date.parse("2018-01-01T00:00:00Z");
  for (let index = 0; index < 2557; index += 1) {
    data.push({
      asset: "btc",
      time: new Date(start + index * DAY_MS).toISOString().replace(".000Z", ".000000000Z"),
      [metric]: valueForIndex(index),
      [`${metric}-status`]: "flash",
      [`${metric}-status-time`]: "2026-04-09T07:11:17.145023000Z",
    });
  }
  return { data };
}

test("source parser joins exact daily samples and freezes the weekly clock", () => {
  const flowIn = sourceProbe.parseRows(
    Buffer.from(JSON.stringify(syntheticPayload("FlowInExNtv", (index) => (index < 2555 && Math.floor(index / 7) % 2 === 0 ? "110.0" : "90.0")))),
    "FlowInExNtv",
  );
  const flowOut = sourceProbe.parseRows(
    Buffer.from(JSON.stringify(syntheticPayload("FlowOutExNtv", () => "100.0"))),
    "FlowOutExNtv",
  );
  const joined = sourceProbe.joinRows(flowIn, flowOut);
  assert.equal(joined.length, 2557);
  assert.equal(joined[0].date, "2018-01-01");
  assert.equal(joined.at(-1).date, "2024-12-31");

  const feasibility = sourceProbe.featureFeasibility(joined);
  assert.equal(feasibility.complete_week_evaluations, 365);
  assert.equal(feasibility.positive_net_inflow_weeks, 183);
  assert.equal(feasibility.nonpositive_net_inflow_weeks, 182);
  assert.equal(feasibility.transitions, 364);
  assert.equal(feasibility.first_complete_week_end, "2018-01-08T00:00:00Z");
  assert.equal(feasibility.first_effective_time, "2018-01-10T00:00:00Z");
  assert.equal(feasibility.excluded_trailing_incomplete_days, 2);
});

test("source parser rejects pagination, gaps and changed status identity", () => {
  const paginated = syntheticPayload("FlowInExNtv", () => "100.0");
  paginated.next_page_url = "https://example.invalid/next";
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(paginated)), "FlowInExNtv"),
    /ENVELOPE_OR_PAGINATION/,
  );

  const gap = syntheticPayload("FlowInExNtv", () => "100.0");
  gap.data[100].time = gap.data[99].time;
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(gap)), "FlowInExNtv"),
    /DAILY_CONTINUITY/,
  );

  const changed = syntheticPayload("FlowInExNtv", () => "100.0");
  changed.data[100]["FlowInExNtv-status"] = "reviewed";
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(changed)), "FlowInExNtv"),
    /STATUS/,
  );
});

test("fixed-point conversion is exact", () => {
  const value = sourceProbe.decimal8("57832.8969765", "test");
  assert.equal(value, 5783289697650n);
  assert.equal(sourceProbe.formatDecimal8(value), "57832.89697650");
});
