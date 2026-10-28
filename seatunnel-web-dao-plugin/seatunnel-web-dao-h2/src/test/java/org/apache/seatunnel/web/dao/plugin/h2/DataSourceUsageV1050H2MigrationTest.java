package org.apache.seatunnel.web.dao.plugin.h2;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataSourceUsageV1050H2MigrationTest {

    @Test
    void classifiesLegacyConnectionsFromActiveSourceAndTargetReferences() throws Exception {
        List<String> migration = statements(read(
                "/db/migration/h2/V1_0_50__add_data_source_usage.sql"));
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:data_source_usage_v1050;MODE=MySQL;DATABASE_TO_UPPER=false",
                "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_seatunnel_web_datasource ("
                        + "id BIGINT PRIMARY KEY, system_managed BOOLEAN)");
                statement.execute("CREATE TABLE t_seatunnel_web_job_definition ("
                        + "source_datasource_id BIGINT, sink_datasource_id BIGINT)");
                statement.execute("CREATE TABLE t_seatunnel_web_streaming_job_definition ("
                        + "source_datasource_id BIGINT, sink_datasource_id BIGINT)");
                statement.execute("CREATE TABLE t_seatunnel_web_offline_import_task ("
                        + "target_datasource_id BIGINT)");
                statement.execute("CREATE TABLE t_seatunnel_web_lake_ods_database_binding ("
                        + "source_data_source_id BIGINT, deleted BOOLEAN)");
                statement.execute("CREATE TABLE t_seatunnel_web_lake_external_catalog_binding ("
                        + "source_data_source_id BIGINT, deleted BOOLEAN)");
                statement.execute("CREATE TABLE t_seatunnel_web_lake_source_object_ref ("
                        + "source_data_source_id BIGINT, deleted BOOLEAN)");

                migration.stream().filter(sql -> sql.startsWith("ALTER TABLE"))
                        .forEach(sql -> execute(statement, sql));
                for (long id = 1; id <= 9; id++) {
                    statement.execute("INSERT INTO t_seatunnel_web_datasource (id, system_managed)"
                            + " VALUES (" + id + ", " + (id == 8) + ")");
                }
                statement.execute("INSERT INTO t_seatunnel_web_job_definition"
                        + " (source_datasource_id, sink_datasource_id) VALUES (1, 2), (3, 3)");
                statement.execute("INSERT INTO t_seatunnel_web_streaming_job_definition"
                        + " (source_datasource_id, sink_datasource_id) VALUES (4, NULL)");
                statement.execute("INSERT INTO t_seatunnel_web_offline_import_task"
                        + " (target_datasource_id) VALUES (5)");
                statement.execute("INSERT INTO t_seatunnel_web_lake_ods_database_binding"
                        + " (source_data_source_id, deleted) VALUES (6, FALSE), (7, TRUE)");

                migration.stream().filter(sql -> sql.startsWith("UPDATE"))
                        .forEach(sql -> execute(statement, sql));

                assertUsage(statement, 1, true, false);  // Batch source only.
                assertUsage(statement, 2, false, true);  // Batch target only.
                assertUsage(statement, 3, true, true);   // Both sides.
                assertUsage(statement, 4, true, false);  // Streaming source only.
                assertUsage(statement, 5, false, true);  // Offline import target only.
                assertUsage(statement, 6, true, false);  // Active lake source binding.
                assertUsage(statement, 7, true, true);   // Deleted binding is not a reference.
                assertUsage(statement, 8, true, true);   // System projection stays dual-purpose.
                assertUsage(statement, 9, true, true);   // Ambiguous history keeps legacy reach.
            }
        }
    }

    private static void assertUsage(Statement statement, long id, boolean source, boolean sink)
            throws Exception {
        try (ResultSet result = statement.executeQuery(
                "SELECT source_enabled, sink_enabled FROM t_seatunnel_web_datasource WHERE id = " + id)) {
            result.next();
            assertEquals(source, result.getBoolean(1), "source usage for datasource " + id);
            assertEquals(sink, result.getBoolean(2), "sink usage for datasource " + id);
        }
    }

    private static void execute(Statement statement, String sql) {
        try {
            statement.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("Migration statement failed: " + sql, e);
        }
    }

    private static List<String> statements(String sql) {
        List<String> statements = new ArrayList<>();
        for (String part : sql.split(";")) {
            if (!part.trim().isEmpty()) {
                statements.add(part.trim());
            }
        }
        return statements;
    }

    private static String read(String resource) throws Exception {
        try (InputStream stream = DataSourceUsageV1050H2MigrationTest.class
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
