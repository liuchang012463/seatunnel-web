package org.apache.seatunnel.web.api.lake;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.apache.seatunnel.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.spi.datasource.BaseConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Resolves the configured Doris data source and reuses one Hikari pool while
 * its connection configuration remains unchanged.
 */
@Component
public class LakeDataSourceResolver implements AutoCloseable {

    private final DataSourceDao dataSourceDao;
    private final LakeProperties properties;
    private final Function<DataSource, BaseConnectionParam> connectionParamFactory;
    private final Map<Long, CachedPool> pools = new HashMap<>();

    public LakeDataSourceResolver(DataSourceDao dataSourceDao, LakeProperties properties) {
        this(dataSourceDao, properties,
                dataSource -> DataSourceUtils.buildJdbcConnectionParams(
                        DbType.DORIS, dataSource.getConnectionParams()));
    }

    /** Visible for tests and alternative server-side parameter resolvers. */
    public LakeDataSourceResolver(
            DataSourceDao dataSourceDao,
            LakeProperties properties,
            Function<DataSource, BaseConnectionParam> connectionParamFactory) {
        this.dataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.connectionParamFactory = Objects.requireNonNull(connectionParamFactory, "connectionParamFactory");
    }

    public javax.sql.DataSource resolveConfigured() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Lake control plane is disabled");
        }
        if (properties.getDataSourceId() == null) {
            throw new IllegalStateException("Lake Doris data source is not configured");
        }
        return resolve(properties.getDataSourceId());
    }

    public synchronized javax.sql.DataSource resolve(Long dataSourceId) {
        if (dataSourceId == null || dataSourceId <= 0) {
            throw new IllegalArgumentException("Lake data source id must be positive");
        }
        DataSource dataSource = dataSourceDao.queryById(dataSourceId);
        if (dataSource == null) {
            throw new IllegalArgumentException("Lake Doris data source does not exist");
        }
        if (dataSource.getDbType() != DbType.DORIS) {
            throw new IllegalArgumentException("Lake data source must be Doris");
        }
        if (dataSource.getConnectionParams() == null
                || dataSource.getConnectionParams().isBlank()) {
            throw new IllegalArgumentException("Lake Doris data source has no connection configuration");
        }

        String fingerprint = fingerprint(dataSource);
        CachedPool cached = pools.get(dataSourceId);
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.dataSource();
        }

        BaseConnectionParam param;
        try {
            param = connectionParamFactory.apply(dataSource);
        } catch (RuntimeException exception) {
            // Do not include connectionParams or the exception text in a
            // user-facing/loggable message; plugin exceptions can echo JSON.
            throw new IllegalArgumentException("Lake Doris data source configuration is invalid");
        }
        if (param == null || param.getUrl() == null || param.getUrl().isBlank()) {
            throw new IllegalArgumentException("Lake Doris JDBC URL is not configured");
        }

        HikariDataSource pool = createPool(dataSourceId, param);
        if (cached != null) {
            cached.dataSource().close();
        }
        pools.put(dataSourceId, new CachedPool(fingerprint, pool));
        return pool;
    }

    public synchronized void evict(Long dataSourceId) {
        CachedPool cached = pools.remove(dataSourceId);
        if (cached != null) {
            cached.dataSource().close();
        }
    }

    private HikariDataSource createPool(Long dataSourceId, BaseConnectionParam param) {
        LakeProperties.ConnectionPool poolProperties = properties.getConnectionPool();
        if (poolProperties == null) {
            poolProperties = new LakeProperties.ConnectionPool();
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("seatunnel-lake-doris-" + dataSourceId);
        config.setJdbcUrl(param.getUrl());
        if (param.getUser() != null && !param.getUser().isBlank()) {
            config.setUsername(param.getUser());
        }
        if (param.getPassword() != null) {
            config.setPassword(param.getPassword());
        }
        if (param.getDriver() != null && !param.getDriver().isBlank()) {
            config.setDriverClassName(param.getDriver());
        }
        config.setMaximumPoolSize(Math.max(1, poolProperties.getMaximumPoolSize()));
        config.setMinimumIdle(Math.max(0, Math.min(
                poolProperties.getMinimumIdle(), config.getMaximumPoolSize())));
        config.setConnectionTimeout(durationMillis(poolProperties.getConnectionTimeout(), 10_000));
        config.setValidationTimeout(durationMillis(poolProperties.getValidationTimeout(), 5_000));
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    private static long durationMillis(Duration duration, long defaultValue) {
        if (duration == null) {
            return defaultValue;
        }
        return Math.max(250, duration.toMillis());
    }

    private static String fingerprint(DataSource dataSource) {
        String value = String.valueOf(dataSource.getId()) + '\u0000'
                + String.valueOf(dataSource.getConnectionParams()) + '\u0000'
                + String.valueOf(dataSource.getUpdateTime());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
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
        pools.clear();
    }

    private record CachedPool(String fingerprint, HikariDataSource dataSource) {
    }
}
