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
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSourceSourceBuilderFileResourceTest {

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
