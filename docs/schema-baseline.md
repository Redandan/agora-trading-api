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

This is source inventory only. It does not connect to MySQL, write migrations, or mutate runtime configuration.
The inventory fails if any JPA entity relies on an implicit table name; baseline
generation requires explicit `@Table(name = "...")` mappings.

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

- `target/schema-baseline/server-source-entity-tables.txt`
- `target/schema-baseline/server-db-tables.txt`
- `target/schema-baseline/missing-in-db.txt`
- `target/schema-baseline/extra-in-db.txt`

It must not print database passwords, write migrations, or mutate the database.

## Baseline Acceptance

Before replacing Hibernate schema update with Flyway validation:

- Compare `target/schema-baseline/entity-tables.txt` with the real `agora_trading` database tables.
- Run `RUN_SCHEMA_BASELINE_COMPARE=1 bash scripts/verify_server.sh` and resolve any `missing-in-db.txt` or `extra-in-db.txt` rows.
- Confirm no marketplace-owned tables are required by trading.
- Generate an explicit `V1__baseline.sql` under `src/main/resources/db/migration`.
- Set production to `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`.
- Set production to `SPRING_FLYWAY_ENABLED=true`.
- Enable `meta-control.migration-drift-check.enabled=true` only after the baseline exists.

Until those checks pass, keep Flyway disabled and keep this repo in temporary bootstrap-only schema mode.
