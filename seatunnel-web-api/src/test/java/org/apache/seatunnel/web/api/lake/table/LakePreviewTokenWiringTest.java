package org.apache.seatunnel.web.api.lake.table;

import org.apache.seatunnel.web.api.lake.LakeConfig;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the plan-fingerprint component is present in the production graph. */
class LakePreviewTokenWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Wiring.class);

    @Test
    void alwaysOnLakeRegistersPreviewTokenService() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LakePreviewTokenService.class);
            assertThat(context).hasSingleBean(org.apache.seatunnel.web.api.lake.LakeProperties.class);
            assertThat(context.getBean(org.apache.seatunnel.web.api.lake.LakeProperties.class)
                    .isEnabled()).isTrue();
        });
    }

    @Test
    void removedLakePropertiesAreIgnoredAndDoNotRequireASecret() {
        contextRunner.withPropertyValues("seatunnel.lake.enabled=false",
                        "seatunnel.lake.preview-token-secret=ignored")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LakeProperties.class).isEnabled()).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({LakeConfig.class, LakePreviewTokenService.class})
    static class Wiring {
    }
}
