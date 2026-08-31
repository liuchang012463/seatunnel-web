package org.apache.seatunnel.web.dao.plugin.h2;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LakeV1021H2MigrationSyntaxTest {

    @Test
    void h2EquivalentMigrationExecutesAndCreatesEightTables() throws Exception {
        String sql;
        try (InputStream stream = getClass().getResourceAsStream(
                "/db/migration/h2/V1_0_21__init_lake_dual_mode_v14.sql")) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:lake_v1021;MODE=MySQL;DATABASE_TO_UPPER=false", "sa", "")) {
            try (var statement = connection.createStatement()) {
                for (String fragment : sql.split(";")) {
                    String command = fragment.trim();
                    if (!command.isEmpty()) {
                        statement.execute(command);
                    }
                }
                try (var result = statement.executeQuery(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME LIKE 't_seatunnel_web_lake_%'")) {
                    result.next();
                    assertEquals(8, result.getInt(1));
                }
            }
        }
    }
}
