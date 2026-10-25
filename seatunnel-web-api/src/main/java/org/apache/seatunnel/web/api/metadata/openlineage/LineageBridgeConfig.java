package org.apache.seatunnel.web.api.metadata.openlineage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Wiring for the OpenLineage lineage bridge. Everything is created only while
 * {@code lineage-bridge.kafka.enabled=true}, so disabled deployments keep zero resources.
 */
@Configuration
@EnableConfigurationProperties(LineageBridgeProperties.class)
public class LineageBridgeConfig {

    /**
     * Independent track for lineage publishing: the Engine has accepted the job when the events
     * are produced, so these round trips must never extend the submission response either.
     */
    @Bean(name = "lineageEventExecutor")
    @ConditionalOnProperty(prefix = "lineage-bridge.kafka", name = "enabled", havingValue = "true")
    public ThreadPoolTaskExecutor lineageEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("lineage-event-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        // Lineage is best effort: a saturated queue must drop the event instead of throwing back
        // into the event multicaster, which would skip the other listeners of the same status
        // change (the alarm engine, for example).
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        // Spring calls afterPropertiesSet() -> initialize(); doing it here would create a second
        // executor and leak the first one.
        return executor;
    }

    /**
     * Creates the lineage topic when the broker does not have it yet. A broker with automatic
     * topic creation turned off would otherwise drop every lineage event.
     */
    @Bean
    @ConditionalOnProperty(prefix = "lineage-bridge.kafka", name = "enabled", havingValue = "true")
    public OpenLineageTopicProvisioner openLineageTopicProvisioner(
            LineageBridgeProperties properties,
            @Qualifier("lineageEventExecutor") Executor executor) {
        // The startup check runs on the lineage executor: an unreachable broker must not delay
        // the start of the application.
        return OpenLineageTopicProvisioner.create(properties, executor);
    }
}
