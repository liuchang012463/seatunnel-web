package org.apache.seatunnel.web.api.lake.catalog;

import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Registry and capability facade for the fixed JDBC source adapters. */
@Component
public final class LakeJdbcCatalogAdapterRegistry {

    private final Map<LakeJdbcAdapterType, LakeJdbcCatalogAdapter> adapters;

    /** Spring wiring discovers only the three fixed server-side adapters. */
    @Autowired
    public LakeJdbcCatalogAdapterRegistry(Collection<LakeJdbcCatalogAdapter> adapters) {
        EnumMap<LakeJdbcAdapterType, LakeJdbcCatalogAdapter> values =
                new EnumMap<>(LakeJdbcAdapterType.class);
        if (adapters != null) {
            for (LakeJdbcCatalogAdapter adapter : adapters) {
                if (adapter != null && adapter.type() != null) {
                    values.put(adapter.type(), adapter);
                }
            }
        }
        this.adapters = Map.copyOf(values);
    }

    /** Small non-Spring constructor for tests and command-line tools. */
    public LakeJdbcCatalogAdapterRegistry() {
        this(List.of(
                new MysqlLakeJdbcCatalogAdapter(),
                new PostgresqlLakeJdbcCatalogAdapter(),
                new OracleLakeJdbcCatalogAdapter()));
    }

    public Optional<LakeJdbcCatalogAdapter> find(LakeJdbcAdapterType type) {
        return Optional.ofNullable(adapters.get(type));
    }

    public Optional<LakeJdbcCatalogAdapter> find(String type) {
        try {
            return find(LakeJdbcAdapterType.parse(type));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public LakeJdbcCatalogAdapter require(LakeJdbcAdapterType type) {
        return find(type).orElseThrow(() -> new IllegalArgumentException(
                "JDBC catalog adapter is not registered"));
    }

    /**
     * Evaluates all checks needed before exposing logical catalog actions.
     * Driver status is computed from server configuration, never request data.
     */
    public LakeCatalogCapability capability(
            LakeJdbcAdapterType type,
            LakeJdbcDriverRegistry driverRegistry,
            LakeCatalogScope scope,
            boolean sourceConfigComplete,
            boolean lakeDorisReachable,
            boolean sourceNetworkReachable) {
        List<String> reasons = new ArrayList<>();
        LakeJdbcCatalogAdapter adapter = adapters.get(type);
        if (adapter == null) {
            reasons.add(LakeCatalogCapabilityReason.ADAPTER_MISSING);
        } else if (!adapter.supportsScope(scope)) {
            reasons.add(LakeCatalogCapabilityReason.SCOPE_UNSUPPORTED);
        }
        LakeJdbcDriverRegistry.DriverStatus driverStatus = driverRegistry == null || type == null
                ? null : driverRegistry.status(type);
        if (driverStatus == null) {
            reasons.add(LakeCatalogCapabilityReason.DRIVER_CONFIG_MISSING);
        } else {
            reasons.addAll(driverStatus.reasonCodes());
        }
        if (!sourceConfigComplete) {
            reasons.add(LakeCatalogCapabilityReason.SOURCE_CONFIG_INCOMPLETE);
        }
        if (!lakeDorisReachable) {
            reasons.add(LakeCatalogCapabilityReason.LAKE_DORIS_UNREACHABLE);
        }
        if (!sourceNetworkReachable) {
            reasons.add(LakeCatalogCapabilityReason.SOURCE_NETWORK_UNREACHABLE);
        }
        return new LakeCatalogCapability(type, reasons.isEmpty(), reasons);
    }

    public LakeCatalogCapability capability(
            String type,
            LakeJdbcDriverRegistry driverRegistry,
            LakeCatalogScope scope,
            boolean sourceConfigComplete,
            boolean lakeDorisReachable,
            boolean sourceNetworkReachable) {
        LakeJdbcAdapterType parsed;
        try {
            parsed = LakeJdbcAdapterType.parse(type);
        } catch (IllegalArgumentException exception) {
            return new LakeCatalogCapability(
                    null,
                    false,
                    List.of(LakeCatalogCapabilityReason.ADAPTER_MISSING));
        }
        return capability(parsed, driverRegistry, scope, sourceConfigComplete,
                lakeDorisReachable, sourceNetworkReachable);
    }
}
