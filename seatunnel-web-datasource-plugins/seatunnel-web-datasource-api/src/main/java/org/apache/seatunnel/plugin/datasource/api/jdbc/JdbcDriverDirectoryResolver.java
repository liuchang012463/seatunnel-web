package org.apache.seatunnel.plugin.datasource.api.jdbc;

import org.apache.commons.lang3.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves the shared, offline JDBC driver directory used by Web and the
 * datasource plugins.  Only generic deployment settings are consulted.
 */
public final class JdbcDriverDirectoryResolver {

    public static final String DRIVER_DIRECTORY_NAME = "jdbc-drivers";
    private static final String DIRECTORY_PROPERTY = "seatunnel.web.jdbc-driver-dir";
    private static final String DIRECTORY_ENV = "SEATUNNEL_WEB_JDBC_DRIVER_DIR";
    private static final String WEB_HOME_ENV = "SEATUNNEL_WEB_HOME";
    private static final String DIST_MODULE = "seatunnel-web-dist";

    private JdbcDriverDirectoryResolver() {
    }

    /** Return the configured directory, or the conventional local directory. */
    public static Path resolveDirectory() {
        String configured = StringUtils.trimToNull(System.getProperty(DIRECTORY_PROPERTY));
        if (configured == null) {
            configured = StringUtils.trimToNull(System.getenv(DIRECTORY_ENV));
        }
        if (configured != null) {
            Path path = Paths.get(configured).toAbsolutePath().normalize();
            if (Files.exists(path) && !Files.isDirectory(path)) {
                throw new IllegalArgumentException("JDBC driver directory is not a directory: " + path);
            }
            return path;
        }

        String webHome = StringUtils.trimToNull(System.getenv(WEB_HOME_ENV));
        if (webHome != null) {
            Path path = Paths.get(webHome).resolve(DRIVER_DIRECTORY_NAME).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                return path;
            }
        }

        Path working = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path current = working; current != null; current = current.getParent()) {
            Path direct = current.resolve(DRIVER_DIRECTORY_NAME);
            if (Files.isDirectory(direct)) {
                return direct.toAbsolutePath().normalize();
            }
            Path development = current.resolve(DIST_MODULE).resolve("src").resolve("main")
                    .resolve(DRIVER_DIRECTORY_NAME);
            if (Files.isDirectory(development)) {
                return development.toAbsolutePath().normalize();
            }
            if (DIST_MODULE.equals(String.valueOf(current.getFileName()))) {
                Path moduleDevelopment = current.resolve("src").resolve("main")
                        .resolve(DRIVER_DIRECTORY_NAME);
                if (Files.isDirectory(moduleDevelopment)) {
                    return moduleDevelopment.toAbsolutePath().normalize();
                }
            }
        }
        return working.resolve(DRIVER_DIRECTORY_NAME).toAbsolutePath().normalize();
    }

    /** Resolve a local path and constrain it to the shared directory. */
    public static Path resolveLocalPath(String location) {
        String value = StringUtils.trimToNull(location);
        if (value == null || value.contains("://") || value.contains("..")) {
            throw new IllegalArgumentException("JDBC driver location must be a local file");
        }
        Path directory = resolveDirectory();
        Path candidate = Paths.get(value);
        if (!candidate.isAbsolute()) {
            candidate = directory.resolve(candidate);
        }
        candidate = candidate.toAbsolutePath().normalize();
        if (!candidate.startsWith(directory)) {
            throw new IllegalArgumentException("JDBC driver path is outside the configured directory");
        }
        return candidate;
    }
}
