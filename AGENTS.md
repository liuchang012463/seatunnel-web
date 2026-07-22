# Repository Development Instructions

## SeaTunnel Compatibility

- The target SeaTunnel Engine version is **2.3.13**. Connector names, HOCON options,
  dependency deployment instructions, and integration tests must match this version.
- Do not copy connector options from another SeaTunnel version without confirming that
  they are supported by SeaTunnel 2.3.13.

## Flyway Migrations

- A migration version must be unique within its Flyway location. For example, the
  MySQL migrations under `seatunnel-web-dao-plugin/seatunnel-web-dao-mysql/src/main/resources/db/migration/mysql`
  cannot contain two `V1_0_3__*.sql` files, even when they change different features.
- Before adding a migration, inspect the target directory and use the next available
  version. Current migrations are ordered globally, not per feature.
- Never rename or edit a migration that has been applied to a shared environment.
  For a newly added, conflicting migration, assign the next unused version before deployment.
- Do not use `flyway repair` or manually edit `flyway_schema_history` to resolve a
  duplicate-version discovery error; fix the migration filename and rebuild the artifact.
