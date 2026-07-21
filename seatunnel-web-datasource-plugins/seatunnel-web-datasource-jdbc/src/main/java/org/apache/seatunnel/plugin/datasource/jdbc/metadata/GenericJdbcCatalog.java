package org.apache.seatunnel.plugin.datasource.jdbc.metadata;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractJdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.jdbc.QueryRequest;
import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.api.modal.DataSourceTableColumn;
import org.apache.seatunnel.plugin.datasource.api.enums.TaskExecutionTypeEnum;
import org.apache.seatunnel.web.common.FrontedTableColumn;
import org.apache.seatunnel.web.common.QueryResult;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generic JDBC metadata implementation based only on the standard JDBC APIs.
 * Vendor-specific datasource plugins can still provide richer catalog behavior.
 */
public class GenericJdbcCatalog extends AbstractJdbcCatalog {

    private static final String[] TABLE_TYPES = {"TABLE", "VIEW"};
    private volatile String identifierQuote;

    public GenericJdbcCatalog(
            BaseConnectionParam param,
            JdbcConnectionProvider connectionProvider) {
        super(param, connectionProvider);
    }

    @Override
    public List<String> listTables() {
        try (Connection connection = getConnection();
             ResultSet resultSet = connection.getMetaData().getTables(
                     catalogPattern(), schemaPattern(), "%", TABLE_TYPES)) {
            List<String> tables = new ArrayList<>();
            while (resultSet.next()) {
                tables.add(toTablePath(resultSet));
            }
            return tables;
        } catch (SQLException e) {
            throw new RuntimeException("Failed listing tables through JDBC metadata", e);
        }
    }

    @Override
    public List<OptionVO> listTableOptions() {
        return listTables().stream()
                .map(this::toOption)
                .collect(Collectors.toList());
    }

    @Override
    public List<DataSourceTableColumn> listColumns(Map<String, Object> requestBody)
            throws Exception {
        QueryRequest request = preprocessRequest(requestBody);
        if (request.getTaskExecuteType() == TaskExecutionTypeEnum.SQL) {
            return queryColumns(request.getQuery());
        }

        TablePath tablePath = normalizeTablePath(request.getTablePath());
        try (Connection connection = getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            Set<String> primaryKeys = primaryKeys(metadata, tablePath);
            try (ResultSet resultSet = metadata.getColumns(
                    blankToNull(tablePath.getDatabaseName()),
                    blankToNull(tablePath.getSchemaName()),
                    tablePath.getTableName(),
                    "%")) {
                List<DataSourceTableColumn> columns = new ArrayList<>();
                while (resultSet.next()) {
                    String columnName = resultSet.getString("COLUMN_NAME");
                    columns.add(DataSourceTableColumn.builder()
                            .columnName(columnName)
                            .columnType(resultSet.getString("TYPE_NAME"))
                            .sourceType(resultSet.getString("TYPE_NAME"))
                            .ordinalPosition(resultSet.getInt("ORDINAL_POSITION"))
                            .isNullable(resultSet.getString("IS_NULLABLE"))
                            .columnComment(resultSet.getString("REMARKS"))
                            .columnKey(primaryKeys.contains(columnName) ? "PRI" : null)
                            .build());
                }
                return columns;
            }
        }
    }

    @Override
    public QueryResult getTop20Data(Map<String, Object> requestBody) throws Exception {
        QueryRequest request = preprocessRequest(requestBody);
        String sql = request.getTaskExecuteType() == TaskExecutionTypeEnum.SQL
                ? stripTrailingSemicolon(request.getQuery())
                : "SELECT * FROM " + buildTableReference(normalizeTablePath(request.getTablePath()));
        return execute(sql, 20);
    }

    @Override
    public Integer count(Map<String, Object> requestBody) throws Exception {
        QueryRequest request = preprocessRequest(requestBody);
        String sql;
        if (request.getTaskExecuteType() == TaskExecutionTypeEnum.SQL) {
            sql = "SELECT COUNT(*) FROM ("
                    + stripTrailingSemicolon(request.getQuery())
                    + ") seatunnel_count";
        } else {
            sql = "SELECT COUNT(*) FROM "
                    + buildTableReference(normalizeTablePath(request.getTablePath()));
        }

        QueryResult result = execute(sql, 1);
        if (result.getData().isEmpty() || result.getData().get(0).isEmpty()) {
            return 0;
        }
        Object value = result.getData().get(0).values().iterator().next();
        return value == null ? 0 : Integer.parseInt(String.valueOf(value));
    }

