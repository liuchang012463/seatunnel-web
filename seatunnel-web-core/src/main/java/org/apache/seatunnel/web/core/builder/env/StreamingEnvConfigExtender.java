package org.apache.seatunnel.web.core.builder.env;

import org.apache.seatunnel.web.spi.bean.dto.config.JobEnvConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.StreamingJobEnvConfig;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StreamingEnvConfigExtender implements EnvConfigExtender {

    /**
     * Checkpoint 下游语义（Doris 2pc、Kafka exactly-once、commit_on_checkpoint）
     * 都依赖 checkpoint.interval；UI 缺省也是 30s，此处兜底保证 env 永远携带。
     */
    private static final int DEFAULT_CHECKPOINT_INTERVAL_MS = 30000;

    @Override
    public boolean supports(JobEnvConfig envConfig) {
        return envConfig instanceof StreamingJobEnvConfig;
    }

    @Override
    public void fill(Map<String, Object> envMap, JobEnvConfig envConfig) {
        StreamingJobEnvConfig streamingEnvConfig = (StreamingJobEnvConfig) envConfig;

        Integer checkpointInterval = streamingEnvConfig.getCheckpointInterval();
        if (checkpointInterval == null || checkpointInterval <= 0) {
            checkpointInterval = DEFAULT_CHECKPOINT_INTERVAL_MS;
        }
        envMap.put("checkpoint.interval", checkpointInterval);
    }
}
