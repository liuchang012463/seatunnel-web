package org.apache.seatunnel.web.core.job.handler.single;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validation shared by the Web Upload source workflow and its MinIO builder. */
public final class LocalFileSourceValidator {

    private static final Set<String> STRUCTURED_FORMATS = Set.of("csv", "excel", "json", "text");

    private LocalFileSourceValidator() {
    }

    public static String validate(Map<String, Object> source) {
        String format = firstNonBlank(source, "fileFormatType", "file_format_type");
        if (StringUtils.isBlank(format)) {
            throw new IllegalArgumentException("本地文件来源必须选择文件格式");
        }

        String normalized = format.trim().toLowerCase(Locale.ROOT);
        if (!STRUCTURED_FORMATS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "本地文件来源只支持 CSV、Excel、JSON 和 Text，当前格式=" + format);
        }

        if (("json".equals(normalized) || "excel".equals(normalized))
                && !hasSchema(source.get("schema"))) {
            throw new IllegalArgumentException(
                    normalized.toUpperCase(Locale.ROOT) + " 本地文件来源必须配置字段 Schema");
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
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return "";
    }
}
