package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.seatunnel.plugin.datasource.api.utils.PasswordUtils;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinioMetadataConnectorAdapterTest {

    private final MinioMetadataConnectorAdapter adapter = new MinioMetadataConnectorAdapter();

    @BeforeAll
    static void configureMasterKey() {
        System.setProperty("seatunnel.web.datasource.master-key", "test-master-key");
    }

    @Test
    void buildsFixed11210MinioStorageServiceRequest() {
        DataSource dataSource = source(31L, """
                {
                  "endpoint": "http://minio.local:9000",
                  "region": "us-east-1",
                  "bucket": "warehouse",
                  "basePath": "/exports/reports",
                  "accessKey": "minio",
                  "secretKey": "%s"
                }
                """.formatted(PasswordUtils.encodePassword("minio-secret")));

        JsonNode service = adapter.serviceRequest(dataSource, "st_ds_31");
        JsonNode pipeline = adapter.metadataPipelineRequest("st_ds_31_metadata", "uuid-1", "st_ds_31");

        assertEquals("S3", service.at("/serviceType").asText());
        assertEquals("S3", service.at("/connection/config/type").asText());
        assertEquals("http://minio.local:9000",
                service.at("/connection/config/awsConfig/endPointURL").asText());
        assertEquals("us-east-1", service.at("/connection/config/awsConfig/awsRegion").asText());
        assertEquals("minio", service.at("/connection/config/awsConfig/awsAccessKeyId").asText());
        assertEquals("minio-secret", service.at("/connection/config/awsConfig/awsSecretAccessKey").asText());
        assertEquals("warehouse", service.at("/connection/config/bucketNames/0").asText());
        assertEquals("^exports/reports(/.*)?$",
                service.at("/connection/config/containerFilterPattern/includes/0").asText());
        assertEquals(true, service.at("/connection/config/supportsMetadataExtraction").asBoolean());
        assertEquals("StorageMetadata", pipeline.at("/sourceConfig/config/type").asText());
        assertTrue(service.at("/connection/config/storageMetadataConfig").isMissingNode());
    }

    private static DataSource source(Long id, String connectionParams) {
        DataSource dataSource = new DataSource();
        dataSource.setId(id);
        dataSource.setName("warehouse");
        dataSource.setDbType(DbType.MINIO);
        dataSource.setConnectionParams(connectionParams);
        return dataSource;
    }
}
