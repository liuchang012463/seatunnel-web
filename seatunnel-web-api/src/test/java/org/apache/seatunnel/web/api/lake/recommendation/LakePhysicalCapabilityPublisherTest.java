package org.apache.seatunnel.web.api.lake.recommendation;

import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.doris.DorisCapability;
import org.apache.seatunnel.web.api.lake.doris.DorisCapabilityReason;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LakePhysicalCapabilityPublisherTest {

    private static final String CHECKSUM =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void publisherIsAProductionSpringBean() {
        assertTrue(LakePhysicalCapabilityPublisher.class.isAnnotationPresent(Component.class));
    }

    @Test
    void reachableConfiguredDorisPublishesPhysicalCapability() {
        LakeProperties properties = configuredProperties();
        DataSourceDao dataSourceDao = mock(DataSourceDao.class);
        LakeDorisClientProvider provider = mock(LakeDorisClientProvider.class);
        DorisLakeClient client = mock(DorisLakeClient.class);
        when(dataSourceDao.queryById(99L)).thenReturn(dorisSource(99L));
        when(dataSourceDao.queryById(42L)).thenReturn(source(42L, DbType.MYSQL));
        when(provider.get(99L)).thenReturn(client);
        when(client.ping()).thenReturn(true);

        DorisCapability capability = new LakePhysicalCapabilityPublisher(
                properties, dataSourceDao, provider).current(42L);

        assertTrue(capability.isPhysicalSupported());
        assertTrue(capability.getReasons().isEmpty());
        verify(provider).get(99L);
        verify(client).ping();
    }

    @Test
    void unreachableDorisIsPublishedAsStableDisabledCapability() {
        LakeProperties properties = configuredProperties();
        DataSourceDao dataSourceDao = mock(DataSourceDao.class);
        LakeDorisClientProvider provider = mock(LakeDorisClientProvider.class);
        DorisLakeClient client = mock(DorisLakeClient.class);
        when(dataSourceDao.queryById(99L)).thenReturn(dorisSource(99L));
        when(dataSourceDao.queryById(42L)).thenReturn(source(42L, DbType.MYSQL));
        when(provider.get(99L)).thenReturn(client);
        when(client.ping()).thenReturn(false);

        DorisCapability capability = new LakePhysicalCapabilityPublisher(
                properties, dataSourceDao, provider).current(42L);

        assertFalse(capability.isPhysicalSupported());
        assertTrue(capability.getReasons().contains(
                DorisCapabilityReason.LAKE_DORIS_UNREACHABLE));
    }

    @Test
    void incompleteServerConfigurationDoesNotProbeDoris() {
        LakeProperties properties = configuredProperties();
        properties.setDriverChecksum(null);
        DataSourceDao dataSourceDao = mock(DataSourceDao.class);
        LakeDorisClientProvider provider = mock(LakeDorisClientProvider.class);
        when(dataSourceDao.queryById(99L)).thenReturn(dorisSource(99L));
        when(dataSourceDao.queryById(42L)).thenReturn(source(42L, DbType.MYSQL));

        DorisCapability capability = new LakePhysicalCapabilityPublisher(
                properties, dataSourceDao, provider).current(42L);

        assertFalse(capability.isPhysicalSupported());
        assertTrue(capability.getReasons().contains(
                DorisCapabilityReason.DRIVER_CHECKSUM_MISSING));
        verifyNoInteractions(provider);
    }

    @Test
    void missingRequestSourceCannotBeHiddenByAReachableLakeDoris() {
        LakeProperties properties = configuredProperties();
        DataSourceDao dataSourceDao = mock(DataSourceDao.class);
        LakeDorisClientProvider provider = mock(LakeDorisClientProvider.class);
        DorisLakeClient client = mock(DorisLakeClient.class);
        when(dataSourceDao.queryById(99L)).thenReturn(dorisSource(99L));
        when(provider.get(99L)).thenReturn(client);
        when(client.ping()).thenReturn(true);

        DorisCapability capability = new LakePhysicalCapabilityPublisher(
                properties, dataSourceDao, provider).current(42L);

        assertFalse(capability.isPhysicalSupported());
        assertTrue(capability.getReasons().contains(
                DorisCapabilityReason.SOURCE_CONFIG_INCOMPLETE));
        verifyNoInteractions(provider);
    }

    private static LakeProperties configuredProperties() {
        LakeProperties properties = new LakeProperties();
        properties.setEnabled(true);
        properties.setDataSourceId(99L);
        properties.setDriverUrl("file:/opt/drivers/doris.jar");
        properties.setDriverClass("com.mysql.cj.jdbc.Driver");
        properties.setDriverChecksum(CHECKSUM);
        return properties;
    }

    private static DataSource dorisSource(Long id) {
        return source(id, DbType.DORIS);
    }

    private static DataSource source(Long id, DbType dbType) {
        DataSource source = new DataSource();
        source.setId(id);
        source.setDbType(dbType);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        source.setConnectionParams("{\"url\":\"jdbc:mysql://doris.example/lake\"}");
        return source;
    }
}
