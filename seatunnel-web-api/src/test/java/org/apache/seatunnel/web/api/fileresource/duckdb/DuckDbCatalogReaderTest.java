package org.apache.seatunnel.web.api.fileresource.duckdb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDbCatalogReaderTest {

    @Test
    void scrubsTheTemporaryDatabasePathFromDriverMessages() {
        String message = "IO Error: The file \"/tmp/seatunnel-web-duckdb-catalog-11666022910816721020.db\""
                + " exists, but it is not a valid DuckDB database file!";

        String scrubbed = DuckDbCatalogReader.scrubTemporaryPath(message);

        assertFalse(scrubbed.contains("/tmp"));
        assertFalse(scrubbed.contains(DuckDbCatalogReader.TEMPORARY_FILE_PREFIX));
        assertTrue(scrubbed.contains("临时副本"));
        assertTrue(scrubbed.contains("is not a valid DuckDB database file"));
    }

    @Test
    void keepsMessagesWithoutATemporaryPath() {
        String message = "Binder Error: Referenced column \"missing\" not found in FROM clause";

        assertEquals(message, DuckDbCatalogReader.scrubTemporaryPath(message));
        assertNull(DuckDbCatalogReader.scrubTemporaryPath(null));
    }
}
