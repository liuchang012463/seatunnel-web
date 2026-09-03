package org.apache.seatunnel.plugin.datasource.localfile.util;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves user supplied relative paths against a local base directory while
 * preventing path traversal outside of the base directory.
 */
public final class LocalPathUtils {

    private LocalPathUtils() {
    }

    public static Path resolveBase(String basePath) {
        Path base = Paths.get(StringUtils.trimToEmpty(basePath)).normalize().toAbsolutePath();
        if (!Files.exists(base)) {
            throw new IllegalArgumentException("Local base path does not exist: " + base);
        }
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("Local base path is not a directory: " + base);
        }
        return base;
    }

    /**
     * Resolves {@code relativePath} ("" or "/" means the base directory itself) under
     * {@code basePath}, guaranteeing the result stays inside the base directory.
     * Absolute paths that point inside the base directory (as returned by
     * {@link org.apache.seatunnel.plugin.datasource.api.datasource.FileDataSourceCatalog
     * listEntries}) are accepted as-is.
     */
    public static Path resolveWithin(Path basePath, String relativePath) {
        String cleaned = StringUtils.trimToEmpty(relativePath);
        Path resolved;
        if (cleaned.isEmpty() || "/".equals(cleaned)) {
            resolved = basePath;
        } else {
            Path candidate = Paths.get(cleaned);
            if (candidate.isAbsolute()) {
                resolved = candidate.normalize();
            } else {
                resolved = basePath.resolve(candidate).normalize();
            }
        }
        if (!resolved.startsWith(basePath)) {
            throw new IllegalArgumentException("Path escapes the datasource base directory: " + relativePath);
        }
        return resolved;
    }

    public static void ensureDirectoryWithin(Path basePath, String relativePath) {
        Path dir = resolveWithin(basePath, relativePath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Path is not a directory: " + dir);
        }
    }

    /**
     * Resolves {@code relativeDir/fileName} under {@code basePath}, guaranteeing the
     * result stays inside the base directory.
     */
    public static Path resolveFileWithin(Path basePath, String relativeDir, String fileName) {
        String dir = StringUtils.trimToEmpty(relativeDir);
        String combined = dir.isEmpty() || "/".equals(dir)
                ? fileName
                : StringUtils.removeEnd(dir, "/") + "/" + StringUtils.trimToEmpty(fileName);
        return resolveWithin(basePath, combined);
    }

    public static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    public static long modifiedTimeOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
