# Local weekly output classification V1

This companion is prospective Manager-side evidence-quality tooling. It does not classify historical output retroactively, write a report, scan Local history, or create research authority. The Server Canonical process remains the sole state writer and Codex Cloud Ops remains the sole research clock.

## Two immutable stages

`PRE_DISPATCH_INTENT` is authored and committed by the Manager after the exact Local task and performance dispatch exist but before Local execution. It freezes the output identity and class, the exact task and dispatch bytes, unique disposition-to-action mappings, the canonical JSON-scalar claim-boundary digest, duplicate-family identity, independence semantics, research-only authorization, and zero candidate variants.

`MANAGER_ACCEPTANCE` is authored only after the Manager validates and commits the exact Local result. It binds the prior intent bytes, task, dispatch and result identities and hashes, the result status and source commit, the accepted disposition, the accepted-result commit, all-false safety assertions, the final `COUNT` or `EXCLUDE` outcome, and an acceptance time. An exclusion carries a nonempty reason; a count carries `null`. A prospective mechanism conclusion may additionally bind one `strategy_path_evidence` path and SHA-256. That evidence is optional for backward compatibility, but without it the row is a legacy mechanism-label proxy rather than verified candidate delivery.

The schema is Draft 2020-12 and recursively closed. Both records use canonical compact UTF-8 JSON with sorted object keys and exactly one trailing LF. Unknown keys, duplicate JSON keys, path traversal, non-UTC times, unknown enums, or unsafe assertions fail closed.

## Read-only validation

`validate_weekly_output_classification(repository_root, acceptance_paths, period_start, period_end)` accepts one explicit nonempty allowlist of acceptance paths and one nonempty half-open UTC interval. It performs no directory or Git-history scan and writes no file, cache, report, state, or repository object.

For each acceptance the validator requires:

- a clean current Git HEAD and exact acceptance bytes at `HEAD:path`;
- exact intent bytes both in the current path and at `result_source_git_commit:intent_path`;
- exact result bytes at `accepted_result_commit:result_path`;
- source-to-result-to-current commit ancestry;
- existing task, dispatch, and result semantic validators to accept the complete closure;
- exact task, dispatch, result, disposition, claim, time, safety, and Manager bindings;
- when strategy-path evidence is present, exact sidecar bytes at both current HEAD and the result source commit, plus a recomputed binding from the decision feature, parent, matched comparator, and runner to the frozen task inputs;
- result completion inside `[period_start, period_end)`.

Timestamps are sanity checks, never substitutes for Git-object proof. `claim_boundary_sha256` is SHA-256 of the no-LF canonical UTF-8 JSON scalar bytes of the exact dispatch `performance_case.claim_boundary` string.

## Counting and family rules

The only output classes are `MECHANISM_CONCLUSION`, `SPEC_OR_CAPABILITY_SLICE`, and `NON_COUNTING`. `COMPLETED` is necessary but not sufficient for `COUNT`; `BLOCKED` and `FAILED` always exclude. `NON_COUNTING` may only exclude. The accepted disposition must map to exactly one action frozen in the pre-dispatch intent.

The validator rejects duplicate output, acceptance, intent, and result bindings. Multiple counted rows may share a `duplicate_family_key` only when every member is `NESTED_NON_INDEPENDENT` and every member has the same output class. The deterministic in-memory supplement reports lexicographically sorted rows and exclusions, raw counts by output class, distinct family counts by output class, and whether each row has verified strategy-path admission. Raw nested conclusions do not imply additional alpha confidence. Mechanism labels without verified admission remain visible for historical comparison but cannot satisfy the candidate-delivery efficiency gate.

## Boundary

This bootstrap task creates no intent or acceptance record and cannot count itself. Classification authority begins only after the Manager independently validates and commits this implementation. The companion does not prove prediction, fees, slippage, utilization, capacity, matched-capital PnL, drawdown, R2 completion, candidate readiness, OOS, deployment, or Trading authority; immediate PnL and drawdown effects are zero.
