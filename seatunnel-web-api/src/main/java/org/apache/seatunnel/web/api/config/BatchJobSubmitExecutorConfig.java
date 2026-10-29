package org.apache.seatunnel.web.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded executor shared by batch and streaming Engine submissions. */
@Configuration
public class BatchJobSubmitExecutorConfig {

    @Bean(name = {"jobSubmitExecutor", "batchJobSubmitExecutor"})
    public ThreadPoolTaskExecutor batchJobSubmitExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("job-submit-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
