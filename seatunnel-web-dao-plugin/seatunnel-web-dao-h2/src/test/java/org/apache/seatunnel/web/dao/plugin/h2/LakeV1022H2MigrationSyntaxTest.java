package org.apache.seatunnel.web.dao.plugin.h2;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LakeV1022H2MigrationSyntaxTest {

    @Test
    void h2EquivalentMigrationAddsLifecycleObservationColumns() throws Exception {
        String base = read("/db/migration/h2/V1_0_21__init_lake_dual_mode_v14.sql");
        String observation = read("/db/migration/h2/V1_0_22__add_lake_lifecycle_partition_observation.sql");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:lake_v1022;MODE=MySQL;DATABASE_TO_UPPER=false", "sa", "")) {
            try (var statement = connection.createStatement()) {
                executeScript(statement, base);
                executeScript(statement, observation);
                try (var result = statement.executeQuery(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE TABLE_NAME='t_seatunnel_web_lake_table_lifecycle_binding' "
                                + "AND COLUMN_NAME IN ('actual_partition_summary_json', 'last_observed_at')")) {
                    result.next();
                    assertEquals(2, result.getInt(1));
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
        try (InputStream stream = LakeV1022H2MigrationSyntaxTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing migration " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
