package org.apache.seatunnel.web.api.metadata.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.api.metadata.OpenMetadataProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    void readsOperatorHealthOnlyThroughOpenMetadataEndpoints() throws Exception {
        AtomicReference<String> versionPath = new AtomicReference<>();
        AtomicReference<String> statusPath = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/system/version", exchange -> {
            versionPath.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"version\":\"1.12.10\"}");
        });
        server.createContext("/api/v1/services/ingestionPipelines/status", exchange -> {
            statusPath.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"code\":200,\"platform\":\"Airflow\",\"version\":\"1.12.10.0\"}");
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        OpenMetadataHealth health = client.health();

        assertEquals("/api/v1/system/version", versionPath.get());
        assertEquals("/api/v1/services/ingestionPipelines/status", statusPath.get());
        assertEquals("1.12.10", health.serverVersion());
        assertEquals("1.12.10.0", health.ingestionVersion());
        org.junit.jupiter.api.Assertions.assertTrue(health.openMetadataUp());
        org.junit.jupiter.api.Assertions.assertTrue(health.orchestratorUp());
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

    @Test
    void readsDatabasesSchemasTablesAndTableDetailsUsingThe11210Paths() throws Exception {
        AtomicReference<String> databasesUri = new AtomicReference<>();
        AtomicReference<String> schemasUri = new AtomicReference<>();
        AtomicReference<String> tablesUri = new AtomicReference<>();
        AtomicReference<String> tableUri = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/databases", exchange -> {
            databasesUri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"data\":[{\"id\":\"db-id\",\"fullyQualifiedName\":\"st_ds_42.orders\","
                    + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"}}]}");
        });
        server.createContext("/api/v1/databaseSchemas", exchange -> {
            schemasUri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"data\":[{\"id\":\"schema-id\",\"name\":\"public\","
                    + "\"fullyQualifiedName\":\"st_ds_42.orders.public\","
                    + "\"database\":{\"fullyQualifiedName\":\"st_ds_42.orders\"},"
                    + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"}}]}");
        });
        server.createContext("/api/v1/tables", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/table-id")) {
                tableUri.set(exchange.getRequestURI().toString());
                respond(exchange, 200, "{\"id\":\"table-id\",\"name\":\"orders\","
                        + "\"fullyQualifiedName\":\"st_ds_42.orders.public.orders\","
                        + "\"tableType\":\"Regular\",\"description\":\"Orders\","
                        + "\"databaseSchema\":{\"fullyQualifiedName\":\"st_ds_42.orders.public\"},"
                        + "\"database\":{\"fullyQualifiedName\":\"st_ds_42.orders\"},"
                        + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"},"
                        + "\"columns\":[{\"name\":\"id\",\"dataType\":\"INT\","
                        + "\"constraint\":\"PRIMARY_KEY\",\"dataLength\":11,"
                        + "\"precision\":10,\"scale\":0,\"ordinalPosition\":1}],"
                        + "\"tableConstraints\":[{\"constraintType\":\"PRIMARY_KEY\","
                        + "\"columns\":[\"id\"]}]} ");
            } else {
                tablesUri.set(exchange.getRequestURI().toString());
                respond(exchange, 200, "{\"data\":[{\"id\":\"table-id\",\"name\":\"orders\","
                        + "\"fullyQualifiedName\":\"st_ds_42.orders.public.orders\","
                        + "\"tableType\":\"Regular\",\"service\":{\"fullyQualifiedName\":\"st_ds_42\"},"
                        + "\"columns\":[{\"name\":\"id\",\"dataType\":\"INT\","
                        + "\"constraint\":\"PRIMARY_KEY\",\"ordinalPosition\":1}],"
                        + "\"tableConstraints\":[{\"constraintType\":\"PRIMARY_KEY\","
                        + "\"columns\":[\"id\"]}]}]}");
            }
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        List<OpenMetadataDatabase> databases = client.listDatabases("st_ds_42", 20);
        List<OpenMetadataDatabaseSchema> schemas = client.listSchemas("st_ds_42.orders", 20);
        List<OpenMetadataTable> tables = client.listTables("st_ds_42.orders.public", true, 20);
        OpenMetadataTable table = client.getTable("table-id");

        assertEquals("db-id", databases.get(0).id());
        assertEquals("schema-id", schemas.get(0).getId());
        assertEquals("st_ds_42.orders", schemas.get(0).getDatabaseFullyQualifiedName());
        assertEquals("table-id", tables.get(0).getId());
        assertEquals("PRIMARY_KEY", tables.get(0).getColumns().get(0).getConstraint());
        assertEquals("orders", table.getName());
        assertEquals("st_ds_42", table.getServiceFullyQualifiedName());
        assertEquals("/api/v1/databases?service=st_ds_42&include=non-deleted&limit=20", databasesUri.get());
        assertEquals("/api/v1/databaseSchemas?database=st_ds_42.orders&limit=20&include=non-deleted", schemasUri.get());
        assertEquals("/api/v1/tables?databaseSchema=st_ds_42.orders.public&fields=columns,tableConstraints&include=non-deleted&limit=20", tablesUri.get());
        assertEquals(
                "/api/v1/tables/table-id?fields=columns,tableConstraints&include=non-deleted",
                tableUri.get());
    }

    @Test
    void followsOpenMetadata11210AfterCursorAndPreservesPagingTotal() throws Exception {
        AtomicReference<String> firstUri = new AtomicReference<>();
        AtomicReference<String> secondUri = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/databases", exchange -> {
            if (exchange.getRequestURI().getQuery().contains("after=")) {
                secondUri.set(exchange.getRequestURI().toString());
                respond(exchange, 200, "{\"data\":[{\"id\":\"db-2\",\"fullyQualifiedName\":\"st_ds_42.archive\","
                        + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"}}],"
                        + "\"paging\":{\"total\":2}}");
            } else {
                firstUri.set(exchange.getRequestURI().toString());
                respond(exchange, 200, "{\"data\":[{\"id\":\"db-1\",\"fullyQualifiedName\":\"st_ds_42.orders\","
                        + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"}}],"
                        + "\"paging\":{\"total\":2,\"after\":\"next token\"}}");
            }
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));
        OpenMetadataPage<OpenMetadataDatabase> first = client.listDatabasesPage("st_ds_42", 1000, null);
        OpenMetadataPage<OpenMetadataDatabase> second = client.listDatabasesPage("st_ds_42", 1000, first.after());

        assertEquals(2L, first.total());
        assertEquals(2L, second.total());
        assertEquals("/api/v1/databases?service=st_ds_42&include=non-deleted&limit=1000", firstUri.get());
        assertEquals("/api/v1/databases?service=st_ds_42&include=non-deleted&limit=1000&after=next%20token",
                secondUri.get());
    }

    @Test
    void readsLatestProfilesAndUpdatesThe11210TableProfilerConfig() throws Exception {
        AtomicReference<String> latestUri = new AtomicReference<>();
        AtomicReference<String> columnUri = new AtomicReference<>();
        AtomicReference<String> configGetMethod = new AtomicReference<>();
        AtomicReference<String> configPutMethod = new AtomicReference<>();
        AtomicReference<String> configUri = new AtomicReference<>();
        AtomicReference<String> configBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/tables/st_ds_42.orders.public.orders/tableProfile/latest", exchange -> {
            latestUri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"id\":\"table-id\",\"name\":\"orders\","
                    + "\"fullyQualifiedName\":\"st_ds_42.orders.public.orders\","
                    + "\"profile\":{\"timestamp\":1700000000000,\"rowCount\":100,\"columnCount\":1},"
                    + "\"columns\":[{\"name\":\"id\",\"dataType\":\"INT\","
                    + "\"constraint\":\"PRIMARY_KEY\",\"profile\":{\"name\":\"id\","
                    + "\"timestamp\":1700000000000,\"valuesCount\":100,\"validCount\":100,"
                    + "\"nullCount\":0,\"distinctCount\":100,\"uniqueCount\":100,"
                    + "\"distinctProportion\":1,\"uniqueProportion\":1,\"min\":1,\"max\":100,"
                    + "\"mean\":50.5}}]}");
        });
        server.createContext("/api/v1/tables/st_ds_42.orders.public.orders/columnProfile", exchange -> {
            columnUri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"data\":[{\"name\":\"id\",\"timestamp\":1700000000000,"
                    + "\"valuesCount\":100,\"nullCount\":0,\"distinctCount\":100}]} ");
        });
        server.createContext("/api/v1/tables/table-id/tableProfilerConfig", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                configGetMethod.set(exchange.getRequestMethod());
                configBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, "{\"tableProfilerConfig\":{\"excludeColumns\":[\"secret\"]}}");
            } else {
                configPutMethod.set(exchange.getRequestMethod());
                configBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, "{\"tableProfilerConfig\":{\"excludeColumns\":[\"id\"]}}");
            }
            configUri.set(exchange.getRequestURI().toString());
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));
        OpenMetadataTableProfile profile = client.getLatestTableProfile("st_ds_42.orders.public.orders");
        List<OpenMetadataColumnProfile> columns = client.listColumnProfiles(
                "st_ds_42.orders.public.orders", 1700000000000L, 1700000001000L);
        JsonNode config = client.getTableProfilerConfig("table-id");
        JsonNode updated = client.updateTableProfilerConfig(
                "table-id", new ObjectMapper().readTree("{\"excludeColumns\":[\"id\"]}"));

        assertEquals(100L, profile.getRowCount());
        assertEquals(1, profile.getColumns().size());
        assertEquals(100L, profile.getColumns().get(0).getDistinctCount());
        assertEquals(1, columns.size());
        assertEquals("id", columns.get(0).getName());
        assertEquals("/api/v1/tables/st_ds_42.orders.public.orders/tableProfile/latest?includeColumnProfile=true", latestUri.get());
        assertEquals("/api/v1/tables/st_ds_42.orders.public.orders/columnProfile?startTs=1700000000000&endTs=1700000001000", columnUri.get());
        assertEquals("GET", configGetMethod.get());
        assertEquals("PUT", configPutMethod.get());
        assertEquals("/api/v1/tables/table-id/tableProfilerConfig", configUri.get());
        assertEquals("{\"excludeColumns\":[\"id\"]}", configBody.get());
        assertEquals("secret", config.path("tableProfilerConfig").path("excludeColumns").get(0).asText());
        assertEquals("id", updated.path("tableProfilerConfig").path("excludeColumns").get(0).asText());
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
