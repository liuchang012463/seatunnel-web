package org.apache.seatunnel.web.core.job.handler.single;

import org.apache.seatunnel.web.spi.bean.dto.batch.BatchFileSyncJobSaveCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideSingleJobDefinitionHandlerFileSyncTest {

    private final GuideSingleJobDefinitionHandler handler =
            new GuideSingleJobDefinitionHandler(new GuideSingleWorkflowValidator(), null, null);

    @Test
    void acceptsFullCopyAcrossAllFileDatasourceTypes() {
        assertDoesNotThrow(() -> handler.validate(command("S3", "1", "MINIO", "2", "FULL")));
        assertDoesNotThrow(() -> handler.validate(command("FTP", "3", "S3", "4", "FULL")));
        assertDoesNotThrow(() -> handler.validate(command("MINIO", "5", "SFTP", "6", "FULL")));
    }

    @Test
    void preservesIncrementalForSameFtpOrSftpDatasource() {
        assertDoesNotThrow(() -> handler.validate(command("FTP", "1", "FTP", "1", "INCREMENTAL")));
        assertDoesNotThrow(() -> handler.validate(command("SFTP", "2", "SFTP", "2", "INCREMENTAL")));
    }

    @Test
    void rejectsObjectStorageIncrementalMode() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> handler.validate(command("S3", "1", "MINIO", "2", "INCREMENTAL")));

        assertTrue(exception.getMessage().contains("supports FTP/SFTP only"));
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command("FTP", "1", "MINIO", "1", "INCREMENTAL")));
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command("SFTP", "1", "S3", "1", "INCREMENTAL")));
    }

    @Test
    void rejectsIncrementalAcrossDifferentDatasources() {
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command("FTP", "1", "SFTP", "2", "INCREMENTAL")));
    }

    @Test
    void rejectsNonFileDatasource() {
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command("MYSQL", "1", "S3", "2", "FULL")));
    }

    private BatchFileSyncJobSaveCommand command(
            String sourceType,
            String sourceId,
            String sinkType,
            String sinkId,
            String syncType) {
        BatchFileSyncJobSaveCommand command = new BatchFileSyncJobSaveCommand();
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
