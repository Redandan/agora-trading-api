# Local Coverage Profiler

## Current boundary

`CoverageProfiler` is a retained pure local fixture tool. It has no Spring,
database, provider, scheduler, MCP, or Production runtime dependency.

The old generic OKX evidence ingestion, append/read services, JPA mappings, and
repositories were removed. Historical migrations and database tables are not
owned by this tool and must not be recreated or cleaned from this document.

## Run locally

The CLI consumes normalized local JSON:

```powershell
mvn -q compile dependency:build-classpath `
  '-Dmdep.outputFile=target/coverage-profiler-classpath.txt'
$cp = "target/classes;" + (Get-Content -Raw target/coverage-profiler-classpath.txt)
& "$env:JAVA_HOME\bin\java.exe" -cp $cp `
  com.agora.service.diagnostic.coverage.CoverageProfilerCli `
  --input docs/samples/coverage-profiler-input.json `
  --output target/coverage-gap-manifest.json
```

The checked-in example output is
`docs/samples/coverage-gap-manifest.json`.

## Contract

Supported dataset names are:

- `md_kline`;
- `market_indicator_history`;
- `bt_decision_audit`;
- `bt_live_signal`;
- `bt_runtime_decision_evidence`.

Ranges use `[requestedStart, requestedEnd)`. Unknown values are never inferred.
Query failure, truncated input, incomplete pagination, duplicate groups, or
provider transitions fail closed.

`structuralCoverageRatio` measures trustworthy unique slots.
`forwardCausalCoverageRatio` additionally requires `FORWARD` provenance and
both effective and available timestamps no later than the decision time.
Historical backfills can satisfy structural coverage but never forward-causal
coverage.

This tool analyzes supplied fixtures only. Its output is not LIVE readiness,
strategy authorization, order evidence, or permission to backfill or mutate
Production.
