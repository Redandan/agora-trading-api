#!/usr/bin/env node
"use strict";

const assert = require("assert");
const {
  populationBands,
  targetsByExecutionTime,
} = require("../../research/btc_daily_bollinger20_2_long_cash_historical.cjs");

function point(day, close) {
  return { closeTime: Date.UTC(2020, 0, 1 + day), close };
}

function testConstantPopulationBands() {
  const bands = populationBands(Array(20).fill(100));
  assert.strictEqual(bands.mean, 100);
  assert.strictEqual(bands.lower, 100);
  assert.strictEqual(bands.upper, 100);
}

function testKnownPopulationBands() {
  const bands = populationBands(Array.from({ length: 20 }, (_, index) => index + 1));
  assert.strictEqual(bands.mean, 10.5);
  assert.ok(Math.abs(bands.lower - (-1.0325625946707973)) < 1e-12);
  assert.ok(Math.abs(bands.upper - 22.032562594670797) < 1e-12);
}

function testStrictLowerEntryAndUpperExit() {
  const closes = [
    ...Array(20).fill(100),
    70,
    ...Array(18).fill(100),
    140,
    100,
  ];
  const daily = closes.map((close, index) => point(index, close));
  const targets = targetsByExecutionTime(daily);
  assert.strictEqual(targets.get(daily[20].closeTime), true);
  assert.strictEqual(targets.get(daily[39].closeTime), false);
  assert.strictEqual(targets.get(daily[40].closeTime), false);
}

function testTouchDoesNotCross() {
  const daily = Array.from({ length: 25 }, (_, index) => point(index, 100));
  const targets = targetsByExecutionTime(daily);
  for (const value of targets.values()) assert.strictEqual(value, false);
}

const tests = [
  testConstantPopulationBands,
  testKnownPopulationBands,
  testStrictLowerEntryAndUpperExit,
  testTouchDoesNotCross,
];
for (const test of tests) test();
process.stdout.write(`${tests.length} Bollinger20/2 formula and signal tests passed\n`);
