package org.apache.seatunnel.web.api.metadata.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.api.metadata.ExplorationAssetType;
import org.apache.seatunnel.web.api.metadata.OpenMetadataProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        server.createContext("/api/v1/system/version", exchange -> respond(exchange, 200, "{\"version\":\"2.0.4\"}"));
        server.createContext("/api/v1/services/ingestionPipelines/status",
                exchange -> respond(exchange, 200, "{\"code\":200,\"platform\":\"Airflow\",\"version\":\"2.0.4.0\"}"));
        server.createContext("/api/v1/services/ingestionPipelines/deploy/pipeline-id", exchange -> {
            deployMethod.set(exchange.getRequestMethod());
            deployBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"code\":200,\"platform\":\"Airflow\",\"version\":\"2.0.4.0\"}");
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
    void retainsTriggerErrorCodeAndHttpStatusFromOpenMetadata() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/ingestionPipelines/trigger/pipeline-id",
                exchange -> respond(exchange, 400, "{\"message\":\"Failed to trigger IngestionPipeline\"}"));
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        MetadataIntegrationException error = assertThrows(
                MetadataIntegrationException.class,
                () -> client.triggerIngestionPipeline("pipeline-id"));

        assertEquals(MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR, error.getErrorCode());
        assertEquals(400, error.getHttpStatusCode());
    }

    @Test
    void rejectsAnAirflowUrlBeforeAnyNetworkRequest() {
        OpenMetadataRestClient client = new OpenMetadataRestClient(properties("http://localhost:8082/api"));

        assertThrows(MetadataIntegrationException.class, client::assertFixedVersion);
    }

    @Test
    void rejectsAnIngestionManagedBuildOutsideTheFixedPatch() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/system/version", exchange -> respond(exchange, 200, "{\"version\":\"2.0.4\"}"));
        server.createContext("/api/v1/services/ingestionPipelines/status",
                exchange -> respond(exchange, 200, "{\"code\":200,\"platform\":\"Airflow\",\"version\":\"2.0.4.1\"}"));
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
            respond(exchange, 200, "{\"version\":\"2.0.4\"}");
        });
        server.createContext("/api/v1/services/ingestionPipelines/status", exchange -> {
            statusPath.set(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"code\":200,\"platform\":\"Airflow\",\"version\":\"2.0.4.0\"}");
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        OpenMetadataHealth health = client.health();

        assertEquals("/api/v1/system/version", versionPath.get());
        assertEquals("/api/v1/services/ingestionPipelines/status", statusPath.get());
        assertEquals("2.0.4", health.serverVersion());
        assertEquals("2.0.4.0", health.ingestionVersion());
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
            respond(exchange, 200, "{\"code\":200,\"platform\":\"Airflow\",\"version\":\"2.0.4.0\"}");
        });
        server.createContext("/api/v1/services/ingestionPipelines/kill/pipeline-id", exchange -> {
            killBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"code\":200,\"platform\":\"Airflow\",\"version\":\"2.0.4.0\"}");
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
    void enablesAnExistingDagThroughTheOpenMetadataToggleResource() throws Exception {
        String pipelineId = "00000000-0000-0000-0000-000000000012";
        AtomicReference<String> patchBody = new AtomicReference<>();
        AtomicReference<String> toggleMethod = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/ingestionPipelines/" + pipelineId, exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "{\"id\":\"" + pipelineId + "\",\"name\":\"diag-pipeline\","
                        + "\"fullyQualifiedName\":\"svc.diag-pipeline\",\"enabled\":true,"
                        + "\"pipelineType\":\"metadata\"}");
            } else {
                patchBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, "{\"id\":\"" + pipelineId + "\",\"name\":\"diag-pipeline\","
                        + "\"fullyQualifiedName\":\"svc.diag-pipeline\",\"enabled\":false,"
                        + "\"pipelineType\":\"metadata\"}");
            }
        });
        server.createContext("/api/v1/services/ingestionPipelines/toggleIngestion/" + pipelineId, exchange -> {
            toggleMethod.set(exchange.getRequestMethod());
            respond(exchange, 200, "{\"id\":\"" + pipelineId + "\",\"enabled\":true}");
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        client.enableIngestionPipeline(pipelineId);

        assertTrue(patchBody.get().contains("enabled"));
        assertEquals("POST", toggleMethod.get());
    }

    @Test
    void rejectsAnHttpSuccessWhoseManagedClientResponseReportsFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/ingestionPipelines/trigger/pipeline-id",
                exchange -> respond(exchange, 200, "{\"code\":500,\"platform\":\"Airflow\",\"version\":\"2.0.4.0\"}"));
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        assertThrows(MetadataIntegrationException.class, () -> client.triggerIngestionPipeline("pipeline-id"));
    }

    @Test
    void createsDatabaseServiceWithSdkCreateRequestWithoutEntityFields() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/databaseServices/name/diag-service", exchange ->
                respond(exchange, 404, "{\"message\":\"not found\"}"));
        server.createContext("/api/v1/services/databaseServices", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000010\","
                    + "\"name\":\"diag-service\",\"fullyQualifiedName\":\"diag-service\","
                    + "\"serviceType\":\"CustomDatabase\"}");
        });
        server.start();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode request = mapper.readTree("{"
                + "\"name\":\"diag-service\",\"displayName\":\"Diag\","
                + "\"serviceType\":\"CustomDatabase\","
                + "\"connection\":{\"config\":{\"type\":\"CustomDatabase\","
                + "\"sourcePythonClass\":\"diag.Source\","
                + "\"connectionOptions\":{\"hostPort\":\"127.0.0.1:1\","
                + "\"username\":\"diag\",\"password\":\"diag\"}}}}");
        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        OpenMetadataEntity result = client.upsertDatabaseService(request);

        assertEquals("00000000-0000-0000-0000-000000000010", result.id());
        assertEquals("POST", method.get());
        assertTrue(!body.get().contains("\"version\""));
        assertTrue(!body.get().contains("\"deleted\""));
    }

    @Test
    void createsIngestionPipelineWithSdkCreateRequestWithoutEntityFields() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/ingestionPipelines/name/diag-pipeline", exchange ->
                respond(exchange, 404, "{\"message\":\"not found\"}"));
        server.createContext("/api/v1/services/ingestionPipelines", exchange -> {
            method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000011\","
                    + "\"name\":\"diag-pipeline\",\"fullyQualifiedName\":\"diag-pipeline\","
                    + "\"pipelineType\":\"metadata\"}");
        });
        server.start();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode request = mapper.readTree("{"
                + "\"name\":\"diag-pipeline\",\"displayName\":\"Diag pipeline\","
                + "\"pipelineType\":\"metadata\","
                + "\"sourceConfig\":{\"config\":{\"type\":\"DatabaseMetadata\"}},"
                + "\"airflowConfig\":{\"pausePipeline\":true,\"scheduleInterval\":null}}");
        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        OpenMetadataEntity result = client.upsertIngestionPipeline(request);

        assertEquals("00000000-0000-0000-0000-000000000011", result.id());
        assertEquals("POST", method.get());
        assertTrue(!body.get().contains("\"version\""));
        assertTrue(!body.get().contains("\"deleted\""));
    }

    @Test
    void readsDatabaseServiceOwnershipFromThe11210DatabaseNameEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/databases/name/st_ds_42.orders", exchange -> respond(exchange, 200,
                "{\"id\":\"00000000-0000-0000-0000-000000000001\",\"fullyQualifiedName\":\"st_ds_42.orders\","
                        + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"}}"));
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        Optional<OpenMetadataDatabase> result = client.findDatabase("st_ds_42.orders");

        assertEquals("00000000-0000-0000-0000-000000000001", result.orElseThrow().id());
        assertEquals("st_ds_42", result.orElseThrow().serviceFullyQualifiedName());
    }

    @Test
    void keepsCollectionOwnershipContextWhenOpenMetadataOmitsDatabaseReferences() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/databases", exchange -> respond(exchange, 200,
                "{\"data\":[{\"id\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"fullyQualifiedName\":\"st_ds_42.orders\"}]}"));
        server.createContext("/api/v1/databaseSchemas", exchange -> respond(exchange, 200,
                "{\"data\":[{\"id\":\"00000000-0000-0000-0000-000000000003\","
                        + "\"name\":\"public\",\"fullyQualifiedName\":\"st_ds_42.orders.public\"}]}"));
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        List<OpenMetadataDatabase> databases = client.listDatabases("st_ds_42", 20);
        List<OpenMetadataDatabaseSchema> schemas = client.listSchemas("st_ds_42.orders", 20);

        assertEquals("st_ds_42", databases.get(0).serviceFullyQualifiedName());
        assertEquals("st_ds_42.orders", schemas.get(0).getDatabaseFullyQualifiedName());
    }

    @Test
    void readsDatabasesSchemasTablesAndTableDetailsUsingThe11210Paths() throws Exception {
        AtomicReference<String> databasesUri = new AtomicReference<>();
        AtomicReference<String> schemasUri = new AtomicReference<>();
        AtomicReference<String> tablesUri = new AtomicReference<>();
        AtomicReference<String> databaseTablesUri = new AtomicReference<>();
        AtomicReference<String> tableUri = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/databases", exchange -> {
            databasesUri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"data\":[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"fullyQualifiedName\":\"st_ds_42.orders\","
                    + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"}}]}");
        });
        server.createContext("/api/v1/databaseSchemas", exchange -> {
            schemasUri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"data\":[{\"id\":\"00000000-0000-0000-0000-000000000003\",\"name\":\"public\","
                    + "\"fullyQualifiedName\":\"st_ds_42.orders.public\","
                    + "\"database\":{\"fullyQualifiedName\":\"st_ds_42.orders\"},"
                    + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"}}]}");
        });
        server.createContext("/api/v1/tables", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/table-id")) {
                tableUri.set(exchange.getRequestURI().toString());
                respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000004\",\"name\":\"orders\","
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
                String query = exchange.getRequestURI().getQuery();
                if (query != null && query.contains("database=")) {
                    databaseTablesUri.set(exchange.getRequestURI().toString());
                } else {
                    tablesUri.set(exchange.getRequestURI().toString());
                }
                respond(exchange, 200, "{\"data\":[{\"id\":\"00000000-0000-0000-0000-000000000004\",\"name\":\"orders\","
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
        List<OpenMetadataTable> databaseTables = client.listTablesByDatabasePage(
                "st_ds_42.orders", true, 20, null).data();
        OpenMetadataTable table = client.getTable("table-id");

        assertEquals("00000000-0000-0000-0000-000000000001", databases.get(0).id());
        assertEquals("00000000-0000-0000-0000-000000000003", schemas.get(0).getId());
        assertEquals("st_ds_42.orders", schemas.get(0).getDatabaseFullyQualifiedName());
        assertEquals("00000000-0000-0000-0000-000000000004", tables.get(0).getId());
        assertEquals("00000000-0000-0000-0000-000000000004", databaseTables.get(0).getId());
        assertEquals("PRIMARY_KEY", tables.get(0).getColumns().get(0).getConstraint());
        assertEquals("orders", table.getName());
        assertEquals("st_ds_42", table.getServiceFullyQualifiedName());
        assertQuery("/api/v1/databases", databasesUri.get(),
                "service=st_ds_42", "include=non-deleted", "limit=20");
        assertQuery("/api/v1/databaseSchemas", schemasUri.get(),
                "database=st_ds_42.orders", "limit=20", "include=non-deleted");
        assertQuery("/api/v1/tables", tablesUri.get(),
                "databaseSchema=st_ds_42.orders.public", "fields=columns,tableConstraints",
                "include=non-deleted", "limit=20");
        assertQuery("/api/v1/tables", databaseTablesUri.get(),
                "database=st_ds_42.orders", "fields=columns,tableConstraints",
                "include=non-deleted", "limit=20");
        assertQuery("/api/v1/tables/table-id", tableUri.get(),
                "fields=columns,tableConstraints,tags,domains", "include=non-deleted");
    }

    @Test
    void followsOpenMetadata11210AfterCursorAndPreservesPagingTotal() throws Exception {
        AtomicReference<String> firstUri = new AtomicReference<>();
        AtomicReference<String> secondUri = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/databases", exchange -> {
            if (exchange.getRequestURI().getQuery().contains("after=")) {
                secondUri.set(exchange.getRequestURI().toString());
                respond(exchange, 200, "{\"data\":[{\"id\":\"00000000-0000-0000-0000-000000000002\",\"fullyQualifiedName\":\"st_ds_42.archive\","
                        + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"}}],"
                        + "\"paging\":{\"total\":2}}");
            } else {
                firstUri.set(exchange.getRequestURI().toString());
                respond(exchange, 200, "{\"data\":[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"fullyQualifiedName\":\"st_ds_42.orders\","
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
        assertQuery("/api/v1/databases", firstUri.get(),
                "service=st_ds_42", "include=non-deleted", "limit=1000");
        assertQuery("/api/v1/databases", secondUri.get(),
                "service=st_ds_42", "include=non-deleted", "limit=1000", "after=next token");
    }

    @Test
    void usesTheServiceFilterWhenDatabaseListOmitsTheServiceReference() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/databases", exchange -> respond(exchange, 200,
                "{\"data\":[{\"id\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"fullyQualifiedName\":\"st_ds_42.orders\"}]}"));
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        List<OpenMetadataDatabase> databases = client.listDatabases("st_ds_42", 20);

        assertEquals(1, databases.size());
        assertEquals("st_ds_42", databases.get(0).serviceFullyQualifiedName());
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

    @Test
    void readsTopicAssetsWithSchemaAndMessages() throws Exception {
        AtomicReference<String> listUri = new AtomicReference<>();
        AtomicReference<String> detailUri = new AtomicReference<>();
        AtomicReference<String> sampleUri = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/topics", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/sampleData")) {
                sampleUri.set(exchange.getRequestURI().toString());
                respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000010\","
                        + "\"sampleData\":{\"messages\":[\"{\\\"id\\\":1}\"]}}");
                return;
            }
            if (path.endsWith("/topic-id")) {
                detailUri.set(exchange.getRequestURI().toString());
                respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000010\","
                        + "\"name\":\"orders\",\"fullyQualifiedName\":\"st_ds_42.orders\","
                        + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"},"
                        + "\"messageSchema\":{\"schemaFields\":[{\"name\":\"id\",\"dataType\":\"INT\","
                        + "\"children\":[{\"name\":\"nested\",\"dataType\":\"STRING\"}]}]},"
                        + "\"partitions\":3}");
                return;
            }
            listUri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"data\":[{\"id\":\"00000000-0000-0000-0000-000000000010\","
                    + "\"name\":\"orders\",\"fullyQualifiedName\":\"st_ds_42.orders\","
                    + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"},"
                    + "\"messageSchema\":{\"schemaFields\":[{\"name\":\"id\",\"dataType\":\"INT\"},"
                    + "{\"name\":\"amount\",\"dataType\":\"DOUBLE\"}]}}]}");
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        OpenMetadataPage<OpenMetadataAsset> topics =
                client.listAssetsPage(ExplorationAssetType.TOPIC, "st_ds_42", null, false, 20, null);
        OpenMetadataAssetDetail detail =
                client.getAssetDetail(ExplorationAssetType.TOPIC, "topic-id");

        assertEquals(1, topics.data().size());
        assertEquals("orders", topics.data().get(0).name());
        assertEquals(2, topics.data().get(0).fieldCount());
        assertEquals("topic", topics.data().get(0).entityType());
        assertEquals("st_ds_42", topics.data().get(0).serviceFullyQualifiedName());
        assertEquals(1, detail.fields().size());
        assertEquals("id", detail.fields().get(0).name());
        assertEquals("INT", detail.fields().get(0).dataType());
        assertEquals("nested", detail.fields().get(0).children().get(0).name());
        assertEquals("st_ds_42", detail.asset().serviceFullyQualifiedName());
        assertEquals(3, detail.properties().get("partitions"));
        assertNotNull(detail.sample());
        assertEquals(List.of("{\"id\":1}"), detail.sample().messages());
        assertQuery("/api/v1/topics", listUri.get(), "service=st_ds_42", "fields=tags");
        assertTrue(detailUri.get().contains("fields=service"));
        assertEquals("/api/v1/topics/topic-id/sampleData", sampleUri.get().split("\\?")[0]);
    }

    @Test
    void readsContainerSampleDataFromItsDedicatedEndpoint() throws Exception {
        AtomicReference<String> samplePath = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/containers", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            if (path.endsWith("/sampleData")) {
                samplePath.set(path);
                respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000012\","
                        + "\"sampleData\":{\"columns\":[\"id\"],\"rows\":[[\"1\"],[\"2\"]]}}");
                return;
            }
            respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000012\","
                    + "\"name\":\"orders\",\"fullyQualifiedName\":\"st_ds_42.orders\","
                    + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"},"
                    + "\"dataModel\":{\"columns\":[{\"name\":\"id\",\"dataType\":\"INT\","
                    + "\"children\":[{\"name\":\"nested\",\"dataType\":\"STRING\"}]}]}}");
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        String resourceId = "st_ds_42.orders & more";
        OpenMetadataAssetDetail detail =
                client.getAssetDetail(ExplorationAssetType.CONTAINER, resourceId);

        assertEquals(1, detail.fields().size());
        assertEquals("nested", detail.fields().get(0).children().get(0).name());
        assertEquals(Boolean.TRUE, detail.properties().get("dataModelAvailable"));
        assertNotNull(detail.sample());
        assertEquals(List.of("id"), detail.sample().columns());
        assertEquals(List.of(List.of("1"), List.of("2")), detail.sample().rows());
        // The hand-built path has to encode the id; the SDK does it for the entity read itself.
        assertEquals("/api/v1/containers/st_ds_42.orders%20%26%20more/sampleData", samplePath.get());
    }

    @Test
    void keepsSchemaWhenSampleReadIsForbidden() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/containers", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/sampleData")) {
                respond(exchange, 403, "{\"message\":\"not allowed\"}");
                return;
            }
            respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000012\","
                    + "\"name\":\"orders\",\"fullyQualifiedName\":\"st_ds_42.orders\","
                    + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"},"
                    + "\"dataModel\":{\"columns\":[{\"name\":\"id\",\"dataType\":\"INT\"}]}}");
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        OpenMetadataAssetDetail detail =
                client.getAssetDetail(ExplorationAssetType.CONTAINER, "container-id");

        assertEquals(1, detail.fields().size());
        assertTrue(detail.samplePermissionDenied());
        assertNull(detail.sample());
    }

    @Test
    void reportsSampleServerErrorsWithoutLosingSchema() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/containers", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/sampleData")) {
                respond(exchange, 500, "{\"message\":\"boom\"}");
                return;
            }
            respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000012\","
                    + "\"name\":\"orders\",\"fullyQualifiedName\":\"st_ds_42.orders\","
                    + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"},"
                    + "\"dataModel\":{\"columns\":[{\"name\":\"id\",\"dataType\":\"INT\"}]}}");
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        OpenMetadataAssetDetail detail =
                client.getAssetDetail(ExplorationAssetType.CONTAINER, "container-id");

        assertEquals(1, detail.fields().size());
        assertTrue(detail.sampleError());
        assertNull(detail.sample());
    }

    @Test
    void readsDirectDirectoryChildrenAndFiltersUnknownFamilies() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/drives/directories", exchange -> respond(exchange, 200,
                "{\"id\":\"00000000-0000-0000-0000-000000000020\","
                        + "\"name\":\"data\",\"fullyQualifiedName\":\"st_ds_42.data\","
                        + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"},"
                        + "\"children\":["
                        + "{\"id\":\"00000000-0000-0000-0000-000000000021\",\"type\":\"directory\","
                        + "\"name\":\"2026\",\"fullyQualifiedName\":\"st_ds_42.data.2026\"},"
                        + "{\"id\":\"00000000-0000-0000-0000-000000000022\",\"type\":\"file\","
                        + "\"name\":\"orders.csv\",\"fullyQualifiedName\":\"st_ds_42.data.orders.csv\"},"
                        + "{\"id\":\"00000000-0000-0000-0000-000000000023\",\"type\":\"spreadsheet\","
                        + "\"name\":\"sheet\",\"fullyQualifiedName\":\"st_ds_42.data.sheet\"}]}"));
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        OpenMetadataAssetDetail detail =
                client.getAssetDetail(ExplorationAssetType.DIRECTORY, "dir-id");

        assertEquals(2, detail.children().size());
        assertEquals("directory", detail.children().get(0).entityType());
        assertEquals("file", detail.children().get(1).entityType());
        assertEquals("st_ds_42.data", detail.children().get(1).parentFullyQualifiedName());
    }

    @Test
    void requestsTheExplicitParentProjection() throws Exception {
        AtomicReference<String> uri = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/drives/directories", exchange -> {
            uri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000020\","
                    + "\"name\":\"data\",\"fullyQualifiedName\":\"st_ds_42.data\","
                    + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"},"
                    + "\"parent\":{\"id\":\"00000000-0000-0000-0000-000000000019\","
                    + "\"type\":\"directory\",\"fullyQualifiedName\":\"st_ds_42\"}}");
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        client.getAssetDetail(
                ExplorationAssetType.DIRECTORY, "dir-id", false, "service,parent");

        assertQuery("/api/v1/drives/directories/dir-id", uri.get(),
                "fields=service,parent");
    }

    @Test
    void listsDriveRootsWithRootFilter() throws Exception {
        AtomicReference<String> directoryUri = new AtomicReference<>();
        AtomicReference<String> fileUri = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/drives/directories", exchange -> {
            directoryUri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"data\":[]}");
        });
        server.createContext("/api/v1/drives/files", exchange -> {
            fileUri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"data\":[]}");
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        client.listAssetsPage(ExplorationAssetType.DIRECTORY, "st_ds_42", null, true, 20, null);
        client.listAssetsPage(ExplorationAssetType.FILE, "st_ds_42", null, true, 20, null);

        assertQuery("/api/v1/drives/directories", directoryUri.get(),
                "service=st_ds_42", "root=true");
        assertQuery("/api/v1/drives/files", fileUri.get(),
                "service=st_ds_42", "root=true");
    }

    @Test
    void readsSearchIndexSampleMessagesFromTheDedicatedEndpoint() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/searchIndexes", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/sampleData")) {
                respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000030\","
                        + "\"sampleData\":{\"messages\":[\"{\\\"title\\\":\\\"a\\\"}\"]}}");
                return;
            }
            respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000030\","
                    + "\"name\":\"docs\",\"fullyQualifiedName\":\"st_ds_42.docs\","
                    + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"},"
                    + "\"fields\":[{\"name\":\"title\",\"dataType\":\"TEXT\","
                    + "\"children\":[{\"name\":\"keyword\",\"dataType\":\"KEYWORD\"}]}]}");
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        OpenMetadataAssetDetail detail =
                client.getAssetDetail(ExplorationAssetType.SEARCH_INDEX, "index-id", true);

        assertEquals(1, detail.fields().size());
        assertEquals("title", detail.fields().get(0).name());
        assertEquals("keyword", detail.fields().get(0).children().get(0).name());
        assertNotNull(detail.sample());
        assertEquals(List.of("{\"title\":\"a\"}"), detail.sample().messages());
    }

    @Test
    void listsContainerChildrenWithOffsetPagination() throws Exception {
        AtomicReference<String> uri = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/containers", exchange -> {
            uri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"data\":[]}");
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        client.listContainerChildren("st_ds_42.bucket", 20, 40);

        URI request = URI.create(uri.get());
        assertTrue(request.getPath().endsWith("/children"));
        List<String> params = Arrays.asList(request.getQuery().split("&"));
        assertTrue(params.contains("limit=20"));
        assertTrue(params.contains("offset=40"));
    }

    @Test
    void narrowsApiEndpointsToTheirCollection() throws Exception {
        AtomicReference<String> uri = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/apiEndpoints", exchange -> {
            uri.set(exchange.getRequestURI().toString());
            respond(exchange, 200, "{\"data\":["
                    + "{\"id\":\"00000000-0000-0000-0000-000000000041\","
                    + "\"name\":\"GET /orders\",\"fullyQualifiedName\":\"st_ds_42.default.GET /orders\","
                    + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"}},"
                    + "{\"id\":\"00000000-0000-0000-0000-000000000042\","
                    + "\"name\":\"GET /invoices\",\"fullyQualifiedName\":\"st_ds_42.invoices.GET /invoices\","
                    + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"}}]}");
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        OpenMetadataPage<OpenMetadataAsset> page = client.listAssetsPage(
                ExplorationAssetType.API_ENDPOINT, "st_ds_42", "st_ds_42.default",
                false, 20, null);

        // OpenMetadata 2.0.4 ignores the apiCollection list filter, so the endpoints of
        // the service are narrowed down to the requested collection here.
        assertEquals(1, page.data().size());
        assertEquals("st_ds_42.default.GET /orders", page.data().get(0).fullyQualifiedName());
        assertQuery("/api/v1/apiEndpoints", uri.get(), "service=st_ds_42");
    }

    @Test
    void readsApiEndpointRequestAndResponseSchemas() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/apiEndpoints", exchange -> respond(exchange, 200,
                "{\"id\":\"00000000-0000-0000-0000-000000000031\","
                        + "\"name\":\"GET /orders\",\"fullyQualifiedName\":\"st_ds_42.default.GET /orders\","
                        + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"},"
                        + "\"apiCollection\":{\"fullyQualifiedName\":\"st_ds_42.default\"},"
                        + "\"requestMethod\":\"GET\","
                        + "\"requestSchema\":{\"schemaFields\":[{\"name\":\"id\",\"dataType\":\"STRING\","
                        + "\"children\":[{\"name\":\"nested\",\"dataType\":\"STRING\"}]}]},"
                        + "\"responseSchema\":{\"schemaFields\":[{\"name\":\"status\",\"dataType\":\"STRING\"}]}}"));
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        OpenMetadataAssetDetail detail =
                client.getAssetDetail(ExplorationAssetType.API_ENDPOINT, "endpoint-id", true);

        assertEquals(1, detail.requestFields().size());
        assertEquals("id", detail.requestFields().get(0).name());
        assertEquals("nested", detail.requestFields().get(0).children().get(0).name());
        assertEquals(1, detail.responseFields().size());
        assertEquals("status", detail.responseFields().get(0).name());
        assertEquals("GET", detail.properties().get("requestMethod"));
        assertNull(detail.sample());
    }

    @Test
    void treatsAMissingSampleEndpointAsNoSample() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/containers", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/sampleData")) {
                respond(exchange, 404, "{\"message\":\"not found\"}");
                return;
            }
            respond(exchange, 200, "{\"id\":\"00000000-0000-0000-0000-000000000012\","
                    + "\"name\":\"orders\",\"fullyQualifiedName\":\"st_ds_42.orders\","
                    + "\"service\":{\"fullyQualifiedName\":\"st_ds_42\"},"
                    + "\"dataModel\":{\"columns\":[{\"name\":\"id\",\"dataType\":\"INT\"}]}}");
        });
        server.start();

        OpenMetadataRestClient client = new OpenMetadataRestClient(
                properties("http://127.0.0.1:" + server.getAddress().getPort() + "/api"));

        OpenMetadataAssetDetail detail =
                client.getAssetDetail(ExplorationAssetType.CONTAINER, "container-id", true);

        assertEquals(1, detail.fields().size());
        assertNull(detail.sample());
        assertFalse(detail.samplePermissionDenied());
        assertFalse(detail.sampleError());
    }

    private static OpenMetadataProperties properties(String baseUrl) {
        OpenMetadataProperties properties = new OpenMetadataProperties();
        properties.setBaseUrl(baseUrl);
        properties.setToken("test-jwt");
        return properties;
    }

    private static void assertQuery(String expectedPath, String actualUri, String... expectedParams) {
        URI uri = URI.create(actualUri);
        assertEquals(expectedPath, uri.getPath());
        List<String> params = Arrays.asList(uri.getQuery().split("&"));
        for (String expectedParam : expectedParams) {
            assertTrue(params.contains(expectedParam),
                    () -> "Missing query parameter " + expectedParam + " in " + actualUri);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
