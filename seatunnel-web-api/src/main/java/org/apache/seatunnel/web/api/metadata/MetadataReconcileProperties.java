package org.apache.seatunnel.web.api.metadata;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "metadata.reconcile")
public class MetadataReconcileProperties {

    private int batchSize = 20;

    private int leaseSeconds = 60;

    private int maxRetryCount = 4;
}
