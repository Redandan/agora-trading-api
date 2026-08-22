#!/usr/bin/env node
"use strict";

const assert = require("assert");
const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "../..");
const sourceProbe = require(path.join(REPO_ROOT, "research/coinmetrics_stablecoin_liquidity_source_probe.cjs"));
const runner = require(path.join(REPO_ROOT, "research/btc_stablecoin_liquidity_growth_long_cash_historical.cjs"));

const MANIFEST = path.join(REPO_ROOT, "research_pipeline/examples/btc-stablecoin-liquidity-growth-long-cash-historical.v1.manifest.json");
const RAW = path.join(REPO_ROOT, ".research-state/experiments/btc-stablecoin-liquidity-growth-long-cash-historical-v1/inputs/coinmetrics-usdt-usdc-stablecoin-liquidity-2018-2024-raw.json");
const LIQUIDITY = path.join(REPO_ROOT, ".research-state/experiments/btc-stablecoin-liquidity-growth-long-cash-historical-v1/inputs/coinmetrics-usdt-usdc-stablecoin-liquidity-2018-2024.csv");
const BTC = path.join(REPO_ROOT, ".research-state/java-parity/selection-2019-2024.tsv");
const RUN1 = path.join(REPO_ROOT, ".research-state/experiments/btc-stablecoin-liquidity-growth-long-cash-historical-v1/artifacts/run1.json");
const RUN2 = path.join(REPO_ROOT, ".research-state/experiments/btc-stablecoin-liquidity-growth-long-cash-historical-v1/artifacts/run2.json");

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function test(name, fn) {
  fn();
  process.stdout.write(`ok - ${name}\n`);
}

test("sealed raw source has two complete assets and frozen feasibility", () => {
  const rows = sourceProbe.parseRows(fs.readFileSync(RAW));
  const feasibility = sourceProbe.featureFeasibility(rows);
  assert.strictEqual(rows.length, 2287);
  assert.strictEqual(rows[0].date, "2018-09-28");
  assert.strictEqual(rows.at(-1).date, "2024-12-31");
  assert.deepStrictEqual(feasibility.variants.PRIMARY_COMBINED, {
    evaluations: 2259,
    positive_days: 1682,
    negative_days: 577,
    transitions: 63,
  });
});

test("normalized source and target timing are frozen", () => {
  const parsed = runner.parseLiquidityRows(LIQUIDITY);
  assert.strictEqual(parsed.digest, "144bd8a20d1954b09f20e85cdf8f16fd8befd201cb77da824ba57562573a7d81");
  const targets = runner.targetsByEffectiveTime(parsed.rows, "COMBINED");
  assert.strictEqual(targets.size, 2259);
  assert.strictEqual([...targets.keys()][0], Date.parse("2018-10-28T00:00:00Z"));
});

test("manifest binds the exact frozen runner and sources", () => {
  const manifest = JSON.parse(fs.readFileSync(MANIFEST, "utf8"));
  runner.validateManifest(manifest);
  const bindings = Object.fromEntries(manifest.source_bindings.map((binding) => [binding.path, binding.sha256]));
  for (const [relative, digest] of Object.entries(bindings)) {
    assert.strictEqual(sha256(path.join(REPO_ROOT, relative)), digest, relative);
  }
});

test("tampered normalized source fails closed", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "agora-stablecoin-source-"));
  const tampered = path.join(directory, "tampered.csv");
  fs.writeFileSync(tampered, `${fs.readFileSync(LIQUIDITY, "utf8")}\n`);
  assert.throws(() => runner.parseLiquidityRows(tampered), /LIQUIDITY_REJECT:SHA256/);
});

test("sealed reruns are byte-identical and permanently reject the family", () => {
  assert.strictEqual(sha256(RUN1), "927bbd73a575f39efca9f73f5b3a6b5820749dd34702fac35cb210912b08127e");
  assert.strictEqual(sha256(RUN2), sha256(RUN1));
  assert.ok(fs.readFileSync(RUN1).equals(fs.readFileSync(RUN2)));
  const result = JSON.parse(fs.readFileSync(RUN1, "utf8"));
  assert.strictEqual(result.status, "NO_CANDIDATE_CLOSE_BTC_STABLECOIN_LIQUIDITY_GROWTH_LONG_CASH_FAMILY");
  assert.strictEqual(result.oos_opened, false);
  assert.strictEqual(result.all_gates_pass, false);
  assert.ok(result.failed_gates.includes("primary_validation_normal_calmar_at_least_buy_hold"));
});

test("recomputed economics preserve the sealed decision", () => {
  const recomputed = runner.buildOutput(BTC, LIQUIDITY, MANIFEST);
  const sealed = JSON.parse(fs.readFileSync(RUN1, "utf8"));
  assert.strictEqual(recomputed.status, sealed.status);
  assert.deepStrictEqual(recomputed.failed_gates, sealed.failed_gates);
  assert.deepStrictEqual(recomputed.windows.validation, sealed.windows.validation);
  assert.deepStrictEqual(recomputed.annual_fair_reset, sealed.annual_fair_reset);
});
