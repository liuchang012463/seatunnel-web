package org.apache.seatunnel.web.dao.plugin.h2;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubmittedTableSnapshotV1043H2MigrationSyntaxTest {

    @Test
    void widensSubmittedTableSnapshotColumns() throws Exception {
        String sql = read("/db/migration/h2/V1_0_43__widen_submitted_table_snapshot.sql");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:submitted_table_snapshot_v1043;MODE=MySQL;DATABASE_TO_UPPER=false",
                "sa", "")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_seatunnel_web_job_instance ("
                        + "id BIGINT PRIMARY KEY,"
                        + "source_table VARCHAR(512),"
                        + "sink_table VARCHAR(512))");
                executeScript(statement, sql);
                try (var result = statement.executeQuery(
                        "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE UPPER(TABLE_NAME)='T_SEATUNNEL_WEB_JOB_INSTANCE' "
                                + "AND UPPER(COLUMN_NAME) IN ('SOURCE_TABLE','SINK_TABLE') "
                                + "ORDER BY COLUMN_NAME")) {
                    result.next();
                    assertEquals(1024, result.getInt(1));
                    result.next();
                    assertEquals(1024, result.getInt(1));
                }
            }
        }
    }

    private static void executeScript(java.sql.Statement statement, String sql) throws Exception {
        for (String fragment : sql.split(";")) {
            String command = fragment.trim();
            if (!command.isEmpty()) {
                statement.execute(command);
            }
        }
    }

    private static String read(String resource) throws Exception {
        try (InputStream stream = SubmittedTableSnapshotV1043H2MigrationSyntaxTest.class
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing migration " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
