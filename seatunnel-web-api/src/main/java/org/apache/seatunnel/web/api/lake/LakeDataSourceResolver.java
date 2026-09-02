package org.apache.seatunnel.web.api.lake;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.plugin.datasource.api.utils.PasswordUtils;
import org.apache.seatunnel.web.api.service.LakeWarehouseService;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.LakeWarehouseConfig;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Creates bounded Doris pools from the persisted lake warehouse configuration. */
@Component
public class LakeDataSourceResolver implements AutoCloseable {

    private final LakeWarehouseService warehouseService;
    private final DataSourceDao legacyDataSourceDao;
    private final LakeProperties properties;
    private final Function<DataSource, BaseConnectionParam> legacyConnectionParamFactory;
    private final Map<String, CachedPool> pools = new HashMap<>();
    private final Map<String, CachedPool> readOnlyPools = new HashMap<>();

    @Autowired
    public LakeDataSourceResolver(LakeWarehouseService warehouseService, LakeProperties properties) {
        this.warehouseService = Objects.requireNonNull(warehouseService, "warehouseService");
        this.legacyDataSourceDao = null;
        this.properties = Objects.requireNonNull(properties, "properties");
        this.legacyConnectionParamFactory = null;
    }

    /** Compatibility constructor retained for focused tests and embedders. */
    public LakeDataSourceResolver(DataSourceDao dataSourceDao, LakeProperties properties) {
        this(dataSourceDao, properties, dataSource -> DataSourceUtils.buildJdbcConnectionParams(
                DbType.DORIS, dataSource.getConnectionParams()));
    }

    /** Compatibility constructor retained for focused tests. */
    public LakeDataSourceResolver(
            DataSourceDao dataSourceDao,
            LakeProperties properties,
            Function<DataSource, BaseConnectionParam> connectionParamFactory) {
        this.warehouseService = null;
        this.legacyDataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.legacyConnectionParamFactory = Objects.requireNonNull(connectionParamFactory,
                "connectionParamFactory");
    }

    public javax.sql.DataSource resolveConfigured() {
        return resolve(null, false);
    }

    public synchronized javax.sql.DataSource resolve(Long dataSourceId) {
        return resolve(dataSourceId, false);
    }

    /** Structured queries use a separate pool marked read-only at checkout. */
    public synchronized javax.sql.DataSource resolveReadOnly(Long dataSourceId) {
        return resolve(dataSourceId, true);
    }

