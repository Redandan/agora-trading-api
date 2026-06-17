# Trading Schema Baseline Prep

This repo contains a reviewable Flyway baseline migration at
`src/main/resources/db/migration/V1__baseline.sql`.

Current production schema mode should be:

- `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`
- `SPRING_FLYWAY_ENABLED=true`
- `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`
- `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`
- `SPRING_FLYWAY_BASELINE_VERSION=1`

The Trading service uses a Trading-owned Flyway history table so it does not mix
with AgoraMarketAPI's existing `flyway_schema_history` rows in the shared
`agora_market` database.

## Read-Only Inventory

Generate the JPA entity table inventory:

```powershell
.\scripts\schema_baseline_inventory.ps1
```

Outputs:

- `target/schema-baseline/entity-tables.txt`
- `target/schema-baseline/implicit-entities.txt`
- `target/schema-baseline/forbidden-marketplace-tables.txt`
- `target/schema-baseline/unsafe-table-names.txt`

This is source inventory only. It does not connect to MySQL, write migrations, or mutate runtime configuration.
The inventory fails if any JPA entity relies on an implicit table name; baseline
generation requires explicit `@Table(name = "...")` mappings.
The inventory also fails if a trading entity maps to an obvious marketplace-owned
table such as users, products, carts, orders, stores, delivery, or wallet tables.
It also rejects unsafe table names outside `[A-Za-z0-9_]` before any baseline
dump workflow can use those names.

## Read-Only Server Compare

On the server, compare the source inventory against the configured shared
trading database:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/schema_baseline_compare_server.sh
```

The same comparison can be included in server verification:

```bash
RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh
```

The compare script reads `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and
`SPRING_DATASOURCE_PASSWORD` from `/home/ubuntu/.env.trading.secrets`, queries
`information_schema.tables`, and writes these read-only outputs:

Those datasource env keys must be present and non-empty even when the compare
script is run directly instead of through `scripts/verify_server.sh`.
The compare also fails before querying MySQL unless the datasource points at
the expected shared database, `agora_market` by default.

- `target/schema-baseline/server-source-entity-tables.txt`
- `target/schema-baseline/server-implicit-entities.txt`
- `target/schema-baseline/server-forbidden-marketplace-tables.txt`
- `target/schema-baseline/server-unsafe-source-tables.txt`
- `target/schema-baseline/server-db-forbidden-marketplace-tables.txt`
- `target/schema-baseline/server-db-known-system-tables.txt`
- `target/schema-baseline/server-db-tables.txt`
- `target/schema-baseline/missing-in-db.txt`
- `target/schema-baseline/extra-in-db.txt`

It must not print database passwords, write migrations, or mutate the database.
The compare fails if any server-side source entity relies on an implicit table
name, matching the local inventory requirement.
It also fails before database comparison if source entity mappings include an
obvious marketplace-owned table name.
It also fails if source entity mappings include unsafe table names outside
`[A-Za-z0-9_]`.
It also writes `server-db-forbidden-marketplace-tables.txt` to report
marketplace-owned tables seen in the target database. In
`SCHEMA_COMPARE_MODE=shared`, these rows are expected because Trading uses the
shared `agora_market` database. In `SCHEMA_COMPARE_MODE=standalone`, they still
fail the compare.
The source and database marketplace checks share one shell pattern in
`scripts/schema_baseline_compare_server.sh` so the two lists cannot drift.
`server-db-known-system-tables.txt` classifies known non-entity system tables
such as `flyway_schema_history` and `trading_flyway_schema_history`. In shared
mode, `extra-in-db.txt` is reported for visibility but does not fail
acceptance; missing trading entity tables still fail.

## Extra Table Cleanup Planning

Extra-table cleanup is a standalone-DB-only historical/operator path. It is
disabled in shared DB mode because marketplace/shared tables are expected in
`agora_market`.

