package org.apache.seatunnel.web.api.lake;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the always-available lake control-plane runtime limits. */
@Configuration
public class LakeConfig {

    @Bean
    public LakeProperties lakeProperties() {
        // Lake connection, credentials and driver facts come from the
        // warehouse tables.  Keep operational limits as code defaults so no
        // lake-prefixed environment variable can re-enable the removed
        // configuration surface.
        return new LakeProperties();
    }
}
