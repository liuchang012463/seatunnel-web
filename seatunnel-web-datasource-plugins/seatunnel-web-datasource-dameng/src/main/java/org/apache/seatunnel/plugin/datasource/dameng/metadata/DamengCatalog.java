package org.apache.seatunnel.plugin.datasource.dameng.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractJdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.api.modal.DataSourceTableColumn;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class DamengCatalog extends AbstractJdbcCatalog {

    private static final String SELECT_COLUMNS_SQL_TEMPLATE =
            "SELECT COLUMN_NAME, DATA_TYPE, NULLABLE, COLUMN_ID FROM ALL_TAB_COLUMNS "
                    + "WHERE OWNER = '%s' AND TABLE_NAME = '%s' ORDER BY COLUMN_ID ASC";

    private static final String SELECT_SPECIFIED_COLUMNS_SQL_TEMPLATE =
            "SELECT COLUMN_NAME, DATA_TYPE, NULLABLE, COLUMN_ID FROM ALL_TAB_COLUMNS "
                    + "WHERE OWNER = '%s' AND TABLE_NAME = '%s' AND COLUMN_NAME IN (%s) ORDER BY COLUMN_ID ASC";

    private final String schemaName;

    public DamengCatalog(BaseConnectionParam param, JdbcConnectionProvider connectionManager) {
        super(param, connectionManager);
        this.schemaName = StringUtils.defaultIfBlank(param.getSchemaName(), "SYSDBA");
    }

    @Override
    protected String applyLimit(String sql, int limit) {
        return sql + " LIMIT " + limit;
    }

    @Override
    protected String getTableName(ResultSet rs) throws SQLException {
        return rs.getString(1);
    }

    @Override
    protected String getListTableSql(String databaseName) {
        // Dameng is Oracle-compatible and does not expose INFORMATION_SCHEMA as a usable schema.
        // List accessible user tables via ALL_TABLES, excluding Dameng system owners.
        return "SELECT OWNER || '.' || TABLE_NAME AS table_path "
                + "FROM ALL_TABLES "
                + "WHERE OWNER NOT IN ('SYS', 'SYSAUDITOR', 'SYSSSO', 'CTISYS') "
                + "ORDER BY OWNER, TABLE_NAME";
    }

    @Override
    protected DataSourceTableColumn buildColumn(Map<String, Object> item) {
        String columnName = item.get("COLUMN_NAME").toString();
        String dataType = item.get("DATA_TYPE").toString();
        String isNullable = item.get("NULLABLE").toString();
        String columnComment = item.getOrDefault("COMMENTS", "").toString();
        String columnKey = item.getOrDefault("COLUMN_KEY", "").toString();
        int ordinalPosition = Integer.parseInt(item.get("COLUMN_ID").toString());

        return DataSourceTableColumn.builder()
                .isNullable(isNullable)
                .columnComment(columnComment)
                .columnKey(columnKey)
                .columnName(columnName)
                .sourceType(dataType.toUpperCase(Locale.ROOT))
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

    private String resolveOwnerForTablePath(TablePath tablePath) {
        String owner = resolveSchemaName(tablePath);
        if (StringUtils.isBlank(owner)) {
            throw new IllegalArgumentException("Dameng owner/schema must not be blank");
        }
        return escapeSql(owner.toUpperCase(Locale.ROOT));
    }

    private String resolveTableName(TablePath tablePath) {
        if (tablePath == null || StringUtils.isBlank(tablePath.getTableName())) {
            throw new IllegalArgumentException("table is null");
        }
        return escapeSql(tablePath.getTableName().toUpperCase(Locale.ROOT));
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
                resolveOwnerForTablePath(tablePath),
                resolveTableName(tablePath));
    }

    @Override
    protected String getSpecifiedColumnSql(TablePath tablePath, List<DataSourceTableColumn> columns) {
        List<String> columnNames = columns.stream()
                .map(DataSourceTableColumn::getColumnName)
                .collect(Collectors.toList());

        String quotedColumnNames = columnNames.stream()
                .map(name -> "'" + escapeSql(name.toUpperCase(Locale.ROOT)) + "'")
                .collect(Collectors.joining(", "));

        return String.format(
                SELECT_SPECIFIED_COLUMNS_SQL_TEMPLATE,
                resolveOwnerForTablePath(tablePath),
                resolveTableName(tablePath),
                quotedColumnNames);
    }
}
