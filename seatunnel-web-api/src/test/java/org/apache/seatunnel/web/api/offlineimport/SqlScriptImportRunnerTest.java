package org.apache.seatunnel.web.api.offlineimport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlScriptImportRunnerTest {

    private static Connection connection;

    @BeforeAll
    static void openH2() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:sqlImportRunner;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE target_table (id INT PRIMARY KEY, flag INT DEFAULT 0)");
        }
    }

    @AfterAll
    static void closeH2() throws SQLException {
        connection.close();
    }

    @BeforeEach
    void cleanTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE target_table");
        }
    }

    private int countRows() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM target_table")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void executesAllStatementsAndReportsProgress() throws Exception {
        ExecutionLogBuffer log = new ExecutionLogBuffer(1024);
        SqlScriptImportRunner runner = new SqlScriptImportRunner(connection, log);
        List<String> statements = SqlScriptSplitter.split(
                "INSERT INTO target_table (id) VALUES (1);\nINSERT INTO target_table (id) VALUES (2),(3);");

        AtomicInteger lastExecuted = new AtomicInteger();
        AtomicInteger lastFailed = new AtomicInteger();
        String failure = runner.execute(statements, true, (executed, failed, total) -> {
            lastExecuted.set(executed);
            lastFailed.set(failed);
            assertEquals(2, total);
        });

        assertNull(failure);
        assertEquals(2, lastExecuted.get());
        assertEquals(0, lastFailed.get());
        assertEquals(3, countRows());
        assertTrue(log.snapshot().contains("OK 2/2"));
    }

    @Test
    void stopOnErrorAbortsTheRemainingStatements() throws Exception {
        ExecutionLogBuffer log = new ExecutionLogBuffer(4096);
        SqlScriptImportRunner runner = new SqlScriptImportRunner(connection, log);
        List<String> statements = SqlScriptSplitter.split(
                "INSERT INTO target_table (id) VALUES (10);\n"
                        + "CREATE TABLE;\n"
                        + "INSERT INTO target_table (id) VALUES (11);");

        String failure = runner.execute(statements, true, (executed, failed, total) -> {
        });

        assertTrue(failure.contains("第 2 条语句执行失败"));
        assertEquals(1, countRows());
        assertTrue(log.snapshot().contains("失败 2/3"));
    }

    @Test
    void continueOnErrorCountsFailuresAndRunsTheRest() throws Exception {
        ExecutionLogBuffer log = new ExecutionLogBuffer(4096);
        SqlScriptImportRunner runner = new SqlScriptImportRunner(connection, log);
        List<String> statements = SqlScriptSplitter.split(
                "INSERT INTO target_table (id) VALUES (20);\nCREATE TABLE;\nINSERT INTO target_table (id) VALUES (21);");

        String failure = runner.execute(statements, false, (executed, failed, total) -> {
        });

        assertTrue(failure.contains("1/3 条语句执行失败"));
        assertEquals(2, countRows());
        assertTrue(log.snapshot().contains("OK 3/3"));
    }

    @Test
    void preludeRunsOutsideTheCounters() throws Exception {
        ExecutionLogBuffer log = new ExecutionLogBuffer(1024);
        SqlScriptImportRunner runner = new SqlScriptImportRunner(connection, log);
        runner.runPrelude(List.of("SET MODE MySQL"));
        String failure = runner.execute(List.of("INSERT INTO target_table (id) VALUES (30)"), true,
                (executed, failed, total) -> {
                });
        assertNull(failure);
        assertTrue(log.snapshot().contains("执行会话预置语句"));
    }
}
