package org.apache.seatunnel.web.spi.bean.dto.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.seatunnel.web.common.enums.JobMode;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class JobEnvConfig {

    /**
     * SeaTunnel env.job.mode
     * BATCH / STREAMING
     */
    private JobMode jobMode;

    private Integer parallelism;

    /** SeaTunnel env: read_limit.bytes_per_second */
    private Long readLimitBytesPerSecond;

    /** SeaTunnel env: read_limit.rows_per_second */
    private Long readLimitRowsPerSecond;

    /**
     * HIGH / MEDIUM / LOW — stored on job env JSON only; not applied by engine.
     */
    private String priority;
}