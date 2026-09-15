package org.apache.seatunnel.web.core.job.handler.single;

import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchFileSyncJobSaveCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideSingleJobDefinitionHandlerFileResourceTest {

    private final GuideSingleJobDefinitionHandler handler =
            new GuideSingleJobDefinitionHandler(
                    new GuideSingleWorkflowValidator(),
                    org.mockito.Mockito.mock(GuideSingleWorkflowAnalyzer.class),
                    org.mockito.Mockito.mock(GuideSingleHoconBuildService.class));

    @Test
    void acceptsAFileResourceAsTheSingleStructuredSource() {
        assertDoesNotThrow(() -> handler.validate(command("42", "csv")));
    }

    @Test
    void rejectsAFileResourceWithoutAnId() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command("", "csv")));

        assertTrue(exception.getMessage().contains("fileResourceId"));
    }

    @Test
    void acceptsAFileResourceAsABinaryFileTransferSource() {
        BatchFileSyncJobSaveCommand command = new BatchFileSyncJobSaveCommand();
        command.setWorkflow(Map.of(
                "nodes", List.of(
                        Map.of(
                                "id", "source",
                                "data", Map.of(
                                        "nodeType", "source",
                                        "config", Map.of(
                                                "source_mode", "FILE_RESOURCE",
                                                "file_resource_id", "42",
                                                "readMode", "resource",
                                                "syncType", "FULL"))),
                        Map.of(
                                "id", "sink",
                                "data", Map.of(
                                        "nodeType", "sink",
                                        "config", Map.of(
                                                "dbType", "FTP",
                                                "syncType", "FULL")))),
                "edges", List.of(Map.of("source", "source", "target", "sink"))));

        assertDoesNotThrow(() -> handler.validate(command));
    }

    private BatchGuideSingleJobSaveCommand command(String resourceId, String format) {
        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setWorkflow(Map.of(
                "nodes", List.of(
                        Map.of(
                                "id", "source",
                                "data", Map.of(
                                        "nodeType", "source",
                                        "config", Map.of(
                                                "sourceMode", "FILE_RESOURCE",
                                                "fileResourceId", resourceId,
                                                "fileFormatType", format))),
                        Map.of(
                                "id", "sink",
                                "data", Map.of(
                                        "nodeType", "sink",
                                        "config", Map.of("dbType", "MYSQL")))),
                "edges", List.of(Map.of("source", "source", "target", "sink"))));
        return command;
    }
}
