package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.LakeProperties;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JdbcDorisLakeClientPartitionTest {

    @Test
    void alterUsesOnlyP0WhitelistAndQuotesIdentifiersAndValues() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        client.alterTableProperties("lake_db", "events",
                Map.of(" partition.retention_count ", "7"));

        verify(statement).execute("ALTER TABLE `lake_db`.`events` SET (\"partition.retention_count\" = \"7\")");
    }

    @Test
    void alterRejectsUnsupportedOrInjectedPropertiesBeforeOpeningJdbcConnection() {
        DataSource dataSource = mock(DataSource.class);
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        assertThrows(IllegalArgumentException.class, () -> client.alterTableProperties(
                "lake_db", "events", Map.of("_auto_bucket", "true")));
        assertThrows(IllegalArgumentException.class, () -> client.alterTableProperties(
                "lake_db", "events", Map.of("partition.retention_count", "7\"; DROP TABLE events")));
        assertThrows(IllegalArgumentException.class, () -> client.alterTableProperties(
                "lake_db", "events", Map.of("partition.retention_count", "0")));
        verifyNoInteractions(dataSource);
    }

    @Test
    void listPartitionsReadsKnownColumnsFromAFullShowPartitionsResult() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(6);
        String[] labels = {"PartitionName", "State", "PartitionKey", "Range", "LowerBound", "UpperBound"};
        doAnswer(invocation -> labels[invocation.getArgument(0, Integer.class) - 1])
                .when(metadata).getColumnLabel(anyInt());
        when(result.next()).thenReturn(true, false);
        String[] values = {"p202609", "NORMAL", "`event_time`", "[\"2026-09-01\", \"2026-10-01\")",
                "2026-09-01", "2026-10-01"};
        doAnswer(invocation -> values[invocation.getArgument(0, Integer.class) - 1])
                .when(result).getString(anyInt());
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        List<DorisPartitionMetadata> partitions = client.listPartitions("lake_db", "events");

        assertEquals(List.of(new DorisPartitionMetadata("p202609", "NORMAL", "`event_time`",
                "[\"2026-09-01\", \"2026-10-01\")", "2026-09-01", "2026-10-01")), partitions);
        verify(statement).executeQuery("SHOW PARTITIONS FROM `lake_db`.`events`");
    }

    @Test
    void listPartitionsPreservesRowsWhenDorisReturnsOnlyACompatibleSubsetOfColumns() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
        when(result.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(3);
        String[] labels = {"PARTITION_NAME", "LOWER_BOUND", "UPPER_BOUND"};
        doAnswer(invocation -> labels[invocation.getArgument(0, Integer.class) - 1])
                .when(metadata).getColumnLabel(anyInt());
        when(result.next()).thenReturn(true, true, false);
        when(result.getString(1)).thenReturn("p_old", "p_new");
        when(result.getString(2)).thenReturn("MINVALUE", "2027-01-01");
        when(result.getString(3)).thenReturn("2026-01-01", "MAXVALUE");
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        List<DorisPartitionMetadata> partitions = client.listPartitions("lake_db", "events");

        assertEquals(2, partitions.size());
        assertEquals("p_old", partitions.get(0).partitionName());
        assertEquals("2026-01-01", partitions.get(0).upperBound());
        assertEquals("2027-01-01", partitions.get(1).lowerBound());
        assertFalse(partitions.get(0).state() != null);
        assertTrue(partitions.get(1).range() == null);
    }

    @Test
    void partitionJdbcFailureIsRedacted() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("password=private-value"));
        JdbcDorisLakeClient client = new JdbcDorisLakeClient(dataSource, new LakeProperties());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.listPartitions("lake_db", "events"));

        assertEquals("Doris list partitions failed: SQLException", error.getMessage());
        assertFalse(error.toString().contains("private-value"));
    }
}
