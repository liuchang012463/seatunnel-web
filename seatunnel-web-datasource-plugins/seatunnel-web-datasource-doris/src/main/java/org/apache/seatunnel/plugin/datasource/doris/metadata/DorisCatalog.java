package org.apache.seatunnel.plugin.datasource.doris.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractJdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.jdbc.HierarchicalJdbcCatalog;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcConnectionProvider;
import org.apache.seatunnel.plugin.datasource.api.jdbc.QueryRequest;
import org.apache.seatunnel.plugin.datasource.api.jdbc.TablePath;
import org.apache.seatunnel.plugin.datasource.api.modal.DataSourceTableColumn;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Doris 元数据服务。
 *
 * <p>所有元数据操作（库表列表、字段信息、数据预览等）均通过 JDBC 连接
 * （MySQL 协议端口 9030）执行 SQL 完成，如 SHOW TABLES、INFORMATION_SCHEMA 查询等。</p>
 */
@Slf4j
public class DorisCatalog extends AbstractJdbcCatalog implements HierarchicalJdbcCatalog {

    private static final String LIST_DATABASE_SQL =
            "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA ORDER BY SCHEMA_NAME";

    public DorisCatalog(BaseConnectionParam param, JdbcConnectionProvider connectionManager) {
        super(param, connectionManager);
    }

    @Override
    public List<OptionVO> listDatabaseOptions() {
        try {
            return queryOptionList(LIST_DATABASE_SQL, rs -> {
                String databaseName = rs.getString("SCHEMA_NAME");
                if (StringUtils.isBlank(databaseName)) {
                    return null;
                }
                OptionVO option = new OptionVO();
                option.setValue(databaseName);
                option.setLabel(databaseName);
                option.setDescription("Doris 数据库");
                return option;
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed listing Doris databases", e);
        }
    }

    @Override
    public List<OptionVO> listTableOptions(String databaseName) {
        if (StringUtils.isBlank(databaseName)) {
            throw new IllegalArgumentException("databaseName must not be blank");
        }
        try {
            return queryOptionList(getListTableSql(databaseName), this::buildTableOption);
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format("Failed listing Doris tables in database %s", databaseName), e);
        }
    }

    @Override
    protected String applyLimit(String sql, int limit) {
        return sql + " LIMIT " + limit;
    }

    @Override
    protected String getTableName(ResultSet rs) throws SQLException {
        return rs.getString("TABLE_NAME");
    }

    @Override
    protected String getListTableSql(String databaseName) {
        return String.format(
                "SELECT TABLE_NAME, TABLE_COMMENT " +
                        "FROM INFORMATION_SCHEMA.TABLES " +
                        "WHERE TABLE_SCHEMA = '%s' AND TABLE_TYPE = 'BASE TABLE' " +
                        "ORDER BY TABLE_NAME",
                sqlLiteral(databaseName)
        );
    }

    @Override
    protected OptionVO buildTableOption(ResultSet rs) throws SQLException {
        String tableName = rs.getString("TABLE_NAME");
        String tableComment = rs.getString("TABLE_COMMENT");

        OptionVO option = new OptionVO();
        option.setValue(tableName);
        option.setLabel(tableComment != null && !tableComment.trim().isEmpty() ? tableComment : tableName);
        option.setDescription(tableComment);
        return option;
    }

    @Override
    protected String quoteIdentifier(String identifier) {
        return "`" + StringUtils.defaultString(identifier).replace("`", "``") + "`";
    }

    @Override
    protected DataSourceTableColumn buildColumn(Map<String, Object> item) {
        String columnName = item.get("COLUMN_NAME").toString();
        String columnType = item.get("COLUMN_TYPE").toString();
        String isNullable = item.get("IS_NULLABLE").toString();
        String columnComment = item.getOrDefault("COLUMN_COMMENT", "").toString();
        String columnKey = item.getOrDefault("COLUMN_KEY", "").toString();
        int ordinalPosition = Integer.parseInt(item.get("ORDINAL_POSITION").toString());

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
                "SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s' ORDER BY ORDINAL_POSITION ASC",
                sqlLiteral(tablePath.getDatabaseName()), sqlLiteral(tablePath.getTableName()));
    }

    @Override
    protected String getSpecifiedColumnSql(TablePath tablePath, List<DataSourceTableColumn> columns) {
        List<String> columnNames = columns.stream()
                .map(DataSourceTableColumn::getColumnName)
                .toList();

        String quotedColumnNames = columnNames.stream()
                .map(name -> "'" + sqlLiteral(name) + "'")
                .collect(Collectors.joining(", "));

        return String.format(
                "SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s' AND COLUMN_NAME IN (%s) ORDER BY ORDINAL_POSITION ASC",
                sqlLiteral(tablePath.getDatabaseName()),
                sqlLiteral(tablePath.getTableName()),
                quotedColumnNames);
    }

