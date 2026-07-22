# Legacy Grid Immutable Archive Contract

## Boundary

`LEGACY_CUSTOM_GRID_ARCHIVE_V1` is the evidence artifact required before the
custom Grid runtime can be removed. Creating or verifying this artifact does
not authorize a Grid disposition, provider order, Bot action, Production DB
mutation, deployment, runtime deletion, or table drop.

The Production exporter may run only after every legacy Grid is closed and the
counts of `HOLDING`, `SELL_FAILED`, `SELL_PARTIAL`, `PENDING_OKX`, and
`SELLING_OKX` are zero. It uses database `SELECT` and authenticated OKX `GET`
requests only.

## Envelope

The archive is one JSON object with:

- `schemaVersion=LEGACY_CUSTOM_GRID_ARCHIVE_V1`;
- `payloadEncoding=base64-utf8-json`;
- `payloadSha256`, the lowercase SHA-256 of the exact decoded payload bytes;
- `payloadBase64`, the immutable UTF-8 JSON payload.

The payload contains the deployed full commit, collection time, all `bt_grid`
and `bt_grid_level` rows, provider order details, provider fills, and a
reconciliation object. Hash verification occurs before any semantic field is
trusted.

## Exact-net rules

For every level with a completed BUY/SELL pair:

1. BUY base flow is fill quantity plus signed BTC fee. BUY quote cost is fill
   price times quantity, adjusted by signed USDT fee.
2. SELL base disposed is fill quantity plus any BTC-denominated sell fee. SELL
   quote proceeds are fill price times quantity plus signed USDT fee.
3. Cost basis per net acquired BTC is exact BUY quote cost divided by net BUY
   base flow.
4. Pair realized exact-net PnL is signed-fee SELL quote proceeds minus the
   proportional exact BUY cost basis of base disposed.
5. Unsold base is `attributedDustBtc`; it is not valued as realized PnL.

Every provider fill must include order ID, side, price, quantity, signed fee,
and fee currency. Fee currencies other than BTC or USDT fail closed. Database
`total_realized_pnl`, gross fill quantity, account balance changes, and
unrealized marks are retained as comparison evidence but cannot substitute for
the provider-derived exact-net calculation.

The sum of `bt_grid.closed_pair_count` must exactly equal the number of
provider-covered pairs reconstructed by the archive. The legacy recycler
cleared a level's BUY/SELL order IDs when returning it to `PENDING`; therefore
an archive that contains only the surviving level order IDs must fail with
`HISTORICAL_COMPLETED_PAIR_COVERAGE_INCOMPLETE`. It is forbidden to silently
replace missing historical pair evidence with the DB aggregate PnL.

## Local verification

Before calling any provider archive exporter, quantify whether Production still
has order-ID coverage for every historical completed pair:

```powershell
.\scripts\prepare_legacy_grid_archive_source_gap_ssh.ps1 `
  -ExpectedCommit <FULL_PRODUCTION_COMMIT>
```

`BLOCKED_HISTORICAL_ARCHIVE_SOURCE_GAP` means an exporter must not fabricate a
complete archive from the surviving rows.

When historical provider order IDs are missing, check whether the already
prepared prior-quarter OKX bills archive is available using authenticated GET
only:

```powershell
.\scripts\check_okx_bills_history_archive_availability_ssh.ps1 `
  -Year 2026 -Quarter Q2
```

This check redacts `fileHref` and never applies for or downloads an archive.
If OKX reports no prepared file, the required POST archive application is a
separate external provider action and must not be inferred from this GET.

Generate the exact state-bound request confirmation without sending POST:

```powershell
.\scripts\request_okx_bills_history_archive_ssh.ps1 `
  -ExpectedCommit <FULL_PRODUCTION_COMMIT> `
  -Year 2026 -Quarter Q2
```

Execution requires the exact returned confirmation plus `-Execute`. It sends
at most one fixed archive POST and forbids retry after an ambiguous response;
reconciliation must return to GET. This is not a trading-order authorization.

After GET reports one finished file, download it without a server-side
temporary file and inspect it locally without extraction:

```powershell
.\scripts\download_okx_bills_history_archive_ssh.ps1 `
  -ExpectedCommit <FULL_PRODUCTION_COMMIT> `
  -Year 2026 -Quarter Q2 `
  -OutputPath <NEW_LOCAL_PATH>

.\scripts\inspect_okx_bills_history_archive.ps1 `
  -ArchivePath <NEW_LOCAL_PATH>
```

The downloader refuses overwrite, redacts the provider URL, verifies byte
count and SHA-256 before writing one new local file, and performs no server
filesystem write. Structural inspection remains
`NOT_GRID_ATTRIBUTED`; it cannot substitute for unique 46-pair reconciliation.

```powershell
.\scripts\verify_legacy_grid_immutable_archive.ps1 `
  -ArchivePath <archive.json> `
  -RequirePass
```

The strongest local result is
`PASS_LEGACY_GRID_IMMUTABLE_ARCHIVE_LOCAL_COMPONENT_ONLY`. Runtime removal
still requires independent Production post-export reconciliation and the other
Gate B evidence. Physical table deletion remains a later, separately
authorized DB migration.