    private javax.sql.DataSource resolve(Long dataSourceId, boolean readOnly) {
        if (warehouseService != null) {
            LakeWarehouseConfig config = warehouseService.requireConfig();
            String fingerprint = fingerprint(config);
            String key = "ODS_DORIS";
            Map<String, CachedPool> targetPools = readOnly ? readOnlyPools : pools;
            CachedPool cached = targetPools.get(key);
            if (cached != null && cached.fingerprint().equals(fingerprint)) {
                return cached.dataSource();
            }
            BaseConnectionParam param = directConnectionParam(config);
            LakeJdbcDriverLoader.ensureLoaded(param.getDriver(), config.getDriverLocation());
            HikariDataSource pool = createPool(key, param, readOnly);
            if (cached != null) {
                cached.dataSource().close();
            }
            targetPools.put(key, new CachedPool(fingerprint, pool));
            return pool;
        }

        if (dataSourceId == null || dataSourceId <= 0) {
            throw new IllegalArgumentException("Lake data source id must be positive");
        }
        DataSource dataSource = legacyDataSourceDao.queryById(dataSourceId);
        if (dataSource == null || dataSource.getDbType() != DbType.DORIS
                || StringUtils.isBlank(dataSource.getConnectionParams())) {
            throw new IllegalArgumentException("Lake Doris data source is unavailable");
        }
        String key = String.valueOf(dataSourceId);
        String fingerprint = fingerprint(dataSource);
        Map<String, CachedPool> targetPools = readOnly ? readOnlyPools : pools;
        CachedPool cached = targetPools.get(key);
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.dataSource();
        }
        BaseConnectionParam param;
        try {
            param = legacyConnectionParamFactory.apply(dataSource);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Lake Doris data source configuration is invalid");
        }
        if (param == null || StringUtils.isBlank(param.getUrl())) {
            throw new IllegalArgumentException("Lake Doris JDBC URL is not configured");
        }
        HikariDataSource pool = createPool(key, param, readOnly);
        if (cached != null) {
            cached.dataSource().close();
        }
        targetPools.put(key, new CachedPool(fingerprint, pool));
        return pool;
    }

    public synchronized void evict(Long dataSourceId) {
        String key = warehouseService != null ? "ODS_DORIS" : String.valueOf(dataSourceId);
        CachedPool cached = pools.remove(key);
        if (cached != null) {
            cached.dataSource().close();
        }
        CachedPool readOnlyCached = readOnlyPools.remove(key);
        if (readOnlyCached != null) {
            readOnlyCached.dataSource().close();
        }
    }

    private BaseConnectionParam directConnectionParam(LakeWarehouseConfig config) {
        ObjectNode node = org.apache.seatunnel.web.common.utils.JSONUtils.createObjectNode();
        node.put("url", config.getJdbcUrl());
        node.put("user", config.getUsername());
        node.put("password", PasswordUtils.decodePassword(config.getPassword()));
        node.put("driver", StringUtils.defaultIfBlank(config.getDriverClass(), "com.mysql.cj.jdbc.Driver"));
        if (StringUtils.isNotBlank(config.getDriverLocation())) {
            node.put("driverLocation", config.getDriverLocation());
        }
        node.put("database", "");
        try {
            return DataSourceUtils.buildJdbcConnectionParams(DbType.DORIS, node.toString());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Lake Doris warehouse configuration is invalid");
        }
    }

    private HikariDataSource createPool(String key, BaseConnectionParam param, boolean readOnly) {
        LakeProperties.ConnectionPool poolProperties = properties.getConnectionPool();
        if (poolProperties == null) {
            poolProperties = new LakeProperties.ConnectionPool();
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("seatunnel-lake-doris-" + (readOnly ? "readonly-" : "") + key);
        config.setJdbcUrl(param.getUrl());
        if (StringUtils.isNotBlank(param.getUser())) {
            config.setUsername(param.getUser());
        }
        if (param.getPassword() != null) {
            config.setPassword(param.getPassword());
        }
        // A locally loaded driver lives in a child classloader.  It is
        // registered through LakeJdbcDriverLoader's DriverShim, so letting
        // Hikari instantiate the class by name would bypass that loader.
        if (StringUtils.isNotBlank(param.getDriver())
                && StringUtils.isBlank(param.getDriverLocation())) {
            config.setDriverClassName(param.getDriver());
        }
        int maximumPoolSize = readOnly ? poolProperties.getReadOnlyMaximumPoolSize()
                : poolProperties.getMaximumPoolSize();
        int minimumIdle = readOnly ? poolProperties.getReadOnlyMinimumIdle()
                : poolProperties.getMinimumIdle();
        config.setMaximumPoolSize(Math.max(1, maximumPoolSize));
        config.setMinimumIdle(Math.max(0, Math.min(minimumIdle, config.getMaximumPoolSize())));
        config.setReadOnly(readOnly);
        config.setConnectionTimeout(durationMillis(poolProperties.getConnectionTimeout(), 10_000));
        config.setValidationTimeout(durationMillis(poolProperties.getValidationTimeout(), 5_000));
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    private static long durationMillis(Duration duration, long defaultValue) {
        return duration == null ? defaultValue : Math.max(250, duration.toMillis());
    }

    private static String fingerprint(LakeWarehouseConfig config) {
        return digest(String.valueOf(config.getId()) + '\u0000'
                + String.valueOf(config.getJdbcUrl()) + '\u0000'
                + String.valueOf(config.getUsername()) + '\u0000'
                + String.valueOf(config.getPassword()) + '\u0000'
                + String.valueOf(config.getConfigVersion()));
    }

    private static String fingerprint(DataSource dataSource) {
        return digest(String.valueOf(dataSource.getId()) + '\u0000'
                + String.valueOf(dataSource.getConnectionParams()) + '\u0000'
                + String.valueOf(dataSource.getUpdateTime()));
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        pools.values().forEach(pool -> pool.dataSource().close());
        readOnlyPools.values().forEach(pool -> pool.dataSource().close());
        pools.clear();
        readOnlyPools.clear();
        LakeJdbcDriverLoader.close();
    }

    private record CachedPool(String fingerprint, HikariDataSource dataSource) {
    }
}
