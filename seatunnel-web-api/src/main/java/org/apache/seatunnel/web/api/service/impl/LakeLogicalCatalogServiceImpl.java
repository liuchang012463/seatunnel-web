package org.apache.seatunnel.web.api.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCapability;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogCapabilityReason;
import org.apache.seatunnel.web.api.lake.catalog.LakeExternalCatalogCapabilityResolver;
import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.catalog.LakeLogicalCapabilityVO;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.service.LakeLogicalCatalogService;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogPageDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeExternalCatalogVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only logical catalog facade.
 *
 * <p>The capability endpoint performs only a bounded Doris ping.  It does not
 * probe the source database from the Web process, so source reachability is
 * explicitly unknown and can never make the response claim support.</p>
 */
@Service
public class LakeLogicalCatalogServiceImpl implements LakeLogicalCatalogService {

    private final DataSourceDao dataSourceDao;
    private final LakeProperties lakeProperties;
    private final LakeExternalCatalogCapabilityResolver capabilityResolver;
    private final LakeDorisClientProvider dorisClientProvider;
    private final LakeExternalCatalogBindingPersistenceService persistenceService;

    @Autowired
    public LakeLogicalCatalogServiceImpl(
            DataSourceDao dataSourceDao,
            LakeProperties lakeProperties,
            LakeExternalCatalogCapabilityResolver capabilityResolver,
            LakeDorisClientProvider dorisClientProvider,
            LakeExternalCatalogBindingPersistenceService persistenceService) {
        this.dataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.lakeProperties = Objects.requireNonNull(lakeProperties, "lakeProperties");
        this.capabilityResolver = Objects.requireNonNull(capabilityResolver, "capabilityResolver");
        this.dorisClientProvider = Objects.requireNonNull(dorisClientProvider, "dorisClientProvider");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService");
    }

    @Override
    public LakeLogicalCapabilityVO capability(Long sourceDataSourceId) {
        LakeJdbcAdapterType adapter = adapterFor(sourceDataSourceId);
        return capability(sourceDataSourceId, adapter, LakeCatalogScope.ALL);
    }

    @Override
    public LakeLogicalCapabilityVO capability(
            Long sourceDataSourceId, LakeJdbcAdapterType adapter, LakeCatalogScope scope) {
        LakeCatalogScope requestedScope = scope == null ? LakeCatalogScope.ALL : scope;
        boolean lakeDorisReachable = probeLakeDoris();
        // There is no source-side probe in this API.  Passing false is
        // intentional: the resolver must publish SOURCE_NETWORK_UNREACHABLE
        // rather than infer network support from local configuration.
        LakeCatalogCapability capability = capabilityResolver.resolve(
                sourceDataSourceId, adapter, requestedScope, lakeDorisReachable, false);
        List<String> reasons = capability == null || capability.reasonCodes() == null
                ? List.of(LakeCatalogCapabilityReason.ADAPTER_MISSING)
                : capability.reasonCodes();
        // Keep the source-network unknown state visible even if a custom
        // resolver implementation omits the stable reason code.
        if (!containsIgnoreCase(reasons, LakeCatalogCapabilityReason.SOURCE_NETWORK_UNREACHABLE)) {
            reasons = append(reasons, LakeCatalogCapabilityReason.SOURCE_NETWORK_UNREACHABLE);
        }
        // Source reachability is intentionally unprobed, therefore this
        // endpoint must remain disabled even when static checks and the lake
        // ping succeed.
        boolean supported = false;
        return new LakeLogicalCapabilityVO(
                sourceDataSourceId,
                adapter,
                requestedScope,
                supported,
                false,
                lakeDorisReachable,
                reasons);
    }

    @Override
    public PaginationResult<LakeExternalCatalogVO> page(LakeExternalCatalogPageDTO request) {
        return persistenceService.page(request);
    }

    @Override
    public LakeExternalCatalogVO detail(Long bindingId) {
        return persistenceService.detail(bindingId);
    }

    private LakeJdbcAdapterType adapterFor(Long sourceDataSourceId) {
        if (sourceDataSourceId == null || sourceDataSourceId <= 0) {
            return null;
        }
        try {
            DataSource source = dataSourceDao.queryById(sourceDataSourceId);
            return source == null || source.getDbType() == null
                    ? null : adapterFor(source.getDbType().name());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static LakeJdbcAdapterType adapterFor(String dbType) {
        if (StringUtils.isBlank(dbType)) {
            return null;
        }
        try {
            return LakeJdbcAdapterType.parse(dbType);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean probeLakeDoris() {
        if (!lakeProperties.isEnabled()
                || lakeProperties.getDataSourceId() == null
                || lakeProperties.getDataSourceId() <= 0) {
            return false;
        }
        try (DorisLakeClient client = dorisClientProvider.get(lakeProperties.getDataSourceId())) {
            return client.ping();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static List<String> append(List<String> source, String value) {
        Set<String> values = new LinkedHashSet<>();
        if (source != null) {
            values.addAll(source);
        }
        values.add(value);
        return List.copyOf(values);
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        return values != null && values.stream().anyMatch(value -> expected.equalsIgnoreCase(value));
    }
}
