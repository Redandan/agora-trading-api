"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const sourceProbe = require("../../research/coinmetrics_btc_native_issuance_contraction_source_probe.cjs");

const DAY_MS = 24 * 60 * 60 * 1000;

function syntheticPayload({ alternating = true } = {}) {
  const data = [];
  const start = Date.parse("2017-01-01T00:00:00Z");
  const levels = [900, 1200, 800, 1100, 700, 1000];
  for (let index = 0; index < 2922; index += 1) {
    const value = alternating ? levels[Math.floor(index / 70) % levels.length] : 1000;
    data.push({
      asset: "btc",
      time: new Date(start + index * DAY_MS).toISOString().replace(".000Z", ".000000000Z"),
      IssTotNtv: `${value}.00000000`,
    });
  }
  return { data };
}

test("source parser freezes the year-paired Sunday feature and concentration gates", () => {
  const rows = sourceProbe.parseRows(Buffer.from(JSON.stringify(syntheticPayload())));
  assert.equal(rows.length, 2922);
  assert.equal(rows[0].date, "2017-01-01");
  assert.equal(rows.at(-1).date, "2024-12-31");

  const feasibility = sourceProbe.featureFeasibility(rows);
  assert.equal(feasibility.evaluations, 362);
  assert.equal(feasibility.first_evaluable_week_end, "2018-01-28");
  assert.equal(feasibility.first_effective_time, "2018-01-31T00:00:00Z");
  assert.equal(feasibility.design.evaluations, 209);
  assert.equal(feasibility.validation.evaluations, 104);
  assert.equal(feasibility.design.support_pass, true);
  assert.equal(feasibility.validation.support_pass, true);
  assert.equal(feasibility.admission_status, "PASS_ALL_PRE_OUTCOME_SUPPORT_AND_CONCENTRATION_GATES");
});

test("constant issuance fails both-state and transition admission before outcomes", () => {
  const rows = sourceProbe.parseRows(Buffer.from(JSON.stringify(syntheticPayload({ alternating: false }))));
  const feasibility = sourceProbe.featureFeasibility(rows);
  assert.equal(feasibility.supportive_weeks, 0);
  assert.equal(feasibility.other_weeks, 362);
  assert.equal(feasibility.design.support_pass, false);
  assert.equal(feasibility.validation.support_pass, false);
  assert.equal(feasibility.admission_status, "DATA_REJECT_PRE_OUTCOME_SUPPORT_OR_CONCENTRATION_GATE_FAILURE");
});

test("source parser rejects pagination, gaps and invalid issuance decimals", () => {
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

  const zero = syntheticPayload();
  zero.data[100].IssTotNtv = "0";
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(zero))),
    /ISSUANCE_RANGE/,
  );

  const overPrecision = syntheticPayload();
  overPrecision.data[100].IssTotNtv = "100.123456789";
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(overPrecision))),
    /ISSUANCE_DECIMAL/,
  );
});
