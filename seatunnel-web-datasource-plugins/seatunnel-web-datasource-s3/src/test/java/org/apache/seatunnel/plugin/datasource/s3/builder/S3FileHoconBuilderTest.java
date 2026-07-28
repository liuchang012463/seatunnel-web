package org.apache.seatunnel.plugin.datasource.s3.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3FileHoconBuilderTest {

    @Test
    void buildsStaticS3BinarySource() {
        Config result = new S3FileHoconBuilder().buildSourceHocon(context(
                Map.of(
                        "dbType", "S3",
                        "endpoint", "https://s3.us-east-1.amazonaws.com",
                        "bucket", "archive",
                        "basePath", "/incoming",
                        "credentialMode", "STATIC",
                        "accessKey", "access",
                        "secretKey", "secret",
                        "pathStyleAccess", false),
                Map.of(
                        "path", "/incoming/images",
                        "fileFilterPattern", ".*\\.png",
                        "filenameExtension", ".png",
                        "binaryChunkSize", 1048576,
                        "binaryCompleteFileMode", true)));

        assertEquals("s3a://archive", result.getString("bucket"));
        assertEquals("https://s3.us-east-1.amazonaws.com",
                result.getString("\"fs.s3a.endpoint\""));
        assertEquals("binary", result.getString("file_format_type"));
        assertEquals("/incoming/images", result.getString("path"));
        assertEquals(".*\\.png", result.getString("file_filter_pattern"));
        assertEquals(".png", result.getString("filename_extension"));
        assertEquals(1048576, result.getInt("binary_chunk_size"));
        assertTrue(result.getBoolean("binary_complete_file_mode"));
        assertEquals("access", result.getString("access_key"));
    }

    @Test
    void buildsIamSourceWithoutStaticKeys() {
        Config result = new S3FileHoconBuilder().buildSourceHocon(context(
                Map.of(
                        "dbType", "S3",
                        "endpoint", "https://s3.us-east-1.amazonaws.com",
                        "bucket", "archive",
                        "basePath", "/",
                        "credentialMode", "INSTANCE_PROFILE"),
                Map.of("path", "/incoming")));

        assertEquals("com.amazonaws.auth.InstanceProfileCredentialsProvider",
                result.getString("\"fs.s3a.aws.credentials.provider\""));
        assertFalse(result.hasPath("access_key"));
        assertFalse(result.hasPath("secret_key"));
    }

    @Test
    void buildsMinioTransactionalSinkWithPathStyle() {
        Config result = new S3FileHoconBuilder().buildSinkHocon(context(
                Map.of(
                        "dbType", "MINIO",
                        "endpoint", "http://minio:9000",
                        "bucket", "archive",
                        "basePath", "/data",
                        "accessKey", "minio",
                        "secretKey", "minio-secret"),
                Map.of("targetPath", "/data/output")));

        assertEquals("/data/output-seatunnel-tmp", result.getString("tmp_path"));
        assertTrue(result.getBoolean("is_enable_transaction"));
        assertEquals("true",
                result.getConfig("hadoop_s3_properties")
                        .getString("\"fs.s3a.path.style.access\""));
        assertEquals("false",
                result.getConfig("hadoop_s3_properties")
                        .getString("\"fs.s3a.connection.ssl.enabled\""));
    }

    @Test
    void rejectsIncrementalAndPathsOutsideRoot() {
        Map<String, Object> connection = Map.of(
                "dbType", "S3",
                "endpoint", "https://s3.us-east-1.amazonaws.com",
                "bucket", "archive",
                "basePath", "/safe",
                "accessKey", "access",
                "secretKey", "secret");

        assertThrows(IllegalArgumentException.class,
                () -> new S3FileHoconBuilder().buildSourceHocon(context(
                        connection,
                        Map.of("path", "/safe", "syncType", "INCREMENTAL"))));
        assertThrows(IllegalArgumentException.class,
                () -> new S3FileHoconBuilder().buildSourceHocon(context(
                        connection,
                        Map.of("path", "/outside"))));
    }

    private HoconBuildContext context(
            Map<String, Object> connection,
            Map<String, Object> node) {
        return HoconBuildContext.builder()
                .connectionConfig(ConfigFactory.parseMap(connection))
                .connectionParam(ConfigFactory.parseMap(connection).root().render())
                .nodeConfig(ConfigFactory.parseMap(node))
                .build();
    }
}
