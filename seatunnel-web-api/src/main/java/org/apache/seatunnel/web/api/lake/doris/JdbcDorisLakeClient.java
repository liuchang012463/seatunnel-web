package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.CatalogPropertyWhitelist;
import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.api.lake.DorisSqlLiteral;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** JDBC implementation of the bounded Doris control-plane API. */
public class JdbcDorisLakeClient implements DorisLakeClient {

    private static final String DATABASES_SQL =
            "SELECT SCHEMA_NAME FROM information_schema.schemata ORDER BY SCHEMA_NAME";
    private static final String TABLES_SQL =
            "SELECT TABLE_NAME FROM information_schema.tables "
                    + "WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME";
    private static final String COLUMNS_SQL =
            "SELECT COLUMN_NAME, ORDINAL_POSITION, IS_NULLABLE, COLUMN_TYPE, DATA_TYPE, "
                    + "CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE "
                    + "FROM information_schema.columns WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                    + "ORDER BY ORDINAL_POSITION";
    private static final String PROPERTIES_SQL =
            "SELECT PROPERTY_NAME, PROPERTY_VALUE FROM information_schema.table_properties "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY PROPERTY_NAME";

    private final DataSource dataSource;
    private final LakeProperties properties;
    private final DorisDdlBuilder ddlBuilder;
    private final DorisContractReader contractReader;

    public JdbcDorisLakeClient(DataSource dataSource) {
        this(dataSource, new LakeProperties());
    }

    public JdbcDorisLakeClient(DataSource dataSource, LakeProperties properties) {
        this(dataSource, properties, new DorisDdlBuilder(), new DorisContractReader());
    }

