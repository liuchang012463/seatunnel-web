package org.apache.seatunnel.web.api.service;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/** Runtime context for one fixed incremental window. */
@Data
@Builder
public class IncrementalBatchExecution {

    private boolean skipped;

    private String batchId;

    private Map<String, String> runtimeParams;
}
