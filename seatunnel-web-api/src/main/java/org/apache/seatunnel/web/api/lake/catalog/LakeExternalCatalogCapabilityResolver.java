package org.apache.seatunnel.web.api.lake.catalog;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Resolves the safe capability of a source-backed Doris external catalog.
 *
 * <p>Capability is deliberately a configuration decision: this resolver does
 * not parse source credentials and does not open a source connection.  Callers
 * that have performed reachability probes can use the overload accepting the
 * two probe results.  The default overload only answers whether the configured
 * control plane, source shape and verified server driver permit the action.</p>
 */
@Component
public class LakeExternalCatalogCapabilityResolver {

    private final DataSourceDao dataSourceDao;
    private final LakeProperties properties;
    private final LakeJdbcDriverRegistry driverRegistry;
    private final LakeJdbcCatalogAdapterRegistry adapterRegistry;

    @Autowired
    public LakeExternalCatalogCapabilityResolver(
            DataSourceDao dataSourceDao,
            LakeProperties properties,
            LakeJdbcDriverRegistry driverRegistry,
            LakeJdbcCatalogAdapterRegistry adapterRegistry) {
        this.dataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
        this.adapterRegistry = Objects.requireNonNull(adapterRegistry, "adapterRegistry");
    }

    /**
     * Resolves static capability without claiming that an unprobed network is
     * reachable.  Network checks are intentionally opt-in on the overload
     * below so a GET capability call remains side-effect free.
     */
    public LakeCatalogCapability resolve(
            Long sourceDataSourceId,
            LakeJdbcAdapterType adapter,
            LakeCatalogScope scope) {
        return resolve(sourceDataSourceId, adapter, scope, true, true);
    }

    public LakeCatalogCapability resolve(
            Long sourceDataSourceId,
            String adapter,
            LakeCatalogScope scope) {
        try {
            return resolve(sourceDataSourceId, LakeJdbcAdapterType.parse(adapter), scope);
        } catch (RuntimeException exception) {
            return disabled(null, List.of(LakeCatalogCapabilityReason.ADAPTER_MISSING));
        }
    }

    /**
     * Resolves capability after the caller supplies bounded reachability probe
     * outcomes.  No probe exception or datasource configuration is copied to
     * the returned object.
     */
    public LakeCatalogCapability resolve(
            Long sourceDataSourceId,
            LakeJdbcAdapterType adapter,
            LakeCatalogScope scope,
            boolean lakeDorisReachable,
            boolean sourceNetworkReachable) {
        DataSource source = null;
        if (sourceDataSourceId != null && sourceDataSourceId > 0) {
            try {
                source = dataSourceDao.queryById(sourceDataSourceId);
            } catch (RuntimeException ignored) {
                // A DAO failure is a safe unavailable capability, not an
                // exception whose message may contain connection details.
            }
        }
        return resolve(source, adapter, scope, lakeDorisReachable, sourceNetworkReachable);
    }

    public LakeCatalogCapability resolve(
            DataSource source,
            LakeJdbcAdapterType adapter,
            LakeCatalogScope scope) {
        return resolve(source, adapter, scope, true, true);
    }

    public LakeCatalogCapability resolve(
            DataSource source,
            LakeJdbcAdapterType adapter,
            LakeCatalogScope scope,
            boolean lakeDorisReachable,
            boolean sourceNetworkReachable) {
        boolean sourceFound = source != null;
        boolean sourceTypeMatches = sourceFound && matchesAdapter(source.getDbType(), adapter);
        boolean sourceEnabled = sourceFound
                && (source.getStatus() == null
                || source.getStatus() == DataSourceLifecycleStatus.ENABLED);
        boolean sourceConfigComplete = sourceFound
                && sourceTypeMatches
                && sourceEnabled
                && StringUtils.isNotBlank(source.getConnectionParams());

        LakeCatalogCapability base = adapterRegistry.capability(
                adapter, driverRegistry, scope, sourceConfigComplete,
                lakeDorisReachable, sourceNetworkReachable);
        List<String> reasons = new ArrayList<>();
        if (!sourceFound) {
            reasons.add(LakeCatalogCapabilityReason.SOURCE_NOT_FOUND);
        } else {
            if (!sourceEnabled) {
                reasons.add(LakeCatalogCapabilityReason.SOURCE_DISABLED);
            }
            if (!sourceTypeMatches) {
                reasons.add(LakeCatalogCapabilityReason.SOURCE_TYPE_MISMATCH);
            }
        }
        reasons.addAll(base.reasonCodes());

        // Adapter availability is registry-driven.  A driver becomes usable
        // only when the server registry reports an enabled, complete and
        // verified entry; do not rely on the Web classpath as proof of
        // availability.
        if (adapter != null && adapter != LakeJdbcAdapterType.MYSQL) {
            LakeJdbcDriverRegistry.DriverStatus status = driverRegistry.status(adapter);
            if (!status.available()) {
                reasons.add(LakeCatalogCapabilityReason.ADAPTER_DISABLED);
            }
        }
        return new LakeCatalogCapability(adapter, base.enabled() && reasons.isEmpty(), reasons);
    }

    private static LakeCatalogCapability disabled(
            LakeJdbcAdapterType adapter, List<String> reasons) {
        return new LakeCatalogCapability(adapter, false, reasons);
    }

    private static boolean matchesAdapter(DbType sourceType, LakeJdbcAdapterType adapter) {
        if (sourceType == null || adapter == null) {
            return false;
        }
        // Generic JDBC sources are accepted only after the execution resolver
        // confirms that their URL/credentials are valid for the requested
        // adapter.  Keeping this check shape-only avoids parsing secrets here.
        if (sourceType == DbType.JDBC) {
            return true;
        }
        return switch (adapter) {
            case MYSQL -> sourceType == DbType.MYSQL;
            case POSTGRESQL -> sourceType == DbType.POSTGRE_SQL;
            case ORACLE -> sourceType == DbType.ORACLE;
        };
    }
}
