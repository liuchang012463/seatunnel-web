package org.apache.seatunnel.plugin.datasource.s3.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectStoragePathUtilsTest {

    @Test
    void normalizesAndConvertsObjectPaths() {
        assertEquals("/archive/2026", ObjectStoragePathUtils.normalizeAbsolute("/archive//2026/"));
        assertEquals("archive/2026/", ObjectStoragePathUtils.toDirectoryPrefix("/archive/2026"));
        assertEquals("/archive/file.bin", ObjectStoragePathUtils.fromObjectKey("archive/file.bin"));
    }

    @Test
    void enforcesDatasourceRoot() {
        assertEquals("/archive",
                ObjectStoragePathUtils.resolveWithinBase("/archive", "/"));
        assertEquals("/archive",
                ObjectStoragePathUtils.resolveWithinBase("/archive", null));
        assertEquals("/archive/2026",
                ObjectStoragePathUtils.resolveWithinBase("/archive", "/archive/2026"));
        assertThrows(IllegalArgumentException.class,
                () -> ObjectStoragePathUtils.resolveWithinBase("/archive", "/other"));
        assertThrows(IllegalArgumentException.class,
                () -> ObjectStoragePathUtils.normalizeAbsolute("/archive\\other"));
        assertThrows(IllegalArgumentException.class,
                () -> ObjectStoragePathUtils.normalizeAbsolute("/archive/../other"));
    }
}
