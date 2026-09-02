package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogDesiredSpec;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcCatalogDdlBuilder;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcDriverRegistry;
import org.apache.seatunnel.web.api.lake.contract.DorisTypeBase;
import org.apache.seatunnel.web.api.lake.contract.TargetColumn;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.contract.TargetDistribution;
import org.apache.seatunnel.web.api.lake.contract.TargetPartition;
import org.apache.seatunnel.web.api.lake.contract.TargetType;
import org.apache.seatunnel.web.common.enums.LakeTableModel;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcDorisLakeClientTest {

    @Test
    void createTableAndCatalogUseValidatedGeneratedSql() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        client.createTable("lake_db", "events", contract());
        client.createCatalog("source_catalog", Map.of("type", "jdbc", "user", "reader"));

        verify(statement).execute(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("CREATE TABLE `lake_db`.`events`")
                        && sql.contains("DUPLICATE KEY(`id`)")
                        && sql.contains("DISTRIBUTED BY RANDOM BUCKETS AUTO")));
        verify(statement).execute(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("CREATE CATALOG `source_catalog`")
                        && sql.contains("'type' = 'jdbc'")));
    }

    @Test
    void metadataQueriesAreBoundedAndIgnoreUnmanagedProperties() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        java.sql.PreparedStatement statement = mock(java.sql.PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, true, false);
        when(result.getString("PROPERTY_NAME")).thenReturn("_auto_bucket", "min_load_replica_num");
        when(result.getString("PROPERTY_VALUE")).thenReturn("true", "-1");
        LakeProperties properties = new LakeProperties();
        properties.setMaxRows(13);
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, properties);

        Map<String, String> values = client.readTableProperties("lake_db", "events");

        assertEquals(Map.of("_auto_bucket", "true"), values);
        verify(statement).setMaxRows(13);
        verify(statement).setString(1, "lake_db");
        verify(statement).setString(2, "events");
    }

    @Test
    void showCreateReadsTheDefinitionColumnWithoutLeakingJdbcErrorText() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(result.getString(2)).thenReturn("CREATE TABLE `events` (...)");
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        assertEquals("CREATE TABLE `events` (...)", client.showCreateTable("lake_db", "events"));

        when(statement.execute(anyString())).thenThrow(new SQLException("password=private-value"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.createDatabase("lake_db"));
        assertNotNull(error.getMessage());
        assertFalse(error.getMessage().contains("private-value"));
        assertFalse(error.toString().contains("private-value"));
    }

    @Test
    void publicApiDoesNotExposeCallerGeneratedSqlExecution() throws Exception {
        assertTrue(Arrays.stream(DorisLakeClient.class.getMethods())
                .noneMatch(method -> method.getName().equals("execute")));
        assertFalse(Modifier.isPublic(JdbcDorisLakeClient.class
                .getDeclaredMethod("execute", String.class).getModifiers()));
    }

    @Test
    void pingReportsConnectivityWithoutPropagatingDriverDetails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("jdbc password=private-value"));
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource);

        assertFalse(client.ping());
    }

    @Test
    void sourceProbeAlwaysCleansUpTemporaryCatalogAfterCreateAttempt() {
        DorisLakeClient client = mock(DorisLakeClient.class,
                org.mockito.Mockito.CALLS_REAL_METHODS);
        LakeJdbcDriverRegistry registry = new LakeJdbcDriverRegistry();
        LakeCatalogDesiredSpec desired = probeSpec();
        LakeJdbcCatalogDdlBuilder.CatalogCredentials credentials =
                new LakeJdbcCatalogDdlBuilder.CatalogCredentials("reader", "secret");

        doNothing().when(client).createCatalog(any(), eq(registry), eq(credentials));
        doReturn(List.of()).when(client).listCatalogDatabases("_lake_probe_test");

        client.probeSource(desired, registry, credentials);

        verify(client).dropCatalog("_lake_probe_test");

        doThrow(new IllegalStateException("source unavailable")).when(client)
                .createCatalog(any(), eq(registry), eq(credentials));
        assertThrows(IllegalStateException.class,
                () -> client.probeSource(desired, registry, credentials));
        verify(client, org.mockito.Mockito.times(2)).dropCatalog("_lake_probe_test");
    }

    private static TargetContract contract() {
        return new TargetContract(LakeTableModel.DUPLICATE, List.of(
                new TargetColumn("id", 1, "id", TargetType.varchar(255), false, true, 1),
                new TargetColumn("payload", 2, "payload", new TargetType(DorisTypeBase.STRING),
                        true, false, 2)), List.of("id"), TargetPartition.disabled(),
                TargetDistribution.random());
    }

    private static LakeCatalogDesiredSpec probeSpec() {
        return new LakeCatalogDesiredSpec(
                "_lake_probe_test", 7L, "source-7", LakeJdbcAdapterType.MYSQL,
                LakeCatalogScope.ALL, "jdbc:mysql://db/app", "file:///mysql.jar",
                "com.mysql.cj.jdbc.Driver", "checksum", "registry", "credential",
                List.of(), List.of(), Map.of());
    }
}
