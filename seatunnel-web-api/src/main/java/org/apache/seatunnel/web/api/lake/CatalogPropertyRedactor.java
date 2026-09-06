package org.apache.seatunnel.web.api.lake;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Secret-safe rendering for catalog properties, errors, and operation details. */
public final class CatalogPropertyRedactor {

    public static final String MASK = "******";
    private static final String SENSITIVE_KEY =
            "password|passwd|pwd|secret|token|credential|access[_-]?key[_-]?secret"
                    + "|connection[_-]?(params?|json|string)|original[_-]?json|authorization"
                    + "|private[_-]?key|jdbc[_-]?url|driver[_-]?url";
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)([\\\"']?(?:" + SENSITIVE_KEY + ")[\\\"']?\\s*[:=]\\s*)"
                    + "(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:''|[^'])*'|[^,}\\]\\s]+)");
    private static final Pattern USER_INFO = Pattern.compile(
            "(?i)(jdbc:[^\\s/@]+://[^\\s/@:]+:)[^\\s/@]+(@)");
    private static final Pattern DDL = Pattern.compile(
            "(?is)\\b(?:create|alter|drop)\\s+(?:external\\s+)?(?:table|database|catalog)\\b");

    private CatalogPropertyRedactor() {
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT).replaceAll("[_\\- .]", "");
        return normalized.contains("password")
                || normalized.equals("passwd")
                || normalized.equals("pwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("credential")
                || normalized.contains("connectionparam")
                || normalized.contains("connectionjson")
                || normalized.contains("connectionstring")
                || normalized.contains("originaljson")
                || normalized.contains("authorization")
                || normalized.contains("privatekey")
                || normalized.equals("jdbcurl")
                || normalized.equals("driverurl")
                || normalized.equals("ddl")
                || normalized.equals("sql");
    }

    /** Recursively copies maps/lists while replacing values under sensitive keys. */
    public static Map<String, Object> redactMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = String.valueOf(entry.getKey());
            result.put(key, isSensitiveKey(key) ? MASK : redactValue(entry.getValue()));
        }
        return result;
    }

    public static Map<String, String> redactProperties(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            result.put(entry.getKey(), isSensitiveKey(entry.getKey()) ? MASK : entry.getValue());
        }
        return result;
    }

    public static String redactText(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        String result = source;
        Matcher matcher = ASSIGNMENT.matcher(result);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + MASK));
        }
        matcher.appendTail(buffer);
        result = buffer.toString();

        matcher = USER_INFO.matcher(result);
        buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + MASK + matcher.group(2)));
        }
        matcher.appendTail(buffer);
        result = buffer.toString();

        // Operation summaries must never become a second storage location for
        // complete executable DDL. Keep a stable marker for diagnostics.
        if (DDL.matcher(result).find()) {
            return "[REDACTED_DDL]";
        }
        return result;
    }

    private static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return redactMap(map);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(redactValue(item));
            }
            return result;
        }
        if (value instanceof String text) {
            return redactText(text);
        }
        return value;
    }
}
