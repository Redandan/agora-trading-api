"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const REPO_ROOT = path.resolve(__dirname, "../..");
const RUNNER_PATH = path.join(REPO_ROOT, "research/btc_daily_sma200_long_cash_historical.cjs");
const MANIFEST_PATH = path.join(
  REPO_ROOT,
  "research_pipeline/examples/btc-daily-sma200-long-cash-historical.v1.manifest.json",
);
const DECISION_PATH = path.join(
  REPO_ROOT,
  "research_pipeline/examples/btc-daily-sma200-long-cash-historical.v1.decision.json",
);
const runner = require(RUNNER_PATH);

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

test("manifest policy and every source binding are frozen", () => {
  const manifest = JSON.parse(fs.readFileSync(MANIFEST_PATH, "utf8"));
  runner.validateManifest(manifest);
  for (const binding of manifest.source_bindings) {
    const source = path.join(REPO_ROOT, binding.path);
    assert.equal(sha256(source), binding.sha256, binding.path);
  }
});

test("prior SMA excludes the current day and future data", () => {
  const day = 86400000;
  const start = Date.UTC(2019, 0, 2);
  const daily = Array.from({ length: 300 }, (_, index) => ({
    closeTime: start + index * day,
    close: 100 + index,
  }));
  for (const period of [150, 200, 250]) {
    const targets = runner.targetsByExecutionTime(daily, period);
    assert.equal(targets.size, daily.length - period);
    assert.ok([...targets.values()].every(Boolean));
  }
  const original = runner.targetsByExecutionTime(daily.slice(0, 260), 200);
  const extended = runner.targetsByExecutionTime(daily, 200);
  assert.deepEqual([...original], [...extended].slice(0, original.size));
});

test("only preregistered SMA periods are accepted", () => {
  const daily = Array.from({ length: 300 }, (_, index) => ({
    closeTime: Date.UTC(2019, 0, 2 + index),
    close: 100 + index,
  }));
  assert.throws(
    () => runner.targetsByExecutionTime(daily, 199),
    /MANIFEST_REJECT:SMA_POLICY/,
  );
});

test("decision binds two byte-identical sealed runs", () => {
  const decision = JSON.parse(fs.readFileSync(DECISION_PATH, "utf8"));
  assert.equal(decision.manifest_sha256, sha256(MANIFEST_PATH));
  assert.equal(decision.runner.sha256, sha256(RUNNER_PATH));
  const artifact = path.join(REPO_ROOT, decision.artifact.path);
  const replication = path.join(REPO_ROOT, decision.deterministic_replication.path);
  assert.equal(sha256(artifact), decision.artifact.sha256);
  assert.equal(sha256(replication), decision.deterministic_replication.sha256);
  assert.deepEqual(fs.readFileSync(artifact), fs.readFileSync(replication));
  const result = JSON.parse(fs.readFileSync(artifact, "utf8"));
  assert.equal(result.status, decision.status);
  assert.equal(result.decision, decision.decision);
  assert.deepEqual(result.failed_gates, decision.failed_gates);
  assert.equal(result.gates.buy_hold_reference_ledger_parity_pass, true);
  assert.equal(result.oos_opened, false);
});
