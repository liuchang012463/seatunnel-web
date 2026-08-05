package org.apache.seatunnel.web.core.time;

import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class IncrementalScheduleIntervalResolverTest {

    @Test
    void resolvesMinuteScheduleToSeconds() {
        JobScheduleConfig schedule = new JobScheduleConfig();
        schedule.setScheduleType("minute");
        schedule.setMinuteValue(Map.of("intervalMinute", 5));

        assertEquals(300, IncrementalScheduleIntervalResolver.resolveSeconds(schedule));
    }

    @Test
    void resolvesAppointedHoursUsingMaximumCyclicGap() {
        JobScheduleConfig schedule = new JobScheduleConfig();
        schedule.setScheduleType("hour");
        schedule.setHourMode("appoint");
        schedule.setHourlyAppointValue(Map.of("hours", List.of(8, 12, 16)));

        assertEquals(16 * 3600, IncrementalScheduleIntervalResolver.resolveSeconds(schedule));
    }

    @Test
    void resolvesWeeklyScheduleUsingMaximumCyclicGap() {
        JobScheduleConfig schedule = new JobScheduleConfig();
        schedule.setScheduleType("week");
        schedule.setWeeklyValue(Map.of("weekdays", List.of("MON", "WED")));

        assertEquals(5 * 24 * 3600, IncrementalScheduleIntervalResolver.resolveSeconds(schedule));
    }

    @Test
    void prefersSourceConfigAndDerivesRuntimeDefaults() {
        JobScheduleConfig schedule = new JobScheduleConfig();
        schedule.setScheduleType("minute");
        schedule.setMinuteValue(Map.of("intervalMinute", 5));
        JobScheduleConfig.IncrementalConfig legacy = new JobScheduleConfig.IncrementalConfig();
        legacy.setEnabled(true);
        legacy.setWatermarkColumn("legacy_time");
        legacy.setInitialWatermark("2020-01-01 00:00:00");
        schedule.setIncremental(legacy);

        Map<String, Object> workflow = workflowWithIncrementalConfig(
                "modified_at", "2024-01-01 00:00:00");

        JobScheduleConfig.IncrementalConfig resolved =
                IncrementalConfigResolver.resolve(workflow, schedule);

        assertNotNull(resolved);
        assertEquals("modified_at", resolved.getWatermarkColumn());
        assertEquals("2024-01-01 00:00:00", resolved.getInitialWatermark());
        assertEquals(0, resolved.getSafetyDelaySeconds());
        assertEquals(0, resolved.getOverlapSeconds());
        assertEquals(300, resolved.getMaxWindowSeconds());
    }

    @Test
    void preservesExplicitLegacyRuntimeValuesWhenSourceConfigIsPresent() {
        JobScheduleConfig schedule = new JobScheduleConfig();
        schedule.setScheduleType("minute");
        schedule.setMinuteValue(Map.of("intervalMinute", 5));
        JobScheduleConfig.IncrementalConfig legacy = new JobScheduleConfig.IncrementalConfig();
        legacy.setEnabled(true);
        legacy.setWatermarkColumn("legacy_time");
        legacy.setInitialWatermark("2020-01-01 00:00:00");
        legacy.setSafetyDelaySeconds(120);
        legacy.setOverlapSeconds(60);
        schedule.setIncremental(legacy);

        JobScheduleConfig.IncrementalConfig resolved = IncrementalConfigResolver.resolve(
                workflowWithIncrementalConfig("modified_at", "2024-01-01 00:00:00"), schedule);

        assertEquals(120, resolved.getSafetyDelaySeconds());
        assertEquals(60, resolved.getOverlapSeconds());
    }

    @Test
    void readsLegacyScheduleConfigWhenSourceConfigIsAbsent() {
        JobScheduleConfig schedule = new JobScheduleConfig();
        schedule.setScheduleType("day");
        JobScheduleConfig.IncrementalConfig legacy = new JobScheduleConfig.IncrementalConfig();
        legacy.setEnabled(true);
        legacy.setWatermarkColumn("update_time");
        legacy.setInitialWatermark("2024-01-01 00:00:00");
        legacy.setSafetyDelaySeconds(30);
        legacy.setOverlapSeconds(10);
        schedule.setIncremental(legacy);

        JobScheduleConfig.IncrementalConfig resolved =
                IncrementalConfigResolver.resolve(Map.of(), schedule);

        assertEquals("update_time", resolved.getWatermarkColumn());
        assertEquals(30, resolved.getSafetyDelaySeconds());
        assertEquals(10, resolved.getOverlapSeconds());
        assertEquals(24 * 3600, resolved.getMaxWindowSeconds());
    }

    private Map<String, Object> workflowWithIncrementalConfig(String fieldName, String startValue) {
        Map<String, Object> sourceConfig = new HashMap<>();
        sourceConfig.put("incrementalConfig", Map.of(
                "enabled", true,
                "fieldName", fieldName,
                "startValue", startValue
        ));
        Map<String, Object> sourceData = new HashMap<>();
        sourceData.put("nodeType", "source");
        sourceData.put("config", sourceConfig);
        return Map.of("nodes", List.of(Map.of("data", sourceData)));
    }
}