    /**
     * Doris has no schema layer for the JDBC catalog.  A two-part table path
     * is therefore database.table, not the generic database.schema shape.
     */
    @Override
    protected TablePath resolveTablePath(String tablePath) {
        if (StringUtils.isBlank(tablePath)) {
            throw new IllegalArgumentException("tablePath must not be blank");
        }

        String[] parts = Arrays.stream(tablePath.split("\\."))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .toArray(String[]::new);
        if (parts.length == 1) {
            return TablePath.of(getParam().getDatabase(), null, parts[0]);
        }
        if (parts.length == 2) {
            return TablePath.of(parts[0], null, parts[1]);
        }
        if (parts.length == 3) {
            // Keep compatibility with callers that already send a fully
            // qualified path, while Doris still uses the first component as
            // the effective database.
            return TablePath.of(parts[0], parts[1], parts[2]);
        }
        throw new IllegalArgumentException("Invalid Doris tablePath: " + tablePath);
    }

    @Override
    protected QueryRequest preprocessRequest(Map<String, Object> requestBody) {
        QueryRequest request = super.preprocessRequest(requestBody);
        Object database = requestBody == null ? null : requestBody.get("database");
        if (request.getTablePath() != null && database != null
                && StringUtils.isNotBlank(database.toString())) {
            request.setTablePath(TablePath.of(
                    database.toString().trim(),
                    null,
                    request.getTablePath().getTableName()));
        }
        return request;
    }

    @Override
    public String buildTableReference(TablePath tablePath) {
        if (tablePath == null || StringUtils.isBlank(tablePath.getTableName())) {
            throw new IllegalArgumentException("table is null");
        }
        String database = tablePath.getDatabaseName();
        if (StringUtils.isBlank(database)) {
            return quoteIdentifier(tablePath.getTableName());
        }
        return quoteIdentifier(database) + "." + quoteIdentifier(tablePath.getTableName());
    }

    /**
     * 获取用于连通性测试过滤条件的字段名。
     *
     * <p>通过 SHOW PARTITIONS 查询判断表是否为分区表：
     * 如果是分区表，返回分区字段名；如果不是分区表，返回第一个字段名。</p>
     *
     * @param database 数据库名
     * @param table    表名
     * @return 用于过滤条件的字段名
     */
    public String getFilterColumn(String database, String table) {
        try (Connection conn = getConnection()) {
            // 尝试通过 SHOW PARTITIONS 获取分区字段
            String partitionCol = tryGetPartitionColumn(conn, database, table);
            if (partitionCol != null) {
                return partitionCol;
            }
            // 非分区表，使用第一个字段
            return getFirstColumn(conn, database, table);
        } catch (SQLException e) {
            throw new RuntimeException(
                    String.format("获取表 '%s.%s' 的过滤字段失败", database, table), e);
        }
    }

    /**
     * 通过 SHOW PARTITIONS 尝试获取分区字段名。
     *
     * @return 分区字段名，如果不是分区表或无法获取则返回 null
     */
    private String tryGetPartitionColumn(Connection conn, String database, String table)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                String.format("SHOW PARTITIONS FROM %s.%s",
                        quoteIdentifier(database), quoteIdentifier(table)));
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            // 查找 PartitionKey 列
            int pkIndex = -1;
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if ("PartitionKey".equalsIgnoreCase(meta.getColumnName(i))) {
                    pkIndex = i;
                    break;
                }
            }
            if (pkIndex < 0) {
                return null;
            }
            while (rs.next()) {
                String key = rs.getString(pkIndex);
                if (key != null && !key.isEmpty()) {
                    return key;
                }
            }
        }
        return null;
    }

    /**
     * 获取表的第一个字段名（按 ORDINAL_POSITION 排序）。
     */
    private String getFirstColumn(Connection conn, String database, String table)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(String.format(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = '%s' AND TABLE_NAME = '%s' AND IS_NULLABLE ='NO'" +
                        "ORDER BY ORDINAL_POSITION ASC LIMIT 1",
                sqlLiteral(database), sqlLiteral(table)));
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getString(1);
            }
        }
        throw new IllegalStateException(
                String.format("表 '%s.%s' 没有找到任何字段", database, table));
    }

    private static String sqlLiteral(String value) {
        return StringUtils.defaultString(value).replace("'", "''");
    }
}
