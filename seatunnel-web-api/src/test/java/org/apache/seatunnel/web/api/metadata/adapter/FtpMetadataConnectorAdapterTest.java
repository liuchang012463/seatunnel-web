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

class FtpMetadataConnectorAdapterTest {

    private final FtpMetadataConnectorAdapter adapter = new FtpMetadataConnectorAdapter();

    @Test
    void buildsCustomDriveServiceRequestForFtp() {
        DataSource dataSource = source(
                "{\"host\":\"ftp.example.com\",\"port\":2121,"
                        + "\"user\":\"reader\",\"password\":\"secret\","
                        + "\"basePath\":\"/data/inbound\","
                        + "\"connectionMode\":\"ACTIVE_LOCAL\","
                        + "\"remoteVerificationEnabled\":false}");

        JsonNode service = adapter.serviceRequest(dataSource, "st_ds_41");
        JsonNode pipeline = adapter.metadataPipelineRequest("st_ds_41_metadata", "uuid-1", "st_ds_41");

        assertEquals("CustomDrive", service.at("/serviceType").asText());
        assertEquals("CustomDrive", service.at("/connection/config/type").asText());
        assertEquals(
                FtpMetadataConnectorAdapter.SOURCE_PYTHON_CLASS,
                service.at("/connection/config/sourcePythonClass").asText());
        assertEquals("ftp.example.com", service.at("/connection/config/connectionOptions/host").asText());
        assertEquals("2121", service.at("/connection/config/connectionOptions/port").asText());
        assertEquals("reader", service.at("/connection/config/connectionOptions/username").asText());
        assertEquals("secret", service.at("/connection/config/connectionOptions/password").asText());
        assertEquals("/data/inbound",
                service.at("/connection/config/connectionOptions/rootDirectories").asText());
        assertEquals("ACTIVE_LOCAL",
                service.at("/connection/config/connectionOptions/connectionMode").asText());
        assertEquals("false",
                service.at("/connection/config/connectionOptions/remoteVerificationEnabled").asText());
        assertEquals("false",
                service.at("/connection/config/connectionOptions/extractSampleData").asText());
        assertEquals(true, service.at("/connection/config/supportsMetadataExtraction").asBoolean());
        assertEquals(MetadataServiceCategory.DRIVE, adapter.serviceCategory());
        assertFalse(adapter.supportsProfiler());
        assertEquals("DriveMetadata", pipeline.at("/sourceConfig/config/type").asText());
        assertEquals("driveService", pipeline.at("/service/type").asText());
    }

    @Test
    void extractsFileSampleDataOnlyWhenTheOperatorOptedIn() {
        DataSource dataSource = source(
                "{\"host\":\"ftp.example.com\",\"user\":\"reader\",\"password\":\"secret\"}");

        JsonNode disabled = adapter.serviceRequest(
                dataSource, "st_ds_41", new MetadataSyncOptions(false, null));
        JsonNode enabled = adapter.serviceRequest(
                dataSource, "st_ds_41", new MetadataSyncOptions(true, null));

        assertEquals("false",
                disabled.at("/connection/config/connectionOptions/extractSampleData").asText());
        assertEquals("true",
                enabled.at("/connection/config/connectionOptions/extractSampleData").asText());
        assertTrue(adapter.supportsSampleData());
        assertEquals(
                List.of(OmResourceType.DIRECTORY, OmResourceType.FILE), adapter.resourceTypes());
        assertEquals(DbType.FTP, adapter.dataSourceType());
    }

    @Test
    void defaultsPortAndPassiveModeWhenOmitted() {
        DataSource dataSource = source(
                "{\"host\":\"ftp.example.com\",\"user\":\"reader\",\"password\":\"secret\"}");

        JsonNode service = adapter.serviceRequest(dataSource, "st_ds_41");

        assertEquals("21", service.at("/connection/config/connectionOptions/port").asText());
        assertEquals("PASSIVE_LOCAL",
                service.at("/connection/config/connectionOptions/connectionMode").asText());
        assertEquals("/", service.at("/connection/config/connectionOptions/rootDirectories").asText());
    }

    private static DataSource source(String connectionParams) {
        DataSource dataSource = new DataSource();
        dataSource.setId(41L);
        dataSource.setName("ftp-files");
        dataSource.setDbType(DbType.FTP);
        dataSource.setConnectionParams(connectionParams);
        return dataSource;
    }
}
