package org.apache.seatunnel.plugin.datasource.http.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpHoconBuilderTest {

    private final HttpHoconBuilder builder = new HttpHoconBuilder();

    @Test
    void shouldBuildGetTextSourceWithMergedHeaders() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("path", "/v1/status");
        node.put("headers", Map.of("X-Tenant", "node"));
        node.put("params", Map.of("active", "true"));

        Config config = builder.buildSourceHocon(context(
                "{\"baseUrl\":\"https://api.example.com\","
                        + "\"defaultHeaders\":{\"Accept\":\"text/plain\",\"X-Tenant\":\"default\"}}",
                node));

        assertEquals("https://api.example.com/v1/status", config.getString("url"));
        assertEquals("GET", config.getString("method"));
        assertEquals("text", config.getString("format"));
        assertEquals("node", config.getString("headers.X-Tenant"));
        assertEquals("true", config.getString("params.active"));
    }

    @Test
    void shouldBuildJsonPostPageNumberWithNativeSpelling() {
        Map<String, Object> pageing = new LinkedHashMap<>();
        pageing.put("page_type", "PageNumber");
        pageing.put("page_field", "page");
        pageing.put("page_start_from", 1);
        pageing.put("page_size", 100);

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("path", "v1/orders");
        node.put("method", "POST");
        node.put("body", "{\"tenant\":\"${tenant}\"}");
        node.put("format", "json");
        node.put("schema", Map.of("fields", Map.of("id", "bigint")));
        node.put("contentField", "$.data");
        node.put("jsonFieldMissedReturnNull", true);
        node.put("pageing", pageing);

        Config config = builder.buildSourceHocon(context(
                "{\"baseUrl\":\"https://api.example.com\","
                        + "\"authenticationType\":\"API_KEY\","
                        + "\"apiKeyHeader\":\"X-API-Key\",\"apiKeyValue\":\"secret\"}",
                node));

        assertEquals("POST", config.getString("method"));
        assertEquals("$.data", config.getString("content_field"));
        assertEquals(true, config.getBoolean("json_filed_missed_return_null"));
        assertEquals("PageNumber", config.getString("pageing.page_type"));
        assertEquals("secret", config.getString("headers.X-API-Key"));
    }

    @Test
    void shouldValidateCursorAndSourceOnlyCapability() {
        Map<String, Object> invalidCursor = Map.of(
                "path", "/v1/orders",
                "pageing", Map.of("page_type", "Cursor", "cursor_field", "cursor"));

        assertThrows(
                IllegalArgumentException.class,
                () -> builder.buildSourceHocon(context(
                        "{\"baseUrl\":\"https://api.example.com\"}", invalidCursor)));
        assertFalse(builder.supportsSink());
        assertThrows(
                UnsupportedOperationException.class,
                () -> builder.buildSinkHocon(context(
                        "{\"baseUrl\":\"https://api.example.com\"}", Map.of())));
    }

    @Test
    void shouldRequireSchemaForJson() {
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.buildSourceHocon(context(
                        "{\"baseUrl\":\"https://api.example.com\"}",
                        Map.of("path", "/v1/orders", "format", "json"))));
    }

    private HoconBuildContext context(String connection, Map<String, Object> node) {
        return HoconBuildContext.builder()
                .connectionParam(connection)
                .connectionConfig(ConfigFactory.parseString(connection))
                .nodeConfig(ConfigFactory.parseMap(node))
                .build();
    }
}
