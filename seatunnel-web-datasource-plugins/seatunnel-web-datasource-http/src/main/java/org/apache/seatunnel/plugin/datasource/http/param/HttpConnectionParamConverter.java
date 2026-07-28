package org.apache.seatunnel.plugin.datasource.http.param;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectionParamConverter;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class HttpConnectionParamConverter implements ConnectionParamConverter {

    @Override
    public HttpConnectionParam createConnectionParams(String connectionJson) {
        HttpConnectionParam param = JSONUtils.parseObject(
                sanitizeEmptyMapFields(connectionJson), HttpConnectionParam.class);
        if (param == null) {
            throw new IllegalArgumentException("HTTP connection param must not be null");
        }
        if (param.getDefaultHeaders() == null) {
            param.setDefaultHeaders(new LinkedHashMap<>());
        }
        if (param.getAuthenticationType() == null) {
            param.setAuthenticationType(HttpAuthenticationType.NONE);
        }
        param.setDbType(DbType.HTTP);
        return param;
    }

    @Override
    public void checkDatasourceParam(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof HttpConnectionParam)) {
            throw new IllegalArgumentException("Invalid HTTP connection param type");
        }
        HttpConnectionParam param = (HttpConnectionParam) connectionParam;
        validateBaseUrl(param.getBaseUrl());
        requirePositive(param.getConnectTimeoutMs(), "connectTimeoutMs");
        requirePositive(param.getSocketTimeoutMs(), "socketTimeoutMs");
        validateAuthentication(param);
        validateDefaultHeaders(param);
    }

    private void validateBaseUrl(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            throw new IllegalArgumentException("HTTP baseUrl cannot be empty");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("HTTP baseUrl is invalid", e);
        }
        String scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("HTTP baseUrl must use http or https");
        }
        if (StringUtils.isBlank(uri.getHost())) {
            throw new IllegalArgumentException("HTTP baseUrl must include a host");
        }
    }

    private void validateAuthentication(HttpConnectionParam param) {
        switch (param.getAuthenticationType()) {
            case NONE -> {
            }
            case BASIC -> {
                requireText(param.getUsername(), "username");
                requireText(param.getPassword(), "password");
            }
            case BEARER -> requireText(param.getBearerToken(), "bearerToken");
            case API_KEY -> {
                requireText(param.getApiKeyHeader(), "apiKeyHeader");
                requireText(param.getApiKeyValue(), "apiKeyValue");
            }
        }
    }

    private void validateDefaultHeaders(HttpConnectionParam param) {
        for (Map.Entry<String, String> entry : param.getDefaultHeaders().entrySet()) {
            requireText(entry.getKey(), "defaultHeaders key");
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("HTTP defaultHeaders value cannot be null");
            }
            String normalized = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if ("authorization".equals(normalized) || "proxy-authorization".equals(normalized)) {
                throw new IllegalArgumentException(
                        "Authentication headers must use the structured HTTP authentication fields");
            }
        }
    }

    private void requirePositive(Integer value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("HTTP " + field + " must be greater than 0");
        }
    }

    private void requireText(String value, String field) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("HTTP " + field + " cannot be empty");
        }
    }

    private String sanitizeEmptyMapFields(String json) {
        if (StringUtils.isEmpty(json)) {
            return json;
        }
        return json.replaceAll(
                "\"defaultHeaders\"\\s*:\\s*\"\"",
                "\"defaultHeaders\":{}");
    }
}
