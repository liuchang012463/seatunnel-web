package org.apache.seatunnel.web.api.lake.table;

import org.apache.seatunnel.web.api.lake.LakeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the preview-token component is present in the production graph. */
class LakePreviewTokenWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Wiring.class);

    @Test
    void disabledLakeStillRegistersPreviewTokenService() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LakePreviewTokenService.class);
            assertThat(context).hasSingleBean(org.apache.seatunnel.web.api.lake.LakeProperties.class);
            assertThat(context.getBean(org.apache.seatunnel.web.api.lake.LakeProperties.class)
                    .isEnabled()).isFalse();
        });
    }

    @Test
    void enabledLakeWithoutSecretFailsWithStableConfigurationError() {
        contextRunner.withPropertyValues("seatunnel.lake.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "Lake preview token secret is required when lake control plane is enabled");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({LakeConfig.class, LakePreviewTokenService.class})
    static class Wiring {
    }
}
