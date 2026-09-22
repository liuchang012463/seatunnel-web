package org.apache.seatunnel.plugin.datasource.vastbase.metadata;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractJdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.api.modal.DataSourceTableColumn;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Metadata catalog for Vastbase's PostgreSQL-compatible information_schema. */
public class VastbaseCatalog extends AbstractJdbcCatalog {

    private static final String SELECT_COLUMNS_SQL_TEMPLATE =
            "SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '%s' "
                    + "AND TABLE_NAME ='%s' ORDER BY ORDINAL_POSITION ASC";

    private static final String SELECT_SPECIFIED_COLUMNS_SQL_TEMPLATE =
            "SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '%s' "
                    + "AND TABLE_NAME = '%s' AND COLUMN_NAME IN (%s) "
                    + "ORDER BY ORDINAL_POSITION ASC";

    public VastbaseCatalog(BaseConnectionParam param, JdbcConnectionProvider connectionManager) {
        super(param, connectionManager);
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
        return "SELECT table_schema || '.' || table_name AS table_path "
                + "FROM information_schema.tables "
                + "WHERE table_catalog = '" + escapeSql(databaseName) + "' "
                + "AND table_schema NOT IN ('pg_catalog', 'information_schema') "
                + "AND table_type = 'BASE TABLE' "
                + "ORDER BY table_schema, table_name";
    }

    @Override
    protected DataSourceTableColumn buildColumn(Map<String, Object> item) {
        String columnName = item.get("column_name").toString();
        String dataType = item.get("data_type").toString();
        String isNullable = item.get("is_nullable").toString();
        String columnComment = item.get("column_comment") == null
                ? null : item.get("column_comment").toString();
        String columnKey = item.get("column_key") == null
                ? null : item.get("column_key").toString();
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

    @Override
    public String buildTableReference(TablePath tablePath) {
        if (tablePath == null || StringUtils.isBlank(tablePath.getTableName())) {
            throw new IllegalArgumentException("table is null");
        }
        return quoteIdentifier(resolveSchemaName(tablePath))
                + "." + quoteIdentifier(tablePath.getTableName());
    }

    @Override
    protected String getSelectColumnsSql(TablePath tablePath) {
        return String.format(SELECT_COLUMNS_SQL_TEMPLATE,
                escapeSql(resolveSchemaName(tablePath)), escapeSql(tablePath.getTableName()));
    }

    @Override
    protected String getSpecifiedColumnSql(TablePath tablePath, List<DataSourceTableColumn> columns) {
        String quotedColumnNames = columns.stream()
                .map(DataSourceTableColumn::getColumnName)
                .map(this::escapeSql)
                .map(name -> "'" + name + "'")
                .collect(Collectors.joining(", "));
        return String.format(SELECT_SPECIFIED_COLUMNS_SQL_TEMPLATE,
                escapeSql(resolveSchemaName(tablePath)), escapeSql(tablePath.getTableName()),
                quotedColumnNames);
    }

    private String resolveSchemaName(TablePath tablePath) {
        String schemaName = tablePath == null ? null : tablePath.getSchemaName();
        if (StringUtils.isBlank(schemaName)) {
            schemaName = getParam().getSchemaName();
        }
        return StringUtils.defaultIfBlank(schemaName, "public");
    }

    private String escapeSql(String value) {
        return value == null ? null : value.replace("'", "''");
    }
}
