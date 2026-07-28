package org.apache.seatunnel.plugin.datasource.http.client;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.http.param.HttpAuthenticationType;
import org.apache.seatunnel.plugin.datasource.http.param.HttpConnectionParam;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class HttpRequestSupport {

    private HttpRequestSupport() {
    }

    public static String resolveUrl(String baseUrl, String relativePath) {
        URI base = URI.create(baseUrl.trim());
        if (StringUtils.isBlank(relativePath)) {
            return base.toString();
        }
        String path = relativePath.trim();
        URI candidate = URI.create(path);
        if (candidate.isAbsolute() || path.startsWith("//")) {
            throw new IllegalArgumentException("HTTP Source path must be relative");
        }
        String normalizedBase = base.toString();
        if (!normalizedBase.endsWith("/") && !path.startsWith("/")) {
            normalizedBase += "/";
        } else if (normalizedBase.endsWith("/") && path.startsWith("/")) {
            path = path.substring(1);
        }
        return URI.create(normalizedBase + path).toString();
    }

    public static Map<String, String> mergeHeaders(
            HttpConnectionParam param, Map<String, ?> nodeHeaders) {
        Map<String, String> result = new LinkedHashMap<>(param.getDefaultHeaders());
        String protectedHeader = protectedHeader(param);
        if (nodeHeaders != null) {
            nodeHeaders.forEach((key, value) -> {
                if (key == null || value == null) {
                    return;
                }
                if (protectedHeader != null && protectedHeader.equalsIgnoreCase(key.trim())) {
                    throw new IllegalArgumentException(
                            "HTTP Source node headers cannot override authentication header " + key);
                }
                result.put(key, String.valueOf(value));
            });
        }
        appendAuthenticationHeader(param, result);
        return result;
    }

    private static void appendAuthenticationHeader(
            HttpConnectionParam param, Map<String, String> headers) {
        HttpAuthenticationType type = param.getAuthenticationType();
        if (type == null || type == HttpAuthenticationType.NONE) {
            return;
        }
        switch (type) {
            case BASIC -> {
                String credential = param.getUsername() + ":" + param.getPassword();
                headers.put("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
            }
            case BEARER -> headers.put("Authorization", "Bearer " + param.getBearerToken());
            case API_KEY -> headers.put(param.getApiKeyHeader(), param.getApiKeyValue());
            default -> {
            }
        }
    }

    private static String protectedHeader(HttpConnectionParam param) {
        if (param.getAuthenticationType() == HttpAuthenticationType.BASIC
                || param.getAuthenticationType() == HttpAuthenticationType.BEARER) {
            return "authorization";
        }
        if (param.getAuthenticationType() == HttpAuthenticationType.API_KEY) {
            return StringUtils.lowerCase(param.getApiKeyHeader(), Locale.ROOT);
        }
        return null;
    }
}
