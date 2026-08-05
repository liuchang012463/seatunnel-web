package org.apache.seatunnel.plugin.datasource.elasticsearch.client;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.plugin.datasource.api.datasource.ConnectivityVerifier;
import org.apache.seatunnel.plugin.datasource.elasticsearch.param.ElasticsearchAuthType;
import org.apache.seatunnel.plugin.datasource.elasticsearch.param.ElasticsearchConnectionParam;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.datasource.ConnectionParam;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ElasticsearchHttpClient implements ConnectivityVerifier {

    @Override
    public boolean checkDataSourceConnectivity(ConnectionParam connectionParam) {
        ElasticsearchConnectionParam param = requireParam(connectionParam);
        String body = request(param, "/");
        if (StringUtils.isBlank(body)) {
            throw new IllegalStateException("Elasticsearch returned an empty response");
        }
        return true;
    }

    public List<String> listIndices(ElasticsearchConnectionParam param) {
        requireParam(param);
        String body = request(param,
                "/_cat/indices?format=json&h=index&expand_wildcards=open,hidden");
        List<Map<String, Object>> rows = JSONUtils.parseObject(
                body, new TypeReference<List<Map<String, Object>>>() {});
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object index = row.get("index");
            if (index != null && StringUtils.isNotBlank(String.valueOf(index))) {
                result.add(String.valueOf(index));
            }
        }
        return result;
    }

    private String request(ElasticsearchConnectionParam param, String path) {
        List<String> hosts = param.hostList();
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("Elasticsearch hosts cannot be empty");
        }

        Exception last = null;
        for (String host : hosts) {
            try {
                HttpClient client = buildClient(param);
                HttpRequest.Builder request = HttpRequest.newBuilder()
                        .uri(URI.create(joinPath(host, path)))
                        .timeout(Duration.ofMillis(param.getSocketTimeoutMs()))
                        .GET();
                addAuthentication(request, param);

                HttpResponse<String> response = client.send(
                        request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 401 || status == 403) {
                    throw new IllegalStateException(
                            "Elasticsearch authentication failed, status=" + status);
                }
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException(
                            "Elasticsearch request failed, status=" + status);
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Elasticsearch request was interrupted", e);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                last = e;
            }
        }

        throw new IllegalStateException(
                "Elasticsearch request failed: "
                        + (last == null ? "unknown error" : last.getClass().getSimpleName()), last);
    }

    private HttpClient buildClient(ElasticsearchConnectionParam param) throws Exception {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(param.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL);

        if (Boolean.FALSE.equals(param.getTlsVerifyCertificate())) {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            builder.sslContext(context);
        }

        if (Boolean.FALSE.equals(param.getTlsVerifyHostname())) {
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm("");
            builder.sslParameters(sslParameters);
        }
        return builder.build();
    }

    private void addAuthentication(HttpRequest.Builder request, ElasticsearchConnectionParam param) {
        ElasticsearchAuthType authType = param.getAuthType() == null
                ? ElasticsearchAuthType.NONE : param.getAuthType();
        switch (authType) {
            case BASIC -> request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (param.getUsername() + ":" + param.getPassword()).getBytes(StandardCharsets.UTF_8)));
            case API_KEY -> request.header("Authorization", "ApiKey " + Base64.getEncoder().encodeToString(
                    (param.getApiKeyId() + ":" + param.getApiKey()).getBytes(StandardCharsets.UTF_8)));
            case API_KEY_ENCODED -> request.header("Authorization", "ApiKey " + param.getApiKeyEncoded());
            case NONE -> {
            }
        }
    }

    private String joinPath(String host, String path) {
        String normalizedHost = host.endsWith("/")
                ? host.substring(0, host.length() - 1) : host;
        return normalizedHost + (path.startsWith("/") ? path : "/" + path);
    }

    private ElasticsearchConnectionParam requireParam(ConnectionParam connectionParam) {
        if (!(connectionParam instanceof ElasticsearchConnectionParam)) {
            throw new IllegalArgumentException("Invalid Elasticsearch connection param type");
        }
        return (ElasticsearchConnectionParam) connectionParam;
    }
}
