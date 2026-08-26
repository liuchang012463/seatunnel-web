package org.apache.seatunnel.web.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataStableNameTest {

    @Test
    void derivesStableNamesFromTheDataSourceId() {
        assertEquals("st_ds_1024", MetadataStableName.serviceFqn(1024L));
        assertEquals("st_ds_1024_metadata", MetadataStableName.metadataPipelineName(1024L));
        assertEquals("st_ds_1024.st_ds_1024_metadata", MetadataStableName.metadataPipelineFqn(1024L));
        assertEquals("st_ds_1024_profiler", MetadataStableName.profilerPipelineName(1024L));
        assertEquals("st_ds_1024.st_ds_1024_profiler", MetadataStableName.profilerPipelineFqn(1024L));
    }

    @Test
    void rejectsMissingOrInvalidIds() {
        assertThrows(IllegalArgumentException.class, () -> MetadataStableName.serviceName(null));
        assertThrows(IllegalArgumentException.class, () -> MetadataStableName.serviceName(0L));
    }
}