    @Override
    public String buildTableReference(TablePath tablePath) {
        List<String> parts = new ArrayList<>(3);
        if (StringUtils.isNotBlank(tablePath.getDatabaseName())) {
            parts.add(quoteIdentifier(tablePath.getDatabaseName()));
        }
        if (StringUtils.isNotBlank(tablePath.getSchemaName())) {
            parts.add(quoteIdentifier(tablePath.getSchemaName()));
        }
        parts.add(quoteIdentifier(tablePath.getTableName()));
        return String.join(".", parts);
    }

    @Override
    protected String quoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        String quote = resolveIdentifierQuote();
        if (StringUtils.isBlank(quote)) {
            return identifier;
        }
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private List<DataSourceTableColumn> queryColumns(String sql) throws SQLException {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(stripTrailingSemicolon(sql))) {
            ResultSetMetaData metadata = statement.getMetaData();
            if (metadata == null) {
                statement.setMaxRows(1);
                try (ResultSet resultSet = statement.executeQuery()) {
                    metadata = resultSet.getMetaData();
                    return toColumns(metadata);
                }
            }
            return toColumns(metadata);
        }
    }

    private List<DataSourceTableColumn> toColumns(ResultSetMetaData metadata) throws SQLException {
        List<DataSourceTableColumn> columns = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            columns.add(DataSourceTableColumn.builder()
                    .columnName(metadata.getColumnLabel(i))
                    .columnType(metadata.getColumnTypeName(i))
                    .sourceType(metadata.getColumnTypeName(i))
                    .ordinalPosition(i)
                    .isNullable(nullableText(metadata.isNullable(i)))
                    .build());
        }
        return columns;
    }

    private Set<String> primaryKeys(DatabaseMetaData metadata, TablePath tablePath)
            throws SQLException {
        Set<String> result = new HashSet<>();
        try (ResultSet keys = metadata.getPrimaryKeys(
                blankToNull(tablePath.getDatabaseName()),
                blankToNull(tablePath.getSchemaName()),
                tablePath.getTableName())) {
            while (keys.next()) {
                result.add(keys.getString("COLUMN_NAME"));
            }
        }
        return result;
    }

    private QueryResult execute(String sql, int maxRows) throws SQLException {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setMaxRows(maxRows);
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                List<FrontedTableColumn> columns = new ArrayList<>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    String columnName = metadata.getColumnLabel(i);
                    FrontedTableColumn column = new FrontedTableColumn();
                    column.setTitle(columnName);
                    column.setDataIndex(columnName);
                    column.setKey(columnName);
                    column.setEllipsis(true);
                    columns.add(column);
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        row.put(metadata.getColumnLabel(i), resultSet.getObject(i));
                    }
                    rows.add(row);
                }
                return new QueryResult(columns, rows);
            }
        }
    }

    private TablePath normalizeTablePath(TablePath tablePath) {
        if (tablePath == null) {
            throw new IllegalArgumentException("table_path cannot be empty");
        }
        return resolveTablePath(tablePath.getTableName());
    }

    private String toTablePath(ResultSet resultSet) throws SQLException {
        String schema = resultSet.getString("TABLE_SCHEM");
        String table = resultSet.getString("TABLE_NAME");
        return StringUtils.isBlank(schema) ? table : schema + "." + table;
    }

    private OptionVO toOption(String tablePath) {
        OptionVO option = new OptionVO();
        option.setValue(tablePath);
        option.setLabel(tablePath);
        return option;
    }

    private String catalogPattern() {
        return blankToNull(getParam().getDatabase());
    }

    private String schemaPattern() {
        return blankToNull(getParam().getSchemaName());
    }

    private String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value;
    }

    private String stripTrailingSemicolon(String sql) {
        String value = StringUtils.trimToEmpty(sql);
        while (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private String nullableText(int nullable) {
        return nullable == ResultSetMetaData.columnNoNulls ? "NO" : "YES";
    }

    private String resolveIdentifierQuote() {
        if (identifierQuote != null) {
            return identifierQuote;
        }
        synchronized (this) {
            if (identifierQuote == null) {
                try (Connection connection = getConnection()) {
                    String quote = connection.getMetaData().getIdentifierQuoteString();
                    identifierQuote = quote == null ? "" : quote.trim();
                } catch (SQLException e) {
                    identifierQuote = "";
                }
            }
        }
        return identifierQuote;
    }
}
