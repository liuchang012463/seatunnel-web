package org.apache.seatunnel.web.core.builder.source;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.core.fileresource.FileResourceReference;
import org.apache.seatunnel.web.core.fileresource.FileResourceResolver;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSourceSourceBuilderFileResourceTest {

    @TempDir
    Path tempDir;

    @Test
    void passesFileResourceReferenceAndFormatToS3FileBuilder() {
        DataSourceSourceBuilder sourceBuilder = new DataSourceSourceBuilder();
        FileResourceResolver resolver = mock(FileResourceResolver.class);
        ReflectionTestUtils.setField(sourceBuilder, "fileResourceResolver", resolver);

        when(resolver.resolve(42L)).thenReturn(new FileResourceReference(
                "MINIO",
                "http://minio:9000",
                "us-east-1",
                "archive",
                "/resources",
                "resources/input.csv",
                "STATIC",
                "access",
                "secret",
                true));

        DataSourceProcessor processor = mock(DataSourceProcessor.class);
        DataSourceHoconBuilder hoconBuilder = mock(DataSourceHoconBuilder.class);
        when(processor.getQueryBuilder("S3File")).thenReturn(hoconBuilder);
        when(processor.sourceOptionRule("S3File"))
                .thenReturn(org.apache.seatunnel.web.common.config.OptionRule.builder().build());
        when(hoconBuilder.supportsSource()).thenReturn(true);
        when(hoconBuilder.buildSourceHocon(any(HoconBuildContext.class)))
                .thenAnswer(invocation -> {
                    HoconBuildContext context = invocation.getArgument(0);
                    return ConfigFactory.parseMap(Map.of(
                            "path", context.getNodeConfig().getString("path"),
                            "file_format_type",
                            context.getNodeConfig().getString("fileFormatType")));
                });

        Config node = ConfigFactory.parseMap(Map.of(
                "sourceMode", "FILE_RESOURCE",
                "fileResourceId", "42",
                "fileFormatType", "csv",
                "encoding", "UTF-8"));

        try (MockedStatic<DataSourceUtils> ignored =
                     org.mockito.Mockito.mockStatic(DataSourceUtils.class)) {
            ignored.when(() -> DataSourceUtils.getDatasourceProcessor(DbType.MINIO))
                    .thenReturn(processor);

            Config result = sourceBuilder.build(node);

            assertEquals("S3File", sourceBuilder.connectorName(node));
            assertEquals("/resources/input.csv", result.getString("path"));
            assertEquals("csv", result.getString("file_format_type"));
        }
    }

    @Test
    void buildsDuckDbFileResourceAsSingleSplitJdbcSource() {
        DataSourceSourceBuilder sourceBuilder = new DataSourceSourceBuilder();
        FileResourceResolver resolver = mock(FileResourceResolver.class);
        ReflectionTestUtils.setField(sourceBuilder, "fileResourceResolver", resolver);
        String driver = "/opt/seatunnel/lib/duckdb_jdbc-1.3.1.0.jar";
        ReflectionTestUtils.setField(sourceBuilder, "duckDbDriverLocation", driver);
        when(resolver.resolve(42L)).thenReturn(new FileResourceReference(
                "MINIO",
                "https://minio.example.com:9000",
                "us-east-1",
                "archive",
                "/resources",
                "resources/weather data.duckdb",
                "STATIC",
                "test-access-key",
                "test-secret-key",
                true));

        Config node = ConfigFactory.parseMap(Map.of(
                "sourceMode", "FILE_RESOURCE",
                "fileResourceId", "42",
                "fileFormatType", "duckdb",
                "readMode", "table",
                "duckdbSchema", "weather schema",
                "duckdbTable", "daily \"summary\"",
                "plugin_output", "duckdb-source"));

        Config result = sourceBuilder.build(node);

        assertEquals("Jdbc", sourceBuilder.connectorName(node));
        assertEquals("jdbc:duckdb:", result.getString("url"));
        assertEquals("org.duckdb.DuckDBDriver", result.getString("driver"));
        assertEquals(driver, result.getString("driver_location"));
        assertTrue(!result.getBoolean("enable_concurrent_read"));
        assertEquals("duckdb-source", result.getString("plugin_output"));

        Config properties = result.getConfig("properties");
        assertEquals("minio.example.com:9000", properties.getString("s3_endpoint"));
        assertEquals("us-east-1", properties.getString("s3_region"));
        assertEquals("path", properties.getString("s3_url_style"));
        assertEquals("true", properties.getString("s3_use_ssl"));
        assertEquals("test-access-key", properties.getString("s3_access_key_id"));
        assertEquals("test-secret-key", properties.getString("s3_secret_access_key"));
        assertEquals("false", properties.getString("autoinstall_known_extensions"));
        assertEquals("true", properties.getString("jdbc_stream_results"));

        String query = result.getString("query");
        assertTrue(query.startsWith(
                "LOAD httpfs;\nATTACH IF NOT EXISTS 's3://archive/resources/weather%20data.duckdb'"));
        assertTrue(query.contains("READ_ONLY"));
        assertTrue(query.contains("USE \"duckdb_source\".\"weather schema\";"));
        assertTrue(query.endsWith(
                "SELECT * FROM \"duckdb_source\".\"weather schema\".\"daily \"\"summary\"\"\""));
        assertTrue(!query.contains("test-access-key"));
        assertTrue(!query.contains("test-secret-key"));
        assertTrue(!query.contains("INSTALL httpfs"));
        assertTrue(!query.contains("TYPE DUCKDB"));
    }

    @Test
    void validatesAndAppendsOneReadOnlyDuckDbSelect() throws Exception {
        DataSourceSourceBuilder sourceBuilder = new DataSourceSourceBuilder();
        FileResourceResolver resolver = mock(FileResourceResolver.class);
        ReflectionTestUtils.setField(sourceBuilder, "fileResourceResolver", resolver);
        Path driver = Files.createFile(tempDir.resolve("duckdb_jdbc.jar"));
        ReflectionTestUtils.setField(sourceBuilder, "duckDbDriverLocation", driver.toString());
        when(resolver.resolve(42L)).thenReturn(new FileResourceReference(
                "MINIO", "http://minio:9000", "us-east-1", "archive", "/resources",
                "resources/sample.db", "STATIC", "access", "secret", true));

        Config validNode = ConfigFactory.parseMap(Map.of(
                "sourceMode", "FILE_RESOURCE",
                "fileResourceId", "42",
                "fileFormatType", "duckdb",
                "readMode", "sql",
                "duckdbSchema", "main",
                "sql", "WITH rows AS (SELECT 1 AS value) SELECT value FROM rows"));
        Config result = sourceBuilder.build(validNode);
        assertTrue(result.getString("query").endsWith(
                "WITH rows AS (SELECT 1 AS value) SELECT value FROM rows"));

        Config invalidNode = ConfigFactory.parseMap(Map.of(
                "sourceMode", "FILE_RESOURCE",
                "fileResourceId", "42",
                "fileFormatType", "duckdb",
                "readMode", "sql",
                "sql", "SELECT 1; ATTACH 's3://other/private.db' AS other"));
        assertThrows(IllegalArgumentException.class, () -> sourceBuilder.build(invalidNode));
    }

    @Test
    void rejectsFileResourceWithoutResourceIdBeforeResolving() {
        DataSourceSourceBuilder sourceBuilder = new DataSourceSourceBuilder();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> sourceBuilder.build(ConfigFactory.parseMap(Map.of(
                        "sourceMode", "FILE_RESOURCE",
                        "fileFormatType", "csv"))));

        assertTrue(exception.getMessage().contains("fileResourceId"));
    }

    @Test
    void rejectsFileResourcePathOutsideStorageBase() {
        DataSourceSourceBuilder sourceBuilder = new DataSourceSourceBuilder();
        FileResourceResolver resolver = mock(FileResourceResolver.class);
        ReflectionTestUtils.setField(sourceBuilder, "fileResourceResolver", resolver);
        when(resolver.resolve(42L)).thenReturn(new FileResourceReference(
                "S3_COMPATIBLE",
                "https://s3.example.com",
                "us-east-1",
                "archive",
                "/safe",
                "/outside/input.csv",
                "STATIC",
                "access",
                "secret",
                false));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> sourceBuilder.build(ConfigFactory.parseMap(Map.of(
                        "sourceMode", "FILE_RESOURCE",
                        "fileResourceId", "42",
                        "fileFormatType", "csv"))));

        assertTrue(exception.getMessage().contains("outside"));
    }

    @Test
    void keepsFileTransferResourceBinary() {
        DataSourceSourceBuilder sourceBuilder = new DataSourceSourceBuilder();
        FileResourceResolver resolver = mock(FileResourceResolver.class);
        ReflectionTestUtils.setField(sourceBuilder, "fileResourceResolver", resolver);
        when(resolver.resolve(42L)).thenReturn(new FileResourceReference(
                "MINIO",
                "http://minio:9000",
                "us-east-1",
                "archive",
                "/resources",
                "resources/input.csv",
                "STATIC",
                "access",
                "secret",
                true));

        DataSourceProcessor processor = mock(DataSourceProcessor.class);
        DataSourceHoconBuilder hoconBuilder = mock(DataSourceHoconBuilder.class);
        when(processor.getQueryBuilder("S3File")).thenReturn(hoconBuilder);
        when(processor.sourceOptionRule("S3File"))
                .thenReturn(org.apache.seatunnel.web.common.config.OptionRule.builder().build());
        when(hoconBuilder.supportsSource()).thenReturn(true);
        when(hoconBuilder.buildSourceHocon(any(HoconBuildContext.class)))
                .thenAnswer(invocation -> {
                    HoconBuildContext context = invocation.getArgument(0);
                    return ConfigFactory.parseMap(Map.of(
                            "file_format_type",
                            context.getNodeConfig().getString("fileFormatType"),
                            "binary_chunk_size",
                            context.getNodeConfig().getInt("binaryChunkSize"),
                            "binary_complete_file_mode",
                            context.getNodeConfig().getBoolean("binaryCompleteFileMode")));
                });

        Config node = ConfigFactory.parseMap(Map.of(
                "sourceMode", "FILE_RESOURCE",
                "fileResourceId", "42",
                "readMode", "resource",
                "fileFormatType", "binary",
                "binaryChunkSize", 2048,
                "binaryCompleteFileMode", true));

        try (MockedStatic<DataSourceUtils> ignored =
                     org.mockito.Mockito.mockStatic(DataSourceUtils.class)) {
            ignored.when(() -> DataSourceUtils.getDatasourceProcessor(DbType.MINIO))
                    .thenReturn(processor);

            Config result = sourceBuilder.build(node);

            assertEquals("binary", result.getString("file_format_type"));
            assertEquals(2048, result.getInt("binary_chunk_size"));
            assertTrue(result.getBoolean("binary_complete_file_mode"));
        }
    }
}
