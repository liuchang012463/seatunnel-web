package org.apache.seatunnel.web.api.offlineimport;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.seatunnel.web.spi.bean.dto.OfflineDatapackFieldMapping;
import org.apache.seatunnel.web.spi.bean.dto.OfflineDatapackTaskConfig;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/** Writes one selected form into one existing table using a single JDBC transaction. */
public final class DatapackImportRunner {

    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DatapackImportRunner() {
    }

    public static int run(
            Connection connection,
            String targetSchema,
            OfflineDatapackTaskConfig config,
            DatapackPackageInspector.Inspection inspection,
            IntConsumer progress) throws SQLException {
        validateConfig(config, inspection);
        TableTarget tableTarget = resolveTarget(connection, config.getTargetTable(), targetSchema);
        List<Column> columns = readColumns(connection, tableTarget);
        List<BoundMapping> mappings = validateMappings(config, inspection, columns);
        String sql = buildInsertSql(connection.getMetaData(), tableTarget, mappings);
        boolean previousAutoCommit = connection.getAutoCommit();
        int inserted = 0;
        int pending = 0;
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (JsonNode record : inspection.records()) {
                if (!config.getFormCode().equals(record.path("formCode").asText())) {
                    continue;
                }
                JsonNode values = record.path("values");
                for (int index = 0; index < mappings.size(); index++) {
                    BoundMapping mapping = mappings.get(index);
                    JsonNode value = values.get(mapping.sourceField());
                    statement.setObject(index + 1, convert(value, mapping.column()));
                }
                statement.addBatch();
                pending++;
                if (pending == BATCH_SIZE) {
                    inserted += executeBatch(statement, pending);
                    pending = 0;
                    progress.accept(inserted);
                }
            }
            if (pending > 0) {
                inserted += executeBatch(statement, pending);
                progress.accept(inserted);
            }
            if (inserted == 0) {
                throw new IllegalArgumentException("所选表单没有可导入记录");
            }
            connection.commit();
            return inserted;
        } catch (Exception exception) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Preserve the original failure while still reporting the transaction rollback attempt.
            }
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            // JDBC exceptions can include bound values from some drivers; do not expose them in task logs.
            throw new IllegalStateException("数据包写入失败，已回滚本次导入");
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException ignored) {
                // Connection is closed by the caller.
            }
        }
    }

    private static void validateConfig(
            OfflineDatapackTaskConfig config,
            DatapackPackageInspector.Inspection inspection) {
        if (config == null || inspection == null || config.getFormCode() == null || config.getFormCode().isBlank()
                || config.getTargetTable() == null || config.getTargetTable().isBlank()
                || config.getMappings() == null || config.getMappings().isEmpty()) {
            throw new IllegalArgumentException("数据包表单映射配置不完整");
        }
        DatapackPackageInspector.FormSummary form = inspection.forms().stream()
                .filter(candidate -> config.getFormCode().equals(candidate.formCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选表单不属于当前数据包"));
        Set<String> sourceFields = new HashSet<>(form.fields());
        Set<String> seenSources = new HashSet<>();
        Set<String> seenTargets = new HashSet<>();
        for (OfflineDatapackFieldMapping mapping : config.getMappings()) {
            if (mapping == null || mapping.getSourceField() == null || mapping.getTargetField() == null
                    || !sourceFields.contains(mapping.getSourceField())
                    || !seenSources.add(mapping.getSourceField())
                    || !seenTargets.add(mapping.getTargetField())) {
                throw new IllegalArgumentException("字段映射包含未知字段或重复目标列");
            }
        }
    }

    private static List<Column> readColumns(Connection connection, TableTarget target) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<Column> columns = new ArrayList<>();
        String escape = metadata.getSearchStringEscape();
        String schemaPattern = target.schema() == null ? null : escapeMetadataPattern(target.schema(), escape);
        String tablePattern = escapeMetadataPattern(target.table(), escape);
        try (ResultSet result = metadata.getColumns(connection.getCatalog(), schemaPattern, tablePattern, null)) {
            while (result.next()) {
                // JDBC treats '%' and '_' in metadata arguments as wildcards; retain only the selected table.
                if (!target.table().equals(result.getString("TABLE_NAME"))) {
                    continue;
                }
                if (target.schema() != null && !target.schema().equals(result.getString("TABLE_SCHEM"))) {
                    continue;
                }
                columns.add(new Column(
                        result.getString("COLUMN_NAME"),
                        result.getInt("DATA_TYPE"),
                        result.getString("TYPE_NAME"),
                        result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        result.getString("COLUMN_DEF")));
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("目标表不存在或无法读取字段结构");
        }
        return columns;
    }

    private static String escapeMetadataPattern(String value, String escape) {
        if (escape == null || escape.isEmpty()) {
            return value;
        }
        return value.replace(escape, escape + escape)
                .replace("%", escape + "%")
                .replace("_", escape + "_");
    }

    private static List<BoundMapping> validateMappings(
            OfflineDatapackTaskConfig config,
            DatapackPackageInspector.Inspection inspection,
            List<Column> columns) {
        List<BoundMapping> result = new ArrayList<>();
        Set<String> resolvedTargets = new HashSet<>();
        for (OfflineDatapackFieldMapping mapping : config.getMappings()) {
            String requestedName = mapping.getTargetField();
            List<Column> exact = columns.stream()
                    .filter(column -> column.name().equals(requestedName))
                    .toList();
            Column column;
            if (exact.size() == 1) {
                column = exact.get(0);
            } else if (exact.size() > 1) {
                throw new IllegalArgumentException("目标表元数据包含重复的精确列名");
            } else {
                List<Column> caseInsensitive = columns.stream()
                        .filter(candidate -> candidate.name().equalsIgnoreCase(requestedName))
                        .toList();
                if (caseInsensitive.size() > 1) {
                    throw new IllegalArgumentException("目标列名忽略大小写后存在歧义，请填写精确列名");
                }
                column = caseInsensitive.isEmpty() ? null : caseInsensitive.get(0);
            }
            if (column == null) {
                throw new IllegalArgumentException("字段映射包含目标表中不存在的列");
            }
            if (!resolvedTargets.add(column.name())) {
                throw new IllegalArgumentException("多个源字段不能映射到同一个目标列");
            }
            if (column.jdbcType() == Types.TIME_WITH_TIMEZONE) {
                throw new IllegalArgumentException("暂不支持映射到 TIME WITH TIME ZONE 目标列");
            }
            // 映射列即使有 DEFAULT，显式绑定 NULL 也会覆盖默认值，因此在保存配置时检查整张表单。
            if (!column.nullable() && hasNullSourceValue(config, inspection, mapping)) {
                throw new IllegalArgumentException(
                        "源字段包含空值，不能映射到非空目标列；请取消映射以使用数据库默认值");
            }
            result.add(new BoundMapping(mapping.getSourceField(), column));
        }
        return result;
    }

    private static boolean hasNullSourceValue(
            OfflineDatapackTaskConfig config,
            DatapackPackageInspector.Inspection inspection,
            OfflineDatapackFieldMapping mapping) {
        for (JsonNode record : inspection.records()) {
            if (!config.getFormCode().equals(record.path("formCode").asText())) {
                continue;
            }
            JsonNode sourceValue = record.path("values").get(mapping.getSourceField());
            if (sourceValue == null || sourceValue.isNull()) {
                return true;
            }
        }
        return false;
    }

    /** Validates the selected table and mappings before task configuration is saved. */
    public static void validateTarget(
            Connection connection,
            String targetSchema,
            OfflineDatapackTaskConfig config,
            DatapackPackageInspector.Inspection inspection) throws SQLException {
        validateConfig(config, inspection);
        TableTarget tableTarget = resolveTarget(connection, config.getTargetTable(), targetSchema);
        validateMappings(config, inspection, readColumns(connection, tableTarget));
    }

    private static String buildInsertSql(DatabaseMetaData metadata, TableTarget target, List<BoundMapping> mappings)
            throws SQLException {
        String quote = metadata.getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            quote = "\"";
        }
        final String identifierQuote = quote;
        String table = quoteIdentifier(target.table(), identifierQuote);
        if (target.schema() != null) {
            table = quoteIdentifier(target.schema(), identifierQuote) + "." + table;
        }
        String columns = mappings.stream()
                .map(mapping -> quoteIdentifier(mapping.column().name(), identifierQuote))
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        String placeholders = String.join(", ", java.util.Collections.nCopies(mappings.size(), "?"));
        return "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")";
    }

    private static String quoteIdentifier(String value, String quote) {
        return quote + value.replace(quote, quote + quote) + quote;
    }

    private static TableTarget resolveTarget(String rawTable, String targetSchema) {
        if (rawTable == null || rawTable.isBlank() || rawTable.length() > 256
                || rawTable.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("目标表名称无效");
        }
        String[] parts = rawTable.trim().split("\\.", -1);
        if (parts.length > 2 || parts[parts.length - 1].isBlank()
                || (parts.length == 2 && parts[0].isBlank())) {
            throw new IllegalArgumentException("目标表名称无效");
        }
        String explicitSchema = parts.length == 2 ? parts[0] : null;
        String schema = explicitSchema == null ? targetSchema : explicitSchema;
        if (explicitSchema != null && targetSchema != null && !targetSchema.isBlank()
                && !explicitSchema.equalsIgnoreCase(targetSchema.trim())) {
            throw new IllegalArgumentException("目标表所属 Schema 与所选 Schema 不一致");
        }
        return new TableTarget(schema == null || schema.isBlank() ? null : schema.trim(), parts[parts.length - 1].trim());
    }

    private static TableTarget resolveTarget(Connection connection, String rawTable, String targetSchema)
            throws SQLException {
        TableTarget target = resolveTarget(rawTable, targetSchema);
        if (target.schema() != null) {
            return target;
        }

        DatabaseMetaData metadata = connection.getMetaData();
        boolean schemaSupported;
        try {
            schemaSupported = metadata.supportsSchemasInDataManipulation()
                    || metadata.supportsSchemasInTableDefinitions();
        } catch (SQLException exception) {
            throw new IllegalArgumentException("无法确认目标数据库 Schema，请选择目标 Schema", exception);
        }
        if (!schemaSupported) {
            return target;
        }
        String currentSchema;
        try {
            currentSchema = connection.getSchema();
        } catch (SQLException exception) {
            throw new IllegalArgumentException("无法读取当前 Schema，请选择目标 Schema", exception);
        }
        if (currentSchema == null || currentSchema.isBlank()) {
            throw new IllegalArgumentException("无法确定当前 Schema，请选择目标 Schema");
        }
        // Resolve once per connection so metadata validation and the INSERT use the same schema.
        return new TableTarget(currentSchema.trim(), target.table());
    }

    private static int executeBatch(PreparedStatement statement, int expected) throws SQLException {
        int[] counts = statement.executeBatch();
        int executed = 0;
        for (int count : counts) {
            if (count == Statement.SUCCESS_NO_INFO) {
                executed++;
            } else if (count == Statement.EXECUTE_FAILED || count == 0) {
                throw new SQLException("batch insert failed");
            } else {
                executed += count;
            }
        }
        if (executed != expected) {
            throw new SQLException("batch insert count mismatch");
        }
        return executed;
    }

    private static Object convert(JsonNode value, Column column) {
        if (value == null || value.isNull()) {
            if (!column.nullable()) {
                throw new IllegalArgumentException("字段值为空，但目标列不允许空值");
            }
            return null;
        }
        try {
            String text = value.isTextual() ? value.textValue() : value.asText();
            return switch (column.jdbcType()) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> Integer.valueOf(text);
                case Types.BIGINT -> Long.valueOf(text);
                case Types.NUMERIC, Types.DECIMAL -> new BigDecimal(text);
                case Types.REAL -> Float.valueOf(text);
                case Types.FLOAT, Types.DOUBLE -> Double.valueOf(text);
                case Types.BOOLEAN, Types.BIT -> parseBoolean(text);
                case Types.DATE -> java.sql.Date.valueOf(LocalDate.parse(text));
                case Types.TIME -> java.sql.Time.valueOf(LocalTime.parse(text));
                case Types.TIME_WITH_TIMEZONE -> throw new IllegalArgumentException(
                        "TIME WITH TIME ZONE is not supported");
                case Types.TIMESTAMP -> Timestamp.valueOf(parseDateTime(text));
                case Types.TIMESTAMP_WITH_TIMEZONE -> OffsetDateTime.parse(text);
                case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> Base64.getDecoder().decode(text);
                default -> value.isTextual() ? text : value.asText();
            };
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("字段值无法转换为目标列类型 " + column.typeName());
        }
    }

    private static Boolean parseBoolean(String text) {
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("boolean");
    }

    private static LocalDateTime parseDateTime(String text) {
        try {
            return LocalDateTime.parse(text, LOCAL_DATE_TIME);
        } catch (RuntimeException ignored) {
            try {
                return LocalDateTime.parse(text);
            } catch (RuntimeException ignoredAgain) {
                return OffsetDateTime.parse(text).toLocalDateTime();
            }
        }
    }

    private record Column(String name, int jdbcType, String typeName, boolean nullable, String defaultValue) {
    }

    private record BoundMapping(String sourceField, Column column) {
    }

    private record TableTarget(String schema, String table) {
    }
}
