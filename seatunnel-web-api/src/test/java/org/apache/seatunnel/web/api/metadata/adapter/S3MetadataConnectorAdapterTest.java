package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.seatunnel.plugin.datasource.api.utils.PasswordUtils;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3MetadataConnectorAdapterTest {

    private final S3MetadataConnectorAdapter adapter = new S3MetadataConnectorAdapter();

    @BeforeAll
    static void configureMasterKey() {
        System.setProperty("seatunnel.web.datasource.master-key", "test-master-key");
    }

    @Test
    void buildsFixed11210S3StorageServiceRequest() {
        DataSource dataSource = source(21L, """
                {
                  "endpoint": "https://s3.cn-north-1.amazonaws.com.cn",
                  "region": "cn-north-1",
                  "bucket": "archive",
                  "basePath": "/incoming/",
                  "credentialMode": "STATIC",
                  "accessKey": "access-id",
                  "secretKey": "plain-secret"
                }
                """);

        JsonNode service = adapter.serviceRequest(dataSource, "st_ds_21");
        JsonNode pipeline = adapter.metadataPipelineRequest("st_ds_21_metadata", "uuid-1", "st_ds_21");

        assertEquals("S3", service.at("/serviceType").asText());
        assertEquals("S3", service.at("/connection/config/type").asText());
        assertEquals("cn-north-1", service.at("/connection/config/awsConfig/awsRegion").asText());
        assertEquals("https://s3.cn-north-1.amazonaws.com.cn",
                service.at("/connection/config/awsConfig/endPointURL").asText());
        assertEquals("access-id", service.at("/connection/config/awsConfig/awsAccessKeyId").asText());
        assertEquals("plain-secret", service.at("/connection/config/awsConfig/awsSecretAccessKey").asText());
        assertEquals("archive", service.at("/connection/config/bucketNames/0").asText());
        assertEquals("^incoming(/.*)?$",
                service.at("/connection/config/containerFilterPattern/includes/0").asText());
        assertEquals(true, service.at("/connection/config/supportsMetadataExtraction").asBoolean());
        assertEquals("StorageMetadata", pipeline.at("/sourceConfig/config/type").asText());
        assertTrue(service.at("/connection/config/storageMetadataConfig").isMissingNode());
    }

    @Test
    void decodesEncryptedSecretKey() {
        DataSource dataSource = source(22L, """
                {
                  "endpoint": "https://s3.us-east-1.amazonaws.com",
                  "region": "us-east-1",
                  "bucket": "archive",
                  "basePath": "/",
                  "credentialMode": "STATIC",
                  "accessKey": "access-id",
                  "secretKey": "%s"
                }
                """.formatted(PasswordUtils.encodePassword("stored-secret")));

        JsonNode service = adapter.serviceRequest(dataSource, "st_ds_22");

        assertEquals("stored-secret",
                service.at("/connection/config/awsConfig/awsSecretAccessKey").asText());
        assertTrue(service.at("/connection/config/containerFilterPattern").isMissingNode());
    }

    @Test
    void supportsInstanceProfileWithoutPersistedKeys() {
        DataSource dataSource = source(23L, """
                {
                  "endpoint": "https://s3.us-east-1.amazonaws.com",
                  "region": "us-east-1",
                  "bucket": "archive",
                  "basePath": "/",
                  "credentialMode": "INSTANCE_PROFILE"
                }
                """);

        JsonNode service = adapter.serviceRequest(dataSource, "st_ds_23");

        assertEquals(true, service.at("/connection/config/awsConfig/enabled").asBoolean());
        assertTrue(service.at("/connection/config/awsConfig/awsAccessKeyId").isMissingNode());
        assertTrue(service.at("/connection/config/awsConfig/awsSecretAccessKey").isMissingNode());
    }

    private static DataSource source(Long id, String connectionParams) {
        DataSource dataSource = new DataSource();
        dataSource.setId(id);
        dataSource.setName("archive");
        dataSource.setDbType(DbType.S3);
        dataSource.setConnectionParams(connectionParams);
        return dataSource;
    }
}
