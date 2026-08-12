package org.apache.seatunnel.web.core.time;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the four runtime values used by a bounded incremental batch.
 *
 * <p>Preview/build-config calls have no runtime context, so they use a
 * deterministic window derived from the configured initial watermark. Actual
 * execution supplies the fixed window through {@code runtimeParams}.</p>
 */
public final class IncrementalSqlRenderer {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private IncrementalSqlRenderer() {
    }

    public static Config render(Config config, JobScheduleConfig schedule) {
        if (config == null || schedule == null || schedule.getIncremental() == null
                || !Boolean.TRUE.equals(schedule.getIncremental().getEnabled())) {
            return config;
        }

        JobScheduleConfig.IncrementalConfig incremental = schedule.getIncremental();
        boolean httpSource = isHttpSource(config);
        Map<String, String> values = resolveValues(incremental, schedule.getRuntimeParams());
        Map<String, Object> overrides = new HashMap<>();

        renderTokenizedValue(config, "sql", values, overrides);
        renderTokenizedValue(config, "query", values, overrides);
        renderTokenizedValue(config, "where_condition", values, overrides);
        renderTokenizedValue(config, "path", values, overrides);

        if (httpSource) {
            renderHttpRequest(config, values, overrides);
        }

        // Table mode has no user SQL. Add a bounded predicate so retries read
        // the same overlap window and upsert makes the replay idempotent.
        String sql = getString(config, "sql");
        String query = getString(config, "query");
        if (StringUtils.isBlank(sql) && StringUtils.isBlank(query)
                && StringUtils.isNotBlank(incremental.getWatermarkColumn())) {
            String column = incremental.getWatermarkColumn().trim();
            String condition = "where " + column + " >= '" + values.get("query_start")
                    + "' and " + column + " < '" + values.get("window_end") + "'";
            overrides.put("where_condition", condition);
        }

        if (overrides.isEmpty()) {
            return config;
        }
        return ConfigFactory.parseMap(overrides).withFallback(config).resolve();
    }

    private static Map<String, String> resolveValues(
            JobScheduleConfig.IncrementalConfig incremental,
            Map<String, String> runtimeParams) {
        Map<String, String> values = new HashMap<>();
        if (runtimeParams != null) {
            values.putAll(runtimeParams);
        }
        if (values.containsKey("window_start") && values.containsKey("window_end")
                && values.containsKey("query_start")) {
            return values;
        }

        LocalDateTime start = parse(incremental.getInitialWatermark());
        int maxWindow = incremental.getMaxWindowSeconds() == null
                ? 1800 : incremental.getMaxWindowSeconds();
        int overlap = incremental.getOverlapSeconds() == null
                ? 0 : incremental.getOverlapSeconds();
        LocalDateTime end = start.plusSeconds(maxWindow);
        values.putIfAbsent("window_start", format(start));
        values.putIfAbsent("window_end", format(end));
        values.putIfAbsent("query_start", format(start.minusSeconds(overlap)));
        values.putIfAbsent("batch_id", "preview");
        return values;
    }

    private static void renderHttpRequest(Config config,
                                          Map<String, String> values,
                                          Map<String, Object> overrides) {
        Map<String, Object> headers = renderObject(config, "headers", values);
        if (!headers.isEmpty()) {
            overrides.put("headers", headers);
        }

        Map<String, Object> params = renderObject(config, "params", values);
        if (!params.isEmpty()) {
            overrides.put("params", params);
        }

        String method = StringUtils.defaultIfBlank(getString(config, "method"), "GET")
                .toUpperCase(java.util.Locale.ROOT);
        if (!"POST".equals(method)) {
            return;
        }

        if (!config.hasPath("body")) {
            return;
        }
        Object rawBody = config.getAnyRef("body");
        if (rawBody instanceof Map) {
            Map<String, Object> rendered = renderMap((Map<?, ?>) rawBody, values);
            if (!rendered.equals(rawBody)) {
                overrides.put("body", JSONUtils.toJsonString(rendered));
            }
            return;
        }
        String body = renderTokens(String.valueOf(rawBody), values);
        if (!body.equals(String.valueOf(rawBody))) {
            overrides.put("body", body);
        }
    }

    private static Map<String, Object> renderObject(Config config,
                                                    String key,
                                                    Map<String, String> values) {
        if (!config.hasPath(key)) {
            return new LinkedHashMap<>();
        }
        Object raw = config.getAnyRef(key);
        if (!(raw instanceof Map)) {
            return new LinkedHashMap<>();
        }
        return renderMap((Map<?, ?>) raw, values);
    }

    private static Map<String, Object> renderMap(Map<?, ?> source,
                                                 Map<String, String> values) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        source.forEach((key, value) -> rendered.put(
                String.valueOf(key), renderObjectValue(value, values)));
        return rendered;
    }

    private static Object renderObjectValue(Object value, Map<String, String> values) {
        if (value instanceof String) {
            return renderTokens((String) value, values);
        }
        if (value instanceof Map) {
            return renderMap((Map<?, ?>) value, values);
        }
        if (value instanceof List) {
            List<Object> rendered = new ArrayList<>();
            for (Object item : (List<?>) value) {
                rendered.add(renderObjectValue(item, values));
            }
            return rendered;
        }
        return value;
    }

    private static void renderTokenizedValue(Config config,
                                             String key,
                                             Map<String, String> values,
                                             Map<String, Object> overrides) {
        String value = getString(config, key);
        if (StringUtils.isBlank(value)) {
            return;
        }
        String rendered = renderTokens(value, values);
        if (!rendered.equals(value)) {
            overrides.put(key, rendered);
        }
    }

    private static String renderTokens(String value, Map<String, String> values) {
        String rendered = value;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return rendered;
    }

    private static boolean isHttpSource(Config config) {
        return "HTTP".equalsIgnoreCase(getString(config, "dbType"));
    }

    private static String getString(Config config, String key) {
        if (!config.hasPath(key)) {
            return "";
        }
        return config.getString(key);
    }

    private static LocalDateTime parse(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("incremental initialWatermark cannot be empty");
        }
        try {
            return LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            return LocalDateTime.parse(value.trim(),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]"));
        }
    }

    public static String format(LocalDateTime value) {
        return FORMATTER.format(value);
    }

    public static String format(LocalDateTime value, String pattern) {
        if (StringUtils.isBlank(pattern)) {
            return format(value);
        }
        try {
            return DateTimeFormatter.ofPattern(pattern).format(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("invalid incremental time format: " + pattern, error);
        }
    }
}
