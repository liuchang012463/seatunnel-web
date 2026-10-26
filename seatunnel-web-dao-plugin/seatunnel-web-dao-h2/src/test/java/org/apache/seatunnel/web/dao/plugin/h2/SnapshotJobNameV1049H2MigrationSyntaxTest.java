package org.apache.seatunnel.web.dao.plugin.h2;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnapshotJobNameV1049H2MigrationSyntaxTest {

    @Test
    void backfillsTheSubmissionSnapshotFromTheDefinition() throws Exception {
        List<String> statements = statements(read(
                "/db/migration/h2/V1_0_49__snapshot_job_name_and_streaming_identity.sql"));
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:snapshot_job_name_v1049;MODE=MySQL;DATABASE_TO_UPPER=false",
                "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_seatunnel_web_job_definition ("
                        + "id BIGINT PRIMARY KEY,"
                        + "job_name VARCHAR(255),"
                        + "source_table CLOB,"
                        + "sink_table CLOB)");
                statement.execute("CREATE TABLE t_seatunnel_web_job_instance ("
                        + "id BIGINT PRIMARY KEY,"
                        + "job_definition_id BIGINT,"
                        + "job_status VARCHAR(32),"
                        + "log_path VARCHAR(512),"
                        + "source_table VARCHAR(512),"
                        + "sink_table VARCHAR(512))");
                statement.execute("CREATE TABLE t_seatunnel_web_streaming_job_definition ("
                        + "id BIGINT PRIMARY KEY,"
                        + "job_name VARCHAR(255),"
                        + "source_datasource_id BIGINT,"
                        + "sink_datasource_id BIGINT,"
                        + "source_table CLOB,"
                        + "sink_table CLOB)");
                statement.execute("CREATE TABLE t_seatunnel_web_streaming_job_instance ("
                        + "id BIGINT PRIMARY KEY,"
                        + "job_definition_id BIGINT,"
                        + "job_status VARCHAR(32),"
                        + "log_path VARCHAR(512))");

                // The ALTER statements run first, then the fixture rows are inserted, then the
                // backfill runs: this is the order a real upgrade sees.
                execute(statement, statements, "ALTER TABLE");
                statement.execute("INSERT INTO t_seatunnel_web_job_definition (id, job_name)"
                        + " VALUES (10, 'batch-definition-name')");
                statement.execute("INSERT INTO t_seatunnel_web_job_instance"
                        + " (id, job_definition_id, job_status) VALUES (1, 10, 'FINISHED')");
                statement.execute("INSERT INTO t_seatunnel_web_job_instance"
                        + " (id, job_definition_id, job_status, job_name)"
                        + " VALUES (2, 10, 'FINISHED', 'captured-name')");
                statement.execute("INSERT INTO t_seatunnel_web_streaming_job_instance"
                        + " (id, job_definition_id, job_status) VALUES (3, 20, 'FINISHED')");
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO t_seatunnel_web_streaming_job_definition"
                                + " (id, job_name, source_datasource_id, sink_datasource_id,"
                                + " source_table, sink_table)"
                                + " VALUES (20, 'stream-definition-name', 7, 8, ?, 'db.orders_dwd')")) {
                    insert.setString(1, longTableList());
                    insert.execute();
                }
                execute(statement, statements, "MERGE INTO");

                assertEquals("batch-definition-name", jobName(statement, "t_seatunnel_web_job_instance", 1));
                // A row that already carries the submission snapshot keeps it.
                assertEquals("captured-name", jobName(statement, "t_seatunnel_web_job_instance", 2));
                assertEquals("stream-definition-name",
                        jobName(statement, "t_seatunnel_web_streaming_job_instance", 3));
                assertEquals(7L, longValue(statement,
                        "SELECT source_datasource_id FROM t_seatunnel_web_streaming_job_instance WHERE id = 3"));
                assertEquals(8L, longValue(statement,
                        "SELECT sink_datasource_id FROM t_seatunnel_web_streaming_job_instance WHERE id = 3"));
                // The definition stores a JSON table list of several thousand characters; the
                // snapshot must keep it verbatim instead of failing or truncating.
                assertEquals(longTableList(), stringValue(statement,
                        "SELECT source_table FROM t_seatunnel_web_streaming_job_instance WHERE id = 3"));
                assertEquals("db.orders_dwd", stringValue(statement,
                        "SELECT sink_table FROM t_seatunnel_web_streaming_job_instance WHERE id = 3"));

                // The batch snapshot column was widened to CLOB as well: a long table list fits.
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO t_seatunnel_web_job_instance"
                                + " (id, job_definition_id, job_status, source_table)"
                                + " VALUES (4, 10, 'FINISHED', ?)")) {
                    insert.setString(1, longTableList());
                    insert.execute();
                }
                assertEquals(longTableList(), stringValue(statement,
                        "SELECT source_table FROM t_seatunnel_web_job_instance WHERE id = 4"));
            }
        }
    }

    /** A GUIDE_MULTI table list longer than the old varchar(1024) snapshot column. */
    private static String longTableList() {
        StringBuilder list = new StringBuilder("[");
        for (int index = 0; index < 120; index++) {
            if (index > 0) {
                list.append(',');
            }
            list.append("{\"database\":\"ods\",\"table\":\"orders_").append(index).append("\"}");
        }
        return list.append(']').toString();
    }

    private static void execute(Statement statement, List<String> statements, String prefix)
            throws Exception {
        for (String sql : statements) {
            if (sql.startsWith(prefix)) {
                statement.execute(sql);
            }
        }
    }

    private static String jobName(Statement statement, String table, long id) throws Exception {
        return stringValue(statement, "SELECT job_name FROM " + table + " WHERE id = " + id);
    }

    private static String stringValue(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static long longValue(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    /** Removes comments and splits the script into statements. */
    private static List<String> statements(String sql) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : sql.split("\\R")) {
            int comment = line.indexOf("--");
            cleaned.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : cleaned.toString().split(";")) {
            if (!part.trim().isEmpty()) {
                statements.add(part.trim());
            }
        }
        return statements;
    }

    private static String read(String resource) throws Exception {
        try (InputStream stream = SnapshotJobNameV1049H2MigrationSyntaxTest.class
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
