package org.apache.seatunnel.web.core.job.handler.single;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validation shared by legacy Web Upload and reusable structured file sources. */
public final class LocalFileSourceValidator {

    private static final Set<String> STRUCTURED_FORMATS = Set.of("csv", "excel", "json", "text");

    private LocalFileSourceValidator() {
    }

    public static String validate(Map<String, Object> source) {
        return validateFormat(source, "本地文件来源");
    }

    /**
     * Validate a structured file that is already persisted in the file
     * resource library.
     *
     * <p>The format rules intentionally live in this shared validator so
     * LocalFile compatibility and FILE_RESOURCE do not grow separate CSV,
     * Excel, JSON, and text implementations.</p>
     */
    public static Long validateFileResource(Map<String, Object> source) {
        Long resourceId = requireFileResourceId(source);
        validateFormat(source, "文件资源来源");
        return resourceId;
    }

    /**
     * Validate a file resource source before the task mode is known.
     *
     * <p>A structured source must use one of the supported formats.  A missing
     * format is kept valid here for the binary FILE_SYNC compatibility path;
     * the GuideSingle handler requires a structured format explicitly.</p>
     */
    public static Long validateFileResourceReference(Map<String, Object> source) {
        Long resourceId = requireFileResourceId(source);
        String format = firstNonBlank(source, "fileFormatType", "file_format_type");
        if (StringUtils.isBlank(format) || "binary".equalsIgnoreCase(format.trim())) {
            return resourceId;
        }
        validateFormat(source, "文件资源来源");
        return resourceId;
    }

    public static Long validateBinaryFileResource(Map<String, Object> source) {
        Long resourceId = requireFileResourceId(source);
        String format = firstNonBlank(source, "fileFormatType", "file_format_type");
        if (StringUtils.isNotBlank(format) && !"binary".equalsIgnoreCase(format.trim())) {
            throw new IllegalArgumentException("FILE_RESOURCE file transfer must use binary format");
        }
        return resourceId;
    }

    public static Long requireFileResourceId(Map<String, Object> source) {
        String resourceId = firstNonBlank(source, "fileResourceId", "file_resource_id");
        if (StringUtils.isBlank(resourceId)) {
            throw new IllegalArgumentException(
                    "FILE_RESOURCE source requires a positive fileResourceId");
        }

        try {
            long value = Long.parseLong(resourceId.trim());
            if (value <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "FILE_RESOURCE source fileResourceId must be a positive number: " + resourceId,
                    e);
        }
    }

    private static String validateFormat(Map<String, Object> source, String sourceLabel) {
        String format = firstNonBlank(source, "fileFormatType", "file_format_type");
        if (StringUtils.isBlank(format)) {
            throw new IllegalArgumentException(sourceLabel + "必须选择文件格式");
        }

        String normalized = format.trim().toLowerCase(Locale.ROOT);
        if (!STRUCTURED_FORMATS.contains(normalized)) {
            throw new IllegalArgumentException(
                    sourceLabel + "只支持 CSV、Excel、JSON 和 Text，当前格式=" + format);
        }

        if (("json".equals(normalized) || "excel".equals(normalized))
                && !hasSchema(source.get("schema"))) {
            throw new IllegalArgumentException(
                    normalized.toUpperCase(Locale.ROOT) + " " + sourceLabel + "必须配置字段 Schema");
        }

        return normalized;
    }

    private static boolean hasSchema(Object rawSchema) {
        if (!(rawSchema instanceof Map<?, ?> schema)) {
            return false;
        }
        Object rawFields = schema.get("fields");
        return rawFields instanceof Map<?, ?> fields && !fields.isEmpty();
    }

    private static String firstNonBlank(Map<String, Object> source, String... keys) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return "";
    }
}
