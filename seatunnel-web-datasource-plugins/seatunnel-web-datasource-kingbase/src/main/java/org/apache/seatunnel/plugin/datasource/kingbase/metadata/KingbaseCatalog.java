package org.apache.seatunnel.plugin.datasource.kingbase.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractJdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.api.modal.DataSourceTableColumn;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.utils.NonNullFunctions;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class KingbaseCatalog extends AbstractJdbcCatalog {

    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "sys", "kdb_schedule", "perf", "src_restrict");

    private static final Set<String> SYSTEM_STATISTICS_VIEWS = Set.of(
            "sys_stat_statements", "sys_stat_statements_all");

    private static final String SELECT_COLUMNS_SQL_TEMPLATE =
            "SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME ='%s' ORDER BY ORDINAL_POSITION ASC";

    private static final String SELECT_SPECIFIED_COLUMNS_SQL_TEMPLATE =
            "SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s' AND COLUMN_NAME IN (%s) ORDER BY ORDINAL_POSITION ASC";

    private final String schemaName;

    public KingbaseCatalog(BaseConnectionParam param, JdbcConnectionProvider connectionManager) {
        super(param, connectionManager);
        this.schemaName = StringUtils.defaultIfBlank(param.getSchemaName(), "public");
    }

    @Override
    protected String applyLimit(String sql, int limit) {
        return sql + " LIMIT " + limit;
    }

    @Override
    protected String getTableName(ResultSet rs) throws SQLException {
        return rs.getString(1);
    }

    /**
     * Kingbase's JDBC metadata includes internal catalog and extension schemas.
     * Keep them out of the schema selector so users only choose application schemas.
     */
    @Override
    public List<OptionVO> listSchemaOptions(String databaseName) {
        return filterSystemMetadataOptions(
                super.listSchemaOptions(databaseName), databaseName, null);
    }

    /**
     * Statistics extension views can be installed in PUBLIC, so schema filtering
     * alone cannot keep these system objects out of the batch table selector.
     */
    @Override
    public List<OptionVO> listTableOptions(String databaseName, String schemaName) {
        return filterSystemMetadataOptions(
                super.listTableOptions(databaseName, schemaName), databaseName, schemaName);
    }

    private List<OptionVO> filterSystemMetadataOptions(
            List<OptionVO> options, String databaseName, String selectedSchema) {
        if (options == null || options.isEmpty()) {
            return options;
        }

        List<OptionVO> filtered = options.stream()
                .filter(option -> option != null
                        && !isSystemMetadataOption(option.getValue(), selectedSchema))
                .toList();
        int removedCount = options.size() - filtered.size();
        if (removedCount > 0) {
            log.debug("Filtered Kingbase system metadata entries: database={}, schema={}, removed={}",
                    databaseName, selectedSchema, removedCount);
        }
        return filtered;
    }

    private boolean isSystemMetadataOption(String value, String selectedSchema) {
        if (StringUtils.isBlank(value)) {
            return false;
        }

        String schema = selectedSchema;
        String objectName = value.trim();
        if (StringUtils.isBlank(schema)) {
            int separator = objectName.indexOf('.');
            if (separator > 0) {
                schema = objectName.substring(0, separator);
                objectName = objectName.substring(separator + 1);
            }
        }

        return isSystemSchema(schema)
                || SYSTEM_STATISTICS_VIEWS.contains(objectName.toLowerCase(Locale.ROOT));
    }

    private boolean isSystemSchema(String schema) {
        if (StringUtils.isBlank(schema)) {
            return false;
        }

        String normalized = schema.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("information_schema")
                || normalized.equals("sys")
                || normalized.startsWith("sys_")
                || normalized.startsWith("pg_")
                || SYSTEM_SCHEMAS.contains(normalized);
    }

    @Override
    protected String getListTableSql(String databaseName) {
        return "SELECT table_schema || '.' || table_name AS table_path " +
                "FROM information_schema.tables " +
                "WHERE table_catalog = '" + databaseName + "' " +
                "AND table_schema NOT IN ('pg_catalog', 'information_schema') " +
                "AND LEFT(LOWER(table_schema), 3) <> 'pg_' " +
                "AND LEFT(LOWER(table_schema), 4) <> 'sys_' " +
                "AND LOWER(table_schema) NOT IN ('sys', 'kdb_schedule', 'perf', 'src_restrict') " +
                "AND LOWER(table_name) NOT IN ('sys_stat_statements', 'sys_stat_statements_all') " +
                "AND table_type = 'BASE TABLE' " +
                "ORDER BY table_schema, table_name";
    }

    @Override
    protected DataSourceTableColumn buildColumn(Map<String, Object> item) {
        String columnName = item.get("column_name").toString();
        String dataType = item.get("data_type").toString();
        String isNullable = item.get("is_nullable").toString();
        String columnComment = item.get("column_comment") != null ? item.get("column_comment").toString() : null;
        String columnKey = item.get("column_key") != null ? item.get("column_key").toString() : null;
        int ordinalPosition = Integer.parseInt(item.get("ordinal_position").toString());

        return DataSourceTableColumn.builder()
                .isNullable(isNullable)
                .columnComment(columnComment)
                .columnKey(columnKey)
                .columnName(columnName)
                .sourceType(dataType.toUpperCase())
                .ordinalPosition(ordinalPosition)
                .build();
    }

    private String resolveSchemaName(TablePath tablePath) {
        String resolved = tablePath == null ? null : tablePath.getSchemaName();
        if (StringUtils.isBlank(resolved)) {
            resolved = schemaName;
        }
        return resolved;
    }

    private String escapeSql(String value) {
        return value == null ? null : value.replace("'", "''");
    }

    @Override
    public String buildTableReference(TablePath tablePath) {
        if (tablePath == null || StringUtils.isBlank(tablePath.getTableName())) {
            throw new IllegalArgumentException("table is null");
        }
        return quoteIdentifier(resolveSchemaName(tablePath))
                + "."
                + quoteIdentifier(tablePath.getTableName());
    }

    @Override
    protected String getSelectColumnsSql(TablePath tablePath) {
        return String.format(
                SELECT_COLUMNS_SQL_TEMPLATE,
                escapeSql(resolveSchemaName(tablePath)),
                escapeSql(tablePath.getTableName()));
    }

    @Override
    protected String getSpecifiedColumnSql(TablePath tablePath, List<DataSourceTableColumn> columns) {
        List<String> columnNames = columns.stream()
                .map(NonNullFunctions.from(DataSourceTableColumn::getColumnName))
                .collect(Collectors.toList());

        String quotedColumnNames = columnNames.stream()
                .map(name -> "'" + escapeSql(name) + "'")
                .collect(Collectors.joining(", "));

        return String.format(
                SELECT_SPECIFIED_COLUMNS_SQL_TEMPLATE,
                escapeSql(resolveSchemaName(tablePath)),
                escapeSql(tablePath.getTableName()),
                quotedColumnNames);
    }
}
