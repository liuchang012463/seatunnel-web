package org.apache.seatunnel.web.api.config;

import org.apache.seatunnel.web.api.metadata.OpenMetadataProperties;
import org.apache.seatunnel.web.api.metadata.MetadataReconcileProperties;
import org.apache.seatunnel.web.api.metadata.MetadataStatusProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({OpenMetadataProperties.class, MetadataReconcileProperties.class, MetadataStatusProperties.class})
public class OpenMetadataConfig {
}
