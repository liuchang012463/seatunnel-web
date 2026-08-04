package org.apache.seatunnel.web.core.job.handler.single;

import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleIncrementalJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

class GuideSingleJobDefinitionHandlerIncrementalTest {

    private GuideSingleJobDefinitionHandler handler;

    @BeforeEach
    void setUp() {
        GuideSingleWorkflowValidator validator = mock(GuideSingleWorkflowValidator.class);
        doNothing().when(validator).validate(any());
        handler = new GuideSingleJobDefinitionHandler(
                validator,
                mock(GuideSingleWorkflowAnalyzer.class),
                mock(GuideSingleHoconBuildService.class)
        );
    }

    @Test
    void acceptsTemporalSourceFieldAndUpsertTarget() {
        BatchGuideSingleIncrementalJobSaveCommand command = validCommand("DATETIME");

        assertDoesNotThrow(() -> handler.validate(command));
    }

    @Test
    void rejectsNonTemporalSourceField() {
        BatchGuideSingleIncrementalJobSaveCommand command = validCommand("BIGINT");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command)
        );
        assertTrue(error.getMessage().contains("时间类型"));
    }

    @Test
    void rejectsNonUpsertTarget() {
        BatchGuideSingleIncrementalJobSaveCommand command = validCommand("TIMESTAMP");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) command.getWorkflow().get("nodes");
        Map<String, Object> sinkData = (Map<String, Object>) nodes.get(1).get("data");
        Map<String, Object> sinkConfig = (Map<String, Object>) sinkData.get("config");
        sinkConfig.put("writeMode", "append");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command)
        );
        assertTrue(error.getMessage().contains("Upsert"));
    }

    @Test
    void rejectsMissingCanonicalField() {
        BatchGuideSingleIncrementalJobSaveCommand command = validCommand("TIMESTAMP");
        Map<String, Object> sourceConfig = sourceConfig(command);
        Map<String, Object> incremental = new java.util.HashMap<>(
                (Map<String, Object>) sourceConfig.get("incrementalConfig"));
        incremental.put("fieldName", "");
        sourceConfig.put("incrementalConfig", incremental);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command)
        );
        assertTrue(error.getMessage().contains("请选择增量识别字段"));
    }

    @Test
    void rejectsInvalidCanonicalStartDate() {
        BatchGuideSingleIncrementalJobSaveCommand command = validCommand("TIMESTAMP");
        Map<String, Object> sourceConfig = sourceConfig(command);
        Map<String, Object> incremental = new java.util.HashMap<>(
                (Map<String, Object>) sourceConfig.get("incrementalConfig"));
        incremental.put("startValue", "2024-02-30 00:00:00");
        sourceConfig.put("incrementalConfig", incremental);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> handler.validate(command)
        );
        assertTrue(error.getMessage().contains("格式必须是"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sourceConfig(BatchGuideSingleIncrementalJobSaveCommand command) {
        List<Map<String, Object>> nodes =
                (List<Map<String, Object>>) command.getWorkflow().get("nodes");
        Map<String, Object> sourceData = (Map<String, Object>) nodes.get(0).get("data");
        return (Map<String, Object>) sourceData.get("config");
    }

    private BatchGuideSingleIncrementalJobSaveCommand validCommand(String fieldType) {
        Map<String, Object> sourceConfig = new java.util.HashMap<>(Map.of(
                "dbType", "MYSQL",
                "dataSourceId", "1",
                "table", "orders",
                "incrementalConfig", Map.of(
                        "enabled", true,
                        "fieldName", "update_time",
                        "startValue", "2024-01-01 00:00:00"
                )
        ));
        Map<String, Object> sourceData = new java.util.HashMap<>(Map.of(
                "nodeType", "source",
                "config", sourceConfig,
                "meta", Map.of(
                        "outputSchema", List.of(Map.of(
                                "originFieldName", "update_time",
                                "type", fieldType
                        ))
                )
        ));
        Map<String, Object> sinkConfig = new java.util.HashMap<>(Map.of(
                "dbType", "MYSQL",
                "dataSourceId", "2",
                "table", "orders",
                "writeMode", "upsert",
                "primaryKey", "id"
        ));
        Map<String, Object> sinkData = Map.of("nodeType", "sink", "config", sinkConfig);

        BatchGuideSingleIncrementalJobSaveCommand command =
                new BatchGuideSingleIncrementalJobSaveCommand();
        command.setWorkflow(new java.util.HashMap<>(Map.of(
                "nodes", List.of(
                        Map.of("id", "source", "data", sourceData),
                        Map.of("id", "sink", "data", sinkData)
                ),
                "edges", List.of(Map.of("source", "source", "target", "sink"))
        )));
        JobScheduleConfig schedule = new JobScheduleConfig();
        schedule.setScheduleType("minute");
        schedule.setMinuteValue(Map.of("intervalMinute", 5));
        command.setSchedule(schedule);
        return command;
    }
}
