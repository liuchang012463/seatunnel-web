package org.apache.seatunnel.web.core.job.handler.single;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.jdbc.builder.JdbcBatchBuilder;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class GuideSingleHoconBuildServiceTest {

    @Test
    void forcesHttpAppendOptionsAndRemovesAdvancedOverridesFromBuildCopy() {
        Map<String, Object> advancedConfig = new LinkedHashMap<>(Map.of(
                "data_save_mode", "DROP_DATA",
                "enable_upsert", true,
                "primary_keys", List.of("legacy_id")));
        Map<String, Object> sinkConfig = new LinkedHashMap<>();
        sinkConfig.put("writeMode", "append");
        sinkConfig.put("primaryKey", "legacy_id");
        sinkConfig.put("primary_keys", List.of("legacy_id"));
        sinkConfig.put("dataSaveMode", "DROP_DATA");
        sinkConfig.put("enableUpsert", true);
        sinkConfig.put("config", advancedConfig);
        sinkConfig.put("extraParams", List.of(
                Map.of("key", "primary_keys", "value", "legacy_id"),
                Map.of("key", "data_save_mode", "value", "DROP_DATA"),
                Map.of("key", "field_ide", "value", "id")));

        Map<String, Object> sourceData = Map.of(
                "nodeType", "source",
                "config", Map.of("dbType", "HTTP"));
        Map<String, Object> sinkData = new LinkedHashMap<>();
        sinkData.put("nodeType", "sink");
        sinkData.put("config", sinkConfig);
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("nodes", List.of(
                Map.of("data", sourceData),
                Map.of("data", sinkData)));

        Map<String, Object> prepared = GuideSingleHoconBuildService.prepareHttpAppendWorkflow(workflow);
        List<?> preparedNodes = (List<?>) prepared.get("nodes");
        Map<?, ?> preparedSinkData = (Map<?, ?>) ((Map<?, ?>) preparedNodes.get(1)).get("data");
        Map<?, ?> preparedSinkConfig = (Map<?, ?>) preparedSinkData.get("config");
        Map<?, ?> preparedAdvancedConfig = (Map<?, ?>) preparedSinkConfig.get("config");

        assertEquals("append", preparedSinkData.get("writeMode"));
        assertEquals("append", preparedSinkConfig.get("writeMode"));
        assertFalse(preparedSinkConfig.containsKey("primaryKey"));
        assertFalse(preparedSinkConfig.containsKey("primary_keys"));
        assertFalse(preparedSinkConfig.containsKey("dataSaveMode"));
        assertFalse(preparedSinkConfig.containsKey("enableUpsert"));
        assertFalse(preparedAdvancedConfig.containsKey("data_save_mode"));
        assertFalse(preparedAdvancedConfig.containsKey("enable_upsert"));
        assertFalse(preparedAdvancedConfig.containsKey("primary_keys"));
        assertEquals(List.of(Map.of("key", "field_ide", "value", "id")),
                preparedSinkConfig.get("extraParams"));
        assertEquals("DROP_DATA", sinkConfig.get("dataSaveMode"));
    }

    @Test
    void removesJdbcParamsOverridesBeforeFinalSinkHoconIsBuilt() {
        List<Map<String, Object>> sourceParams = List.of(
                param("enable_upsert", true));
        Map<String, Object> sourceConfig = new LinkedHashMap<>();
        sourceConfig.put("dbType", "HTTP");
        sourceConfig.put("params", sourceParams);
        Map<String, Object> sourceData = Map.of(
                "nodeType", "source",
                "config", sourceConfig);

        Map<String, Object> nestedSinkConfig = new LinkedHashMap<>();
        nestedSinkConfig.put("writeMode", "overwrite");
        nestedSinkConfig.put("data_save_mode", "DROP_DATA");
        nestedSinkConfig.put("enable_upsert", true);
        nestedSinkConfig.put("primary_keys", List.of("nested_id"));
        nestedSinkConfig.put("params", List.of(
                param("data_save_mode", "DROP_DATA"),
                param("enable_upsert", true),
                param("primary_keys", List.of("nested_id"))));

        Map<String, Object> sinkConfig = new LinkedHashMap<>();
        sinkConfig.put("targetTableName", "orders");
        sinkConfig.put("writeMode", "append");
        sinkConfig.put("params", List.of(
                param("data_save_mode", "DROP_DATA"),
                param("enable_upsert", true),
                param("primary_keys", List.of("legacy_id")),
                param("batch_size", 64),
                param("field_ide", "ORIGINAL")));
        sinkConfig.put("config", nestedSinkConfig);

        Map<String, Object> sinkData = new LinkedHashMap<>();
        sinkData.put("nodeType", "sink");
        sinkData.put("writeMode", "append");
        sinkData.put("params", List.of(param("primary_keys", List.of("outer_id"))));
        sinkData.put("config", sinkConfig);

        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("nodes", List.of(
                Map.of("data", sourceData),
                Map.of("data", sinkData)));
        String originalWorkflow = JSONUtils.toJsonString(workflow);

        Map<String, Object> prepared = GuideSingleHoconBuildService.prepareHttpAppendWorkflow(workflow);
        List<?> preparedNodes = (List<?>) prepared.get("nodes");
        Map<?, ?> preparedSourceData = (Map<?, ?>) ((Map<?, ?>) preparedNodes.get(0)).get("data");
        Map<?, ?> preparedSourceConfig = (Map<?, ?>) preparedSourceData.get("config");
        Map<?, ?> preparedSinkData = (Map<?, ?>) ((Map<?, ?>) preparedNodes.get(1)).get("data");
        Map<?, ?> preparedSinkConfig = (Map<?, ?>) preparedSinkData.get("config");
        Map<?, ?> preparedNestedSinkConfig = (Map<?, ?>) preparedSinkConfig.get("config");

        assertEquals("append", preparedSinkData.get("writeMode"));
        assertFalse(preparedSinkData.containsKey("params"));
        assertEquals("append", preparedSinkConfig.get("writeMode"));
        assertEquals(List.of(
                        param("batch_size", 64),
                        param("field_ide", "ORIGINAL")),
                preparedSinkConfig.get("params"));
        assertFalse(preparedNestedSinkConfig.containsKey("writeMode"));
        assertFalse(preparedNestedSinkConfig.containsKey("data_save_mode"));
        assertFalse(preparedNestedSinkConfig.containsKey("enable_upsert"));
        assertFalse(preparedNestedSinkConfig.containsKey("primary_keys"));
        assertFalse(preparedNestedSinkConfig.containsKey("params"));
        assertEquals(sourceParams, preparedSourceConfig.get("params"));
        assertEquals(originalWorkflow, JSONUtils.toJsonString(workflow));

        @SuppressWarnings("unchecked")
        Map<String, Object> preparedSinkPluginConfig =
                (Map<String, Object>) preparedSinkData.get("config");
        Config finalSink = new JdbcBatchBuilder().buildSinkHocon(HoconBuildContext.builder()
                .connectionConfig(ConfigFactory.parseString("""
                        url = "jdbc:mysql://localhost:3306/demo"
                        driver = "com.mysql.cj.jdbc.Driver"
                        user = "test"
                        password = "test"
                        database = "demo"
                        schemaName = "public"
                        """))
                .nodeConfig(ConfigFactory.parseMap(preparedSinkPluginConfig))
                .build());
        String finalJdbcHocon = "Jdbc " + finalSink.root().render();
        Config emittedSink = ConfigFactory.parseString(finalJdbcHocon).getConfig("Jdbc");

        assertEquals("APPEND_DATA", emittedSink.getString("data_save_mode"));
        assertFalse(emittedSink.getBoolean("enable_upsert"));
        assertFalse(emittedSink.hasPath("primary_keys"));
        assertEquals(64, emittedSink.getInt("batch_size"));
        assertEquals("ORIGINAL", emittedSink.getString("field_ide"));
    }

    @Test
    void leavesNonHttpAppendWorkflowUntouched() {
        Map<String, Object> workflow = new LinkedHashMap<>(Map.of(
                "nodes", List.of(
                        Map.of("data", Map.of("nodeType", "source", "config", Map.of("dbType", "MYSQL"))),
                        Map.of("data", Map.of("nodeType", "sink", "config", Map.of("writeMode", "append"))))));

        assertSame(workflow, GuideSingleHoconBuildService.prepareHttpAppendWorkflow(workflow));
    }

    private static Map<String, Object> param(String key, Object value) {
        return Map.of("key", key, "value", value);
    }
}
