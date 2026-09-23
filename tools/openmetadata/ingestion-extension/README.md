# OpenMetadata ingestion extensions

Mount this directory as `/opt/om-extensions:ro` into the official
`openmetadata/ingestion:1.12.10` image. Add its `python` directory to the front
of `PYTHONPATH`; the included `sitecustomize.py` connects all other directories
automatically.

## Current extensions

| Database | `sourcePythonClass` | Driver location |
| --- | --- | --- |
| Kingbase | `kingbase_connector.kingbase_source.KingbaseSource` | `drivers/kingbase/kingbase8-8.6.0.jar` |
| Dameng | `dameng_connector.dameng_source.DamengSource` | `drivers/dameng/DmJdbcDriver8.jar` |
| Vastbase | `metadata.ingestion.source.database.customdatabase.vastbase_connector.vastbase_source.VastbaseSource` | `drivers/vastbase/Vastbase-G100-2.16_pg_2026062910.jar` |

Kingbase and Dameng use the vendored `JayDeBeApi 1.2.3` and `JPype1 1.7.1`
packages. Their top-level Python connector packages live under `connectors/`.
Vastbase and the shared CustomDatabase loader live under
`python/metadata/ingestion/source/database/customdatabase/`.

## Adding another extension

1. Put the connector package under `connectors/<package>/`, or use the
   OpenMetadata database package path under
   `python/metadata/ingestion/source/database/<service>/`.
2. Put its JDBC JAR under `drivers/<name>/` and configure the connector to use
   `/opt/om-extensions/drivers/<name>/<driver>.jar` (or set its driver path in
   `python/sitecustomize.py`).
3. Put any required Python package directories and their matching
   `*.dist-info/` metadata under `python/`. Native Python modules must match the
   runtime image's Python 3.10 and CPU architecture.
4. Point the OpenMetadata connection's `sourcePythonClass` at the connector
   class, then run `tools/openmetadata/verify-ingestion-extensions.sh`.

The directory is read-only in the container. Change files on the host; restart
the ingestion container only when a running Python process has cached the old
module.
