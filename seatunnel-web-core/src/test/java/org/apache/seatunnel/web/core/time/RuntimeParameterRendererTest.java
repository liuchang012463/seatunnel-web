package org.apache.seatunnel.web.core.time;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeParameterRendererTest {

    private static final Map<String, String> VALUES = Map.of(
            "window_start", "2024-02-01 10:00:00",
            "window_end", "2024-02-01 10:30:00");

    @Test
    void quotesWindowValuesWhenBodyUsesJsonValuePlaceholders() {
        assertEquals(
                "{\"from\":\"2024-02-01 10:00:00\",\"to\":\"2024-02-01 10:30:00\"}",
                RuntimeParameterRenderer.renderJsonBody(
                        "{\"from\":${window_start},\"to\":${window_end}}", VALUES));
    }

    @Test
    void doesNotDoubleQuotePlaceholdersAlreadyInsideJsonStrings() {
        assertEquals(
                "{\"from\":\"2024-02-01 10:00:00Z\"}",
                RuntimeParameterRenderer.renderJsonBody(
                        "{\"from\":\"${window_start}Z\"}", VALUES));
    }

    @Test
    void rendersNestedRequestValuesAsText() {
        Object rendered = RuntimeParameterRenderer.renderValue(
                Map.of("from", "${window_start}", "nested", Map.of("to", "${window_end}")),
                VALUES);

        assertTrue(rendered instanceof Map);
        Map<?, ?> renderedMap = (Map<?, ?>) rendered;
        assertEquals("2024-02-01 10:00:00", renderedMap.get("from"));
        assertEquals(
                "2024-02-01 10:30:00",
                ((Map<?, ?>) renderedMap.get("nested")).get("to"));
    }
}
