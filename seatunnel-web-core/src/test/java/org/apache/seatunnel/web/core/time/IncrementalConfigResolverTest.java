package org.apache.seatunnel.web.core.time;

import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalConfigResolverTest {

    @Test
    void suppliesHttpDefaultsWithoutAWatermarkColumn() {
        Map<String, Object> sourceConfig = Map.of(
                "dbType", "HTTP",
                "incrementalConfig", Map.of("enabled", true));
        Map<String, Object> workflow = Map.of(
                "nodes", List.of(Map.of(
                        "data", Map.of("nodeType", "source", "config", sourceConfig))));
        JobScheduleConfig schedule = new JobScheduleConfig();

        JobScheduleConfig.IncrementalConfig resolved =
                IncrementalConfigResolver.resolve(workflow, schedule);

        assertTrue(resolved.getEnabled());
        assertEquals("", resolved.getWatermarkColumn());
        assertEquals(IncrementalConfigResolver.DEFAULT_START_VALUE, resolved.getInitialWatermark());
        assertEquals(
                IncrementalConfigResolver.DEFAULT_TIME_FORMAT,
                IncrementalConfigResolver.sourceTimeFormat(workflow));
    }
}
