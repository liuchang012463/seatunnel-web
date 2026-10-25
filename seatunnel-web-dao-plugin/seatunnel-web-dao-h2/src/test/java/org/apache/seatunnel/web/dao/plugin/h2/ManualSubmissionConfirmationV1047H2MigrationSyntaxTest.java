package org.apache.seatunnel.web.dao.plugin.h2;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManualSubmissionConfirmationV1047H2MigrationSyntaxTest {

    @Test
    void reconcilesHistoricalSubmissionUnknownRows() throws Exception {
        String sql = read("/db/migration/h2/V1_0_47__remove_manual_submission_confirmation.sql");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:manual_submission_v1047;MODE=MySQL;DATABASE_TO_UPPER=false",
                "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_seatunnel_web_job_instance ("
                        + "id BIGINT PRIMARY KEY,"
                        + "job_status VARCHAR(32),"
                        + "engine_job_id VARCHAR(64),"
                        + "end_time TIMESTAMP,"
                        + "error_message VARCHAR(2048))");
                statement.execute("INSERT INTO t_seatunnel_web_job_instance"
                        + " (id, job_status, engine_job_id) VALUES"
                        + " (1, 'SUBMISSION_UNKNOWN', '101'),"
                        + " (2, 'SUBMISSION_UNKNOWN', NULL),"
                        + " (3, 'FINISHED', '103')");

                executeScript(statement, sql);

                assertStatus(statement, 1, "STARTING");
                assertStatus(statement, 2, "FAILED");
                assertStatus(statement, 3, "FINISHED");
            }
        }
    }

    private static void assertStatus(Statement statement, long id, String expected)
            throws Exception {
        try (ResultSet result = statement.executeQuery(
                "SELECT job_status FROM t_seatunnel_web_job_instance WHERE id = " + id)) {
            result.next();
            assertEquals(expected, result.getString(1));
        }
    }

    /**
     * The migration comment contains a semicolon ("automatically; legacy"), so comments are
     * removed before the script is split into statements.
     */
    private static void executeScript(Statement statement, String sql) throws Exception {
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\\R")) {
            int comment = line.indexOf("--");
            cleaned.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        for (String fragment : cleaned.toString().split(";")) {
            String command = fragment.trim();
            if (!command.isEmpty()) {
                statement.execute(command);
            }
        }
    }

    private static String read(String resource) throws Exception {
        try (InputStream stream = ManualSubmissionConfirmationV1047H2MigrationSyntaxTest.class
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing migration " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
