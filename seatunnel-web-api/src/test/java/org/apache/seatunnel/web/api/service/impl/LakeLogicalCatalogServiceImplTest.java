package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCapability;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCapabilityReason;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogDesiredSpec;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCredentialRevisionService;
import org.apache.seatunnel.web.api.lake.catalog.LakeExternalCatalogCapabilityResolver;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcCatalogDdlBuilder;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcDriverRegistry;
import org.apache.seatunnel.web.api.lake.catalog.LakeLogicalCapabilityVO;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationExecution;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationHandle;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceOperationCoordinator;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogOperationResult;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogValidationResult;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogValidationStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationType;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogPageDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeExternalCatalogVO;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
                LakeCatalogCapabilityReason.SOURCE_NETWORK_UNKNOWN));
        verify(client).ping();
        verify(resolver).resolve(7L, LakeJdbcAdapterType.MYSQL,
                LakeCatalogScope.ALL, true, false);
    }

    @Test
    void explicitProbeRunsFromDorisAndPublishesReachableObservation() {
        TestContext context = new TestContext();
        doNothing().when(context.client).probeSource(
                any(LakeCatalogDesiredSpec.class), eq(context.driverRegistry),
                any(LakeJdbcCatalogDdlBuilder.CatalogCredentials.class));

        LakeLogicalCapabilityVO result = context.service.probe(
                7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.ALL);

        assertTrue(result.isSupported());
        assertTrue(result.isSourceNetworkReachabilityKnown());
        assertTrue(result.isSourceNetworkReachable());
        assertTrue(result.isLakeDorisReachable());
        verify(context.client).probeSource(any(LakeCatalogDesiredSpec.class),
                eq(context.driverRegistry), any(LakeJdbcCatalogDdlBuilder.CatalogCredentials.class));
    }

    @Test
    void failedExternalProbeIsARealNegativeObservationAndIsNotSetupUnknown() {
        TestContext context = new TestContext();
        when(context.resolver.resolve(eq(7L), eq(LakeJdbcAdapterType.MYSQL),
                eq(LakeCatalogScope.ALL), eq(true), eq(false)))
                .thenReturn(new LakeCatalogCapability(
                        LakeJdbcAdapterType.MYSQL, false,
                        List.of(LakeCatalogCapabilityReason.SOURCE_NETWORK_UNREACHABLE)));
        doThrow(new IllegalStateException("source unavailable")).when(context.client).probeSource(
                any(LakeCatalogDesiredSpec.class), eq(context.driverRegistry),
                any(LakeJdbcCatalogDdlBuilder.CatalogCredentials.class));

        LakeLogicalCapabilityVO result = context.service.probe(
                7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.ALL);

        assertFalse(result.isSupported());
        assertTrue(result.isSourceNetworkReachabilityKnown());
        assertFalse(result.isSourceNetworkReachable());
        assertTrue(result.getReasonCodes().contains(
                LakeCatalogCapabilityReason.SOURCE_NETWORK_UNREACHABLE));
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

    @Test
    void createUsesExecutionCredentialsAndPublishesValidatedDesiredState() {
        TestContext context = new TestContext();
        LakeExternalCatalogCreateDTO request = request();

        LakeExternalCatalogVO pending = binding(77L, LakeResourceStatus.PENDING_CREATE);
        LakeExternalCatalogVO ready = binding(77L, LakeResourceStatus.READY);
        when(context.persistence.createPending(any(), eq(42))).thenReturn(pending);
        when(context.persistence.detail(77L)).thenReturn(ready);
        when(context.client.ping()).thenReturn(true);
        when(context.client.catalogExists("orders_catalog")).thenReturn(true);
        when(context.client.validateCatalog(eq("orders_catalog"),
                any(LakeCatalogDesiredSpec.class)))
                .thenReturn(LakeCatalogValidationResult.match(Map.of("type", "jdbc")));

        LakeExternalCatalogVO result = context.service.create(request);

        org.junit.jupiter.api.Assertions.assertSame(ready, result);
        verify(context.client).createCatalog(any(LakeCatalogDesiredSpec.class),
                eq(context.driverRegistry), any(LakeJdbcCatalogDdlBuilder.CatalogCredentials.class));
        verify(context.coordinator).begin(any());
        verify(context.coordinator).finalizeSuccess(
                eq(context.handle), eq("Catalog created and validated"), any());
        org.junit.jupiter.api.Assertions.assertFalse(request.getOptions().containsKey("password"));
    }

    @Test
    void validateReadsThroughDorisAndPublishesSafeActualSnapshot() {
        TestContext context = new TestContext();
        LakeExternalCatalogVO binding = binding(77L, LakeResourceStatus.READY);
        LakeCatalogDesiredSpec desired = desired(context);
        when(context.persistence.detail(77L)).thenReturn(binding);
        when(context.persistence.desiredSpec(77L)).thenReturn(desired);
        when(context.persistence.detail(77L)).thenReturn(binding);
        when(context.client.ping()).thenReturn(true);
        when(context.client.validateCatalog("orders_catalog", desired))
                .thenReturn(new LakeCatalogValidationResult(
                        LakeCatalogValidationStatus.MATCH,
                        "LAKE_CATALOG_MATCH",
                        Map.of("type", "jdbc", "password", "secret"),
                        Map.of()));

        LakeExternalCatalogVO result = context.service.validate(77L);

        org.junit.jupiter.api.Assertions.assertSame(binding, result);
        verify(context.client).validateCatalog("orders_catalog", desired);
        verify(context.coordinator).finalizeSuccess(
                eq(context.handle), eq("Catalog validation completed"), any());
    }

    private static LakeExternalCatalogCreateDTO request() {
        LakeExternalCatalogCreateDTO request = new LakeExternalCatalogCreateDTO();
        request.setLakeDataSourceId(99L);
        request.setSourceDataSourceId(7L);
        request.setTargetCatalogName("orders_catalog");
        request.setAdapter("MYSQL");
        request.setScope(LakeCatalogScope.ALL);
        return request;
    }

    private static LakeExternalCatalogVO binding(Long id, LakeResourceStatus status) {
        LakeExternalCatalogVO value = new LakeExternalCatalogVO();
        value.setId(id);
        value.setLakeDataSourceId(99L);
        value.setSourceDataSourceId(7L);
        value.setTargetCatalogName("orders_catalog");
        value.setAdapter("MYSQL");
        value.setScope(LakeCatalogScope.ALL);
        value.setResourceStatus(status);
        value.setLockVersion(2);
        value.setGeneration(1);
        return value;
    }

    private static LakeCatalogDesiredSpec desired(TestContext context) {
        return new LakeCatalogDesiredSpec(
                "orders_catalog", 7L, "datasource-7", LakeJdbcAdapterType.MYSQL,
                LakeCatalogScope.ALL, "jdbc:mysql://db/app", "file:///mysql.jar",
                "com.mysql.cj.jdbc.Driver", context.checksum, "registry-v1",
                "credential-v1", List.of(), List.of(), Map.of());
    }

    private static final class TestContext {
        private final LakeProperties properties = properties();
        private final LakeJdbcDriverRegistry driverRegistry =
                new LakeJdbcDriverRegistry(properties);
        private final DataSourceDao dataSourceDao = mock(DataSourceDao.class);
        private final LakeExternalCatalogBindingPersistenceService persistence =
                mock(LakeExternalCatalogBindingPersistenceService.class);
        private final LakeExternalCatalogCapabilityResolver resolver =
                mock(LakeExternalCatalogCapabilityResolver.class);
        private final LakeDorisClientProvider provider = mock(LakeDorisClientProvider.class);
        private final DorisLakeClient client = mock(DorisLakeClient.class);
        private final LakeResourceOperationCoordinator coordinator =
                mock(LakeResourceOperationCoordinator.class);
        private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        private final LakeCatalogCredentialRevisionService credentials =
                new LakeCatalogCredentialRevisionService(properties, ignored -> {
                    BaseConnectionParam param = new BaseConnectionParam() {
                    };
                    param.setUrl("jdbc:mysql://db/app");
                    param.setUser("reader");
                    param.setPassword("secret");
                    return param;
                });
        private final LakeOperationHandle handle = new LakeOperationHandle(
                1L, "EXTERNAL_CATALOG_BINDING", 77L, 1, "operation-token", 2);
        private final String checksum =
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        private final LakeLogicalCatalogServiceImpl service;

        private TestContext() {
            DataSource source = new DataSource();
            source.setId(7L);
            source.setDbType(DbType.MYSQL);
            source.setConnectionParams("{}");
            when(dataSourceDao.queryById(7L)).thenReturn(source);
            when(client.ping()).thenReturn(true);
            when(resolver.resolve(eq(7L), eq(LakeJdbcAdapterType.MYSQL),
                    eq(LakeCatalogScope.ALL), eq(true), eq(true)))
                    .thenReturn(new LakeCatalogCapability(
                            LakeJdbcAdapterType.MYSQL, true, List.of()));
            when(provider.get(99L)).thenReturn(client);
            when(currentUserProvider.getCurrentUserId()).thenReturn(42);
            when(coordinator.begin(any())).thenReturn(handle);
            doAnswer(invocation -> new LakeOperationExecution<>(
                    handle,
                    ((org.apache.seatunnel.web.api.lake.operation.LakeExternalOperation<LakeCatalogOperationResult>)
                            invocation.getArgument(1)).execute()))
                    .when(coordinator).execute(eq(handle), any());
            when(coordinator.finalizeSuccess(eq(handle), any(String.class), any())).thenReturn(true);
            service = new LakeLogicalCatalogServiceImpl(
                    dataSourceDao, properties, resolver, provider, persistence,
                    driverRegistry, credentials, coordinator, currentUserProvider);
        }

        private static LakeProperties properties() {
            LakeProperties properties = new LakeProperties();
            properties.setEnabled(true);
            properties.setDataSourceId(99L);
            properties.setCatalogCredentialSecret("catalog-secret");
            LakeProperties.JdbcCatalog catalog = new LakeProperties.JdbcCatalog();
            catalog.setRegistryRevision("registry-v1");
            LakeProperties.Driver mysql = new LakeProperties.Driver();
            mysql.setEnabled(true);
            mysql.setVerified(true);
            mysql.setUrl("file:///mysql.jar");
            mysql.setDriverClass("com.mysql.cj.jdbc.Driver");
            mysql.setChecksum(
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
            catalog.setMysql(mysql);
            properties.setJdbcCatalog(catalog);
            return properties;
        }
    }
}
