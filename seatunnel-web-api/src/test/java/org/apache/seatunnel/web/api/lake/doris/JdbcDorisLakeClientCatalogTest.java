package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogDesiredSpec;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogValidationCode;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcCatalogDdlBuilder;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcDriverRegistry;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JdbcDorisLakeClientCatalogTest {

    private static final String CHECKSUM =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void showCatalogReadsKeyValueRowsAndFiltersSecretsAndInjectedDefaults() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW CATALOG `source_catalog`")).thenReturn(result);
        when(result.next()).thenReturn(true, true, true, true, true, true, false);
        when(result.getString("Key")).thenReturn(
                "Type", "JDBC_URL", "Driver_Class", "Include_Table_List", "User", "Password");
        when(result.getString("Value")).thenReturn(
                "jdbc", "jdbc:postgresql://source.example/app", "org.postgresql.Driver",
                "Sales_DB.Orders", "reader", "not-returned");

        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        Map<String, String> properties = client.readCatalogProperties("source_catalog");

        assertEquals(Map.of(
                "type", "jdbc",
                "jdbc_url", "******",
                "driver_class", "org.postgresql.Driver",
                "include_table_list", "Sales_DB.Orders"), properties);
        assertFalse(properties.containsKey("user"));
        assertFalse(properties.containsKey("password"));
        verify(statement).executeQuery("SHOW CATALOG `source_catalog`");
    }

    @Test
    void genericAlterAndRefreshUseBoundedEscapedDdl() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        client.alterCatalogProperties("source_catalog", Map.of(
                "include_table_list", "Sales.O'Reilly",
                "include_database_list", "Sales"));
        client.refreshCatalog("source_catalog");

        verify(statement).execute(
                "ALTER CATALOG `source_catalog` SET PROPERTIES "
                        + "('include_database_list' = 'Sales', "
                        + "'include_table_list' = 'Sales.O''Reilly')");
        verify(statement).execute("REFRESH CATALOG `source_catalog`");
    }

    @Test
    void genericAlterRejectsCredentialsBeforeOpeningJdbcConnection() {
        DataSource dataSource = mock(DataSource.class);
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        assertThrows(IllegalArgumentException.class,
                () -> client.alterCatalogProperties("source_catalog", Map.of("password", "secret")));
        assertThrows(IllegalArgumentException.class,
                () -> client.alterCatalogProperties("source_catalog", Map.of("user", "reader")));
        verifyNoInteractions(dataSource);
    }

    @Test
    void fullAlterUsesServerDriverRegistryAndExecutionOnlyCredentials() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        LakeProperties.JdbcCatalog configuration = new LakeProperties.JdbcCatalog();
        configuration.setRegistryRevision("drivers-v1");
        LakeProperties.Driver mysql = LakeProperties.Driver.mysqlDefaults();
        mysql.setEnabled(true);
        mysql.setVerified(true);
        mysql.setUrl("file:/opt/drivers/mysql.jar");
        mysql.setChecksum(CHECKSUM);
        configuration.setMysql(mysql);
        LakeCatalogDesiredSpec mysqlDesired = new LakeCatalogDesiredSpec(
                "source_catalog", 17L, "source-v1", LakeJdbcAdapterType.MYSQL,
                LakeCatalogScope.ALL, "jdbc:mysql://source.example/source",
                "file:/opt/drivers/mysql.jar", "com.mysql.cj.jdbc.Driver", CHECKSUM,
                "drivers-v1", "credential-v1", List.of(), List.of(), Map.of());

        new JdbcDorisLakeClient(dataSource, new LakeProperties()).alterCatalog(
                "SOURCE_CATALOG", mysqlDesired, new LakeJdbcDriverRegistry(configuration),
                new LakeJdbcCatalogDdlBuilder.CatalogCredentials("reader", "secret"));

        verify(statement).execute(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.startsWith("ALTER CATALOG `source_catalog` SET PROPERTIES")
                        && sql.contains("'user' = 'reader'")
                        && sql.contains("'password' = 'secret'")
                        && sql.contains("'driver_url' = 'file:/opt/drivers/mysql.jar'")));
    }

    @Test
    void validateCatalogReturnsMatchAfterBoundedShowCatalogReads() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet catalogs = mock(ResultSet.class);
        ResultSet properties = mock(ResultSet.class);
        ResultSet databases = mock(ResultSet.class);
        ResultSet tables = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW CATALOGS")).thenReturn(catalogs);
        when(statement.executeQuery("SHOW CATALOG `source_catalog`")).thenReturn(properties);
        when(statement.executeQuery("SHOW DATABASES FROM `source_catalog`")).thenReturn(databases);
        when(statement.executeQuery("SHOW TABLES FROM `source_catalog`.`Sales_DB`")).thenReturn(tables);
        when(catalogs.next()).thenReturn(true, false);
        when(catalogs.getString(1)).thenReturn("source_catalog");
        stubProperties(properties, desired(), false);
        when(databases.next()).thenReturn(true, false);
        when(databases.getString(1)).thenReturn("Sales_DB");
        when(tables.next()).thenReturn(true, false);
        when(tables.getString(1)).thenReturn("Orders");

        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        assertTrue(client.validateCatalog("source_catalog", desired()).isMatch());
        verify(statement).executeQuery("SHOW CATALOGS");
        verify(statement).executeQuery("SHOW CATALOG `source_catalog`");
        verify(statement).executeQuery("SHOW DATABASES FROM `source_catalog`");
        verify(statement).executeQuery("SHOW TABLES FROM `source_catalog`.`Sales_DB`");
    }

    @Test
    void validateCatalogDistinguishesMissingAndUnknownReads() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet catalogs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW CATALOGS")).thenReturn(catalogs);
        when(catalogs.next()).thenReturn(false);
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        assertTrue(client.validateCatalog("source_catalog", desired()).isMissing());

        when(catalogs.next()).thenThrow(new SQLException("password=private-value"));
        var unknown = client.validateCatalog("source_catalog", desired());
        assertTrue(unknown.isUnknown());
        assertEquals(LakeCatalogValidationCode.METADATA_UNAVAILABLE, unknown.code());
        assertFalse(unknown.toString().contains("private-value"));
    }

    @Test
    void validateCatalogRejectsNameDriftBeforeOpeningJdbcConnection() {
        DataSource dataSource = mock(DataSource.class);
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        var result = client.validateCatalog("other_catalog", desired());

        assertTrue(result.isMismatch());
        assertEquals(LakeCatalogValidationCode.NAME_MISMATCH, result.code());
        assertEquals(Map.of("catalog_name", "******"), result.mismatches());
        verifyNoInteractions(dataSource);
    }

    @Test
    void validateCatalogReportsMissingRequestedDatabase() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet catalogs = mock(ResultSet.class);
        ResultSet catalogProperties = mock(ResultSet.class);
        ResultSet databases = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW CATALOGS")).thenReturn(catalogs);
        when(statement.executeQuery("SHOW CATALOG `source_catalog`")).thenReturn(catalogProperties);
        when(statement.executeQuery("SHOW DATABASES FROM `source_catalog`")).thenReturn(databases);
        when(catalogs.next()).thenReturn(true, false);
        when(catalogs.getString(1)).thenReturn("source_catalog");
        LakeCatalogDesiredSpec databaseDesired = new LakeCatalogDesiredSpec(
                "source_catalog", 17L, "source-v1", LakeJdbcAdapterType.POSTGRESQL,
                LakeCatalogScope.DATABASE, "jdbc:postgresql://source.example/app",
                "file:/opt/drivers/postgresql.jar", "org.postgresql.Driver", CHECKSUM,
                "drivers-v1", "credential-v1", List.of("Missing_DB"), List.of(), Map.of());
        stubProperties(catalogProperties, databaseDesired, false);
        when(databases.next()).thenReturn(true, false);
        when(databases.getString(1)).thenReturn("Other_DB");

        var result = new JdbcDorisLakeClient(dataSource, new LakeProperties())
                .validateCatalog("source_catalog", databaseDesired);

        assertTrue(result.isMismatch());
        assertEquals(LakeCatalogValidationCode.DATABASE_MISSING, result.code());
        assertEquals(Map.of("include_database_list", "******"), result.mismatches());
        verify(statement).executeQuery("SHOW DATABASES FROM `source_catalog`");
    }

    @Test
    void validateCatalogReportsMissingRequestedTable() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet catalogs = mock(ResultSet.class);
        ResultSet catalogProperties = mock(ResultSet.class);
        ResultSet databases = mock(ResultSet.class);
        ResultSet tables = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW CATALOGS")).thenReturn(catalogs);
        when(statement.executeQuery("SHOW CATALOG `source_catalog`")).thenReturn(catalogProperties);
        when(statement.executeQuery("SHOW DATABASES FROM `source_catalog`")).thenReturn(databases);
        when(statement.executeQuery("SHOW TABLES FROM `source_catalog`.`Sales_DB`")).thenReturn(tables);
        when(catalogs.next()).thenReturn(true, false);
        when(catalogs.getString(1)).thenReturn("source_catalog");
        LakeCatalogDesiredSpec tableDesired = desired();
        stubProperties(catalogProperties, tableDesired, false);
        when(databases.next()).thenReturn(true, false);
        when(databases.getString(1)).thenReturn("Sales_DB");
        when(tables.next()).thenReturn(true, false);
        when(tables.getString(1)).thenReturn("Other_Table");

        var result = new JdbcDorisLakeClient(dataSource, new LakeProperties())
                .validateCatalog("source_catalog", tableDesired);

        assertTrue(result.isMismatch());
        assertEquals(LakeCatalogValidationCode.TABLE_MISSING, result.code());
        assertEquals(Map.of("include_table_list", "******"), result.mismatches());
    }

    @Test
    void metadataProbeFailureIsUnknownAndDoesNotExposeConnectorDetails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet catalogs = mock(ResultSet.class);
        ResultSet catalogProperties = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW CATALOGS")).thenReturn(catalogs);
        when(statement.executeQuery("SHOW CATALOG `source_catalog`")).thenReturn(catalogProperties);
        when(statement.executeQuery("SHOW DATABASES FROM `source_catalog`"))
                .thenThrow(new SQLException("jdbc_url=jdbc:postgresql://user:secret@db/source"));
        when(catalogs.next()).thenReturn(true, false);
        when(catalogs.getString(1)).thenReturn("source_catalog");
        stubProperties(catalogProperties, desired(), false);

        var result = new JdbcDorisLakeClient(dataSource, new LakeProperties())
                .validateCatalog("source_catalog", desired());

        assertTrue(result.isUnknown());
        assertEquals(LakeCatalogValidationCode.METADATA_UNAVAILABLE, result.code());
        assertFalse(result.toString().contains("secret"));
        assertFalse(result.toString().contains("jdbc:postgresql"));
    }

    @Test
    void showCatalogJdbcFailureIsRedacted() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString()))
                .thenThrow(new SQLException("password=private-value"));
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.readCatalogProperties("source_catalog"));

        assertEquals("Doris read catalog properties failed: SQLException", error.getMessage());
        assertFalse(error.toString().contains("private-value"));
    }

    @Test
    void refreshRejectsInjectedCatalogIdentifier() {
        DataSource dataSource = mock(DataSource.class);
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        assertThrows(IllegalArgumentException.class,
                () -> client.refreshCatalog("source_catalog;DROP"));
        verifyNoInteractions(dataSource);
    }

    private static LakeCatalogDesiredSpec desired() {
        return new LakeCatalogDesiredSpec(
                "source_catalog", 17L, "source-v1", LakeJdbcAdapterType.POSTGRESQL,
                LakeCatalogScope.TABLE, "jdbc:postgresql://source.example/app",
                "file:/opt/drivers/postgresql.jar", "org.postgresql.Driver", CHECKSUM,
                "drivers-v1", "credential-v1", List.of("Sales_DB"),
                List.of("Orders"), Map.of());
    }

    private static void stubProperties(
            ResultSet result,
            LakeCatalogDesiredSpec desired,
            boolean mismatch) throws SQLException {
        List<String> keys = new java.util.ArrayList<>(List.of(
                "type", "jdbc_url", "driver_url", "driver_class",
                "only_specified_database"));
        List<String> values = new java.util.ArrayList<>(List.of(
                "jdbc", desired.jdbcUrl(), desired.driverUrl(),
                mismatch ? "com.mysql.cj.jdbc.Driver" : desired.driverClass(),
                desired.scope() == LakeCatalogScope.ALL ? "false" : "true"));
        if (desired.scope() != LakeCatalogScope.ALL) {
            keys.add("include_database_list");
            values.add(String.join(",", desired.databaseInclude()));
        }
        if (desired.scope() == LakeCatalogScope.TABLE) {
            keys.add("include_table_list");
            values.add(desired.databaseInclude().get(0) + "."
                    + String.join("," + desired.databaseInclude().get(0) + ".",
                    desired.tableInclude()));
        }
        int[] row = {0};
        when(result.next()).thenAnswer(invocation -> row[0]++ < keys.size());
        int[] keyIndex = {0};
        when(result.getString("Key")).thenAnswer(
                invocation -> keys.get(keyIndex[0]++));
        int[] valueIndex = {0};
        when(result.getString("Value")).thenAnswer(
                invocation -> values.get(valueIndex[0]++));
    }
}
