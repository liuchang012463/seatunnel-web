package org.apache.seatunnel.plugin.datasource.http.catalog;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.http.client.HttpRequestSupport;
import org.apache.seatunnel.plugin.datasource.http.param.HttpConnectionParam;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Fetches an HTTP OpenAPI/Swagger document using the datasource's existing auth settings. */
public class HttpOpenApiDocumentClient {

    public String fetch(HttpConnectionParam param) {
        if (param == null) {
            throw new IllegalArgumentException("HTTP connection param must not be null");
        }
        String configuredUrl = StringUtils.trimToEmpty(param.getOpenApiSpecUrl());
        if (configuredUrl.isEmpty()) {
            return "";
        }

        return fetch(param, resolveDocumentUri(param.getBaseUrl(), configuredUrl),
                HttpRequestSupport.mergeHeaders(param, Map.of()));
    }

    public void verifyForMetadata(HttpConnectionParam param) {
        if (StringUtils.isBlank(param.getOpenApiSpecUrl())) {
            return;
        }
        // OpenMetadata 的 REST 连接只向文档地址发送 Bearer Token。
        Map<String, String> headers = StringUtils.isBlank(param.getBearerToken())
                ? Map.of()
                : Map.of("Authorization", "Bearer " + param.getBearerToken());
        String document = fetch(param, resolveDocumentUri(param.getBaseUrl(), param.getOpenApiSpecUrl()), headers);
        try {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(document);
            if (parsed instanceof Map<?, ?> spec && (spec.get("openapi") != null || spec.get("swagger") != null)) {
                return;
            }
        } catch (YAMLException e) {
            // 解析错误可能包含文档原文，统一返回字段级错误。
        }
        throw new IllegalArgumentException("OpenAPI 文档地址返回的内容不是有效的 OpenAPI 或 Swagger 文档");
    }

    private String fetch(HttpConnectionParam param, URI documentUri, Map<String, String> headers) {
        int connectTimeoutMs = positiveOrDefault(param.getConnectTimeoutMs(), 12000);
        int socketTimeoutMs = positiveOrDefault(param.getSocketTimeoutMs(), 60000);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(documentUri)
                .timeout(Duration.ofMillis(socketTimeoutMs))
                .GET();
        if (headers.keySet().stream().noneMatch("Accept"::equalsIgnoreCase)) {
            requestBuilder.header("Accept", "application/json");
        }
        headers.forEach(requestBuilder::header);

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            HttpResponse<String> response = client.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                if (status == 401 || status == 403) {
                    throw new IllegalStateException(
                            "HTTP OpenAPI document authentication failed, status=" + status);
                }
                throw new IllegalStateException(
                        "HTTP OpenAPI document fetch failed, status=" + status);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP OpenAPI document fetch was interrupted", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "HTTP OpenAPI document fetch failed: " + e.getClass().getSimpleName(), e);
        }
    }

    public static URI resolveDocumentUri(String baseUrl, String configuredUrl) {
        URI uri;
        try {
            uri = URI.create(configuredUrl.trim());
            if (!uri.isAbsolute()) {
                uri = URI.create(HttpRequestSupport.resolveUrl(baseUrl, configuredUrl));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("HTTP OpenAPI document URL is invalid", e);
        }

        String scheme = StringUtils.lowerCase(uri.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(
                    "HTTP OpenAPI document URL must use http or https");
        }
        if (StringUtils.isBlank(uri.getHost())) {
            throw new IllegalArgumentException(
                    "HTTP OpenAPI document URL must include a host");
        }
        return uri;
    }

    private int positiveOrDefault(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
