package org.apache.seatunnel.web.api.lake.source;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.api.metadata.OpenMetadataProperties;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakeSourceObjectResolverTest {

    @Mock private DataSourceDao dataSourceDao;
    @Mock private MetadataBindingDao metadataBindingDao;
    @Mock private OpenMetadataClient openMetadataClient;

    @Test
    void resolvesOwnedUuidAndProducesSourceBaseline() {
        DataSource source = new DataSource();
        source.setId(7L);
        source.setDbType(DbType.MYSQL);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.READY);
        binding.setOmServiceFqn("st_ds_7");
        OpenMetadataTable table = table("table-1", "st_ds_7.orders.public.orders");
        when(dataSourceDao.queryById(7L)).thenReturn(source);
        when(metadataBindingDao.queryByDataSourceId(7L)).thenReturn(binding);
        when(openMetadataClient.getTable("table-1")).thenReturn(table);
        LakeSourceObjectResolver resolver = new LakeSourceObjectResolver(
                dataSourceDao, metadataBindingDao, openMetadataClient, enabledProperties());

        SourceObjectSnapshot snapshot = resolver.resolve(7L, "table-1");

        assertEquals("table-1", snapshot.omEntityId());
        assertEquals("st_ds_7.orders.public.orders", snapshot.omFqn());
        assertEquals(64, snapshot.sourceSchemaHash().length());
    }

    @Test
    void distinguishesMissingUuidFromUnavailableOpenMetadata() {
        when(dataSourceDao.queryById(7L)).thenReturn(source());
        when(metadataBindingDao.queryByDataSourceId(7L)).thenReturn(binding());
        when(openMetadataClient.getTable("missing")).thenReturn(null);
        LakeSourceObjectResolver resolver = resolver();

        LakeServiceException missing = assertThrows(LakeServiceException.class,
                () -> resolver.resolve(7L, "missing"));
        assertEquals(LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING, missing.getLakeErrorCode());

        doThrow(new MetadataIntegrationException(
                org.apache.seatunnel.web.api.metadata.MetadataErrorCode.OM_CONNECTION_ERROR,
                "remote secret must not be copied"))
                .when(openMetadataClient).assertFixedVersion();
        LakeServiceException unknown = assertThrows(LakeServiceException.class,
                () -> resolver.resolve(7L, "remote"));
        assertEquals(LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN, unknown.getLakeErrorCode());
        assertEquals(null, unknown.getCause());
    }

    @Test
    void rejectsTableOwnedByAnotherOmServiceAsMissing() {
        when(dataSourceDao.queryById(7L)).thenReturn(source());
        when(metadataBindingDao.queryByDataSourceId(7L)).thenReturn(binding());
        when(openMetadataClient.getTable("table-1"))
                .thenReturn(table("table-1", "other.orders.public.orders"));
        LakeServiceException exception = assertThrows(LakeServiceException.class,
                () -> resolver().resolve(7L, "table-1"));
        assertEquals(LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING, exception.getLakeErrorCode());
    }

    private LakeSourceObjectResolver resolver() {
        return new LakeSourceObjectResolver(
                dataSourceDao, metadataBindingDao, openMetadataClient, enabledProperties());
    }

    private static DataSource source() {
        DataSource source = new DataSource();
        source.setId(7L);
        source.setDbType(DbType.MYSQL);
        source.setStatus(DataSourceLifecycleStatus.ENABLED);
        return source;
    }

    private static MetadataSourceBinding binding() {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setDesiredState(MetadataDesiredState.ACTIVE);
        binding.setSyncStatus(MetadataSyncStatus.READY);
        binding.setOmServiceFqn("st_ds_7");
        return binding;
    }

    private static OpenMetadataProperties enabledProperties() {
        OpenMetadataProperties properties = new OpenMetadataProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static OpenMetadataTable table(String id, String fqn) {
        OpenMetadataTable table = new OpenMetadataTable();
        table.setId(id);
        table.setFullyQualifiedName(fqn);
        table.setServiceFullyQualifiedName(fqn.substring(0, fqn.indexOf('.')));
        return table;
    }
}
