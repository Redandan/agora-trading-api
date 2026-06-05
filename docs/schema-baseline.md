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

## Baseline Acceptance

Before replacing Hibernate schema update with Flyway validation:

- Compare `target/schema-baseline/entity-tables.txt` with the real `agora_trading` database tables.
- Confirm no marketplace-owned tables are required by trading.
- Generate an explicit `V1__baseline.sql` under `src/main/resources/db/migration`.
- Set production to `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`.
- Set production to `SPRING_FLYWAY_ENABLED=true`.
- Enable `meta-control.migration-drift-check.enabled=true` only after the baseline exists.

Until those checks pass, keep Flyway disabled and keep this repo in temporary bootstrap-only schema mode.
