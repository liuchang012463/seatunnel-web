package org.apache.seatunnel.web.api.fileresource.duckdb;

import org.apache.seatunnel.web.api.fileresource.storage.FileResourceStorageProvider;
import org.apache.seatunnel.web.api.fileresource.storage.StorageObjectMetadata;
import org.apache.seatunnel.web.dao.entity.FileResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void rejectsAnObjectLargerThanTheCatalogLimitBeforeDownloading() throws Exception {
        FileResourceStorageProvider storageProvider = mock(FileResourceStorageProvider.class);
        when(storageProvider.head("big.db")).thenReturn(new StorageObjectMetadata(2048L, null, null));
        DuckDbCatalogReader reader = new DuckDbCatalogReader(storageProvider, 1024L, 1);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> reader.inspect(resource("big.db")));

        assertTrue(error.getMessage().contains("2048"));
        verify(storageProvider, never()).download(anyString(), any());
    }

    @Test
    void readsAnObjectAtTheCatalogLimit() throws Exception {
        FileResourceStorageProvider storageProvider = mock(FileResourceStorageProvider.class);
        when(storageProvider.head("exact.db")).thenReturn(new StorageObjectMetadata(1024L, null, null));
        DuckDbCatalogReader reader = new DuckDbCatalogReader(storageProvider, 1024L, 1);

        // The copy is written, so the limit itself is allowed; DuckDB then rejects the empty copy.
        Exception error = assertThrows(Exception.class, () -> reader.inspect(resource("exact.db")));

        assertFalse(String.valueOf(error.getMessage()).contains("超过目录浏览上限"));
        verify(storageProvider).download(eq("exact.db"), any());
    }

    private static FileResource resource(String objectKey) {
        FileResource resource = new FileResource();
        resource.setName(objectKey);
        resource.setObjectKey(objectKey);
        return resource;
    }
}
