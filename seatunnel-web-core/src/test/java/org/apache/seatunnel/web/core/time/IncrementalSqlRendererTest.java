package org.apache.seatunnel.web.core.time;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalSqlRendererTest {

    @Test
    void addsOverlapPredicateForTableMode() {
        JobScheduleConfig schedule = incrementalSchedule();
        Config rendered = IncrementalSqlRenderer.render(
                ConfigFactory.parseMap(Map.of("table_path", "orders")), schedule);

        assertTrue(rendered.hasPath("where_condition"));
        assertEquals(
                "where update_time >= '2023-12-31 23:59:00.000000' and update_time < '2024-01-01 00:30:00.000000'",
                rendered.getString("where_condition"));
    }

    @Test
    void rendersFixedRuntimeWindowInCustomQuery() {
        JobScheduleConfig schedule = incrementalSchedule();
        schedule.setRuntimeParams(Map.of(
                "window_start", "2024-02-01 10:00:00.000000",
                "window_end", "2024-02-01 10:30:00.000000",
                "query_start", "2024-02-01 09:59:00.000000",
                "batch_id", "batch-1"));

        Config rendered = IncrementalSqlRenderer.render(
                ConfigFactory.parseMap(Map.of(
                        "query", "select * from orders where update_time >= '${query_start}' "
                                + "and update_time < '${window_end}' /* ${batch_id} */")),
                schedule);

        assertEquals(
                "select * from orders where update_time >= '2024-02-01 09:59:00.000000' "
                        + "and update_time < '2024-02-01 10:30:00.000000' /* batch-1 */",
                rendered.getString("query"));
    }

    @Test
    void derivesPreviewWindowFromFiveMinuteSchedule() {
        JobScheduleConfig schedule = incrementalSchedule();
        schedule.setScheduleType("minute");
        schedule.setMinuteValue(Map.of("intervalMinute", 5));

        Map<String, Object> sourceConfig = Map.of(
                "incrementalConfig", Map.of(
                        "enabled", true,
                        "fieldName", "update_time",
                        "startValue", "2024-01-01 00:00:00"
                )
        );
        Map<String, Object> sourceData = Map.of("nodeType", "source", "config", sourceConfig);
        IncrementalConfigResolver.resolve(
                Map.of("nodes", List.of(Map.of("data", sourceData))),
                schedule
        );

        Config rendered = IncrementalSqlRenderer.render(
                ConfigFactory.parseMap(Map.of("table_path", "orders")), schedule);

        assertEquals(
                "where update_time >= '2023-12-31 23:59:00.000000' and update_time < '2024-01-01 00:05:00.000000'",
                rendered.getString("where_condition"));
    }

    @Test
    void usesZeroOverlapWhenNewSourceConfigHasNoHiddenRuntimeValues() {
        JobScheduleConfig schedule = new JobScheduleConfig();
        schedule.setScheduleType("minute");
        schedule.setMinuteValue(Map.of("intervalMinute", 5));

        Map<String, Object> sourceConfig = Map.of(
                "incrementalConfig", Map.of(
                        "enabled", true,
                        "fieldName", "update_time",
                        "startValue", "2024-01-01 00:00:00"
                )
        );
        Map<String, Object> sourceData = Map.of("nodeType", "source", "config", sourceConfig);
        IncrementalConfigResolver.resolve(
                Map.of("nodes", List.of(Map.of("data", sourceData))),
                schedule
        );

        Config rendered = IncrementalSqlRenderer.render(
                ConfigFactory.parseMap(Map.of("table_path", "orders")), schedule);

        assertEquals(
                "where update_time >= '2024-01-01 00:00:00.000000' and update_time < '2024-01-01 00:05:00.000000'",
                rendered.getString("where_condition"));
    }

    private JobScheduleConfig incrementalSchedule() {
        JobScheduleConfig config = new JobScheduleConfig();
        JobScheduleConfig.IncrementalConfig incremental = new JobScheduleConfig.IncrementalConfig();
        incremental.setEnabled(true);
        incremental.setWatermarkColumn("update_time");
        incremental.setInitialWatermark("2024-01-01 00:00:00");
        incremental.setSafetyDelaySeconds(0);
        incremental.setOverlapSeconds(60);
        incremental.setMaxWindowSeconds(1800);
        config.setIncremental(incremental);
        return config;
    }
}
