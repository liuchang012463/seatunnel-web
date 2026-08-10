package org.apache.seatunnel.plugin.datasource.http.builder;

import com.google.auto.service.AutoService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.hocon.DataSourceHoconBuilder;
import org.apache.seatunnel.plugin.datasource.api.hocon.HoconBuildContext;
import org.apache.seatunnel.plugin.datasource.http.client.HttpRequestSupport;
import org.apache.seatunnel.plugin.datasource.http.param.HttpConnectionParam;
import org.apache.seatunnel.plugin.datasource.http.param.HttpConnectionParamConverter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@AutoService(DataSourceHoconBuilder.class)
public class HttpHoconBuilder implements DataSourceHoconBuilder {

    private static final Set<String> INTERNAL_KEYS = Set.of(
            "dataSourceId", "datasourceId", "dbType", "connectorType", "pluginName",
            "nodeId", "nodeName", "path", "extraParams");

    private static final Map<String, String> FIELD_MAPPING = Map.ofEntries(
            Map.entry("method", "method"),
            Map.entry("params", "params"),
            Map.entry("body", "body"),
            Map.entry("format", "format"),
            Map.entry("schema", "schema"),
            Map.entry("contentField", "content_field"),
            Map.entry("jsonField", "json_field"),
            Map.entry("pageing", "pageing"),
            Map.entry("pollIntervalMillis", "poll_interval_millis"),
            Map.entry("retry", "retry"),
            Map.entry("retryBackoffMultiplierMs", "retry_backoff_multiplier_ms"),
            Map.entry("retryBackoffMaxMs", "retry_backoff_max_ms"),
            Map.entry("enableMultiLines", "enable_multi_lines"),
            Map.entry("keepParamsAsForm", "keep_params_as_form"),
            Map.entry("keepPageParamAsHttpParam", "keep_page_param_as_http_param"),
            Map.entry("jsonFieldMissedReturnNull", "json_filed_missed_return_null"),
            Map.entry("connectTimeoutMs", "connect_timeout_ms"),
            Map.entry("socketTimeoutMs", "socket_timeout_ms"));

    @Override
    public String pluginName() {
        return "HTTP";
    }

    @Override
    public Config buildSourceHocon(HoconBuildContext context) {
        HttpConnectionParam param = new HttpConnectionParamConverter()
                .createConnectionParams(context.getConnectionParam());
        new HttpConnectionParamConverter().checkDatasourceParam(param);
        Map<String, Object> node = nodeValues(context);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", HttpRequestSupport.resolveUrl(
                param.getBaseUrl(), stringValue(node.get("path"))));
        result.put("connect_timeout_ms", param.getConnectTimeoutMs());
        result.put("socket_timeout_ms", param.getSocketTimeoutMs());

        FIELD_MAPPING.forEach((field, hoconKey) -> putIfPresent(
                result, hoconKey, node.get(field)));
        result.put("headers", quoteMapKeys(HttpRequestSupport.mergeHeaders(
                param, asMap(node.get("headers")))));
        appendExtraParams(result, node);
        result.putIfAbsent("method", "GET");
        result.putIfAbsent("format", "text");
        removeEmptyMaps(result);
        validate(result);
        return ConfigFactory.parseMap(result);
    }

    @Override
    public boolean supportsSink() {
        return false;
    }

    @Override
    public Config buildSinkHocon(HoconBuildContext context) {
        throw new UnsupportedOperationException("HTTP datasource does not support sink side");
    }

    private void validate(Map<String, Object> config) {
        String method = String.valueOf(config.get("method")).toUpperCase(Locale.ROOT);
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new IllegalArgumentException("HTTP Source method only supports GET or POST");
        }
        config.put("method", method);

        String format = String.valueOf(config.get("format")).toLowerCase(Locale.ROOT);
        if (!"json".equals(format) && !"text".equals(format)) {
            throw new IllegalArgumentException("HTTP Source format only supports json or text");
        }
        config.put("format", format);
        if ("json".equals(format) && config.get("schema") == null) {
            throw new IllegalArgumentException("HTTP Source schema is required when format is json");
        }
        if ("json".equals(format)
                && StringUtils.isBlank(stringValue(config.get("content_field")))) {
            throw new IllegalArgumentException(
                    "HTTP Source content_field is required when format is json");
        }

        validatePositive(config, "connect_timeout_ms");
        validatePositive(config, "socket_timeout_ms");
        validateOptionalNonNegative(config, "retry");
        validateOptionalPositive(config, "retry_backoff_multiplier_ms");
        validateOptionalPositive(config, "retry_backoff_max_ms");
        validateOptionalPositive(config, "poll_interval_millis");
        validatePageing(asMap(config.get("pageing")));
    }

    private void validatePageing(Map<String, Object> pageing) {
        if (pageing.isEmpty()) {
            return;
        }
        String type = StringUtils.defaultIfBlank(
                stringValue(pageing.get("page_type")), "PageNumber");
        if (!"PageNumber".equals(type) && !"Cursor".equals(type)) {
            throw new IllegalArgumentException(
                    "HTTP Source pageing.page_type only supports PageNumber or Cursor");
        }
        if ("Cursor".equals(type)
                && (StringUtils.isBlank(stringValue(pageing.get("cursor_field")))
                || StringUtils.isBlank(stringValue(pageing.get("cursor_response_field"))))) {
            throw new IllegalArgumentException(
                    "HTTP Cursor pageing requires cursor_field and cursor_response_field");
        }
        if ("PageNumber".equals(type)
                && StringUtils.isBlank(stringValue(pageing.get("page_field")))) {
            throw new IllegalArgumentException(
                    "HTTP PageNumber pageing requires page_field");
        }
    }

    private void appendExtraParams(Map<String, Object> target, Map<String, Object> node) {
        asMap(node.get("extraParams")).forEach((key, value) -> {
            if (!INTERNAL_KEYS.contains(key) && !target.containsKey(key)) {
                target.put(key, value);
            }
        });
    }

    private void validatePositive(Map<String, Object> config, String key) {
        Number value = (Number) config.get(key);
        if (value == null || value.longValue() <= 0) {
            throw new IllegalArgumentException("HTTP Source " + key + " must be greater than 0");
        }
    }

    private void validateOptionalPositive(Map<String, Object> config, String key) {
        Object raw = config.get(key);
        if (raw instanceof Number && ((Number) raw).longValue() <= 0) {
            throw new IllegalArgumentException("HTTP Source " + key + " must be greater than 0");
        }
    }

    private void validateOptionalNonNegative(Map<String, Object> config, String key) {
        Object raw = config.get(key);
        if (raw instanceof Number && ((Number) raw).longValue() < 0) {
            throw new IllegalArgumentException("HTTP Source " + key + " must not be negative");
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String)
                || StringUtils.isNotBlank((String) value))) {
            target.put(key, value);
        }
    }

    private Map<String, Object> nodeValues(HoconBuildContext context) {
        if (context.getNodeConfig() == null) {
            return Collections.emptyMap();
        }
        return new LinkedHashMap<>(context.getNodeConfig().root().unwrapped());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) value)
                : Collections.emptyMap();
    }

    private Map<String, Object> quoteMapKeys(Map<String, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(
                key.contains(".") ? "\"" + key + "\"" : key, value));
        return result;
    }

    private void removeEmptyMaps(Map<String, Object> result) {
        result.entrySet().removeIf(entry ->
                entry.getValue() instanceof Map && ((Map<?, ?>) entry.getValue()).isEmpty());
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
