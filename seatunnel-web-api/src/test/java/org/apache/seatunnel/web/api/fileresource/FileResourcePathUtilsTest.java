package org.apache.seatunnel.web.api.fileresource;

import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileResourcePathUtilsTest {

    @Test
    void normalizesLogicalAndRelativePathsWithoutChangingSafeNames() {
        assertEquals("/incoming/2026", FileResourcePathUtils.normalizePath("incoming/2026/"));
        assertEquals("incoming/ users.csv ",
                FileResourcePathUtils.normalizeRelativePath("incoming/ users.csv "));
        assertEquals("/incoming/2026/users.csv",
                FileResourcePathUtils.join("/incoming/2026", "users.csv"));
        assertEquals("/incoming/ users.csv ",
                FileResourcePathUtils.join("/", "incoming/ users.csv "));
    }

    @Test
    void rejectsTraversalAndAbsoluteDirectoryUploadPaths() {
        assertThrows(ServiceException.class,
                () -> FileResourcePathUtils.normalizePath("/incoming/../secret"));
        assertThrows(ServiceException.class,
                () -> FileResourcePathUtils.normalizeRelativePath("../secret.csv"));
        assertThrows(ServiceException.class,
                () -> FileResourcePathUtils.normalizeRelativePath("/secret.csv"));
        assertThrows(ServiceException.class,
                () -> FileResourcePathUtils.normalizeRelativePath("folder\\secret.csv"));
    }

    @Test
    void checksResourceFolderBoundaries() {
        assertTrue(FileResourcePathUtils.isSameOrDescendant(
                "/incoming/users.csv", "/incoming"));
        assertFalse(FileResourcePathUtils.isSameOrDescendant(
                "/incoming-old/users.csv", "/incoming"));
    }
}
