package org.apache.seatunnel.web.core.builder.source;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Writes JDBC session initialization scripts to a directory shared with SeaTunnel Engine. */
@Component
public class DuckDbSourceInitSqlFileService {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    @Value("${seatunnel.web.duckdb.init-sql-dir:}")
    private String initSqlDirectory;

    @Value("${seatunnel.web.duckdb.init-sql-engine-dir:}")
    private String engineInitSqlDirectory;

    public String write(Long fileResourceId, String sql) {
        if (fileResourceId == null || fileResourceId <= 0) {
            throw new IllegalArgumentException("DuckDB source requires a valid file resource id");
        }
        if (StringUtils.isBlank(sql)) {
            throw new IllegalArgumentException("DuckDB source initialization SQL must not be blank");
        }

        String fileName = "duckdb-resource-" + fileResourceId + ".sql";
        Path directory = configuredDirectory(initSqlDirectory, "SEATUNNEL_WEB_DUCKDB_INIT_SQL_DIR");
        Path engineTarget = configuredDirectory(
                engineInitSqlDirectory, "SEATUNNEL_WEB_DUCKDB_INIT_SQL_ENGINE_DIR").resolve(fileName);
        Path target = directory.resolve(fileName);
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            setPermissions(directory, DIRECTORY_PERMISSIONS);
            temporary = Files.createTempFile(directory, "duckdb-resource-" + fileResourceId + "-", ".tmp");
            Files.writeString(
                    temporary,
                    sql,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            setPermissions(temporary, FILE_PERMISSIONS);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return engineTarget.toString();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to prepare DuckDB source initialization SQL; check the shared init SQL directory", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The next write replaces this resource's script.
                }
            }
        }
    }

    public void delete(Long fileResourceId) throws IOException {
        if (fileResourceId == null || fileResourceId <= 0 || StringUtils.isBlank(initSqlDirectory)) {
            return;
        }
        Files.deleteIfExists(configuredDirectory(initSqlDirectory, "SEATUNNEL_WEB_DUCKDB_INIT_SQL_DIR")
                .resolve("duckdb-resource-" + fileResourceId + ".sql"));
    }

    private Path configuredDirectory(String configuredPath, String environmentVariable) {
        if (StringUtils.isBlank(configuredPath)) {
            throw new IllegalStateException(
                    "DuckDB source requires " + environmentVariable + " to configure the init SQL directory");
        }
        Path directory = Path.of(configuredPath.trim()).normalize();
        if (!directory.isAbsolute()) {
            throw new IllegalStateException("DuckDB init SQL directory must be an absolute path");
        }
        String path = directory.toString();
        if (path.contains(";") || path.contains("\n") || path.contains("\r")) {
            throw new IllegalStateException(
                    "DuckDB init SQL directory path must not contain semicolons or line breaks");
        }
        return directory;
    }

    private void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Linux Docker deployments support POSIX permissions; keep local non-POSIX development usable.
        }
    }
}
