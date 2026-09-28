package org.apache.seatunnel.web.core.builder.env;

import org.apache.seatunnel.web.spi.bean.dto.config.JobEnvConfig;

import java.util.Map;

public interface EnvConfigExtender {

    boolean supports(JobEnvConfig envConfig);

    void fill(Map<String, Object> envMap, JobEnvConfig envConfig);
}
