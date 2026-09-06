package org.apache.seatunnel.web.dao.plugin.h2;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the offline H2 equivalent of the warehouse projection migration. */
class LakeV1025H2MigrationSyntaxTest {

    @Test
    void h2EquivalentMigrationCreatesWarehouseDriverAndAliasTables() throws Exception {
        String sql = read("/db/migration/h2/V1_0_25__add_lake_warehouse_configuration.sql");
        String versionSql = read("/db/migration/h2/V1_0_26__add_lake_jdbc_driver_version.sql");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:lake_v1025;MODE=MySQL;DATABASE_TO_UPPER=false", "sa", "")) {
            try (var statement = connection.createStatement()) {
                // The production datasource table is created by the base Web
                // schema.  Keep this focused migration test independent from
                // the MySQL-only initializer.
                statement.execute("CREATE TABLE t_seatunnel_web_datasource ("
                        + "id bigint PRIMARY KEY, status varchar(24))");
                executeScript(statement, sql);
                executeScript(statement, versionSql);

                try (var result = statement.executeQuery(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME IN ("
                                + "'t_seatunnel_web_lake_warehouse_config',"
                                + "'t_seatunnel_web_lake_jdbc_driver',"
                                + "'t_seatunnel_web_lake_datasource_alias')")) {
                    result.next();
                    assertEquals(3, result.getInt(1));
                }
                try (var result = statement.executeQuery(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE TABLE_NAME='t_seatunnel_web_datasource' "
                                + "AND COLUMN_NAME IN ('system_managed', 'system_key')")) {
                    result.next();
                    assertEquals(2, result.getInt(1));
                }
                try (var result = statement.executeQuery(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE TABLE_NAME='t_seatunnel_web_lake_jdbc_driver' "
                                + "AND COLUMN_NAME='version'")) {
                    result.next();
                    assertEquals(1, result.getInt(1));
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
        try (InputStream stream = LakeV1025H2MigrationSyntaxTest.class
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing migration " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
