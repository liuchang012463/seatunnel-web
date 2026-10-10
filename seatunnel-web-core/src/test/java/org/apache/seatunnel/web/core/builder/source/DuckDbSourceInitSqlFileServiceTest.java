package org.apache.seatunnel.web.core.builder.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDbSourceInitSqlFileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsAnUpToDateScriptInPlaceAndReplacesAChangedOne() throws Exception {
        DuckDbSourceInitSqlFileService service = service();
        Path script = tempDir.resolve("init/duckdb-resource-42.sql");

        assertEquals("/opt/seatunnel/lib/duckdb-init/duckdb-resource-42.sql", service.write(42L, "SELECT 1;"));
        assertEquals("SELECT 1;", Files.readString(script));
        Object fileKey = Files.readAttributes(script, BasicFileAttributes.class).fileKey();
        assertNotNull(fileKey);

        // Rewriting an unchanged script would touch the file the engine may be reading.
        service.write(42L, "SELECT 1;");
        assertEquals(fileKey, Files.readAttributes(script, BasicFileAttributes.class).fileKey());

        service.write(42L, "SELECT 2;");
        assertEquals("SELECT 2;", Files.readString(script));
    }

    @Test
    void requiresAnAbsoluteInitSqlDirectory() {
        DuckDbSourceInitSqlFileService service = new DuckDbSourceInitSqlFileService();
        ReflectionTestUtils.setField(service, "initSqlDirectory", "relative/init");
        ReflectionTestUtils.setField(service, "engineInitSqlDirectory", "/opt/seatunnel/lib/duckdb-init");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class, () -> service.write(42L, "SELECT 1;"));

        assertTrue(exception.getMessage().contains("absolute path"));
    }

    private DuckDbSourceInitSqlFileService service() {
        DuckDbSourceInitSqlFileService service = new DuckDbSourceInitSqlFileService();
        ReflectionTestUtils.setField(service, "initSqlDirectory", tempDir.resolve("init").toString());
        ReflectionTestUtils.setField(service, "engineInitSqlDirectory", "/opt/seatunnel/lib/duckdb-init");
        return service;
    }
}
