package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps list-page status reads local; it never runs because a user opened the page. */
@Slf4j
@Component
public class MetadataStatusScheduler {

    private final OpenMetadataProperties openMetadataProperties;
    private final MetadataStatusSynchronizer synchronizer;

    public MetadataStatusScheduler(
            OpenMetadataProperties openMetadataProperties, MetadataStatusSynchronizer synchronizer) {
        this.openMetadataProperties = openMetadataProperties;
        this.synchronizer = synchronizer;
    }

    @Scheduled(fixedDelayString = "${metadata.status.interval-ms:10000}")
    public void run() {
        if (!openMetadataProperties.isEnabled()) {
            return;
        }
        try {
            synchronizer.refreshStatuses();
        } catch (Exception e) {
            log.warn("Metadata status loop failed without exposing external credentials: type={}",
                    e.getClass().getSimpleName());
        }
    }
}
