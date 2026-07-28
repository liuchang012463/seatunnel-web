package org.apache.seatunnel.plugin.datasource.http.client;

import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.http.param.HttpConnectionParam;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class HttpConnectivityVerifier implements ConnectivityVerifier {

    @Override
    public boolean checkDataSourceConnectivity(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof HttpConnectionParam)) {
            throw new IllegalArgumentException("Invalid HTTP connection param type");
        }
        HttpConnectionParam param = (HttpConnectionParam) connectionParam;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(param.getConnectTimeoutMs()))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(HttpRequestSupport.resolveUrl(
                            param.getBaseUrl(), param.getHealthCheckPath())))
                    .timeout(Duration.ofMillis(param.getSocketTimeoutMs()))
                    .GET();
            for (Map.Entry<String, String> header
                    : HttpRequestSupport.mergeHeaders(param, Map.of()).entrySet()) {
                request.header(header.getKey(), header.getValue());
            }
            HttpResponse<Void> response = client.send(
                    request.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new IllegalStateException("HTTP authentication failed, status=" + status);
            }
            if (status < 200 || status >= 400) {
                throw new IllegalStateException("HTTP health check failed, status=" + status);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP health check was interrupted", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "HTTP health check failed: " + e.getClass().getSimpleName(), e);
        }
    }
}
