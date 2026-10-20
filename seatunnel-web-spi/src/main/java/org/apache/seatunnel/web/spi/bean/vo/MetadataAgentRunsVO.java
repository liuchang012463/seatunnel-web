package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.List;

/**
 * Run history of one OpenMetadata agent (metadata scan, profiler, sample collection).
 * Only the agents the data source actually owns are returned.
 */
@Data
public class MetadataAgentRunsVO {

    /** METADATA, PROFILER or AUTO_CLASSIFICATION. */
    private String agentType;

    /** Operator-facing name of the agent. */
    private String label;

    /** The OpenMetadata ingestion pipeline that runs this agent. */
    private String pipelineFqn;

    private List<MetadataPipelineRunVO> runs;
}
