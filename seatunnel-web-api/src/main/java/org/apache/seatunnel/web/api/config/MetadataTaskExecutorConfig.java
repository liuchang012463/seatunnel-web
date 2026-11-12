package org.apache.seatunnel.web.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Bounded executor for OpenMetadata operations initiated by a user action. */
@Configuration
public class MetadataTaskExecutorConfig {

    @Bean(name = "metadataExplorationExecutor")
    public ThreadPoolTaskExecutor metadataExplorationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("metadata-exploration-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        // Spring calls afterPropertiesSet() -> initialize(); doing it here would create a second
        // executor and leak the first one.
        return executor;
    }

    /** Isolates blocking LLM calls from the metadata-pipeline operation workers. */
    @Bean(name = "metadataCompletionExecutor")
    public ThreadPoolTaskExecutor metadataCompletionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("metadata-completion-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
