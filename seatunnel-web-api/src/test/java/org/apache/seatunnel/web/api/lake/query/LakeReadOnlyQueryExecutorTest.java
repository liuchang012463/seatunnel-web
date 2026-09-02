package org.apache.seatunnel.web.api.lake.query;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeReadOnlyQueryExecutorTest {

    @Test
    void executesGeneratedReadOnlyPlanWithBoundsAndClosesResources() throws Exception {
        JdbcFixture fixture = fixture();
        when(fixture.resultSet.next()).thenReturn(true, false);
        when(fixture.resultSet.getObject(1)).thenReturn(7);
        when(fixture.resultSet.getObject(2)).thenReturn("Ada");

        LakeReadOnlyQueryProperties properties = properties(10, 100);
        LakeReadOnlyQueryResultVO result = new LakeReadOnlyQueryExecutor(
                fixture.dataSource, properties).execute(plan(false));

        assertEquals(List.of("id", "name"), result.columns());
        assertEquals(List.of(Map.of("id", 7, "name", "Ada")), result.rows());
        assertEquals(1, result.rowCount());
        assertFalse(result.truncated());
        verify(fixture.connection).prepareStatement(
                "SELECT `t0`.`id` AS `id`, `t0`.`name` AS `name` "
                        + "FROM `cat`.`db`.`orders` AS `t0` LIMIT 1");
        verify(fixture.connection).setReadOnly(true);
        verify(fixture.statement).setQueryTimeout(2);
        verify(fixture.statement).setMaxRows(1);
        verify(fixture.dataSource).getConnection();
        verify(fixture.statement).close();
        verify(fixture.connection).close();
    }

    @Test
    void cancelsAndTruncatesWhenResultBytesExceedServerBound() throws Exception {
        JdbcFixture fixture = fixture();
        when(fixture.resultSet.next()).thenReturn(true);
        when(fixture.resultSet.getObject(1)).thenReturn("0123456789");
        when(fixture.resultSet.getObject(2)).thenReturn("0123456789");

        LakeReadOnlyQueryResultVO result = new LakeReadOnlyQueryExecutor(
                fixture.dataSource, properties(10, 5)).execute(plan(false));

        assertTrue(result.truncated());
        assertEquals(0, result.rowCount());
        verify(fixture.statement).cancel();
    }

    @Test
    void timeoutIsStableAndDoesNotExposeGeneratedSql() throws Exception {
        JdbcFixture fixture = fixture();
        when(fixture.statement.executeQuery()).thenThrow(new java.sql.SQLException(
                "SELECT secret FROM internal_table", "HYT00"));

        LakeQueryExecutionException failure = assertThrows(LakeQueryExecutionException.class,
                () -> new LakeReadOnlyQueryExecutor(fixture.dataSource,
                        properties(10, 100)).execute(plan(true)));

        assertEquals(LakeQueryErrorCode.TIMEOUT, failure.errorCode());
        assertEquals(LakeQueryErrorCode.TIMEOUT, failure.getMessage());
        assertFalse(failure.getMessage().contains("SELECT"));
    }

    @Test
    void postgresStatementTimeoutIsNotReportedAsUserCancellation() throws Exception {
        JdbcFixture fixture = fixture();
        when(fixture.statement.executeQuery()).thenThrow(new java.sql.SQLException(
                "canceling statement due to statement timeout", "57014"));

        LakeQueryExecutionException failure = assertThrows(LakeQueryExecutionException.class,
                () -> new LakeReadOnlyQueryExecutor(fixture.dataSource,
                        properties(10, 100)).execute(plan(false)));

        assertEquals(LakeQueryErrorCode.TIMEOUT, failure.errorCode());
    }

    @Test
    void explicitJdbcCancelMessageRemainsCancellation() throws Exception {
        JdbcFixture fixture = fixture();
        when(fixture.statement.executeQuery()).thenThrow(new java.sql.SQLException(
                "canceling statement due to user request", "57014"));

        LakeQueryExecutionException failure = assertThrows(LakeQueryExecutionException.class,
                () -> new LakeReadOnlyQueryExecutor(fixture.dataSource,
                        properties(10, 100), new LakeReadOnlyQueryCancellationRegistry())
                        .execute(plan(false), "query-cancelled-by-driver"));

        assertEquals(LakeQueryErrorCode.CANCELLED, failure.errorCode());
    }

    @Test
    void setReadOnlyFailureStillExecutesOnlyGeneratedSelect() throws Exception {
        JdbcFixture fixture = fixture();
        doThrow(new java.sql.SQLException("driver does not support hint"))
                .when(fixture.connection).setReadOnly(true);
        when(fixture.resultSet.next()).thenReturn(false);

        LakeReadOnlyQueryResultVO result = new LakeReadOnlyQueryExecutor(
                fixture.dataSource, properties(10, 100)).execute(plan(true));

        assertTrue(result.explain());
        verify(fixture.connection).prepareStatement(
                "EXPLAIN SELECT `t0`.`id` AS `id`, `t0`.`name` AS `name` "
                        + "FROM `cat`.`db`.`orders` AS `t0` LIMIT 1");
        verify(fixture.connection).setReadOnly(true);
        verify(fixture.dataSource).getConnection();
    }

    private static LakeReadOnlyQueryPlan plan(boolean explain) {
        LakeQueryTableIdentity table = new LakeQueryTableIdentity("cat", "db", "orders");
        LakeQueryColumnIdentity id = new LakeQueryColumnIdentity(table, "id");
        LakeQueryColumnIdentity name = new LakeQueryColumnIdentity(table, "name");
        return LakeReadOnlyQueryPlan.single(table,
                List.of(new LakeQueryOutputColumn(id, "t0", "id"),
                        new LakeQueryOutputColumn(name, "t0", "name")),
                1, 1, explain);
    }

    private static LakeReadOnlyQueryProperties properties(long maxRows, long maxBytes) {
        LakeReadOnlyQueryProperties properties = new LakeReadOnlyQueryProperties();
        properties.setQueryTimeout(Duration.ofMillis(1_001));
        properties.setMaxRows(maxRows);
        properties.setMaxBytes(maxBytes);
        return properties;
    }

    private static JdbcFixture fixture() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        when(metadata.getColumnLabel(2)).thenReturn("name");
        return new JdbcFixture(dataSource, connection, statement, resultSet);
    }

    private record JdbcFixture(
            DataSource dataSource,
            Connection connection,
            PreparedStatement statement,
            ResultSet resultSet) {
    }
}
