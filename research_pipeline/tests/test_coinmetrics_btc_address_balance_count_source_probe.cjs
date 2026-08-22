"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const sourceProbe = require("../../research/coinmetrics_btc_address_balance_count_source_probe.cjs");

const DAY_MS = 24 * 60 * 60 * 1000;

function syntheticPayload({ alternating = true } = {}) {
  const data = [];
  const start = Date.parse("2018-01-01T00:00:00Z");
  for (let index = 0; index < 2557; index += 1) {
    const blockOffset = alternating && Math.floor(index / 28) % 2 === 1 ? 1000000 : 0;
    data.push({
      asset: "btc",
      time: new Date(start + index * DAY_MS).toISOString().replace(".000Z", ".000000000Z"),
      AdrBalCnt: String(20000000 + blockOffset + index),
    });
  }
  return { data };
}

test("source parser freezes the 28-day Sunday feature and both-state support gates", () => {
  const rows = sourceProbe.parseRows(Buffer.from(JSON.stringify(syntheticPayload())));
  assert.equal(rows.length, 2557);
  assert.equal(rows[0].date, "2018-01-01");
  assert.equal(rows.at(-1).date, "2024-12-31");

  const feasibility = sourceProbe.featureFeasibility(rows);
  assert.equal(feasibility.evaluations, 361);
  assert.equal(feasibility.complete_week_count, 365);
  assert.equal(feasibility.excluded_incomplete_tail_days, 2);
  assert.equal(feasibility.first_evaluable_week_end, "2018-02-04");
  assert.equal(feasibility.first_effective_time, "2018-02-07T00:00:00Z");
  assert.equal(feasibility.design.evaluations, 209);
  assert.equal(feasibility.validation.evaluations, 104);
  assert.equal(feasibility.design.support_pass, true);
  assert.equal(feasibility.validation.support_pass, true);
  assert.equal(feasibility.admission_status, "PASS_BOTH_STATE_SUPPORT_BEFORE_BTC_OUTCOME_ACCESS");
});

test("strictly increasing address count fails the frozen both-state admission before outcomes", () => {
  const rows = sourceProbe.parseRows(Buffer.from(JSON.stringify(syntheticPayload({ alternating: false }))));
  const feasibility = sourceProbe.featureFeasibility(rows);
  assert.equal(feasibility.expansion_weeks, 361);
  assert.equal(feasibility.nonexpansion_weeks, 0);
  assert.equal(feasibility.design.support_pass, false);
  assert.equal(feasibility.validation.support_pass, false);
  assert.equal(feasibility.admission_status, "DATA_REJECT_INADEQUATE_BOTH_STATE_SUPPORT_BEFORE_BTC_OUTCOME_ACCESS");
});

test("source parser rejects pagination, gaps and invalid address counts", () => {
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
  zero.data[100].AdrBalCnt = "0";
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(zero))),
    /ADDRESS_BALANCE_COUNT_INTEGER/,
  );

  const fractional = syntheticPayload();
  fractional.data[100].AdrBalCnt = "100.5";
  assert.throws(
    () => sourceProbe.parseRows(Buffer.from(JSON.stringify(fractional))),
    /ADDRESS_BALANCE_COUNT_INTEGER/,
  );
});
