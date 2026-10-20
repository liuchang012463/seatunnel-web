package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Log of the last run of one OpenMetadata agent. OpenMetadata 2.0.4 only exposes the
 * latest run, keyed by the task instance name.
 */
@Data
public class MetadataAgentLogsVO {

    /** METADATA, PROFILER or AUTO_CLASSIFICATION. */
    private String agentType;

    private Map<String, String> logs = new LinkedHashMap<>();

    /** Why the log is empty, for example "the agent never ran". */
    private String message;
}
