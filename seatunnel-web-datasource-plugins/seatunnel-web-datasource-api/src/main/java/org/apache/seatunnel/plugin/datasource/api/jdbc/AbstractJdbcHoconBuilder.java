package org.apache.seatunnel.plugin.datasource.api.jdbc;

import com.typesafe.config.Config;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.utils.PasswordUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.apache.seatunnel.plugin.datasource.api.hocon.JdbcBatchConstants.*;


@Slf4j
public abstract class AbstractJdbcHoconBuilder {

    protected abstract String defaultDriver();

    /**
     * Keep the JDBC family on the same password chain as Doris: decrypt
     * encrypted values, pass plain values through unchanged.
     */
    protected String processPassword(String rawPassword) {
        return PasswordUtils.decodeIfEncrypted(rawPassword);
    }

    protected void putConnCommon(Config conn, Map<String, Object> map) {
        map.put(URL, JdbcConfigReaders.getStringRequired(conn, URL));
        map.put(USERNAME, JdbcConfigReaders.getStringRequired(conn, USER));

        String driver = JdbcConfigReaders.getString(conn, DRIVER, "");
        if (StringUtils.isBlank(driver)) {
            driver = defaultDriver();
        }
        map.put(DRIVER, driver);

        String driverLocation = resolveEngineDriverLocation(conn);
        if (driverLocation != null) {
            map.put(DRIVER_LOCATION, driverLocation);
        }

        String password = JdbcConfigReaders.getString(conn, PASSWORD, "");
        if (StringUtils.isNotBlank(password)) {
            map.put(PASSWORD, processPassword(password));
        }
    }

    /**
     * Resolves the driver jars of the data source to the paths the engine has to load.
     *
     * <p>The engine attaches {@code driver_location} jars to the job class loader, so the job runs
     * with the same driver artifact the Web connected with instead of whichever driver happens to
     * be visible to the engine process. This is what allows two data sources sharing one driver
     * class name (PostgreSQL-compatible databases ship a repackaged {@code org.postgresql.Driver})
     * to be used by different jobs.
     *
     * <p>Paths are emitted as the Web resolves them locally, so engine nodes must expose the Web
     * driver directory at the same path. A jar the Web cannot read locally is skipped: such a data
     * source cannot be connected to from the Web either, and the engine would reject the job
     * because it validates every declared jar on every node.
     */
    private String resolveEngineDriverLocation(Config conn) {
        String driverLocation =
                JdbcConfigReaders.getString(conn, DRIVER_LOCATION_CAMEL, "");

        if (StringUtils.isBlank(driverLocation)) {
            return null;
        }

        List<String> jarPaths = new ArrayList<>();

        for (String location : driverLocation.split(",")) {
            String trimmed = location.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            Path jarPath = resolveDriverJar(trimmed);

            if (jarPath != null) {
                jarPaths.add(jarPath.toString());
            }
        }

        return jarPaths.isEmpty() ? null : String.join(";", jarPaths);
    }

    private Path resolveDriverJar(String location) {
        Path path = Paths.get(location);

        if (!path.isAbsolute()) {
            path = JdbcDriverDirectoryResolver.resolveDirectory().resolve(path);
        }

        path = path.toAbsolutePath().normalize();

        if (!Files.isRegularFile(path)
                || !path.getFileName().toString().toLowerCase().endsWith(".jar")) {
            log.warn(
                    "JDBC driver jar is not available locally, the job will not pin it: {}",
                    path);
            return null;
        }

        return path;
    }

    protected String buildTablePath(String database, String schema, String table) {
        if (StringUtils.isBlank(table)) {
            throw new IllegalArgumentException("Table must not be blank");
        }

        if (StringUtils.isBlank(database) && StringUtils.isBlank(schema)) {
            return table;
        }
        if (StringUtils.isBlank(schema)) {
            return database + "." + table;
        }
        if (StringUtils.isBlank(database)) {
            return schema + "." + table;
        }
        return database + "." + schema + "." + table;
    }
}
