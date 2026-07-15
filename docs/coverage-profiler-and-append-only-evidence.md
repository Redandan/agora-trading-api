# Coverage Profiler And Append-Only Evidence

## Status And Boundary

This batch implements a local fixture-driven coverage profiler and a forward-only
`V2__append_only_execution_evidence.sql` migration. It does not register an MCP
tool or HTTP route, connect to a provider, start a collector or scheduler, or
enable strategy, order, OCO, Grid, Earn, fund, or Telegram behavior.

Status at handoff:

- Implemented locally: profiler, machine-readable manifest, CLI, focused tests,
  four create-only tables, immutable JPA mappings, read-only repositories, and a
  read-only summary service.
- Design only: retention execution and provider pagination/recovery workflow.
- Requires separate provider authorization: authenticated quote, fill/fee,
  funding-bill, and margin ingestion.
- Deployable after independent audit: migration and empty-table/read-only runtime
  compatibility. It has not been pushed or deployed by this batch.

## Local Coverage Profiler

The CLI consumes normalized, locally supplied JSON. It does not start Spring and
does not open database or network connections.

```powershell
mvn -q compile dependency:build-classpath `
  '-Dmdep.outputFile=target/coverage-profiler-classpath.txt'
$cp = "target/classes;" + (Get-Content -Raw target/coverage-profiler-classpath.txt)
& "$env:JAVA_HOME\bin\java.exe" -cp $cp `
  com.agora.service.diagnostic.coverage.CoverageProfilerCli `
  --input docs/samples/coverage-profiler-input.json `
  --output target/coverage-gap-manifest.json
```

Supported datasets are `md_kline`, `market_indicator_history`,
`bt_decision_audit`, `bt_live_signal`, and
`bt_runtime_decision_evidence`. Input adapters must provide a stable dedupe key,
provider, provenance, event/effective/available/ingested timestamps, and the
decision time. Unknown values are not inferred and never count as clean.

Ranges use `[requestedStart, requestedEnd)`. A query failure, truncated page, or
incomplete pagination forces `coverageRatio=0` and a full-range
`QUERY_FAILED`/`PAGE_INCOMPLETE` gap even when partial rows were returned.
Duplicate groups fail closed as a group; the profiler never chooses a winner.

`cleanCount` measures structurally trustworthy rows. `forwardCausalCount` is a
stricter numerator: provenance must be `FORWARD`, and both
`effectiveAt <= decisionTime` and `availableAt <= decisionTime` must hold.
`HISTORICAL_BACKFILL` rows may be clean historical evidence but never enter the
forward causal numerator. Intersection coverage requires a causal slot from
every requested dataset.

`HOURLY_SCALAR` with `EXECUTABLE_QUOTE` or `EXECUTABLE_DEPTH` usage is rejected;
hourly indicator values are not executable top-of-book/depth evidence.

## V2 Evidence Tables

Migration dependency: the reviewed `V1__baseline.sql`. V2 creates only these
tables and does not alter or reference another shared table:

| Table | Immutable/dedupe identity | Signed meaning / payload |
| --- | --- | --- |
| `executable_quote_snapshot` | SHA-256 `dedupe_key`; provider/symbol/event lookup | Positive bid/ask and sizes; bid must not exceed ask; only `QUOTE`/`DEPTH` |
| `fill_fee_ledger` | SHA-256 `dedupe_key`; unique provider/account-hash/trade/currency | `signed_fee_amount`: cost negative, rebate positive |
| `funding_bill_ledger` | SHA-256 `dedupe_key`; unique provider/account-hash/bill | `signed_funding_amount`: paid negative, received positive |
| `margin_snapshot` | SHA-256 `dedupe_key`; account/event lookup | Non-negative equity, available, used, maintenance margin, and ratio |

All four tables require provider, `event_at`, `provider_at`, `received_at`,
`ingested_at`, forward/backfill `source_mode`, raw SHA-256, and retention class.
Cursor and page keys preserve provider pagination. Gap linkage is either fully
absent or fully populated with manifest id, dataset, and a valid half-open gap
range. Account references are hashes; no account key or credential is stored.
There are no cascade foreign keys.

JPA entities are `@Immutable`; repositories deliberately expose no save, update,
or delete method. The current service is read-only. Future ingestion must use a
separately reviewed append path that treats a duplicate-key conflict as an
idempotent replay only after the immutable raw hash and semantic fields match;
otherwise it must stop rather than overwrite.

## Retention Design

Rows default to `TRADING_EVIDENCE_LONG`; `retain_until` may be null when policy
has not been approved. No purge or archival job is implemented. A future
retention action requires separate authorization, an immutable export/hash
receipt, legal-hold handling, and a reviewed delete boundary. The current
append-only service contract must not be weakened to implement retention.

## Forward-Only Deployment Plan

Preconditions:

1. Independent audit passes and the exact commit is authorized for deployment.
2. Production is confirmed on the reviewed V1 baseline with
   `trading_flyway_schema_history`; no unresolved migration is present.
3. A current schema-only backup/restore point and table-size/free-space snapshot
   are recorded.
4. Provider ingestion, collectors, schedulers, and all trading mutation gates
   remain off.

Apply V2 through the normal Flyway startup. `CREATE TABLE` takes metadata locks;
it does not scan or rebuild existing shared tables. Stop if a target table
already exists outside Flyway history, Flyway checksum/history differs, the DB
is not the expected shared `agora_market`, metadata-lock wait crosses the
operator threshold, free space is below the approved reserve, or startup emits
schema validation/SQL errors.

Index risk is initially bounded because all four tables are empty. Quote data is
the expected high-volume table; it has one unique and two secondary indexes.
Fee/funding tables each have two unique and two secondary indexes. Margin has
one unique and three secondary indexes. Before any ingestion authorization,
estimate provider row rate, daily bytes, index amplification, retention horizon,
and disk headroom from a representative local/staging payload.

Startup compatibility requires Flyway V2 success, Hibernate `validate`, and the
application starting with all four tables empty. No startup writer exists.

Read-only acceptance after a separately authorized deploy:

- Flyway history contains V2 with the reviewed checksum.
- `SHOW CREATE TABLE` matches the four create-only definitions and no other
  shared table changed.
- all four table counts are zero before provider ingestion;
- health and standard split verification pass;
- the read service reports `READ_ONLY_NO_PROVIDER_INGESTION` and no query error;
- runtime logs contain no provider auth call, insert, scheduler, strategy,
  order/OCO/Grid/Earn/fund/Telegram action attributable to this batch.

## Rollback And Stop Conditions

This is a forward-only migration. Do not automatically drop the tables on an
application rollback. If startup fails after V2, stop the new runtime and return
traffic to the prior compatible runtime; leave V2 and Flyway history intact for
diagnosis. Because the previous runtime has no mappings to these new tables, the
empty tables are additive and compatible. Dropping tables or editing Flyway
history requires a new explicit destructive-schema authorization and is not a
rollback step in this plan.

Stop immediately on unexpected non-zero rows, duplicate/check-constraint errors,
unknown provenance/timestamps, provider calls, writes outside the four tables,
lock pressure on shared tables, Flyway drift, disk alarms, or any high-risk
runtime behavior.
