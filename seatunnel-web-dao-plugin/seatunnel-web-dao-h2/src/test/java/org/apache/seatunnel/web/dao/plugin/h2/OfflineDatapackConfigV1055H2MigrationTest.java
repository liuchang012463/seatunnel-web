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
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineDatapackConfigV1055H2MigrationTest {

    @Test
    void storesValidMappingJsonLargerThanMySqlTextLimit() throws Exception {
        String migration = read("/db/migration/h2/V1_0_55__add_offline_datapack_task_config.sql");
        String configJson = largeValidMappingConfig();
        assertTrue(configJson.getBytes(StandardCharsets.UTF_8).length > 65_535);

        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:offline_datapack_v1055;MODE=MySQL;DATABASE_TO_UPPER=false", "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_seatunnel_web_offline_import_task (options_json VARCHAR(2048))");
                for (String fragment : migration.split(";")) {
                    if (!fragment.isBlank()) {
                        statement.execute(fragment.trim());
                    }
                }
                statement.execute("INSERT INTO t_seatunnel_web_offline_import_task (options_json) VALUES ('{}')");
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE t_seatunnel_web_offline_import_task SET datapack_config_json = ?")) {
                update.setString(1, configJson);
                assertEquals(1, update.executeUpdate());
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT datapack_config_json FROM t_seatunnel_web_offline_import_task")) {
                result.next();
                assertEquals(configJson, result.getString(1));
            }
        }
    }

    private static String largeValidMappingConfig() {
        List<String> mappings = new ArrayList<>();
        for (int index = 0; index < 512; index++) {
            String source = String.format("s%0127d", index);
            String target = String.format("t%0127d", index);
            mappings.add("{\"sourceField\":\"" + source + "\",\"targetField\":\"" + target + "\"}");
        }
        return "{\"mappings\":[" + String.join(",", mappings) + "]}";
    }

    private static String read(String resource) throws Exception {
        try (InputStream stream = OfflineDatapackConfigV1055H2MigrationTest.class
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing migration: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
