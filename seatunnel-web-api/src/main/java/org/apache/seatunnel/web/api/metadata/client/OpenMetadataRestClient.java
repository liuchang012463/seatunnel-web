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
        request("POST", "/v1/services/ingestionPipelines/deploy/" + encode(id), null, false);
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
