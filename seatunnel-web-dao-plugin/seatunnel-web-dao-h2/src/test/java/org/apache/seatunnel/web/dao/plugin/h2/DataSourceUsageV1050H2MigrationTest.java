package org.apache.seatunnel.web.dao.plugin.h2;

import org.junit.jupiter.api.Test;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.spi.bean.dto.config.ScriptJobContent;
import org.apache.seatunnel.web.common.utils.JSONUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                        + "id BIGINT, job_version INT, mode VARCHAR(32), "
                        + "source_datasource_id BIGINT, sink_datasource_id BIGINT)");
                statement.execute("CREATE TABLE t_seatunnel_web_streaming_job_definition ("
                        + "id BIGINT, job_version INT, mode VARCHAR(32), "
                        + "source_datasource_id BIGINT, sink_datasource_id BIGINT)");
                statement.execute("CREATE TABLE t_seatunnel_web_job_definition_content ("
                        + "job_definition_id BIGINT, version INT, mode VARCHAR(32), definition_content CLOB)");
                statement.execute("CREATE TABLE t_seatunnel_web_streaming_job_definition_content ("
                        + "job_definition_id BIGINT, version INT, mode VARCHAR(32), definition_content CLOB)");
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
                for (long id = 1; id <= 16; id++) {
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
                statement.execute("INSERT INTO t_seatunnel_web_job_definition"
                        + " (id, job_version, mode, source_datasource_id, sink_datasource_id)"
                        + " VALUES (100, 2, 'SCRIPT', 10, 11)");
                statement.execute("INSERT INTO t_seatunnel_web_job_definition"
                        + " (id, job_version, mode, source_datasource_id, sink_datasource_id)"
                        + " VALUES (101, 2, 'SCRIPT', 16, NULL)");
                statement.execute("INSERT INTO t_seatunnel_web_streaming_job_definition"
                        + " (id, job_version, mode, source_datasource_id, sink_datasource_id)"
                        + " VALUES (200, 1, 'SCRIPT', 14, NULL)");
                insertScript(statement, "t_seatunnel_web_job_definition_content", 100L, 2,
                        "source = ["
                                + "{ plugin_name = \"Jdbc\", dataSourceId = 10 },"
                                + "{ plugin_name = \"Jdbc\", dataSourceId = 12 },"
                                + "{ plugin_name = \"Jdbc\", \"dataSourceId\" = \"15\" }]\n"
                                + "sink = ["
                                + "{ plugin_name = \"Jdbc\", dataSourceId = 11 },"
                                + "{ plugin_name = \"Jdbc\", dataSourceId = 10 },"
                                + "{ plugin_name = \"Jdbc\", dataSourceId = 13 }]");
                insertScript(statement, "t_seatunnel_web_job_definition_content", 101L, 1,
                        "sink = [{ plugin_name = \"Jdbc\", dataSourceId = 16 }]");
                insertScript(statement, "t_seatunnel_web_streaming_job_definition_content", 200L, 1,
                        "source = ["
                                + "{ plugin_name = \"Jdbc\", dataSourceId = 14 },"
                                + "{ plugin_name = \"Jdbc\", dataSourceId = 12 }]\n"
                                + "sink = [{ plugin_name = \"Jdbc\", dataSourceId = 13 }]");

                try (ResultSet match = statement.executeQuery("SELECT REGEXP_LIKE(LOWER(definition_content), "
                        + "CONCAT('.*(datasourceid|datasource_id).{0,32}[^0-9]', 10, '([^0-9].*|$)')) "
                        + "FROM t_seatunnel_web_job_definition_content WHERE job_definition_id = 100")) {
                    match.next();
                    assertTrue(match.getBoolean(1), "JSON-wrapped HOCON should match datasource ID 10");
                }

                migration.stream().filter(sql -> sql.contains("UPDATE t_seatunnel_web_datasource"))
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
                assertUsage(statement, 10, true, true); // Direct source plus extra script sink.
                assertUsage(statement, 11, true, true); // Script sink.
                assertUsage(statement, 12, true, true); // Second script source.
                assertUsage(statement, 13, true, true); // Additional script sink.
                assertUsage(statement, 14, true, true); // Streaming script source.
                assertUsage(statement, 15, true, true); // JSON-wrapped, quoted key and string ID.
                assertUsage(statement, 16, true, false); // Stale SCRIPT content is not current.
            }
        }
    }

    private static void insertScript(Statement statement, String table, long jobId, int version, String hocon)
            throws Exception {
        ScriptJobContent script = new ScriptJobContent();
        script.setScriptType("HOCON");
        script.setHoconContent(hocon);
        statement.execute("INSERT INTO " + table
                + " (job_definition_id, version, mode, definition_content) VALUES ("
                + jobId + ", " + version + ", '" + JobDefinitionMode.SCRIPT.name() + "', '"
                + JSONUtils.toJsonString(script).replace("'", "''") + "')");
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
