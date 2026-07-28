package org.apache.seatunnel.plugin.datasource.s3.client;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ObjectStoragePathUtils {
    private ObjectStoragePathUtils() {
    }

    public static String normalizeAbsolute(String path) {
        String value = path == null || path.trim().isEmpty() ? "/" : path.trim();
        if (value.contains("\\")) {
            throw new IllegalArgumentException("Object path must not contain backslashes: " + path);
        }
        if (!value.startsWith("/")) {
            throw new IllegalArgumentException("Object path must be absolute: " + path);
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String part : value.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException("Object path must not contain '..': " + path);
            }
            parts.add(part);
        }
        return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
    }

    public static String resolveWithinBase(String basePath, String requestedPath) {
        String base = normalizeAbsolute(basePath);
        String requested = requestedPath == null || requestedPath.trim().isEmpty()
                ? base : normalizeAbsolute(requestedPath);
        if ("/".equals(requested)) {
            return base;
        }
        if (!"/".equals(base) && !requested.equals(base) && !requested.startsWith(base + "/")) {
            throw new IllegalArgumentException("Object path is outside datasource root: " + requestedPath);
        }
        return requested;
    }

    public static String toObjectKey(String path) {
        String normalized = normalizeAbsolute(path);
        return "/".equals(normalized) ? "" : normalized.substring(1);
    }

    public static String toDirectoryPrefix(String path) {
        String key = toObjectKey(path);
        return key.isEmpty() ? "" : key + "/";
    }

    public static String fromObjectKey(String key) {
        if (key == null || key.isBlank()) {
            return "/";
        }
        return normalizeAbsolute("/" + key.replaceAll("/+$", ""));
    }
}
