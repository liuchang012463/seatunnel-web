package org.apache.seatunnel.web.api.offlineimport;

import org.apache.seatunnel.web.spi.bean.dto.OfflineDatapackFieldMapping;
import org.apache.seatunnel.web.spi.bean.dto.OfflineDatapackTaskConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatapackImportRunnerTest {

    @Test
    void bindsValuesAsParametersAndAppendsToTheExistingTable() throws Exception {
        String marker = "value'); DROP TABLE TARGET_TABLE; --";
        DatapackPackageInspector.Inspection packageData = inspect(packageJson(List.of(
                record(1, marker), record(2, "second"))));
        OfflineDatapackTaskConfig config = config();

        try (Connection connection = connection()) {
            createTable(connection);
            int inserted = DatapackImportRunner.run(connection, null, config, packageData, ignored -> { });
            assertEquals(2, inserted);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT NAME FROM TARGET_TABLE WHERE ID = 1")) {
                rows.next();
                assertEquals(marker, rows.getString(1));
            }
            assertTableExists(connection);
        }
    }

    @Test
    void rollsBackEarlierBatchesWhenAValueCannotConvertToTheTargetType() throws Exception {
        List<String> records = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            records.add(record(i, "valid"));
        }
        records.add(record("not-an-integer", "invalid"));
        DatapackPackageInspector.Inspection packageData = inspect(packageJson(records));

        try (Connection connection = connection()) {
            createTable(connection);
            assertThrows(IllegalArgumentException.class,
                    () -> DatapackImportRunner.run(connection, null, config(), packageData, ignored -> { }));
            try (Statement statement = connection.createStatement();
                 ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM TARGET_TABLE")) {
                count.next();
                assertEquals(0, count.getInt(1));
            }
        }
    }

    @Test
    void mapsQuotedCaseDistinctColumnNamesExactly() throws Exception {
        DatapackPackageInspector.Inspection packageData = inspect(packageJson(List.of(record(1, "alpha"))));
        OfflineDatapackTaskConfig config = config();
        config.setTargetTable("TARGET_CASE");
        config.setMappings(List.of(mapping("id", "foo"), mapping("name", "FOO")));

        try (Connection connection = postgresConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE \"TARGET_CASE\" (\"foo\" INTEGER, \"FOO\" VARCHAR(80))");
            }
            assertEquals(1, DatapackImportRunner.run(connection, null, config, packageData, ignored -> { }));
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT \"foo\", \"FOO\" FROM \"TARGET_CASE\"")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
                assertEquals("alpha", rows.getString(2));
            }
        }
    }

    @Test
    void rejectsAmbiguousCaseInsensitiveColumnFallback() throws Exception {
        DatapackPackageInspector.Inspection packageData = inspect(packageJson(List.of(record(1, "alpha"))));
        OfflineDatapackTaskConfig config = config();
        config.setTargetTable("TARGET_CASE_AMBIGUOUS");
        config.setMappings(List.of(mapping("name", "Foo")));

        try (Connection connection = postgresConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE \"TARGET_CASE_AMBIGUOUS\" (\"foo\" VARCHAR(80), \"FOO\" VARCHAR(80))");
            }
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DatapackImportRunner.run(connection, null, config, packageData, ignored -> { }));
            assertTrue(error.getMessage().contains("歧义"));
        }
    }

    @Test
    void doesNotReadColumnsFromTablesMatchingPercentOrUnderscorePatterns() throws Exception {
        assertPatternSiblingColumnsAreNotUsed("TARGET%TABLE", "TARGETxTABLE");
        assertPatternSiblingColumnsAreNotUsed("TARGET_TABLE", "TARGETxTABLE");
    }

    @Test
    void rejectsTimeWithTimeZoneDuringTargetValidation() throws Exception {
        DatapackPackageInspector.Inspection packageData = inspect(packageJson(List.of(record(1, "12:00:00+02:00"))));
        OfflineDatapackTaskConfig config = config();
        config.setTargetTable("TARGET_TIMEZONE");

        try (Connection connection = postgresConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE \"TARGET_TIMEZONE\" (\"ID\" INTEGER, \"NAME\" TIME WITH TIME ZONE)");
            }
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DatapackImportRunner.validateTarget(connection, null, config, packageData));
            assertTrue(error.getMessage().contains("TIME WITH TIME ZONE"));
        }
    }

    @Test
    void resolvesDefaultSchemaAndIgnoresSiblingSchemaWithSameTableName() throws Exception {
        DatapackPackageInspector.Inspection packageData = inspect(packageJson(List.of(record(1, "alpha"))));
        OfflineDatapackTaskConfig config = config();
        config.setTargetTable("TARGET_SCHEMA_DUP");

        try (Connection connection = postgresConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA APP");
            statement.execute("CREATE SCHEMA OTHER");
            statement.execute("CREATE TABLE APP.TARGET_SCHEMA_DUP (ID INTEGER, NAME VARCHAR(80))");
            statement.execute("CREATE TABLE OTHER.TARGET_SCHEMA_DUP (ID INTEGER, WRONG_COLUMN VARCHAR(80))");
            connection.setSchema("APP");

            config.setMappings(List.of(mapping("name", "WRONG_COLUMN")));
            assertThrows(IllegalArgumentException.class,
                    () -> DatapackImportRunner.validateTarget(connection, null, config, packageData));

            config.setMappings(List.of(mapping("id", "ID"), mapping("name", "NAME")));
            DatapackImportRunner.validateTarget(connection, null, config, packageData);
            assertEquals(1, DatapackImportRunner.run(connection, null, config, packageData, ignored -> { }));

            try (ResultSet appRows = statement.executeQuery("SELECT COUNT(*) FROM APP.TARGET_SCHEMA_DUP")) {
                appRows.next();
                assertEquals(1, appRows.getInt(1));
            }
            try (ResultSet otherRows = statement.executeQuery("SELECT COUNT(*) FROM OTHER.TARGET_SCHEMA_DUP")) {
                otherRows.next();
                assertEquals(0, otherRows.getInt(1));
            }
        }
    }

    @Test
    void rejectsNullMappingToNotNullColumnWithDefaultAndAllowsDatabaseDefaultWhenUnmapped() throws Exception {
        DatapackPackageInspector.Inspection packageData = inspect(
                "{\"records\":[{\"formCode\":\"FORM_A\",\"values\":{\"id\":null,\"name\":\"alpha\"}}]}");
        OfflineDatapackTaskConfig config = config();
        config.setTargetTable("TARGET_DEFAULT");

        try (Connection connection = postgresConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE TARGET_DEFAULT (ID INTEGER NOT NULL DEFAULT 7, NAME VARCHAR(80))");
            assertTrue(connection.getSchema() != null && !connection.getSchema().isBlank());

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DatapackImportRunner.validateTarget(connection, null, config, packageData));
            assertTrue(error.getMessage().contains("数据库默认值"));

            // 不映射含空值的源列后，让数据库应用声明的默认值。
            config.setMappings(List.of(mapping("name", "NAME")));
            DatapackImportRunner.validateTarget(connection, null, config, packageData);
            assertEquals(1, DatapackImportRunner.run(connection, null, config, packageData, ignored -> { }));
            try (ResultSet rows = statement.executeQuery("SELECT ID, NAME FROM TARGET_DEFAULT")) {
                rows.next();
                assertEquals(7, rows.getInt("ID"));
                assertEquals("alpha", rows.getString("NAME"));
            }
        }
    }

    private void assertPatternSiblingColumnsAreNotUsed(String targetTable, String siblingTable) throws Exception {
        DatapackPackageInspector.Inspection packageData = inspect(packageJson(List.of(record(1, "alpha"))));
        OfflineDatapackTaskConfig config = config();
        config.setTargetTable(targetTable);

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE \"" + targetTable + "\" (\"ID\" INTEGER, \"NAME\" VARCHAR(80))");
            statement.execute("CREATE TABLE \"" + siblingTable + "\" (\"ID\" INTEGER, \"WRONG_COLUMN\" VARCHAR(80))");

            // First prove the literal table name can be resolved despite metadata wildcard characters.
            config.setMappings(List.of(mapping("id", "ID"), mapping("name", "NAME")));
            assertEquals(1, DatapackImportRunner.run(connection, null, config, packageData, ignored -> { }));

            // A wildcard sibling's columns must not become valid for the selected target table.
            config.setMappings(List.of(mapping("name", "WRONG_COLUMN")));
            assertThrows(IllegalArgumentException.class,
                    () -> DatapackImportRunner.run(connection, null, config, packageData, ignored -> { }));
        }
    }

    private DatapackPackageInspector.Inspection inspect(String json) throws Exception {
        OfflineImportProperties properties = new OfflineImportProperties();
        DatapackPackageInspector inspector = new DatapackPackageInspector(properties);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String fingerprint = inspector.fingerprint(bytes);
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
        com.fasterxml.jackson.databind.JsonNode records = root.path("records");
        return new DatapackPackageInspector.Inspection(
                fingerprint, "pkg-1", "DATA", records.size(),
                List.of(new DatapackPackageInspector.FormSummary("FORM_A", countRecords(json), List.of("id", "name"))),
                records);
    }

    private int countRecords(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json)
                    .path("records").size();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String packageJson(List<String> records) {
        return "{\"records\":[" + String.join(",", records.stream()
                .map(row -> "{\"formCode\":\"FORM_A\",\"values\":{" + row + "}}")
                .toList()) + "]}";
    }

    private String record(Object id, String name) {
        String value = id instanceof Number ? id.toString() : "\"" + id + "\"";
        return "\"id\":" + value + ",\"name\":\"" + name + "\"";
    }

    private OfflineDatapackTaskConfig config() {
        OfflineDatapackTaskConfig config = new OfflineDatapackTaskConfig();
        config.setFormCode("FORM_A");
        config.setTargetTable("TARGET_TABLE");
        config.setMappings(List.of(mapping("id", "id"), mapping("name", "name")));
        return config;
    }

    private OfflineDatapackFieldMapping mapping(String source, String target) {
        OfflineDatapackFieldMapping mapping = new OfflineDatapackFieldMapping();
        mapping.setSourceField(source);
        mapping.setTargetField(target);
        return mapping;
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private Connection postgresConnection() throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private void createTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE TARGET_TABLE (ID INTEGER NOT NULL, NAME VARCHAR(80))");
        }
    }

    private void assertTableExists(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM TARGET_TABLE")) {
            result.next();
            assertEquals(2, result.getInt(1));
        }
    }
}
