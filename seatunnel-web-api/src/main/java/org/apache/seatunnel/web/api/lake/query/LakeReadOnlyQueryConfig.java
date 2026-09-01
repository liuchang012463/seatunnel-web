package org.apache.seatunnel.web.api.lake.query;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers query-only bounds without creating a connection or executor bean. */
@Configuration
@EnableConfigurationProperties(LakeReadOnlyQueryProperties.class)
public class LakeReadOnlyQueryConfig {
}
