package org.apache.seatunnel.web.dao.plugin.h2;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileResourceV1033H2MigrationSyntaxTest {

    @Test
    void createsResourceAndUploadRecordTables() throws Exception {
        String sql = read("/db/migration/h2/V1_0_33__init_file_resource_catalog.sql");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:file_resource_v1033;MODE=MySQL;DATABASE_TO_UPPER=false",
                "sa", "")) {
            try (var statement = connection.createStatement()) {
                executeScript(statement, sql);
                try (var result = statement.executeQuery(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME IN ("
                                + "'t_seatunnel_web_file_resource',"
                                + "'t_seatunnel_web_file_upload_record')")) {
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
        try (InputStream stream = FileResourceV1033H2MigrationSyntaxTest.class
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing migration " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
