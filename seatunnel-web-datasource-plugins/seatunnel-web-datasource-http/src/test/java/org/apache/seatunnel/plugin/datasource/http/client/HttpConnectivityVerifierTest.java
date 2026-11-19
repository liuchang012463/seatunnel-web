package org.apache.seatunnel.plugin.datasource.http.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.seatunnel.plugin.datasource.http.param.HttpAuthenticationType;
import org.apache.seatunnel.plugin.datasource.http.param.HttpConnectionParam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpConnectivityVerifierTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200, "ok"));
        server.createContext("/auth", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            respond(exchange, "Bearer expected-token".equals(authorization) ? 200 : 401, "auth");
        });
        server.createContext("/forbidden", exchange -> respond(exchange, 403, "forbidden"));
        server.createContext("/openapi.json", exchange -> respond(exchange, 200, "{\"openapi\":\"3.0.3\"}"));
        server.createContext("/openapi.yaml", exchange -> respond(exchange, 200, "openapi: 3.0.3\npaths: {}"));
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "slow");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldVerifySuccessfulGetAndBearerAuthentication() {
        HttpConnectionParam plain = param("/ok");
        assertTrue(new HttpConnectivityVerifier().checkDataSourceConnectivity(plain));

        HttpConnectionParam bearer = param("/auth");
        bearer.setAuthenticationType(HttpAuthenticationType.BEARER);
        bearer.setBearerToken("expected-token");
        assertTrue(new HttpConnectivityVerifier().checkDataSourceConnectivity(bearer));
    }

    @Test
    void shouldDistinguishAuthenticationFailureAndTimeoutWithoutLeakingSecret() {
        IllegalStateException forbidden = assertThrows(
                IllegalStateException.class,
                () -> new HttpConnectivityVerifier().checkDataSourceConnectivity(param("/forbidden")));
        assertTrue(forbidden.getMessage().contains("authentication failed"));

        HttpConnectionParam slow = param("/slow");
        slow.setSocketTimeoutMs(50);
        IllegalStateException timeout = assertThrows(
                IllegalStateException.class,
                () -> new HttpConnectivityVerifier().checkDataSourceConnectivity(slow));
        assertTrue(timeout.getMessage().contains("HttpTimeoutException"));
    }

    @Test
    void verifiesConfiguredOpenApiDocumentEvenWhenTheHealthCheckSucceeds() {
        HttpConnectionParam param = param("/ok");
        HttpConnectivityVerifier verifier = new HttpConnectivityVerifier();

        param.setOpenApiSpecUrl("/missing.json");
        IllegalStateException unavailable = assertThrows(IllegalStateException.class,
                () -> verifier.checkDataSourceConnectivity(param));
        assertTrue(unavailable.getMessage().contains("404"));

        param.setOpenApiSpecUrl("/ok");
        assertThrows(IllegalArgumentException.class, () -> verifier.checkDataSourceConnectivity(param));

        param.setOpenApiSpecUrl("/openapi.json");
        assertTrue(verifier.checkDataSourceConnectivity(param));
        param.setOpenApiSpecUrl("/openapi.yaml");
        assertTrue(verifier.checkDataSourceConnectivity(param));
    }

    private HttpConnectionParam param(String path) {
        HttpConnectionParam param = new HttpConnectionParam();
        param.setBaseUrl(baseUrl);
        param.setHealthCheckPath(path);
        param.setConnectTimeoutMs(1000);
        param.setSocketTimeoutMs(1000);
        return param;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