    public JdbcDorisLakeClient(DataSource dataSource, LakeProperties properties,
                               DorisDdlBuilder ddlBuilder, DorisContractReader contractReader) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.ddlBuilder = Objects.requireNonNull(ddlBuilder, "ddlBuilder");
        this.contractReader = Objects.requireNonNull(contractReader, "contractReader");
    }

    @Override
    public boolean ping() {
        try {
            return withConnection("ping", connection -> {
                try (Statement statement = connection.createStatement()) {
                    configure(statement);
                    try (ResultSet result = statement.executeQuery("SELECT 1")) {
                        return result.next();
                    }
                }
            });
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Override
    public List<String> listDatabases() {
        return queryNames("list databases", DATABASES_SQL);
    }

    @Override
    public boolean databaseExists(String databaseName) {
        String database = DorisIdentifier.validate(databaseName);
        return queryExists("database", "SELECT 1 FROM information_schema.schemata "
                + "WHERE SCHEMA_NAME = ?", database);
    }

    @Override
    public void createDatabase(String databaseName) {
        String database = DorisIdentifier.validate(databaseName);
        execute("CREATE DATABASE IF NOT EXISTS " + DorisIdentifier.quote(database));
    }

    @Override
    public void dropDatabase(String databaseName) {
        String database = DorisIdentifier.validate(databaseName);
        execute("DROP DATABASE IF EXISTS " + DorisIdentifier.quote(database));
    }

    @Override
    public List<String> listTables(String databaseName) {
        String database = DorisIdentifier.validate(databaseName);
        return queryNames("list tables", TABLES_SQL, database);
    }

    @Override
    public boolean tableExists(String databaseName, String tableName) {
        String database = DorisIdentifier.validate(databaseName);
        String table = DorisIdentifier.validate(tableName);
        return queryExists("table", "SELECT 1 FROM information_schema.tables "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", database, table);
    }

    @Override
    public void createTable(String databaseName, String tableName, TargetContract contract) {
        createTable(databaseName, tableName, contract, Map.of());
    }

    @Override
    public void createTable(String databaseName, String tableName, TargetContract contract,
                            Map<String, String> tableProperties) {
        execute(ddlBuilder.build(databaseName, tableName, contract, tableProperties));
    }

    @Override
    public void dropTable(String databaseName, String tableName) {
        String database = DorisIdentifier.validate(databaseName);
        String table = DorisIdentifier.validate(tableName);
        execute("DROP TABLE IF EXISTS " + DorisIdentifier.quote(database) + '.'
                + DorisIdentifier.quote(table));
    }

    @Override
    public String showCreateTable(String databaseName, String tableName) {
        String database = DorisIdentifier.validate(databaseName);
        String table = DorisIdentifier.validate(tableName);
        String sql = "SHOW CREATE TABLE " + DorisIdentifier.quote(database) + '.'
                + DorisIdentifier.quote(table);
        return withConnection("show create table", connection -> {
            try (Statement statement = connection.createStatement()) {
                configure(statement);
                try (ResultSet result = statement.executeQuery(sql)) {
                    if (!result.next()) {
                        throw new IllegalStateException("Doris table metadata is unavailable");
                    }
                    ResultSetMetaData metadata = result.getMetaData();
                    int columnCount = metadata.getColumnCount();
                    String ddl = result.getString(columnCount >= 2 ? 2 : 1);
                    if (ddl == null || ddl.isBlank()) {
                        throw new IllegalStateException("Doris table definition is unavailable");
                    }
                    return ddl;
                }
            }
        });
    }

    @Override
    public TargetContract readContract(String databaseName, String tableName) {
        return contractReader.read(showCreateTable(databaseName, tableName));
    }

    @Override
    public List<DorisColumnMetadata> listColumns(String databaseName, String tableName) {
        String database = DorisIdentifier.validate(databaseName);
        String table = DorisIdentifier.validate(tableName);
        return withConnection("list columns", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(COLUMNS_SQL)) {
                configure(statement);
                statement.setString(1, database);
                statement.setString(2, table);
                try (ResultSet result = statement.executeQuery()) {
                    List<DorisColumnMetadata> columns = new ArrayList<>();
                    while (result.next()) {
                        String type = result.getString("COLUMN_TYPE");
                        if (type == null || type.isBlank()) {
                            type = result.getString("DATA_TYPE");
                        }
                        columns.add(new DorisColumnMetadata(
                                result.getString("COLUMN_NAME"),
                                result.getInt("ORDINAL_POSITION"),
                                type,
                                "YES".equalsIgnoreCase(result.getString("IS_NULLABLE")),
                                nullableLong(result, "CHARACTER_MAXIMUM_LENGTH"),
                                nullableInteger(result, "NUMERIC_PRECISION"),
                                nullableInteger(result, "NUMERIC_SCALE")));
                    }
                    return List.copyOf(columns);
                }
            }
        });
    }

    @Override
    public Map<String, String> readTableProperties(String databaseName, String tableName) {
        String database = DorisIdentifier.validate(databaseName);
        String table = DorisIdentifier.validate(tableName);
        return withConnection("read table properties", connection -> {
            try (PreparedStatement statement = connection.prepareStatement(PROPERTIES_SQL)) {
                configure(statement);
                statement.setString(1, database);
                statement.setString(2, table);
                try (ResultSet result = statement.executeQuery()) {
                    TreeMap<String, String> managed = new TreeMap<>();
                    while (result.next()) {
                        String key = result.getString("PROPERTY_NAME");
                        if (DorisTablePropertyWhitelist.isAllowed(key)) {
                            managed.put(key.trim().toLowerCase(java.util.Locale.ROOT),
                                    result.getString("PROPERTY_VALUE"));
                        }
                    }
                    return Collections.unmodifiableMap(managed);
                }
            }
        });
    }

    @Override
    public List<String> listCatalogs() {
        return withConnection("list catalogs", connection -> {
            try (Statement statement = connection.createStatement()) {
                configure(statement);
                try (ResultSet result = statement.executeQuery("SHOW CATALOGS")) {
                    List<String> catalogs = new ArrayList<>();
                    while (result.next()) {
                        String value = result.getString(1);
                        if (value != null && !value.isBlank()) {
                            catalogs.add(value);
                        }
                    }
                    return List.copyOf(catalogs);
                }
            }
        });
    }

    @Override
    public boolean catalogExists(String catalogName) {
        String catalog = DorisIdentifier.validate(catalogName);
        return listCatalogs().stream().anyMatch(catalog::equalsIgnoreCase);
    }

    @Override
    public void createCatalog(String catalogName, Map<String, String> properties) {
        String catalog = DorisIdentifier.validate(catalogName);
        Map<String, String> validated = CatalogPropertyWhitelist.validateAndCopy(properties);
        for (Map.Entry<String, String> entry : validated.entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Doris catalog property value must not be null");
            }
        }
        StringBuilder sql = new StringBuilder("CREATE CATALOG ")
                .append(DorisIdentifier.quote(catalog)).append(" PROPERTIES (");
        int index = 0;
        for (Map.Entry<String, String> entry : new TreeMap<>(validated).entrySet()) {
            if (index++ > 0) {
                sql.append(", ");
            }
            sql.append(DorisSqlLiteral.quote(entry.getKey())).append(" = ")
                    .append(DorisSqlLiteral.quote(entry.getValue()));
        }
        sql.append(')');
        execute(sql.toString());
    }

    @Override
    public void dropCatalog(String catalogName) {
        String catalog = DorisIdentifier.validate(catalogName);
        execute("DROP CATALOG IF EXISTS " + DorisIdentifier.quote(catalog));
    }

    @Override
    public void execute(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Doris SQL must not be blank");
        }
        withConnection("execute statement", connection -> {
            try (Statement statement = connection.createStatement()) {
                configure(statement);
                statement.execute(sql);
                return null;
            }
        });
    }

    private List<String> queryNames(String operation, String sql, String... parameters) {
        return withConnection(operation, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                configure(statement);
                for (int index = 0; index < parameters.length; index++) {
                    statement.setString(index + 1, parameters[index]);
                }
                try (ResultSet result = statement.executeQuery()) {
                    List<String> names = new ArrayList<>();
                    while (result.next()) {
                        String value = result.getString(1);
                        if (value != null && !value.isBlank()) {
                            names.add(value);
                        }
                    }
                    return List.copyOf(names);
                }
            }
        });
    }

    private boolean queryExists(String operation, String sql, String... parameters) {
        return withConnection("check " + operation, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                configure(statement);
                for (int index = 0; index < parameters.length; index++) {
                    statement.setString(index + 1, parameters[index]);
                }
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
        });
    }

    private <T> T withConnection(String operation, SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.execute(connection);
        } catch (SQLException exception) {
            throw safeFailure(operation, exception);
        }
    }

    private void configure(Statement statement) throws SQLException {
        long maxRows = properties.getMaxRows();
        if (maxRows > 0) {
            statement.setMaxRows((int) Math.min(Integer.MAX_VALUE, maxRows));
        }
        Duration timeout = properties.getQueryTimeout();
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            long seconds = Math.max(1, (timeout.toMillis() + 999) / 1000);
            statement.setQueryTimeout((int) Math.min(Integer.MAX_VALUE, seconds));
        }
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static IllegalStateException safeFailure(String operation, SQLException exception) {
        return new IllegalStateException("Doris " + operation + " failed: "
                + exception.getClass().getSimpleName());
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
