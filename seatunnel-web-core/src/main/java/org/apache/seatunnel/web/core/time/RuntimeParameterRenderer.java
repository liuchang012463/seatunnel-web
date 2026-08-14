package org.apache.seatunnel.web.core.time;

import org.apache.seatunnel.web.common.utils.JSONUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders runtime placeholders used by HTTP request configuration.
 *
 * <p>HTTP request bodies are stored as text. A placeholder can therefore be
 * written either inside a JSON string ({@code "${window_start}"}) or as a
 * JSON value ({@code ${window_start}}). The latter needs JSON quoting when it
 * is rendered because all current window values are timestamp strings.</p>
 */
public final class RuntimeParameterRenderer {

    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final DateTimeFormatter PREVIEW_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private RuntimeParameterRenderer() {
    }

    /**
     * Creates a short, recent preview window for calls made outside a
     * scheduled task, such as the HTTP response parser in the UI.
     */
    public static Map<String, String> previewValues() {
        LocalDateTime windowEnd = LocalDateTime.now().withNano(0);
        LocalDateTime windowStart = windowEnd.minusMinutes(30);
        String start = PREVIEW_FORMATTER.format(windowStart);
        String end = PREVIEW_FORMATTER.format(windowEnd);

        Map<String, String> values = new LinkedHashMap<>();
        values.put("window_start", start);
        values.put("window_end", end);
        values.put("query_start", start);
        values.put("batch_id", "preview");
        return values;
    }

    /**
     * Replaces placeholders in a normal text value, preserving unknown
     * placeholders for the caller to handle.
     */
    public static String renderText(String value, Map<String, String> values) {
        return render(value, values, false);
    }

    /**
     * Replaces placeholders in a JSON request body. A known placeholder that
     * is outside a JSON string is rendered as a quoted JSON string value.
     */
    public static String renderJsonBody(String value, Map<String, String> values) {
        return render(value, values, true);
    }

    /**
     * Recursively renders strings in a map/list based request value.
     */
    public static Object renderValue(Object value, Map<String, String> values) {
        if (value instanceof String) {
            return renderText((String) value, values);
        }
        if (value instanceof Map) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            ((Map<?, ?>) value).forEach((key, entryValue) ->
                    rendered.put(String.valueOf(key), renderValue(entryValue, values)));
            return rendered;
        }
        if (value instanceof List) {
            List<Object> rendered = new ArrayList<>();
            for (Object item : (List<?>) value) {
                rendered.add(renderValue(item, values));
            }
            return rendered;
        }
        return value;
    }

    private static String render(
            String value, Map<String, String> values, boolean quoteJsonValues) {
        if (value == null || value.isEmpty() || values == null || values.isEmpty()) {
            return value;
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String replacement = values.get(matcher.group(1));
            if (replacement == null) {
                matcher.appendReplacement(rendered, Matcher.quoteReplacement(matcher.group()));
                continue;
            }

            if (quoteJsonValues && !isInsideJsonString(value, matcher.start())) {
                replacement = JSONUtils.toJsonString(replacement);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static boolean isInsideJsonString(String value, int position) {
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < position; index++) {
            char current = value.charAt(index);
            if (current == '"' && !escaped) {
                inString = !inString;
            }
            if (current == '\\' && !escaped) {
                escaped = true;
            } else {
                escaped = false;
            }
        }
        return inString;
    }
}
