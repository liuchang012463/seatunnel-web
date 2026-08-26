package org.apache.seatunnel.web.api.metadata.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.api.metadata.OpenMetadataProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Strict REST client for the OpenMetadata 1.12.10 Server API. It never targets
 * the orchestration service directly.
 */
@Component
public class OpenMetadataRestClient implements OpenMetadataClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OpenMetadataProperties properties;
    private final HttpClient httpClient;
    private final AtomicBoolean versionVerified = new AtomicBoolean(false);

    public OpenMetadataRestClient(OpenMetadataProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build());
    }

    OpenMetadataRestClient(OpenMetadataProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public void assertFixedVersion() {
        if (versionVerified.get()) {
            return;
        }
        JsonNode response = request("GET", "/v1/system/version", null, false);
        String actualVersion = response.path("version").asText();
        if (!properties.getExpectedServerVersion().equals(actualVersion)) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata Server version does not match the fixed 1.12.10 contract");
        }
        JsonNode ingestionServiceStatus = request("GET", "/v1/services/ingestionPipelines/status", null, false);
        int managedStatusCode = ingestionServiceStatus.path("code").asInt(-1);
        if (managedStatusCode < 200 || managedStatusCode >= 300) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata PipelineServiceClient is not healthy");
        }
        String actualIngestionVersion = ingestionServiceStatus.path("version").asText();
        if (!properties.getExpectedIngestionPatch().equals(actualIngestionVersion)) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata IngestionPipeline managed build does not match the fixed 1.12.10.0 contract");
        }
        versionVerified.set(true);
    }

    @Override
    public Optional<OpenMetadataEntity> findDatabaseService(String fullyQualifiedName) {
        return findByName("/v1/services/databaseServices/name/", fullyQualifiedName);
    }

    @Override
    public Optional<OpenMetadataDatabase> findDatabase(String fullyQualifiedName) {
        JsonNode result = request("GET", "/v1/databases/name/" + encode(fullyQualifiedName), null, true);
        if (result == null) {
            return Optional.empty();
        }
        String id = result.path("id").asText();
        String fqn = result.path("fullyQualifiedName").asText();
        String serviceFqn = result.path("service").path("fullyQualifiedName").asText();
        if (id.isBlank() || fqn.isBlank() || serviceFqn.isBlank()) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata database response lacks its service identity");
        }
        return Optional.of(new OpenMetadataDatabase(id, fqn, serviceFqn));
    }

    @Override
    public List<OpenMetadataDatabase> listDatabases(String serviceFullyQualifiedName, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        JsonNode response = request(
                "GET",
                "/v1/databases?service=" + encode(serviceFullyQualifiedName) + "&limit=" + safeLimit,
                null,
                false);
        List<OpenMetadataDatabase> databases = new ArrayList<>();
        for (JsonNode database : response.path("data")) {
            String id = database.path("id").asText();
            String fqn = database.path("fullyQualifiedName").asText();
            String serviceFqn = database.path("service").path("fullyQualifiedName").asText();
            if (!id.isBlank() && !fqn.isBlank() && !serviceFqn.isBlank()) {
                databases.add(new OpenMetadataDatabase(id, fqn, serviceFqn));
            }
        }
        return databases;
    }

    @Override
    public OpenMetadataEntity upsertDatabaseService(JsonNode request) {
        return entity(request("PUT", "/v1/services/databaseServices", request, false));
    }

    @Override
    public Optional<OpenMetadataEntity> findIngestionPipeline(String fullyQualifiedName) {
        return findByName("/v1/services/ingestionPipelines/name/", fullyQualifiedName);
    }

    @Override
    public OpenMetadataEntity upsertIngestionPipeline(JsonNode request) {
        return entity(request("PUT", "/v1/services/ingestionPipelines", request, false));
    }

    @Override
    public void deployIngestionPipeline(String id) {
        pipelineControl("POST", "/v1/services/ingestionPipelines/deploy/" + encode(id));
    }

    @Override
    public void triggerIngestionPipeline(String id) {
        pipelineControl("POST", "/v1/services/ingestionPipelines/trigger/" + encode(id));
    }

    @Override
    public List<OpenMetadataPipelineRun> listIngestionPipelineRuns(String fullyQualifiedName, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        JsonNode response = request(
                "GET",
                "/v1/services/ingestionPipelines/" + encode(fullyQualifiedName)
                        + "/pipelineStatus?limit=" + safeLimit,
                null,
                true);
        if (response == null) {
            return List.of();
        }
        List<OpenMetadataPipelineRun> runs = new ArrayList<>();
        for (JsonNode run : response.path("data")) {
            runs.add(new OpenMetadataPipelineRun(
                    run.path("runId").asText(),
                    run.path("pipelineState").asText(),
                    nullableLong(run, "startDate"),
                    nullableLong(run, "timestamp"),
                    nullableLong(run, "endDate"),
                    warningCount(run)));
        }
        return runs;
    }

    @Override
    public void killIngestionPipeline(String id) {
        pipelineControl("POST", "/v1/services/ingestionPipelines/kill/" + encode(id));
    }

    @Override
    public void deleteIngestionPipeline(String id) {
        request("DELETE", "/v1/services/ingestionPipelines/" + encode(id) + "?hardDelete=true", null, true);
    }

    @Override
    public void deleteDatabaseServiceRecursively(String id) {
        request("DELETE", "/v1/services/databaseServices/" + encode(id) + "?recursive=true&hardDelete=true", null, true);
    }

    private Optional<OpenMetadataEntity> findByName(String prefix, String fullyQualifiedName) {
        JsonNode result = request("GET", prefix + encode(fullyQualifiedName), null, true);
        return result == null ? Optional.empty() : Optional.of(entity(result));
    }

    private void pipelineControl(String method, String path) {
        JsonNode response = request(method, path, null, false);
        int code = response.path("code").asInt(-1);
        if (code < 200 || code >= 300) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR,
                    "OpenMetadata PipelineServiceClient did not accept the pipeline operation");
        }
        String version = response.path("version").asText();
        if (!properties.getExpectedIngestionPatch().equals(version)) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata IngestionPipeline managed build does not match the fixed 1.12.10.0 contract");
        }
    }

    private JsonNode request(String method, String path, JsonNode body, boolean absentOn404) {
        URI uri = endpoint(path);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                    .header("Accept", "application/json");
            if (properties.getToken() != null && !properties.getToken().isBlank()) {
                builder.header("Authorization", "Bearer " + properties.getToken());
            }
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 && absentOn404) {
                return null;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new MetadataIntegrationException(
                        MetadataErrorCode.OM_CONNECTION_ERROR,
                        "OpenMetadata returned HTTP " + response.statusCode());
            }
            return response.body() == null || response.body().isBlank()
                    ? OBJECT_MAPPER.createObjectNode()
                    : OBJECT_MAPPER.readTree(response.body());
        } catch (MetadataIntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata request failed", e);
        }
    }

    private URI endpoint(String path) {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.contains(":8082") || baseUrl.contains("/airflow")) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata base URL is not configured as a safe /api endpoint");
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!normalized.endsWith("/api")) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata base URL must end in /api");
        }
        return URI.create(normalized + path);
    }

    private static OpenMetadataEntity entity(JsonNode json) {
        String id = json.path("id").asText();
        String fqn = json.path("fullyQualifiedName").asText();
        if (id.isBlank() || fqn.isBlank()) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata response lacks the entity identity required for reconciliation");
        }
        return new OpenMetadataEntity(id, fqn);
    }

    private static Long nullableLong(JsonNode json, String field) {
        return json.hasNonNull(field) ? json.get(field).asLong() : null;
    }

    private static Integer warningCount(JsonNode run) {
        int warnings = 0;
        boolean present = false;
        for (JsonNode step : run.path("status")) {
            if (step.has("warnings")) {
                present = true;
                warnings += step.path("warnings").size();
            }
        }
        return present ? warnings : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
