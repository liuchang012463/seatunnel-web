package org.apache.seatunnel.plugin.datasource.elasticsearch.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElasticsearchHoconBuilderTest {

    private final ElasticsearchHoconBuilder builder = new ElasticsearchHoconBuilder();

    @Test
    void buildsSourceWithTypedAdvancedParameters() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("index", "orders");
        node.put("extraParams", Map.of(
                "source", "[\"_id\",\"order_id\"]",
                "query", "{\"match\":{\"status\":\"OPEN\"}}",
                "scrollSize", "25",
                "pluginName", "must-not-leak"));

        Config config = builder.buildSourceHocon(context(node));

        assertEquals("http://localhost:9200", config.getStringList("hosts").get(0));
        assertEquals("orders", config.getString("index"));
        assertEquals(2, config.getStringList("source").size());
        assertEquals("OPEN", config.getString("query.match.status"));
        assertEquals(25, config.getInt("scroll_size"));
        assertFalse(config.hasPath("pluginName"));
    }

    @Test
    void buildsSinkUsingFrontendWriteModeAliases() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("targetTableName", "orders_copy");
        node.put("writeMode", "upsert");
        node.put("primaryKey", "order_id,tenant_id");

        Config config = builder.buildSinkHocon(context(node));

        assertEquals("orders_copy", config.getString("index"));
        assertEquals("CREATE_SCHEMA_WHEN_NOT_EXIST", config.getString("schema_save_mode"));
        assertEquals("APPEND_DATA", config.getString("data_save_mode"));
        assertEquals(2, config.getStringList("primary_keys").size());
    }

    @Test
    void requiresSourceIndexOrIndexList() {
        assertThrows(IllegalArgumentException.class,
                () -> builder.buildSourceHocon(context(Map.of())));
    }

    private HoconBuildContext context(Map<String, Object> node) {
        String connection = "{\"hosts\":\"localhost:9200\"}";
        return HoconBuildContext.builder()
                .connectionParam(connection)
                .connectionConfig(ConfigFactory.parseString(connection))
                .nodeConfig(ConfigFactory.parseMap(node))
                .build();
    }
}
