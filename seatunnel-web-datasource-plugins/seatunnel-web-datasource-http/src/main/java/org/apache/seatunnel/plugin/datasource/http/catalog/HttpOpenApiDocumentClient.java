package org.apache.seatunnel.plugin.datasource.http.catalog;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.http.client.HttpRequestSupport;
import org.apache.seatunnel.plugin.datasource.http.param.HttpConnectionParam;

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

        URI documentUri = resolveDocumentUri(param, configuredUrl);
        int connectTimeoutMs = positiveOrDefault(param.getConnectTimeoutMs(), 12000);
        int socketTimeoutMs = positiveOrDefault(param.getSocketTimeoutMs(), 60000);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(documentUri)
                .timeout(Duration.ofMillis(socketTimeoutMs))
                .GET();
        Map<String, String> headers = HttpRequestSupport.mergeHeaders(param, Map.of());
        headers.putIfAbsent("Accept", "application/json");
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

    private URI resolveDocumentUri(HttpConnectionParam param, String configuredUrl) {
        URI uri;
        try {
            uri = URI.create(configuredUrl);
            if (!uri.isAbsolute()) {
                uri = URI.create(HttpRequestSupport.resolveUrl(param.getBaseUrl(), configuredUrl));
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
