package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MetadataReconcileScheduler {

    private final OpenMetadataConfigResolver configResolver;
    private final MetadataSourceReconciler reconciler;

    public MetadataReconcileScheduler(
            OpenMetadataConfigResolver configResolver, MetadataSourceReconciler reconciler) {
        this.configResolver = configResolver;
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${metadata.reconcile.interval-ms:20000}")
    public void run() {
        if (!configResolver.isEnabled()) {
            return;
        }
        try {
            reconciler.reconcilePendingBindings();
        } catch (Exception e) {
            log.warn("Metadata reconciliation loop failed without exposing external credentials: type={}",
                    e.getClass().getSimpleName());
        }
    }
}
