package org.apache.seatunnel.web.api.lake.recommendation;

import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.doris.DorisCapability;
import org.apache.seatunnel.web.api.lake.doris.DorisCapabilityChecks;
import org.apache.seatunnel.web.api.lake.doris.DorisCapabilityResolver;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Publishes a server-owned, read-only physical capability snapshot.
 *
 * <p>The publisher validates only configuration facts owned by the server and
 * performs at most one bounded {@code SELECT 1} through the existing Doris
 * client provider.  It never accepts request data, opens a source connection,
 * or executes a write.  Failures are reduced to the stable capability reasons
 * emitted by {@link DorisCapabilityResolver}.</p>
 */
@Component
public final class LakePhysicalCapabilityPublisher implements Supplier<DorisCapability> {

    private final LakeProperties properties;
    private final DataSourceDao dataSourceDao;
    private final LakeDorisClientProvider clientProvider;
    private final DorisCapabilityResolver capabilityResolver;

    @Autowired
    public LakePhysicalCapabilityPublisher(
            LakeProperties properties,
            DataSourceDao dataSourceDao,
            LakeDorisClientProvider clientProvider) {
        this(properties, dataSourceDao, clientProvider, new DorisCapabilityResolver());
    }

    /** Visible for focused tests and alternate server-owned capability facades. */
    public LakePhysicalCapabilityPublisher(
            LakeProperties properties,
            DataSourceDao dataSourceDao,
            LakeDorisClientProvider clientProvider,
            DorisCapabilityResolver capabilityResolver) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.dataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.clientProvider = Objects.requireNonNull(clientProvider, "clientProvider");
        this.capabilityResolver = Objects.requireNonNull(capabilityResolver, "capabilityResolver");
    }

    /**
     * Returns a fresh capability snapshot for one request source; no result is
     * cached across requests.  The request source and the configured Doris
     * lake source are deliberately loaded and validated independently.
     */
    public DorisCapability current(Long sourceDataSourceId) {
        boolean adapterExists = properties.isEnabled();
        boolean driverConfigExists = configured(properties.getDriverUrl())
                && configured(properties.getDriverClass());
        boolean driverChecksumConfigured = configured(properties.getDriverChecksum());
        DataSource lakeDataSource = source(properties.getDataSourceId());
        DataSource source = source(sourceDataSourceId);
        boolean lakeConfigComplete = completeDorisSource(lakeDataSource);
        boolean sourceConfigComplete = completeSource(source);

        // Do not create a pool or connect if the server-side configuration is
        // incomplete.  This keeps a malformed/disabled setup bounded and
        // avoids turning a provider exception into a capability claim.
        boolean lakeDorisReachable = false;
        if (adapterExists && driverConfigExists && driverChecksumConfigured
                && lakeConfigComplete && sourceConfigComplete) {
            lakeDorisReachable = pingConfiguredDoris();
        }

        // The source-side network is not a prerequisite for PHYSICAL mode.
        // It is deliberately marked true here so the physical publisher does
        // not pretend to have probed an unrelated external source; logical
        // mode is handled separately and remains UNKNOWN until a bounded
        // source probe is introduced.
        return capabilityResolver.resolve(new DorisCapabilityChecks(
                adapterExists,
                driverConfigExists,
                driverChecksumConfigured,
                sourceConfigComplete,
                lakeDorisReachable,
                true));
    }

    /**
     * A source-less call cannot prove a physical recommendation and is kept
     * only for Supplier compatibility with non-request callers.
     */
    public DorisCapability current() {
        return current(null);
    }

    @Override
    public DorisCapability get() {
        return current();
    }

    private DataSource source(Long dataSourceId) {
        if (dataSourceId == null || dataSourceId <= 0) {
            return null;
        }
        try {
            return dataSourceDao.queryById(dataSourceId);
        } catch (RuntimeException ignored) {
            // Capability responses must not expose DAO/connection details.
            return null;
        }
    }

    private boolean pingConfiguredDoris() {
        Long dataSourceId = properties.getDataSourceId();
        try (DorisLakeClient client = clientProvider.get(dataSourceId)) {
            return client != null && client.ping();
        } catch (RuntimeException ignored) {
            // Provider and JDBC exceptions may contain URL or credential data.
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean completeDorisSource(DataSource source) {
        return source != null
                && source.getDbType() == DbType.DORIS
                && (source.getStatus() == null
                || source.getStatus() == DataSourceLifecycleStatus.ENABLED)
                && configured(source.getConnectionParams());
    }

    private static boolean completeSource(DataSource source) {
        return source != null
                && source.getDbType() != null
                && (source.getStatus() == null
                || source.getStatus() == DataSourceLifecycleStatus.ENABLED)
                && configured(source.getConnectionParams());
    }

    private static boolean configured(String value) {
        return value != null && !value.isBlank();
    }
}
