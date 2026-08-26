package org.apache.seatunnel.web.api.metadata.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.api.metadata.OpenMetadataProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenMetadataRestClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void usesExact11210ApiPathsAndSendsNoDeployBody() throws Exception {
        AtomicReference<String> deployMethod = new AtomicReference<>();
        AtomicReference<String> deployBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/system/version", exchange -> respond(exchange, 200, "{\"version\":\"1.12.10\"}"));
        server.createContext("/api/v1/services/ingestionPipelines/status",
                exchange -> respond(exchange, 200, "{\"version\":\"1.12.10.0\"}"));
        server.createContext("/api/v1/services/ingestionPipelines/deploy/pipeline-id", exchange -> {
            deployMethod.set(exchange.getRequestMethod());
            deployBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{}");
        });
        server.start();

        OpenMetadataProperties properties = properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api");
        OpenMetadataRestClient client = new OpenMetadataRestClient(properties);

        client.assertFixedVersion();
        client.deployIngestionPipeline("pipeline-id");

        assertEquals("POST", deployMethod.get());
        assertEquals("", deployBody.get());
    }

    @Test
    void rejectsAnAirflowUrlBeforeAnyNetworkRequest() {
        OpenMetadataRestClient client = new OpenMetadataRestClient(properties("http://localhost:8082/api"));

        assertThrows(MetadataIntegrationException.class, client::assertFixedVersion);
    }

    @Test
    void rejectsAnIngestionManagedBuildOutsideTheFixedPatch() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/system/version", exchange -> respond(exchange, 200, "{\"version\":\"1.12.10\"}"));
        server.createContext("/api/v1/services/ingestionPipelines/status",
                exchange -> respond(exchange, 200, "{\"version\":\"1.12.10.1\"}"));
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        assertThrows(MetadataIntegrationException.class, client::assertFixedVersion);
    }

    private static OpenMetadataProperties properties(String baseUrl) {
        OpenMetadataProperties properties = new OpenMetadataProperties();
        properties.setBaseUrl(baseUrl);
        properties.setToken("test-jwt");
        return properties;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
