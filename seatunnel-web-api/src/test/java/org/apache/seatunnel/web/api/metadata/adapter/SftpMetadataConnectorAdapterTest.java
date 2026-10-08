package org.apache.seatunnel.web.api.metadata.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.seatunnel.web.api.metadata.MetadataServiceCategory;
import org.apache.seatunnel.web.api.metadata.OmResourceType;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SftpMetadataConnectorAdapterTest {

    private final SftpMetadataConnectorAdapter adapter = new SftpMetadataConnectorAdapter();

    @Test
    void buildsFixed11210SftpDriveServiceRequest() {
        DataSource dataSource = source(
                "{\"host\":\"sftp.example.com\",\"port\":2222,"
                        + "\"user\":\"reader\",\"password\":\"secret\",\"basePath\":\"/data/inbound\"}");

        JsonNode service = adapter.serviceRequest(dataSource, "st_ds_40");
        JsonNode pipeline = adapter.metadataPipelineRequest("st_ds_40_metadata", "uuid-1", "st_ds_40");

        assertEquals("Sftp", service.at("/serviceType").asText());
        assertEquals("Sftp", service.at("/connection/config/type").asText());
        assertEquals("sftp.example.com", service.at("/connection/config/host").asText());
        assertEquals(2222, service.at("/connection/config/port").asInt());
        assertEquals("reader", service.at("/connection/config/authType/username").asText());
        assertEquals("secret", service.at("/connection/config/authType/password").asText());
        assertEquals("/data/inbound", service.at("/connection/config/rootDirectories/0").asText());
        assertEquals(true, service.at("/connection/config/supportsMetadataExtraction").asBoolean());
        assertEquals(MetadataServiceCategory.DRIVE, adapter.serviceCategory());
        assertFalse(adapter.supportsProfiler());
        assertEquals("DriveMetadata", pipeline.at("/sourceConfig/config/type").asText());
        assertEquals("driveService", pipeline.at("/service/type").asText());
    }

    @Test
    void extractsFileSampleDataOnlyWhenTheOperatorOptedIn() {
        DataSource dataSource = source(
                "{\"host\":\"sftp.example.com\",\"user\":\"reader\",\"password\":\"secret\"}");

        JsonNode disabled = adapter.serviceRequest(
                dataSource, "st_ds_40", new MetadataSyncOptions(false, null));
        JsonNode enabled = adapter.serviceRequest(
                dataSource, "st_ds_40", new MetadataSyncOptions(true, null));

        assertEquals(false, disabled.at("/connection/config/extractSampleData").asBoolean());
        assertEquals(true, enabled.at("/connection/config/extractSampleData").asBoolean());
        assertTrue(adapter.supportsSampleData());
        assertEquals(
                List.of(OmResourceType.DIRECTORY, OmResourceType.FILE), adapter.resourceTypes());
    }

    private static DataSource source(String connectionParams) {
        DataSource dataSource = new DataSource();
        dataSource.setId(40L);
        dataSource.setName("files");
        dataSource.setDbType(DbType.SFTP);
        dataSource.setConnectionParams(connectionParams);
        return dataSource;
    }
}
