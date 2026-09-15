package org.apache.seatunnel.web.api.fileresource;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.spi.enums.Status;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates logical paths exposed by the file resource API.
 *
 * <p>Logical paths are absolute from the resource root.  Browser supplied
 * folder paths are handled by {@link #normalizeRelativePath(String)} and are
 * deliberately kept separate so an uploaded file can never escape the root.</p>
 */
public final class FileResourcePathUtils {

    public static final int MAX_PATH_LENGTH = 1024;

    private FileResourcePathUtils() {
    }

    public static String normalizePath(String path) {
        // Keep whitespace in a valid path segment.  This matters for an
        // uploaded filename such as " report.csv "; trimming here would
        // silently change the relative path after join().
        String value = StringUtils.defaultIfBlank(path, "/");
        if (value.indexOf('\\') >= 0 || value.indexOf('\u0000') >= 0) {
            throw invalid("文件路径不合法");
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        if (value.length() > MAX_PATH_LENGTH) {
            throw invalid("文件路径过长");
        }

        String[] segments = value.split("/", -1);
        List<String> normalizedSegments = new ArrayList<>();
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() && (index == 0 || index == segments.length - 1)) {
                continue;
            }
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("文件路径不合法");
            }
            normalizedSegments.add(segment);
        }
        return normalizedSegments.isEmpty() ? "/" : "/" + String.join("/", normalizedSegments);
    }

    public static String normalizeRelativePath(String path) {
        // Do not trim a browser supplied filename: leading/trailing spaces are
        // valid object-key characters and directory uploads must preserve the
        // relative path exactly.  Blank-only values are still rejected below.
        String value = StringUtils.defaultString(path);
        if (value.isEmpty() || value.startsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf('\u0000') >= 0
                || value.length() > MAX_PATH_LENGTH || value.isBlank()) {
            throw invalid("文件相对路径不合法");
        }

        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("文件相对路径不合法");
            }
        }
        return value;
    }

    public static String join(String directory, String relativePath) {
        String normalizedDirectory = normalizePath(directory);
        String normalizedRelative = normalizeRelativePath(relativePath);
        String path = "/".equals(normalizedDirectory)
                ? "/" + normalizedRelative
                : normalizedDirectory + "/" + normalizedRelative;
        return normalizePath(path);
    }

    public static String parent(String path) {
        String normalized = normalizePath(path);
        if ("/".equals(normalized)) {
            return "/";
        }
        int separator = normalized.lastIndexOf('/');
        return separator <= 0 ? "/" : normalized.substring(0, separator);
    }

    public static String name(String path) {
        String normalized = normalizePath(path);
        if ("/".equals(normalized)) {
            return "/";
        }
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    public static boolean isSameOrDescendant(String path, String directory) {
        String normalizedPath = normalizePath(path);
        String normalizedDirectory = normalizePath(directory);
        return normalizedPath.equals(normalizedDirectory)
                || ("/".equals(normalizedDirectory)
                ? normalizedPath.startsWith("/")
                : normalizedPath.startsWith(normalizedDirectory + "/"));
    }

    private static ServiceException invalid(String field) {
        return new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, field);
    }
}
