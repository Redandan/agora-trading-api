# Trading Schema Baseline Prep

This repo does not yet contain a Flyway baseline migration.

Current production bootstrap mode remains:

- `SPRING_JPA_HIBERNATE_DDL_AUTO=update`
- `SPRING_FLYWAY_ENABLED=false`

Do not enable Flyway until the baseline has been generated from the real trading database schema and reviewed.

## Read-Only Inventory

Generate the JPA entity table inventory:

```powershell
.\scripts\schema_baseline_inventory.ps1
```

Outputs:

- `target/schema-baseline/entity-tables.txt`
- `target/schema-baseline/implicit-entities.txt`
- `target/schema-baseline/forbidden-marketplace-tables.txt`

This is source inventory only. It does not connect to MySQL, write migrations, or mutate runtime configuration.
The inventory fails if any JPA entity relies on an implicit table name; baseline
generation requires explicit `@Table(name = "...")` mappings.
The inventory also fails if a trading entity maps to an obvious marketplace-owned
table such as users, products, carts, orders, stores, delivery, or wallet tables.

## Read-Only Server Compare

On the server, compare the source inventory against the configured trading database:

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
the standalone trading database, `agora_trading` by default.

- `target/schema-baseline/server-source-entity-tables.txt`
- `target/schema-baseline/server-implicit-entities.txt`
- `target/schema-baseline/server-forbidden-marketplace-tables.txt`
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
It also writes and fails on `server-db-forbidden-marketplace-tables.txt` if the
target trading database contains obvious marketplace-owned tables.
The source and database marketplace checks share one shell pattern in
`scripts/schema_baseline_compare_server.sh` so the two lists cannot drift.
`server-db-known-system-tables.txt` classifies known non-entity system tables
such as `flyway_schema_history`, but this does not relax `extra-in-db.txt`
failure before baseline acceptance.

## Extra Table Cleanup Planning

If the server compare reports only empty residual extra tables, generate a
review-only cleanup plan:

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

Before replacing Hibernate schema update with Flyway validation:

- Compare `target/schema-baseline/entity-tables.txt` with the real `agora_trading` database tables.
- Run `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` and resolve any `missing-in-db.txt` or `extra-in-db.txt` rows.
- For empty residual extra tables, generate and review `scripts/schema_extra_tables_cleanup_plan_server.sh` output before any manual cleanup.
- Use `scripts/schema_extra_tables_cleanup_apply_server.sh` only after the generated row counts and backup path have been reviewed.
- Confirm no marketplace-owned tables are required by trading.
- Generate an explicit `V1__baseline.sql` under `src/main/resources/db/migration`.
- Set production to `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`.
- Set production to `SPRING_FLYWAY_ENABLED=true`.
- Enable `meta-control.migration-drift-check.enabled=true` only after the baseline exists.

Until those checks pass, keep Flyway disabled and keep this repo in temporary bootstrap-only schema mode.
