package org.apache.seatunnel.plugin.datasource.ftp.client;

import java.util.ArrayDeque;
import java.util.Deque;

public final class RemotePathUtils {
    private RemotePathUtils() {}

    public static String normalizeAbsolute(String path) {
        String value = path == null || path.trim().isEmpty() ? "/" : path.trim().replace('\\', '/');
        if (!value.startsWith("/")) {
            throw new IllegalArgumentException("Remote path must be absolute: " + path);
        }
        Deque<String> parts = new ArrayDeque<>();
        for (String part : value.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException("Remote path must not contain '..': " + path);
            }
            parts.add(part);
        }
        return parts.isEmpty() ? "/" : "/" + String.join("/", parts);
    }

    public static String resolveWithinBase(String basePath, String requestedPath) {
        String base = normalizeAbsolute(basePath);
        String requested = requestedPath == null || requestedPath.trim().isEmpty()
                ? base
                : normalizeAbsolute(requestedPath);
        if (!"/".equals(base) && !requested.equals(base) && !requested.startsWith(base + "/")) {
            throw new IllegalArgumentException("Remote path is outside datasource root: " + requestedPath);
        }
        return requested;
    }

    public static String child(String parent, String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
                || ".".equals(name) || "..".equals(name)) {
            throw new IllegalArgumentException("Invalid remote entry name: " + name);
        }
        return normalizeAbsolute(("/".equals(parent) ? "" : parent) + "/" + name);
    }
}