If `SCHEMA_COMPARE_MODE=standalone` and the server compare reports only empty
residual extra tables, generate a review-only cleanup plan:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/schema_extra_tables_cleanup_plan_server.sh
```

The cleanup-plan script reads the existing `extra-in-db.txt`, queries exact
`COUNT(*)` values for every listed table, and writes:

- `target/schema-baseline/extra-table-row-counts.tsv`
- `target/schema-baseline/extra-table-cleanup-plan.sql`

It must not execute `DROP TABLE` or mutate the database. The generated SQL keeps
drop statements commented out so an operator must explicitly review, back up the
database, re-run the compare, and choose what to apply. If any extra table has
rows, the cleanup-plan script fails.

After review, a guarded operator script can create a fresh MySQL backup and
optionally apply the empty-table cleanup:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/schema_extra_tables_cleanup_apply_server.sh
```

The apply script is dry-run by default. It re-runs the cleanup planner, refuses
to continue if any listed table has rows, and writes a full database backup under
`/home/ubuntu/backups/agora-trading-api-schema-cleanup/`. If `extra-in-db.txt`
is missing, it first runs the read-only schema compare to regenerate comparison
outputs, and then continues only if the extra-table list exists. It drops tables
only when `APPLY_SCHEMA_EXTRA_TABLE_CLEANUP=1` is explicitly set for that
command:

```bash
APPLY_SCHEMA_EXTRA_TABLE_CLEANUP=1 bash scripts/schema_extra_tables_cleanup_apply_server.sh
```

Re-run `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` immediately
after any applied cleanup.

## Baseline Acceptance

For baseline drift review and future schema-change acceptance:

- Compare `target/schema-baseline/entity-tables.txt` with the real shared
  `agora_market` database tables.
- Run `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` and resolve
  any `missing-in-db.txt` rows.
- Keep `SCHEMA_COMPARE_MODE=shared` for the current split. Do not run
  extra-table cleanup in shared DB mode.
- Confirm no marketplace-owned tables are mapped by trading source entities.
- Keep the explicit `V1__baseline.sql` under `src/main/resources/db/migration`.
- Set production to `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`.
- Set production to `SPRING_FLYWAY_ENABLED=true`.
- Set production to `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`.
- Set production to `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` for existing
  shared-schema adoption.
- Enable `meta-control.migration-drift-check.enabled=true` only after the baseline exists.
- Keep MCP `getAppliedMigrations` and `MigrationDriftChecker` on the same
  Trading-owned `trading_flyway_schema_history` table; do not read
  AgoraMarketAPI's shared `flyway_schema_history` for Trading migration status.

## Baseline Generation

After the shared-mode compare passes on the server, generate the reviewable
baseline DDL without mutating MySQL:

```bash
cd /home/ubuntu/agora-trading-api
bash scripts/schema_baseline_generate_server.sh
```

The generator:

- re-runs `scripts/schema_baseline_compare_server.sh` in `SCHEMA_COMPARE_MODE=shared`;
- fails if any trading entity table is missing from `agora_market`;
- dumps DDL only for tables listed in
  `target/schema-baseline/server-source-entity-tables.txt`;
- excludes shared marketplace extra tables by construction;
- writes `src/main/resources/db/migration/V1__baseline.sql`.

This script does not enable Flyway, does not change `ddl-auto`, and does not run
extra-table cleanup. Review and commit the generated migration separately before
any deploy that changes production schema settings.

## Production Verification

The 2026-06-13 hardening deploy verified the baseline in production with:

- `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`
- `SPRING_FLYWAY_ENABLED=true`
- `SPRING_FLYWAY_TABLE=trading_flyway_schema_history`
- `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`
- `SPRING_FLYWAY_BASELINE_VERSION=1`

Flyway created `trading_flyway_schema_history` in the shared `agora_market`
database and recorded baseline version `1`. The post-hardening shared-mode
compare passed with 39 source entity tables, 0 missing trading tables, 176
database tables, 2 known system tables, and 137 expected marketplace/shared
extra tables.

Future Trading schema changes should be added as `V2__...` migrations under
`src/main/resources/db/migration`. Do not use AgoraMarketAPI's
`flyway_schema_history` table for Trading migrations.
