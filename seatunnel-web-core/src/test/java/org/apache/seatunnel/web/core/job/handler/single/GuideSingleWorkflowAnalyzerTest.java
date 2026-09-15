package org.apache.seatunnel.web.core.job.handler.single;

import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GuideSingleWorkflowAnalyzerTest {

    @Test
    void recognizesFileResourceSourceWithoutDatasourceId() {
        Map<String, Object> workflow = Map.of(
                "nodes", List.of(
                        Map.of(
                                "id", "source",
                                "data", Map.of(
                                        "nodeType", "source",
                                        "config", Map.of(
                                                "sourceMode", "FILE_RESOURCE",
                                                "fileResourceId", "42",
                                                "fileFormatType", "csv"))),
                        Map.of(
                                "id", "sink",
                                "data", Map.of(
                                        "nodeType", "sink",
                                        "config", Map.of("dbType", "MYSQL")))),
                "edges", List.of(Map.of("source", "source", "target", "sink")));

        JobDefinitionAnalysisResult result = new GuideSingleWorkflowAnalyzer().analyze(workflow);

        assertEquals("FILE_RESOURCE", result.getSourceType());
        assertEquals("42", result.getSourceTable());
        assertNull(result.getSourceDatasourceId());
    }
}
