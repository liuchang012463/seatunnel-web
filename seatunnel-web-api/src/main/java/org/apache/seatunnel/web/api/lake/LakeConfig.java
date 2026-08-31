package org.apache.seatunnel.web.api.lake;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the opt-in lake control-plane configuration. */
@Configuration
@EnableConfigurationProperties(LakeProperties.class)
public class LakeConfig {
}
