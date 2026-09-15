package org.apache.seatunnel.web.core.job.handler.single;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalFileSourceValidatorTest {

    @Test
    void acceptsTheFourStructuredFormats() {
        for (String format : new String[]{"csv", "excel", "json", "text"}) {
            Map<String, Object> source = new HashMap<>(Map.of("fileFormatType", format));
            if ("json".equals(format) || "excel".equals(format)) {
                source.put("schema", Map.of("fields", Map.of("id", "long")));
            }
            assertEquals(format, LocalFileSourceValidator.validate(source));
        }
    }

    @Test
    void requiresSchemaForJson() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalFileSourceValidator.validate(Map.of("fileFormatType", "json")));
    }

    @Test
    void requiresSchemaForExcel() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalFileSourceValidator.validate(Map.of("file_format_type", "excel")));
    }

    @Test
    void rejectsUnsupportedFormats() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalFileSourceValidator.validate(Map.of("fileFormatType", "parquet")));
    }
}
