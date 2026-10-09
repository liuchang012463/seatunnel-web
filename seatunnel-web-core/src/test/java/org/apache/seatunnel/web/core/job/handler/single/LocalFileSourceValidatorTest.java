package org.apache.seatunnel.web.core.job.handler.single;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void validatesFileResourceIdAndStructuredFormat() {
        assertEquals(42L, LocalFileSourceValidator.validateFileResource(Map.of(
                "fileResourceId", "42",
                "fileFormatType", "csv")));
    }

    @Test
    void allowsDuckDbCustomSqlWithoutASelectedTable() {
        assertEquals(42L, LocalFileSourceValidator.validateFileResource(Map.of(
                "fileResourceId", "42",
                "fileFormatType", "duckdb",
                "readMode", "sql",
                "sql", "SELECT 1")));
    }

    @Test
    void requiresDuckDbTableOutsideCustomSqlMode() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalFileSourceValidator.validateFileResource(Map.of(
                        "fileResourceId", "42",
                        "fileFormatType", "duckdb",
                        "readMode", "table")));
    }

    @Test
    void requiresFileResourceId() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                LocalFileSourceValidator.validateFileResource(Map.of("fileFormatType", "csv")));

        assertTrue(exception.getMessage().contains("fileResourceId"));
    }

    @Test
    void requiresDuckDbTableOnlyInTableMode() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                LocalFileSourceValidator.validate(Map.of(
                        "fileFormatType", "duckdb",
                        "readMode", "table")));

        assertTrue(exception.getMessage().contains("DuckDB 表名"));

        assertEquals("duckdb", LocalFileSourceValidator.validate(Map.of(
                "fileFormatType", "duckdb",
                "readMode", "table",
                "duckdbTable", "main.orders")));
        assertEquals("duckdb", LocalFileSourceValidator.validate(Map.of(
                "fileFormatType", "duckdb",
                "readMode", "sql",
                "sql", "select * from duckdb_source.main.orders")));
    }
}
