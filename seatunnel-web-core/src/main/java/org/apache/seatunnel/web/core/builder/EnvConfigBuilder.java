package org.apache.seatunnel.web.core.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import org.apache.seatunnel.web.core.builder.env.EnvConfigExtender;
import org.apache.seatunnel.web.spi.bean.dto.config.JobEnvConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EnvConfigBuilder {

    private final List<EnvConfigExtender> extenders;

    public EnvConfigBuilder(List<EnvConfigExtender> extenders) {
        this.extenders = extenders;
    }

    public String build(JobEnvConfig envConfig) {
        if (envConfig == null) {
            throw new IllegalArgumentException("JobEnvConfig cannot be null");
        }

        Map<String, Object> envMap = new LinkedHashMap<>();

        fillCommonConfig(envMap, envConfig);
        fillExtConfig(envMap, envConfig);

        Config cfg = ConfigFactory.parseMap(envMap);
        return cfg.root().render(
                ConfigRenderOptions.defaults()
                        .setJson(false)
                        .setComments(false)
                        .setOriginComments(false)
        );
    }

    private void fillCommonConfig(Map<String, Object> envMap, JobEnvConfig envConfig) {
        if (envConfig.getJobMode() != null) {
            envMap.put("job.mode", envConfig.getJobMode().getCode());
        }

        if (envConfig.getParallelism() != null && envConfig.getParallelism() > 0) {
            envMap.put("parallelism", envConfig.getParallelism());
        }

        if (envConfig.getReadLimitBytesPerSecond() != null
                && envConfig.getReadLimitBytesPerSecond() > 0) {
            envMap.put("read_limit.bytes_per_second", envConfig.getReadLimitBytesPerSecond());
        }
        if (envConfig.getReadLimitRowsPerSecond() != null
                && envConfig.getReadLimitRowsPerSecond() > 0) {
            envMap.put("read_limit.rows_per_second", envConfig.getReadLimitRowsPerSecond());
        }
        // do NOT put priority into engine envMap
    }

    private void fillExtConfig(Map<String, Object> envMap, JobEnvConfig envConfig) {
        if (extenders == null || extenders.isEmpty()) {
            return;
        }

        for (EnvConfigExtender extender : extenders) {
            if (extender.supports(envConfig)) {
                extender.fill(envMap, envConfig);
            }
        }
    }
}