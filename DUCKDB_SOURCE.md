# DuckDB file source

The file ingest workflow can read a DuckDB `.db` or `.duckdb` object from the managed MinIO file lake. It attaches that object read-only through DuckDB JDBC and reads a selected table or a custom `SELECT` query. This feature adds a source only; it does not add a DuckDB sink.

## Engine requirements

Configure every SeaTunnel Web API instance and every SeaTunnel Engine worker so they can use the same absolute init-SQL directory. For example, set this on the Web API:

```text
SEATUNNEL_WEB_DUCKDB_INIT_SQL_DIR=/opt/seatunnel-web/duckdb-init
```

Mount one shared Docker volume at `/opt/seatunnel-web/duckdb-init` in the Web API and all Engine containers. The Web API needs write access; Engine workers need read access. Use compatible container UID/GID permissions across those services. The generated SQL files contain MinIO credentials and are created with owner read/write and group read permissions. Deleting a file resource also removes its generated SQL file.

SeaTunnel Engine 3.0.0 also needs the DuckDB source connector, the DuckDB JDBC driver (`org.duckdb.DuckDBDriver`) on every worker's classpath, and DuckDB's `httpfs` extension. The generated initialization SQL installs and loads `httpfs`; the Engine must be able to download the extension, or the extension must already be installed in its DuckDB extension directory. Every worker must be able to reach the MinIO runtime endpoint configured for file resources.

## Task setup

1. In **文件引接**, choose a `.db` or `.duckdb` file from the file lake. The source panel can also upload a DuckDB file through the same picker.
2. The Web API reads the database catalog and lists its databases, schemas, tables, and columns. Select the database, schema, and source table, or switch to **自定义 SQL**.
3. Use **预览** to read up to 20 sample rows and **字段解析** to load the query's column names and types into the source schema.
4. Configure the target database and table as usual. SeaTunnel Engine reads the source schema from the selected table or custom query.

The file resource storage must use static S3-compatible credentials. For SeaTunnel execution, the source builds a JDBC `session_init_sql_file` that sets the MinIO endpoint, region, path-style mode, credentials, and read-only `ATTACH`. The Engine reads the database object from MinIO. For catalog discovery and preview, the Web API temporarily downloads the object to a local DuckDB JDBC file and opens it read-only; it removes the temporary copy after the request.

## Limitations

- The file must contain a native DuckDB database. The `.db` suffix alone does not convert a SQLite database into DuckDB format.
- The Web API preview reads at most 20 rows per request. Full source ingestion runs in SeaTunnel Engine when the task starts.
- The MinIO runtime endpoint must be usable from every Engine worker. The Web API's internal-only endpoint is not sufficient.
- Docker deployments must mount the init-SQL volume at the same absolute path in Web and Engine. Without that shared mount, the Engine cannot open the JDBC startup script even though it can reach the MinIO object.
