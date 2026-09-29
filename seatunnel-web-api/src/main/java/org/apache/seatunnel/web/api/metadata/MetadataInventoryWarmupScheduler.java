package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.spi.bean.dto.DataInventoryFilterDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the unfiltered data-inventory snapshot warm.  The snapshot refresh is
 * stale-while-revalidate, so this touch never blocks the scheduler thread on
 * a cold OpenMetadata walk longer than once per deploy and the exploration
 * dashboards always read a seconds-old aggregate.
 */
@Slf4j
@Component
public class MetadataInventoryWarmupScheduler {

    private final OpenMetadataConfigResolver configResolver;
    private final DataInventoryService dataInventoryService;
    private final ExecutorService warmExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "metadata-inventory-warmup");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public MetadataInventoryWarmupScheduler(
            OpenMetadataConfigResolver configResolver, DataInventoryService dataInventoryService) {
        this.configResolver = configResolver;
        this.dataInventoryService = dataInventoryService;
    }

    @Scheduled(fixedDelayString = "${metadata.inventory.warm-interval-ms:60000}")
    public void warm() {
        if (!configResolver.isEnabled()) {
            return;
        }
        // A missing snapshot still needs a full first build; run it off the
        // shared scheduler thread so the status loop never waits for it.
        warmExecutor.submit(() -> {
            try {
                // A fresh snapshot returns immediately; a stale one is
                // rebuilt in the cache's background executor and this call
                // returns the stale payload without waiting.
                dataInventoryService.summary(new DataInventoryFilterDTO());
            } catch (Exception error) {
                log.warn("Inventory snapshot warmup failed: type={}, message={}",
                        error.getClass().getSimpleName(), error.getMessage());
            }
        });
    }
}
