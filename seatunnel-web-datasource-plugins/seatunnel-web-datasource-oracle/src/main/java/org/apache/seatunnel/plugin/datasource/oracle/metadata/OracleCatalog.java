package org.apache.seatunnel.plugin.datasource.oracle.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractJdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.jdbc.QueryRequest;
import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.api.modal.DataSourceTableColumn;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.common.utils.NonNullFunctions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class OracleCatalog extends AbstractJdbcCatalog {

    private static final String SELECT_COLUMNS_SQL_TEMPLATE =
            "SELECT COLUMN_NAME, DATA_TYPE, NULLABLE, COLUMN_ID FROM ALL_TAB_COLUMNS " +
                    "WHERE OWNER = '%s' AND TABLE_NAME = '%s' ORDER BY COLUMN_ID ASC";

    private static final String SELECT_SPECIFIED_COLUMNS_SQL_TEMPLATE =
            "SELECT COLUMN_NAME, DATA_TYPE, NULLABLE, COLUMN_ID FROM ALL_TAB_COLUMNS " +
                    "WHERE OWNER = '%s' AND TABLE_NAME = '%s' AND COLUMN_NAME IN (%s) ORDER BY COLUMN_ID ASC";

    public OracleCatalog(BaseConnectionParam param, JdbcConnectionProvider connectionManager) {
        super(param, connectionManager);
    }

    @Override
    protected String quoteIdentifier(String identifier) {
        if (StringUtils.isBlank(identifier)) {
            return identifier;
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    protected String formatDateTimeLiteral(LocalDateTime dateTime) {
        return "TO_TIMESTAMP('" +
                dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) +
                "', 'YYYY-MM-DD HH24:MI:SS')";
    }

    @Override
    protected String applyLimit(String sql, int limit) {
        return sql + " FETCH NEXT " + limit + " ROWS ONLY";
    }

    @Override
    protected String getTableName(ResultSet rs) throws SQLException {
        return rs.getString(1);
    }

    @Override
    protected String getListTableSql(String databaseName) {
        // Align with PostgreSQL: return schema.table across accessible owners.
        // Do not use ORACLE_MAINTAINED — it is missing on older Oracle (ORA-00904).
        return "SELECT OWNER || '.' || TABLE_NAME AS table_path "
                + "FROM ALL_TABLES "
                + "WHERE OWNER NOT IN ("
                + "'ANONYMOUS','APPQOSSYS','AUDSYS','CTXSYS','DBSFWUSER','DBSNMP',"
                + "'DIP','DVF','DVSYS','GGSYS','GSMADMIN_INTERNAL','GSMCATUSER',"
                + "'GSMUSER','LBACSYS','MDDATA','MDSYS','OJVMSYS','OLAPSYS',"
                + "'ORACLE_OCM','ORDDATA','ORDPLUGINS','ORDSYS','OUTLN',"
                + "'REMOTE_SCHEDULER_AGENT','SI_INFORMTN_SCHEMA','SYS','SYS$UMF',"
                + "'SYSBACKUP','SYSDG','SYSKM','SYSMAN','SYSRAC','SYSTEM','WMSYS','XDB'"
                + ") "
                + "ORDER BY OWNER, TABLE_NAME";
    }

    private String resolveOwner() {
        BaseConnectionParam param = getParam();

        if (StringUtils.isNotBlank(param.getUser())) {
            return param.getUser().toUpperCase();
        }

        if (StringUtils.isNotBlank(param.getSchemaName())) {
            return param.getSchemaName().toUpperCase();
        }
        return null;
    }

    @Override
    protected QueryRequest preprocessRequest(Map<String, Object> requestBody) {
        QueryRequest request = super.preprocessRequest(requestBody);
        TablePath tablePath = request.getTablePath();
        if (tablePath == null) {
            return request;
        }

        if (hasExplicitSchema(requestBody)) {
            String resolvedSchema = resolvePhysicalOwner(tablePath.getSchemaName());
            if (!StringUtils.equals(tablePath.getSchemaName(), resolvedSchema)) {
                request.setTablePath(TablePath.of(
                        tablePath.getDatabaseName(),
                        resolvedSchema,
                        tablePath.getTableName()));
            }
        } else if (usesConfiguredSchemaFallback(requestBody)) {
            String owner = resolvePhysicalOwner(configuredOwner(tablePath));
            if (StringUtils.isNotBlank(owner)) {
                request.setTablePath(TablePath.of(
                        tablePath.getDatabaseName(), owner, tablePath.getTableName()));
            }
        }
        return request;
    }

    private boolean hasExplicitSchema(Map<String, Object> requestBody) {
        if (requestBody == null) {
            return false;
        }
        Object schema = requestBody.get("schema_name");
        if (schema != null && StringUtils.isNotBlank(String.valueOf(schema))) {
            return true;
        }
        Object rawTablePath = requestBody.get("table_path");
        return rawTablePath != null && splitIdentifierPath(String.valueOf(rawTablePath)).size() > 1;
    }

    private String resolvePhysicalOwner(String requestedOwner) {
        if (StringUtils.isBlank(requestedOwner)) {
            return requestedOwner;
        }
        String foldedOwner = requestedOwner.toUpperCase(Locale.ROOT);
        if (StringUtils.equals(requestedOwner, foldedOwner)) {
            return requestedOwner;
        }

        String sql = "SELECT USERNAME FROM ALL_USERS WHERE USERNAME = ? OR USERNAME = ?";
        try (Connection connection = getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requestedOwner);
            statement.setString(2, foldedOwner);
            boolean exactOwnerExists = false;
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String owner = resultSet.getString(1);
                    if (StringUtils.equals(owner, requestedOwner)) {
                        exactOwnerExists = true;
                    }
                }
            }
            return exactOwnerExists ? requestedOwner : foldedOwner;
        } catch (SQLException e) {
            log.warn("Failed resolving Oracle owner case for '{}'; preserving the requested name", requestedOwner, e);
            return requestedOwner;
        }
    }

    private boolean usesConfiguredSchemaFallback(Map<String, Object> requestBody) {
        if (requestBody == null
                || (requestBody.get("schema_name") != null
                        && StringUtils.isNotBlank(String.valueOf(requestBody.get("schema_name"))))
                || requestBody.get("table_path") == null) {
            return false;
        }
        return splitIdentifierPath(String.valueOf(requestBody.get("table_path"))).size() == 1;
    }

    private String configuredOwner(TablePath tablePath) {
        if (StringUtils.isNotBlank(tablePath.getSchemaName())) {
            return tablePath.getSchemaName();
        }
        BaseConnectionParam param = getParam();
        return StringUtils.defaultIfBlank(param.getUser(), param.getSchemaName());
    }

    private String escapeSql(String value) {
        return value == null ? null : value.replace("'", "''");
    }

    @Override
    protected DataSourceTableColumn buildColumn(Map<String, Object> item) {
        String columnName = item.get("COLUMN_NAME").toString();
        String columnType = item.get("DATA_TYPE").toString();
        String isNullable = item.get("NULLABLE").toString();
        String columnComment = item.getOrDefault("COMMENTS", "").toString();
        String columnKey = item.getOrDefault("COLUMN_KEY", "").toString();
        int ordinalPosition = Integer.parseInt(item.get("COLUMN_ID").toString());

        return DataSourceTableColumn.builder()
                .isNullable(isNullable)
                .columnComment(columnComment)
                .columnKey(columnKey)
                .columnName(columnName)
                .sourceType(columnType)
                .ordinalPosition(ordinalPosition)
                .build();
    }

    @Override
    protected String getSelectColumnsSql(TablePath tablePath) {
        return String.format(
                SELECT_COLUMNS_SQL_TEMPLATE,
                resolveOwnerForTablePath(tablePath),
                tablePath.getTableName());
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
                resolveOwnerForTablePath(tablePath),
                tablePath.getTableName(),
                quotedColumnNames
        );
    }

    public String buildTableReference(TablePath tablePath) {
        String tableName = tablePath.getTableName();
        if (StringUtils.isBlank(tableName)) {
            throw new IllegalArgumentException("table is null");
        }

        String owner = tablePath.getSchemaName();
        if (StringUtils.isBlank(owner)) {
            owner = resolveOwner();
        }

        if (StringUtils.isBlank(owner)) {
            return quoteIdentifier(tableName);
        }

        return quoteIdentifier(owner) + "." + quoteIdentifier(tableName);
    }

    private String resolveOwnerForTablePath(TablePath tablePath) {
        String owner = tablePath.getSchemaName();

        if (StringUtils.isBlank(owner)) {
            owner = resolveOwner();
        }

        if (StringUtils.isBlank(owner)) {
            throw new IllegalArgumentException("Oracle owner/schema must not be blank");
        }

        return escapeSql(owner);
    }
}
