package org.apache.seatunnel.plugin.datasource.jdbc.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcBatchBuilderTest {

    private final JdbcBatchBuilder builder = new JdbcBatchBuilder();

    @Test
    void buildsJdbcSourceFromCustomQuery() {
        Config config = builder.buildSourceHocon(context("sql = \"select * from orders\""));

        assertEquals("jdbc:vendor://localhost:1234/catalog", config.getString("url"));
        assertEquals("com.vendor.Driver", config.getString("driver"));
        assertEquals("select * from orders", config.getString("query"));
    }

    @Test
    void buildsJdbcSinkFromTargetTable() {
        Config config = builder.buildSinkHocon(
                context("targetTableName = orders\nautoCreateTable = true"));

        assertTrue(config.getString("table").endsWith("orders"));
        assertTrue(config.getBoolean("generate_sink_sql"));
        assertEquals("CREATE_SCHEMA_WHEN_NOT_EXIST", config.getString("schema_save_mode"));
        assertEquals("APPEND_DATA", config.getString("data_save_mode"));
    }

    @Test
    void emitsDriverLocationOfAnExistingDriverJar(@TempDir Path dir) throws Exception {
        Path driverJar = Files.createFile(dir.resolve("vendor-driver.jar"));

        Config config =
                withDriverDirectory(
                        dir,
                        () ->
                                builder.buildSourceHocon(
                                        context(
                                                "sql = \"select 1\"",
                                                "driverLocation = \"" + driverJar + "\"")));

        assertEquals(driverJar.toString(), config.getString("driver_location"));
    }

    @Test
    void emitsDriverLocationOfAFileNameRelativeToTheDriverDirectory(@TempDir Path dir)
            throws Exception {
        Path driverJar = Files.createFile(dir.resolve("vendor-driver.jar"));

        Config config =
                withDriverDirectory(
                        dir,
                        () ->
                                builder.buildSourceHocon(
                                        context(
                                                "sql = \"select 1\"",
                                                "driverLocation = \"vendor-driver.jar\"")));

        assertEquals(driverJar.toString(), config.getString("driver_location"));
    }

    @Test
    void omitsDriverLocationOfAnUnreadableDriverJar(@TempDir Path dir) {
        Config config =
                withDriverDirectory(
                        dir,
                        () ->
                                builder.buildSourceHocon(
                                        context(
                                                "sql = \"select 1\"",
                                                "driverLocation = \"" + dir.resolve("absent.jar") + "\"")));

        assertFalse(config.hasPath("driver_location"));
    }

    @Test
    void omitsDriverLocationOutsideTheDriverDirectory(@TempDir Path dir, @TempDir Path outside)
            throws Exception {
        Path outsideJar = Files.createFile(outside.resolve("escape.jar"));

        Config absolute =
                withDriverDirectory(
                        dir,
                        () ->
                                builder.buildSourceHocon(
                                        context(
                                                "sql = \"select 1\"",
                                                "driverLocation = \"" + outsideJar + "\"")));
        Config relative =
                withDriverDirectory(
                        dir,
                        () ->
                                builder.buildSourceHocon(
                                        context(
                                                "sql = \"select 1\"",
                                                "driverLocation = \"../"
                                                        + outside.getFileName()
                                                        + "/escape.jar\"")));

        assertFalse(absolute.hasPath("driver_location"));
        assertFalse(relative.hasPath("driver_location"));
    }

    private <T> T withDriverDirectory(Path directory, java.util.function.Supplier<T> action) {
        String property = "seatunnel.web.jdbc-driver-dir";
        String previous = System.getProperty(property);
        System.setProperty(property, directory.toString());

        try {
            return action.get();
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    private HoconBuildContext context(String nodeConfig) {
        return context(nodeConfig, "");
    }

    private HoconBuildContext context(String nodeConfig, String extraConnectionConfig) {
        return HoconBuildContext.builder()
                .connectionConfig(ConfigFactory.parseString(
                        "url = \"jdbc:vendor://localhost:1234/catalog\"\n"
                                + "driver = \"com.vendor.Driver\"\n"
                                + "user = test\n"
                                + "password = test\n"
                                + "database = catalog\n"
                                + "schemaName = public\n"
                                + extraConnectionConfig))
                .nodeConfig(ConfigFactory.parseString(nodeConfig))
                .build();
    }
}
