package org.apache.seatunnel.plugin.datasource.ftp.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemotePathUtilsTest {
    @Test void resolvesPathWithinDatasourceRoot() {
        assertEquals("/tenant/files/incoming", RemotePathUtils.resolveWithinBase("/tenant/files", "/tenant/files/incoming/"));
    }

    @Test void rejectsTraversalAndOutsidePath() {
        assertThrows(IllegalArgumentException.class, () -> RemotePathUtils.resolveWithinBase("/tenant/files", "/tenant/files/../secret"));
        assertThrows(IllegalArgumentException.class, () -> RemotePathUtils.resolveWithinBase("/tenant/files", "/other"));
    }
}
