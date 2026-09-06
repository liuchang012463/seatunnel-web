package org.apache.seatunnel.web.api.lake;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.jdbc.JdbcDriverDirectoryResolver;

import java.net.URL;
import java.net.URLClassLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Loads JDBC jars from the local/shared driver directory without any network
 * access.  A small Driver shim is registered so Hikari/DriverManager can use
 * a driver loaded by the child URLClassLoader (the JDBC caller-class check
 * otherwise rejects drivers loaded outside the application classloader).
 */
public final class LakeJdbcDriverLoader {

    private static final Map<String, LoadedDriver> LOADED = new ConcurrentHashMap<>();
    private static final Map<String, DriverShim> REGISTERED_SHIMS = new ConcurrentHashMap<>();

    private LakeJdbcDriverLoader() {
    }

    /** Register/load a local driver and return the class name for Hikari. */
    public static String ensureLoaded(String driverClass, String driverLocation) {
        if (StringUtils.isBlank(driverClass)) {
            throw new IllegalArgumentException("JDBC driver class cannot be empty");
        }
        String location = StringUtils.trimToNull(driverLocation);
        String key = cacheKey(driverClass.trim(), location);
        LOADED.computeIfAbsent(key, ignored -> load(driverClass.trim(), location));
        return driverClass.trim();
    }

    /**
     * Resolve a driver directory using the same locations as the JDBC data
     * source plugins.  This deliberately uses generic deployment settings;
     * no lake-specific environment variable is consulted.
     */
    public static Path resolveDriverDirectory() {
        return JdbcDriverDirectoryResolver.resolveDirectory();
    }

    /** Resolve and constrain a local driver path to the configured directory. */
    public static Path resolveLocalPath(String location) {
        return JdbcDriverDirectoryResolver.resolveLocalPath(location);
    }

    /** Open a connection using the local driver artifact and supplied credentials. */
    public static Connection connect(
            String jdbcUrl, String username, String password,
            String driverClass, String driverLocation) throws Exception {
        String normalizedClass = ensureLoaded(driverClass, driverLocation);
        LoadedDriver loaded = LOADED.get(cacheKey(normalizedClass,
                StringUtils.trimToNull(driverLocation)));
        if (loaded == null) {
            throw new SQLException("JDBC driver could not be loaded: " + normalizedClass);
        }
        Properties properties = new Properties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        Connection connection = loaded.driver().connect(jdbcUrl, properties);
        if (connection == null) {
            throw new SQLException("JDBC driver rejected URL: " + jdbcUrl);
        }
        return connection;
    }

    /** Release child classloaders and DriverManager shims during application shutdown. */
    public static synchronized void close() {
        for (DriverShim shim : REGISTERED_SHIMS.values()) {
            try {
                DriverManager.deregisterDriver(shim);
            } catch (SQLException ignored) {
                // The JVM may already be shutting down; continue closing the
                // remaining local classloaders.
            }
        }
        REGISTERED_SHIMS.clear();
        for (LoadedDriver loaded : LOADED.values()) {
            if (loaded.classLoader() != null) {
                try {
                    loaded.classLoader().close();
                } catch (Exception ignored) {
                    // Best-effort cleanup only.
                }
            }
        }
        LOADED.clear();
    }

    private static LoadedDriver load(String driverClass, String driverLocation) {
        try {
            if (StringUtils.isBlank(driverLocation)) {
                Class<?> type = Class.forName(driverClass, true,
                        Thread.currentThread().getContextClassLoader());
                Driver driver = (Driver) type.getDeclaredConstructor().newInstance();
                return new LoadedDriver(driver, null, null);
            }
            Path path = resolveLocalPath(driverLocation);
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("JDBC driver jar not found: " + path);
            }
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{path.toUri().toURL()},
                    Thread.currentThread().getContextClassLoader());
            Class<?> type = Class.forName(driverClass, true, loader);
            Driver driver = (Driver) type.getDeclaredConstructor().newInstance();
            DriverShim shim = new DriverShim(driver);
            String baseKey = driverClass + "\u0000" + StringUtils.defaultString(driverLocation);
            DriverShim previous = REGISTERED_SHIMS.put(baseKey, shim);
            if (previous != null) {
                try {
                    DriverManager.deregisterDriver(previous);
                } catch (SQLException ignored) {
                    // A stale shim must not prevent the new local artifact
                    // from being registered.  The caller still uses the
                    // freshly loaded driver directly for connection tests.
                }
            }
            DriverManager.registerDriver(shim);
            return new LoadedDriver(driver, loader, shim);
        } catch (Exception exception) {
            throw new IllegalArgumentException("JDBC driver cannot be loaded: " + driverClass, exception);
        }
    }

    /**
     * Include the local artifact digest in the cache key.  Overwriting a jar
     * with the same file name must load the new classloader after the
     * registration checksum changes; caching by class and path alone would
     * silently keep executing the previous driver bytes.
     */
    private static String cacheKey(String driverClass, String driverLocation) {
        String location = StringUtils.defaultString(driverLocation);
        String artifactDigest = "";
        if (StringUtils.isNotBlank(driverLocation)) {
            Path path = resolveLocalPath(driverLocation);
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("JDBC driver jar not found: " + path);
            }
            try (InputStream input = Files.newInputStream(path)) {
                artifactDigest = DigestUtils.sha256Hex(input);
            } catch (IOException exception) {
                throw new IllegalArgumentException("JDBC driver jar cannot be read: " + path, exception);
            }
        }
        return driverClass + "\u0000" + location + "\u0000" + artifactDigest;
    }

    private record LoadedDriver(Driver driver, URLClassLoader classLoader, DriverShim shim) {
    }

    private static final class DriverShim implements Driver {
        private final Driver delegate;

        private DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() {
            try {
                return delegate.getParentLogger();
            } catch (Exception ignored) {
                return Logger.getLogger("org.apache.seatunnel.web.api.lake.jdbc");
            }
        }
    }
}
