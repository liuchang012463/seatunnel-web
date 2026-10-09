package org.apache.seatunnel.web.api.fileresource.duckdb;

import org.apache.seatunnel.web.api.fileresource.storage.FileResourceStorageProvider;
import org.apache.seatunnel.web.dao.entity.FileResource;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Reads DuckDB catalog metadata from a temporary local copy of an object. */
@Component
public class DuckDbCatalogReader {

    private final FileResourceStorageProvider storageProvider;

    public DuckDbCatalogReader(FileResourceStorageProvider storageProvider) {
        this.storageProvider = storageProvider;
    }

    public DuckDbCatalogVO inspect(FileResource resource) throws Exception {
        return withReadOnlyConnection(resource, connection -> readCatalog(connection, resource.getName()));
    }

    public DuckDbPreviewVO preview(FileResource resource, DuckDbPreviewRequest request) throws Exception {
        return withReadOnlyConnection(resource, connection -> readPreview(connection, request));
    }

    private <T> T withReadOnlyConnection(FileResource resource, ConnectionReader<T> reader) throws Exception {
        String fileName = resource.getName();
        String suffix = fileName.toLowerCase(Locale.ROOT).endsWith(".duckdb") ? ".duckdb" : ".db";
        Path temporaryDatabase = Files.createTempFile("seatunnel-web-duckdb-catalog-", suffix);
        try {
            try (OutputStream output = Files.newOutputStream(temporaryDatabase)) {
                storageProvider.download(resource.getObjectKey(), output);
            }

            Properties connectionProperties = new Properties();
            connectionProperties.setProperty("duckdb.read_only", "true");
            try (Connection connection = DriverManager.getConnection(
                    "jdbc:duckdb:" + temporaryDatabase.toAbsolutePath(), connectionProperties)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET enable_external_access = false");
                }
                return reader.read(connection);
            }
        } finally {
            try {
                Files.deleteIfExists(temporaryDatabase);
            } catch (Exception ignored) {
                temporaryDatabase.toFile().deleteOnExit();
            }
        }
    }

    private DuckDbPreviewVO readPreview(Connection connection, DuckDbPreviewRequest request) throws Exception {
        String schemaName = request == null ? "" : trim(request.schemaName());
        String readMode = request == null ? "table" : trim(request.readMode()).toLowerCase(Locale.ROOT);
        if (!schemaName.isEmpty()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("USE " + quoteIdentifier(schemaName));
            }
        }

        String query = "sql".equals(readMode)
                ? selectQuery(request == null ? null : request.query())
                : tableQuery(schemaName, request == null ? null : request.tableName());
        int limit = request == null || request.limit() == null
                ? 20
                : Math.max(0, Math.min(request.limit(), 20));
        long total = 0;
        if (limit > 0) {
            try (Statement statement = connection.createStatement();
                 ResultSet count = statement.executeQuery(
                         "SELECT COUNT(*) FROM (" + query + ") AS duckdb_preview_count")) {
                if (count.next()) total = count.getLong(1);
            }
        }

        List<DuckDbPreviewVO.Column> columns = new ArrayList<>();
        List<Map<String, Object>> data = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT * FROM (" + query + ") AS duckdb_preview LIMIT " + limit)) {
            ResultSetMetaData metadata = rows.getMetaData();
            Set<String> usedKeys = new HashSet<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                String name = metadata.getColumnLabel(index);
                if (name == null || name.isBlank()) name = metadata.getColumnName(index);
                String key = name;
                if (!usedKeys.add(key)) {
                    key = name + "_" + index;
                    usedKeys.add(key);
                }
                columns.add(new DuckDbPreviewVO.Column(
                        name,
                        key,
                        metadata.getColumnTypeName(index),
                        metadata.isNullable(index) != ResultSetMetaData.columnNoNulls));
            }
            while (rows.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int index = 1; index <= columns.size(); index++) {
                    row.put(columns.get(index - 1).key(), previewValue(rows.getObject(index)));
                }
                data.add(row);
            }
        }
        return new DuckDbPreviewVO(columns, data, total);
    }

    private String tableQuery(String schemaName, String tableName) {
        if (schemaName.isBlank() || trim(tableName).isBlank()) {
            throw new IllegalArgumentException("请选择 DuckDB Schema 和表");
        }
        return "SELECT * FROM " + quoteIdentifier(schemaName) + "." + quoteIdentifier(tableName);
    }

    private String selectQuery(String value) {
        String query = trim(value);
        if (query.endsWith(";")) query = query.substring(0, query.length() - 1).trim();
        if (query.isBlank() || query.contains(";")
                || !query.matches("(?is)^(SELECT|WITH)\\b.*")) {
            throw new IllegalArgumentException("DuckDB 自定义 SQL 只支持单条 SELECT 查询");
        }
        return query;
    }

    private String quoteIdentifier(String value) {
        String identifier = trim(value);
        if (identifier.isEmpty() || identifier.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("DuckDB 标识符无效");
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private Object previewValue(Object value) {
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof TemporalAccessor) return value.toString();
        return value;
    }

    private DuckDbCatalogVO readCatalog(Connection connection, String fileName) throws Exception {
        Map<String, LinkedHashMap<String, TableBuilder>> tablesBySchema = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT c.table_schema,
                            c.table_name,
                            t.table_type,
                            c.column_name,
                            c.data_type,
                            c.is_nullable
                     FROM information_schema.columns c
                     JOIN information_schema.tables t
                       ON t.table_catalog = c.table_catalog
                      AND t.table_schema = c.table_schema
                      AND t.table_name = c.table_name
                     WHERE c.table_catalog = current_catalog()
                       AND c.table_schema NOT IN ('information_schema', 'pg_catalog')
                       AND t.table_type IN ('BASE TABLE', 'VIEW')
                     ORDER BY c.table_schema, c.table_name, c.ordinal_position
                     """)) {
            while (rows.next()) {
                String schemaName = rows.getString("table_schema");
                String tableName = rows.getString("table_name");
                String tableType = rows.getString("table_type");
                LinkedHashMap<String, TableBuilder> tables = tablesBySchema.computeIfAbsent(
                        schemaName, ignored -> new LinkedHashMap<>());
                TableBuilder table = tables.computeIfAbsent(tableName,
                        ignored -> new TableBuilder(tableName, tableType));
                table.columns.add(new DuckDbCatalogVO.Column(
                        rows.getString("column_name"),
                        rows.getString("data_type"),
                        "YES".equalsIgnoreCase(rows.getString("is_nullable"))));
            }
        }

        List<DuckDbCatalogVO.Schema> schemas = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT schema_name
                     FROM information_schema.schemata
                     WHERE catalog_name = current_catalog()
                       AND schema_name NOT IN ('information_schema', 'pg_catalog')
                     ORDER BY CASE WHEN schema_name = 'main' THEN 0 ELSE 1 END, schema_name
                     """)) {
            while (rows.next()) {
                String schemaName = rows.getString("schema_name");
                Map<String, TableBuilder> tables = tablesBySchema.getOrDefault(schemaName, new LinkedHashMap<>());
                List<DuckDbCatalogVO.Table> tableValues = tables.values().stream()
                        .map(TableBuilder::toValue)
                        .toList();
                schemas.add(new DuckDbCatalogVO.Schema(schemaName, tableValues));
            }
        }
        return new DuckDbCatalogVO(databaseName(fileName), schemas);
    }

    private String databaseName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".duckdb")) return fileName.substring(0, fileName.length() - 7);
        if (lower.endsWith(".db")) return fileName.substring(0, fileName.length() - 3);
        return fileName;
    }

    private static final class TableBuilder {
        private final String name;
        private final String type;
        private final List<DuckDbCatalogVO.Column> columns = new ArrayList<>();

        private TableBuilder(String name, String type) {
            this.name = name;
            this.type = type;
        }

        private DuckDbCatalogVO.Table toValue() {
            return new DuckDbCatalogVO.Table(name, type, columns);
        }
    }

    @FunctionalInterface
    private interface ConnectionReader<T> {
        T read(Connection connection) throws Exception;
    }
}
