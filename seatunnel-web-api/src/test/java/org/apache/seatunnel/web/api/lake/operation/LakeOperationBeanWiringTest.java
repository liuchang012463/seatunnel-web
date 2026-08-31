package org.apache.seatunnel.web.api.lake.operation;

import org.apache.seatunnel.web.api.lake.LakeConfig;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.dao.repository.LakeExternalCatalogBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeResourceOperationDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Checks the production component graph without enabling lake business operations. */
class LakeOperationBeanWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Wiring.class)
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(LakeResourceOperationDao.class, () -> mock(LakeResourceOperationDao.class))
            .withBean(LakeSourceObjectRefDao.class, () -> mock(LakeSourceObjectRefDao.class))
            .withBean(LakeOdsDatabaseBindingDao.class, () -> mock(LakeOdsDatabaseBindingDao.class))
            .withBean(LakeOdsTableMappingDao.class, () -> mock(LakeOdsTableMappingDao.class))
            .withBean(LakeExternalCatalogBindingDao.class, () -> mock(LakeExternalCatalogBindingDao.class));

    @Test
    void registersOneGatewayAndCoordinatorEvenWhenLakeIsDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LakeProperties.class);
            assertThat(context).hasSingleBean(DaoLakeResourceGateway.class);
            assertThat(context).hasSingleBean(SpringLakeOperationTransactionBoundary.class);
            assertThat(context).hasSingleBean(LakeResourceOperationCoordinator.class);
            assertThat(context.getBean(LakeProperties.class).isEnabled()).isFalse();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            LakeConfig.class,
            DaoLakeResourceGateway.class,
            SpringLakeOperationTransactionBoundary.class,
            LakeResourceOperationCoordinator.class
    })
    static class Wiring {
    }
}
