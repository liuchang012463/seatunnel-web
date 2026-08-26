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
import java.util.List;
import java.util.Optional;
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
                exchange -> respond(exchange, 200, "{\"code\":200,\"platform\":\"Airflow\",\"version\":\"1.12.10.0\"}"));
        server.createContext("/api/v1/services/ingestionPipelines/deploy/pipeline-id", exchange -> {
            deployMethod.set(exchange.getRequestMethod());
            deployBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"code\":200,\"platform\":\"Airflow\",\"version\":\"1.12.10.0\"}");
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
                exchange -> respond(exchange, 200, "{\"code\":200,\"platform\":\"Airflow\",\"version\":\"1.12.10.1\"}"));
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        assertThrows(MetadataIntegrationException.class, client::assertFixedVersion);
    }

    @Test
    void uses11210TriggerKillAndPipelineStatusPathsWithoutAirflowCalls() throws Exception {
        AtomicReference<String> triggerBody = new AtomicReference<>();
        AtomicReference<String> killBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/ingestionPipelines/trigger/pipeline-id", exchange -> {
            triggerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"code\":200,\"platform\":\"Airflow\",\"version\":\"1.12.10.0\"}");
        });
        server.createContext("/api/v1/services/ingestionPipelines/kill/pipeline-id", exchange -> {
            killBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"code\":200,\"platform\":\"Airflow\",\"version\":\"1.12.10.0\"}");
        });
        server.createContext("/api/v1/services/ingestionPipelines/st_ds_42.st_ds_42_metadata/pipelineStatus", exchange ->
                respond(exchange, 200,
                        "{\"data\":[{\"runId\":\"run-1\",\"pipelineState\":\"success\","
                                + "\"startDate\":1700000000,\"timestamp\":1700000010,\"endDate\":1700000020,"
                                + "\"status\":[{\"warnings\":[{},{}]}]}]}"));
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        client.triggerIngestionPipeline("pipeline-id");
        client.killIngestionPipeline("pipeline-id");
        List<OpenMetadataPipelineRun> runs = client.listIngestionPipelineRuns("st_ds_42.st_ds_42_metadata", 5);

        assertEquals("", triggerBody.get());
        assertEquals("", killBody.get());
        assertEquals(1, runs.size());
        assertEquals("success", runs.get(0).pipelineState());
        assertEquals(1700000010L, runs.get(0).timestamp());
        assertEquals(2, runs.get(0).warningsCount());
    }

    @Test
    void rejectsAnHttpSuccessWhoseManagedClientResponseReportsFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/ingestionPipelines/trigger/pipeline-id",
                exchange -> respond(exchange, 200, "{\"code\":500,\"platform\":\"Airflow\",\"version\":\"1.12.10.0\"}"));
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        assertThrows(MetadataIntegrationException.class, () -> client.triggerIngestionPipeline("pipeline-id"));
    }

    @Test
    void readsDatabaseServiceOwnershipFromThe11210DatabaseNameEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/databases/name/st_ds_42.orders", exchange -> respond(exchange, 200,
                "{\"id\":\"db-id\",\"fullyQualifiedName\":\"st_ds_42.orders\","
                        + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"}}"));
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        Optional<OpenMetadataDatabase> result = client.findDatabase("st_ds_42.orders");

        assertEquals("db-id", result.orElseThrow().id());
        assertEquals("st_ds_42", result.orElseThrow().serviceFullyQualifiedName());
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
