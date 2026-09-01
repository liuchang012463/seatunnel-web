package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCapability;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCapabilityReason;
import org.apache.seatunnel.web.api.lake.catalog.LakeExternalCatalogCapabilityResolver;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.catalog.LakeLogicalCapabilityVO;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogPageDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeExternalCatalogVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeLogicalCatalogServiceImplTest {

    @Test
    void capabilityPingsLakeButNeverClaimsUnprobedSourceReachability() {
        DataSourceDao dataSourceDao = mock(DataSourceDao.class);
        DataSource source = new DataSource();
        source.setId(7L);
        source.setDbType(org.apache.seatunnel.web.spi.enums.DbType.MYSQL);
        when(dataSourceDao.queryById(7L)).thenReturn(source);

        LakeExternalCatalogCapabilityResolver resolver = mock(
                LakeExternalCatalogCapabilityResolver.class);
        when(resolver.resolve(eq(7L), eq(LakeJdbcAdapterType.MYSQL),
                eq(LakeCatalogScope.ALL), eq(true), eq(false)))
                .thenReturn(new LakeCatalogCapability(LakeJdbcAdapterType.MYSQL, true, List.of()));

        DorisLakeClient client = mock(DorisLakeClient.class);
        when(client.ping()).thenReturn(true);
        LakeDorisClientProvider provider = mock(LakeDorisClientProvider.class);
        when(provider.get(99L)).thenReturn(client);

        LakeProperties properties = new LakeProperties();
        properties.setEnabled(true);
        properties.setDataSourceId(99L);
        LakeLogicalCatalogServiceImpl service = new LakeLogicalCatalogServiceImpl(
                dataSourceDao, properties, resolver, provider,
                mock(LakeExternalCatalogBindingPersistenceService.class));

        LakeLogicalCapabilityVO result = service.capability(7L);

        assertFalse(result.isSupported());
        assertFalse(result.isSourceNetworkReachabilityKnown());
        assertTrue(result.isLakeDorisReachable());
        assertTrue(result.getReasonCodes().contains(
                LakeCatalogCapabilityReason.SOURCE_NETWORK_UNREACHABLE));
        verify(client).ping();
        verify(resolver).resolve(7L, LakeJdbcAdapterType.MYSQL,
                LakeCatalogScope.ALL, true, false);
    }

    @Test
    void pageAndDetailAreLocalPersistenceReads() {
        LakeExternalCatalogBindingPersistenceService persistence = mock(
                LakeExternalCatalogBindingPersistenceService.class);
        LakeLogicalCatalogServiceImpl service = new LakeLogicalCatalogServiceImpl(
                mock(DataSourceDao.class), new LakeProperties(),
                mock(LakeExternalCatalogCapabilityResolver.class),
                mock(LakeDorisClientProvider.class), persistence);
        LakeExternalCatalogPageDTO request = new LakeExternalCatalogPageDTO();
        PaginationResult<LakeExternalCatalogVO> page = mock(PaginationResult.class);
        LakeExternalCatalogVO detail = new LakeExternalCatalogVO();
        when(persistence.page(request)).thenReturn(page);
        when(persistence.detail(11L)).thenReturn(detail);

        assertSame(page, service.page(request));
        assertSame(detail, service.detail(11L));
        verify(persistence).page(request);
        verify(persistence).detail(11L);
    }
}
