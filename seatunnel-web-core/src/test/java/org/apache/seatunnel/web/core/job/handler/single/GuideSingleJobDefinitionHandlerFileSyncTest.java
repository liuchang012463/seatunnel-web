package org.apache.seatunnel.web.core.job.handler.single;

import org.apache.seatunnel.web.core.job.handler.JobRuntimeContext;
import org.apache.seatunnel.web.core.job.handler.JobRuntimeContextFactory;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchFileSyncJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.JobBasicConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuideSingleJobDefinitionHandlerFileSyncTest {

    @Test
    void acceptsFullCopyAcrossAllFileDatasourceTypes() {
        GuideSingleJobDefinitionHandler handler = newHandler();
        assertDoesNotThrow(() -> handler.validate(command("S3", "1", "MINIO", "2", "FULL")));
        assertDoesNotThrow(() -> handler.validate(command("FTP", "3", "S3", "4", "FULL")));
        assertDoesNotThrow(() -> handler.validate(command("MINIO", "5", "SFTP", "6", "FULL")));
    }

    @Test
    void preservesIncrementalForSameFtpOrSftpDatasource() {
        GuideSingleJobDefinitionHandler handler = newHandler();
        assertDoesNotThrow(() -> handler.validate(command("FTP", "1", "FTP", "1", "INCREMENTAL")));
        assertDoesNotThrow(() -> handler.validate(command("SFTP", "2", "SFTP", "2", "INCREMENTAL")));
    }

    @Test
    void acceptsObjectStorageIncrementalOnlyOnSeaTunnel300() {
        GuideSingleJobDefinitionHandler handler = newHandler();
        JobRuntimeContextFactory factory = mock(JobRuntimeContextFactory.class);
        when(factory.create(any())).thenReturn(JobRuntimeContext.builder().engineVersion("3.0.0").build());
        ReflectionTestUtils.setField(handler, "jobRuntimeContextFactory", factory);

        assertDoesNotThrow(() -> handler.validate(command("S3", "1", "S3", "1", "INCREMENTAL")));
        assertDoesNotThrow(() -> handler.validate(command("MINIO", "2", "MINIO", "2", "INCREMENTAL")));
    }

    @Test
    void rejectsObjectStorageIncrementalUnlessEngineIs300() {
        GuideSingleJobDefinitionHandler handler = newHandler();
        IllegalArgumentException missingVersion =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> handler.validate(command("S3", "1", "S3", "1", "INCREMENTAL")));
        assertTrue(missingVersion.getMessage().contains("SeaTunnel Engine 3.0.0"));

        JobRuntimeContextFactory factory = mock(JobRuntimeContextFactory.class);
        when(factory.create(any())).thenReturn(JobRuntimeContext.builder().engineVersion("2.3.13").build());
        ReflectionTestUtils.setField(handler, "jobRuntimeContextFactory", factory);

        IllegalArgumentException wrongVersion =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> handler.validate(command("MINIO", "2", "MINIO", "2", "INCREMENTAL")));
        assertTrue(wrongVersion.getMessage().contains("SeaTunnel Engine 3.0.0"));
    }

    @Test
    void rejectsIncrementalAcrossDifferentObjectStorageTypes() {
        GuideSingleJobDefinitionHandler handler = newHandler();
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> handler.validate(command("S3", "1", "MINIO", "2", "INCREMENTAL")));

        assertTrue(exception.getMessage().contains("same source and target datasource type"));
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command("FTP", "1", "MINIO", "1", "INCREMENTAL")));
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command("SFTP", "1", "S3", "1", "INCREMENTAL")));
    }

    @Test
    void rejectsIncrementalAcrossDifferentDatasources() {
        GuideSingleJobDefinitionHandler handler = newHandler();
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command("FTP", "1", "SFTP", "2", "INCREMENTAL")));
    }

    @Test
    void rejectsIncrementalAcrossDifferentFileProtocolsEvenWhenDatasourceIdMatches() {
        GuideSingleJobDefinitionHandler handler = newHandler();
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command("FTP", "1", "SFTP", "1", "INCREMENTAL")));
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command("SFTP", "1", "FTP", "1", "INCREMENTAL")));
    }

    @Test
    void rejectsNonFileDatasource() {
        GuideSingleJobDefinitionHandler handler = newHandler();
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command("MYSQL", "1", "S3", "2", "FULL")));
    }

    private GuideSingleJobDefinitionHandler newHandler() {
        return new GuideSingleJobDefinitionHandler(new GuideSingleWorkflowValidator(), null, null);
    }

    private BatchFileSyncJobSaveCommand command(
            String sourceType,
            String sourceId,
            String sinkType,
            String sinkId,
            String syncType) {
        BatchFileSyncJobSaveCommand command = new BatchFileSyncJobSaveCommand();
        JobBasicConfig basic = new JobBasicConfig();
        basic.setJobName("file-sync-test");
        basic.setClientId(42L);
        command.setBasic(basic);
        command.setWorkflow(
                Map.of(
                        "nodes",
                        List.of(
                                node("source-node", "source", sourceType, sourceId, syncType),
                                node("sink-node", "sink", sinkType, sinkId, syncType)),
                        "edges",
                        List.of(Map.of("source", "source-node", "target", "sink-node"))));
        return command;
    }

    private Map<String, Object> node(
            String id, String nodeType, String dbType, String datasourceId, String syncType) {
        return Map.of(
                "id",
                id,
                "data",
                Map.of(
                        "nodeType",
                        nodeType,
                        "config",
                        Map.of(
                                "dbType",
                                dbType,
                                "dataSourceId",
                                datasourceId,
                                "syncType",
                                syncType)));
    }
}
