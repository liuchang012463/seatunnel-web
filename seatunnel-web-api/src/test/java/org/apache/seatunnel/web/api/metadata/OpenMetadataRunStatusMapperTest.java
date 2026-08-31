package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenMetadataRunStatusMapperTest {

    @Test
    void mapsEveryOpenMetadata11210PipelineStateWithoutLeakingItToClients() {
        assertEquals(MetadataRunStatus.QUEUED, OpenMetadataRunStatusMapper.fromPipelineState("queued"));
        assertEquals(MetadataRunStatus.RUNNING, OpenMetadataRunStatusMapper.fromPipelineState("running"));
        assertEquals(MetadataRunStatus.SUCCESS, OpenMetadataRunStatusMapper.fromPipelineState("success"));
        assertEquals(MetadataRunStatus.SUCCESS, OpenMetadataRunStatusMapper.fromPipelineState("partialSuccess"));
        assertEquals(MetadataRunStatus.FAILED, OpenMetadataRunStatusMapper.fromPipelineState("failed"));
        assertEquals(MetadataRunStatus.FAILED, OpenMetadataRunStatusMapper.fromPipelineState("stopped"));
        assertEquals(MetadataRunStatus.UNKNOWN, OpenMetadataRunStatusMapper.fromPipelineState("new-state"));
        assertEquals(MetadataRunStatus.UNKNOWN, OpenMetadataRunStatusMapper.fromPipelineState(null));
    }
}
