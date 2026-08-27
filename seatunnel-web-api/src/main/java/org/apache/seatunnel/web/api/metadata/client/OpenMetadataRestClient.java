package org.apache.seatunnel.web.api.metadata.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.api.metadata.OpenMetadataProperties;
import org.openmetadata.schema.entity.data.Database;
import org.openmetadata.schema.entity.data.DatabaseSchema;
import org.openmetadata.schema.api.services.CreateDatabaseService;
import org.openmetadata.schema.api.services.ingestionPipelines.CreateIngestionPipeline;
import org.openmetadata.schema.type.Column;
import org.openmetadata.schema.type.ColumnProfile;
import org.openmetadata.schema.type.TableConstraint;
import org.openmetadata.schema.type.TableProfile;
import org.openmetadata.sdk.config.OpenMetadataConfig;
import org.openmetadata.sdk.exceptions.OpenMetadataException;
import org.openmetadata.sdk.models.ListParams;
import org.openmetadata.sdk.models.ListResponse;
import org.openmetadata.sdk.network.HttpMethod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenMetadata 1.12.10 boundary backed exclusively by the official
 * {@code org.open-metadata:openmetadata-sdk:1.12.10} Java SDK.
 *
 * <p>The SDK exposes typed entity services for normal CRUD and collection
 * operations. A few 1.12.10 control-plane operations (version, pipeline
 * deploy/trigger/status and the latest-profile extension) are not surfaced as
 * typed methods by that SDK release; those calls still use the SDK's official
 * {@link org.openmetadata.sdk.network.HttpClient}, never a second HTTP client
 * and never an Airflow endpoint.</p>
 */
@Component
public class OpenMetadataRestClient implements OpenMetadataClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final OpenMetadataProperties properties;
    private final org.openmetadata.sdk.client.OpenMetadataClient sdkClient;
    private final AtomicBoolean versionVerified = new AtomicBoolean(false);

    @Autowired
    public OpenMetadataRestClient(OpenMetadataProperties properties) {
        this(properties, createSdkClient(properties));
    }

    OpenMetadataRestClient(
            OpenMetadataProperties properties,
            org.openmetadata.sdk.client.OpenMetadataClient sdkClient) {
        this.properties = properties;
        this.sdkClient = sdkClient;
    }

    private static org.openmetadata.sdk.client.OpenMetadataClient createSdkClient(
            OpenMetadataProperties properties) {
        String baseUrl = properties == null ? null : properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            // Disabled installations must still be able to start. Calls fail
            // through validateBaseUrl() until an /api endpoint is configured.
            baseUrl = "http://127.0.0.1:8585/api";
        }
        OpenMetadataConfig config = OpenMetadataConfig.builder()
                .baseUrl(baseUrl)
                .accessToken(properties == null ? null : properties.getToken())
                .connectTimeout(properties == null ? 2000 : properties.getConnectTimeoutMs())
                .readTimeout(properties == null ? 10000 : properties.getReadTimeoutMs())
                .writeTimeout(properties == null ? 10000 : properties.getReadTimeoutMs())
                .build();
        return new org.openmetadata.sdk.client.OpenMetadataClient(config);
    }

    @Override
    public void assertFixedVersion() {
        if (versionVerified.get()) {
            return;
        }
        JsonNode response = sdkRequest("GET", "/v1/system/version", null, false);
        String actualVersion = response.path("version").asText();
        if (!properties.getExpectedServerVersion().equals(actualVersion)) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata Server version does not match the fixed 1.12.10 contract");
        }
        JsonNode ingestionServiceStatus = sdkRequest(
                "GET", "/v1/services/ingestionPipelines/status", null, false);
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
    public OpenMetadataHealth health() {
        String serverVersion = null;
        boolean openMetadataUp = false;
        try {
            JsonNode versionResponse = sdkRequest("GET", "/v1/system/version", null, false);
            serverVersion = versionResponse.path("version").asText(null);
            openMetadataUp = true;
        } catch (Exception ignored) {
            return new OpenMetadataHealth(false, false, null, null);
        }
        try {
            JsonNode status = sdkRequest(
                    "GET", "/v1/services/ingestionPipelines/status", null, false);
            int code = status.path("code").asInt(-1);
            return new OpenMetadataHealth(
                    openMetadataUp,
                    code >= 200 && code < 300,
                    serverVersion,
                    status.path("version").asText(null));
        } catch (Exception ignored) {
            return new OpenMetadataHealth(openMetadataUp, false, serverVersion, null);
        }
    }

    @Override
    public Optional<OpenMetadataEntity> findDatabaseService(String fullyQualifiedName) {
        validateBaseUrl();
        try {
            org.openmetadata.schema.entity.services.DatabaseService service =
                    sdkClient.databaseServices().getByName(fullyQualifiedName);
            return Optional.of(entity(service));
        } catch (OpenMetadataException error) {
            if (isNotFound(error)) {
                return Optional.empty();
            }
            throw sdkFailure(MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata database service lookup failed", error);
        }
    }

    @Override
    public Optional<OpenMetadataDatabase> findDatabase(String fullyQualifiedName) {
        validateBaseUrl();
        try {
            Database database = sdkClient.databases().getByName(fullyQualifiedName);
            return Optional.ofNullable(toDatabase(database));
        } catch (OpenMetadataException error) {
            if (isNotFound(error)) {
                return Optional.empty();
            }
            throw sdkFailure(MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata database lookup failed", error);
        }
    }

    @Override
    public List<OpenMetadataDatabase> listDatabases(String serviceFullyQualifiedName, int limit) {
        return listDatabasesPage(serviceFullyQualifiedName, limit, null).data();
    }

    @Override
    public OpenMetadataPage<OpenMetadataDatabase> listDatabasesPage(
            String serviceFullyQualifiedName, int limit, String after) {
        validateBaseUrl();
        int safeLimit = safeLimit(limit, 1000);
        ListParams params = new ListParams()
                .setService(serviceFullyQualifiedName)
                .setLimit(safeLimit)
                .addQueryParam("include", "non-deleted");
        if (after != null && !after.isBlank()) {
            params.setAfter(after);
        }
        try {
            ListResponse<Database> response = sdkClient.databases().list(params);
            List<OpenMetadataDatabase> data = new ArrayList<>();
            for (Database database : safeList(response == null ? null : response.getData())) {
                OpenMetadataDatabase parsed = toDatabase(database);
                if (parsed != null) {
                    data.add(parsed);
                }
            }
            return page(response, data);
        } catch (OpenMetadataException error) {
            throw sdkFailure(MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata database collection lookup failed", error);
        }
    }

    @Override
    public List<OpenMetadataDatabaseSchema> listSchemas(String databaseFullyQualifiedName, int limit) {
        return listSchemasPage(databaseFullyQualifiedName, limit, null).data();
    }

    @Override
    public OpenMetadataPage<OpenMetadataDatabaseSchema> listSchemasPage(
            String databaseFullyQualifiedName, int limit, String after) {
        validateBaseUrl();
        int safeLimit = safeLimit(limit, 1000);
        ListParams params = new ListParams()
                .setDatabase(databaseFullyQualifiedName)
                .setLimit(safeLimit)
                .addQueryParam("include", "non-deleted");
        if (after != null && !after.isBlank()) {
            params.setAfter(after);
        }
        try {
            ListResponse<DatabaseSchema> response = sdkClient.databaseSchemas().list(params);
            List<OpenMetadataDatabaseSchema> data = new ArrayList<>();
            for (DatabaseSchema schema : safeList(response == null ? null : response.getData())) {
                OpenMetadataDatabaseSchema parsed = toSchema(schema);
                if (parsed != null) {
                    data.add(parsed);
                }
            }
            return page(response, data);
        } catch (OpenMetadataException error) {
            throw sdkFailure(MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata schema collection lookup failed", error);
        }
    }

    @Override
    public List<OpenMetadataTable> listTables(
            String schemaFullyQualifiedName, boolean includeColumns, int limit) {
        return listTablesPage(schemaFullyQualifiedName, includeColumns, limit, null).data();
    }

    @Override
    public OpenMetadataPage<OpenMetadataTable> listTablesPage(
            String schemaFullyQualifiedName, boolean includeColumns, int limit, String after) {
        validateBaseUrl();
        int safeLimit = safeLimit(limit, 1000);
        ListParams params = new ListParams()
                .setDatabaseSchema(schemaFullyQualifiedName)
                .setFields(includeColumns ? "columns,tableConstraints" : "tableConstraints")
                .setLimit(safeLimit)
                .addQueryParam("include", "non-deleted");
        if (after != null && !after.isBlank()) {
            params.setAfter(after);
        }
        try {
            ListResponse<org.openmetadata.schema.entity.data.Table> response = sdkClient.tables().list(params);
            List<OpenMetadataTable> data = new ArrayList<>();
            for (org.openmetadata.schema.entity.data.Table table
                    : safeList(response == null ? null : response.getData())) {
                OpenMetadataTable parsed = toTable(table);
                if (parsed != null) {
                    data.add(parsed);
                }
            }
            return page(response, data);
        } catch (OpenMetadataException error) {
            throw sdkFailure(MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata table collection lookup failed", error);
        }
    }

    @Override
    public OpenMetadataTable getTable(String tableId) {
        validateBaseUrl();
        try {
            return toTable(sdkClient.tables().get(tableId, "columns,tableConstraints", "non-deleted"));
        } catch (OpenMetadataException error) {
            if (isNotFound(error)) {
                return null;
            }
            throw sdkFailure(MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata table lookup failed", error);
        }
    }

    @Override
    public OpenMetadataTableProfile getLatestTableProfile(String tableFullyQualifiedName) {
        JsonNode response = sdkRequest(
                "GET", "/v1/tables/" + encode(tableFullyQualifiedName)
                        + "/tableProfile/latest?includeColumnProfile=true", null, true);
        return response == null ? null : parseLatestProfile(response);
    }

    @Override
    public List<OpenMetadataColumnProfile> listColumnProfiles(
            String columnOrTableFullyQualifiedName, long startTs, long endTs) {
        JsonNode response = sdkRequest(
                "GET", "/v1/tables/" + encode(columnOrTableFullyQualifiedName)
                        + "/columnProfile?startTs=" + startTs + "&endTs=" + endTs,
                null,
                false);
        List<OpenMetadataColumnProfile> profiles = new ArrayList<>();
        for (JsonNode profile : response.path("data")) {
            OpenMetadataColumnProfile parsed = parseColumnProfile(profile);
            if (parsed != null) {
                profiles.add(parsed);
            }
        }
        return profiles;
    }

    @Override
    public JsonNode getTableProfilerConfig(String tableId) {
        // 1.12.10's official SDK exposes the typed update operation but not
        // the corresponding GET extension, so use its own HttpClient for this
        // exact server path rather than introducing a second HTTP client.
        return sdkRequest(
                "GET", "/v1/tables/" + encode(tableId) + "/tableProfilerConfig", null, true);
    }

    @Override
    public JsonNode updateTableProfilerConfig(String tableId, JsonNode profilerConfig) {
        if (profilerConfig == null || profilerConfig.isNull()) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata table profiler config cannot be empty");
        }
        // The GET/PUT extension is not modelled as a dedicated typed method in
        // SDK 1.12.10. The request is still executed by the SDK client and
        // therefore inherits its authentication, timeout and error handling.
        return sdkRequest(
                "PUT", "/v1/tables/" + encode(tableId) + "/tableProfilerConfig",
                profilerConfig, false);
    }

    @Override
    public OpenMetadataEntity upsertDatabaseService(JsonNode request) {
        validateBaseUrl();
        try {
            // SDK 1.12.10's generic upsert accepts an entity, whose generated
            // defaults include read-only fields (version/deleted/entityStatus).
            // The Server PUT contract deserializes CreateDatabaseService and
            // rejects those fields. Resolve by name, then use the SDK's typed
            // create/update methods so no hand-built HTTP request is needed.
            CreateDatabaseService createRequest =
                    OBJECT_MAPPER.treeToValue(request, CreateDatabaseService.class);
            org.openmetadata.schema.entity.services.DatabaseService existing =
                    findDatabaseServiceEntity(createRequest.getName());
            if (existing == null) {
                return entity(sdkClient.databaseServices().create(createRequest));
            }
            org.openmetadata.schema.entity.services.DatabaseService desired =
                    mergeDatabaseService(existing, createRequest);
            return entity(sdkClient.databaseServices().update(existing.getId().toString(), desired));
        } catch (OpenMetadataException error) {
            throw sdkFailure(MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata database service upsert failed", error);
        } catch (Exception error) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata database service request is invalid", error);
        }
    }

    @Override
    public Optional<OpenMetadataEntity> findIngestionPipeline(String fullyQualifiedName) {
        validateBaseUrl();
        try {
            org.openmetadata.schema.entity.services.ingestionPipelines.IngestionPipeline pipeline =
                    sdkClient.ingestionPipelines().getByName(fullyQualifiedName);
            return Optional.of(entity(pipeline));
        } catch (OpenMetadataException error) {
            if (isNotFound(error)) {
                return Optional.empty();
            }
            throw sdkFailure(MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata ingestion pipeline lookup failed", error);
        }
    }

    @Override
    public OpenMetadataEntity upsertIngestionPipeline(JsonNode request) {
        validateBaseUrl();
        try {
            // As with DatabaseService, use the SDK's create request for the
            // create path. The entity upsert serializer includes server-only
            // fields that OpenMetadata 1.12.10 does not accept on create.
            CreateIngestionPipeline createRequest =
                    OBJECT_MAPPER.treeToValue(request, CreateIngestionPipeline.class);
            org.openmetadata.schema.entity.services.ingestionPipelines.IngestionPipeline existing =
                    findIngestionPipelineEntity(createRequest);
            if (existing == null) {
                return entity(sdkClient.ingestionPipelines().create(createRequest));
            }
            org.openmetadata.schema.entity.services.ingestionPipelines.IngestionPipeline desired =
                    mergeIngestionPipeline(existing, createRequest);
            return entity(sdkClient.ingestionPipelines().update(existing.getId().toString(), desired));
        } catch (OpenMetadataException error) {
            throw sdkFailure(MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata ingestion pipeline upsert failed", error);
        } catch (Exception error) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata ingestion pipeline request is invalid", error);
        }
    }

    private org.openmetadata.schema.entity.services.DatabaseService findDatabaseServiceEntity(
            String name) {
        try {
            return sdkClient.databaseServices().getByName(name);
        } catch (OpenMetadataException error) {
            if (isNotFound(error)) {
                return null;
            }
            throw error;
        }
    }

    private static org.openmetadata.schema.entity.services.DatabaseService mergeDatabaseService(
            org.openmetadata.schema.entity.services.DatabaseService existing,
            CreateDatabaseService desired) {
        existing.setName(desired.getName());
        existing.setDisplayName(desired.getDisplayName());
        existing.setDescription(desired.getDescription());
        existing.setServiceType(desired.getServiceType());
        existing.setConnection(desired.getConnection());
        existing.setOwners(desired.getOwners());
        existing.setIngestionRunner(desired.getIngestionRunner());
        return existing;
    }

    private org.openmetadata.schema.entity.services.ingestionPipelines.IngestionPipeline
            findIngestionPipelineEntity(CreateIngestionPipeline desired) {
        String name = desired.getName();
        // IngestionPipeline FQNs are scoped by their service in OpenMetadata
        // 1.12.10 (serviceFqn.pipelineName).  The generated create DTO keeps
        // the short name, so resolving by that short name would always miss an
        // existing pipeline and attempt a duplicate create on every reconcile.
        // Fall back to the short name for callers that intentionally omit the
        // service reference (and for backwards-compatible test fixtures).
        if (desired.getService() != null
                && desired.getService().getFullyQualifiedName() != null
                && !desired.getService().getFullyQualifiedName().isBlank()
                && name != null
                && !name.isBlank()) {
            name = desired.getService().getFullyQualifiedName() + "." + name;
        }
        try {
            return sdkClient.ingestionPipelines().getByName(name);
        } catch (OpenMetadataException error) {
            if (isNotFound(error)) {
                return null;
            }
            throw error;
        }
    }

    private static org.openmetadata.schema.entity.services.ingestionPipelines.IngestionPipeline
            mergeIngestionPipeline(
                    org.openmetadata.schema.entity.services.ingestionPipelines.IngestionPipeline existing,
                    CreateIngestionPipeline desired) {
        existing.setName(desired.getName());
        existing.setDisplayName(desired.getDisplayName());
        existing.setDescription(desired.getDescription());
        existing.setPipelineType(desired.getPipelineType());
        existing.setSourceConfig(desired.getSourceConfig());
        existing.setAirflowConfig(desired.getAirflowConfig());
        existing.setLoggerLevel(desired.getLoggerLevel());
        existing.setRaiseOnError(desired.getRaiseOnError());
        existing.setService(desired.getService());
        existing.setOwners(desired.getOwners());
        existing.setProvider(desired.getProvider());
        existing.setProcessingEngine(desired.getProcessingEngine());
        existing.setEnableStreamableLogs(desired.getEnableStreamableLogs());
        return existing;
    }

    @Override
    public void deployIngestionPipeline(String id) {
        pipelineControl("/v1/services/ingestionPipelines/deploy/" + encode(id),
                MetadataErrorCode.OM_PIPELINE_DEPLOY_ERROR);
    }

    @Override
    public void enableIngestionPipeline(String id) {
        validateBaseUrl();
        try {
            org.openmetadata.schema.entity.services.ingestionPipelines.IngestionPipeline pipeline =
                    sdkClient.ingestionPipelines().get(id);
            // OpenMetadata 1.12.10's managed deploy creates a DAG with the
            // requested pause-on-creation value, but it does not unpause an
            // already existing DagModel. Toggle through the OM resource so
            // the official PipelineServiceClient calls its /enable operation.
            // Setting enabled=false first selects the enable branch in the
            // server-side toggle implementation; the response persists it
            // back as enabled=true after the managed call succeeds.
            if (!Boolean.FALSE.equals(pipeline.getEnabled())) {
                pipeline.setEnabled(false);
                sdkClient.ingestionPipelines().update(id, pipeline);
            }
            sdkRequest(
                    "POST",
                    "/v1/services/ingestionPipelines/toggleIngestion/" + encode(id),
                    null,
                    false);
        } catch (OpenMetadataException error) {
            throw sdkFailure(
                    MetadataErrorCode.OM_PIPELINE_DEPLOY_ERROR,
                    "OpenMetadata ingestion pipeline enable failed",
                    error);
        } catch (MetadataIntegrationException error) {
            throw error;
        } catch (Exception error) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_PIPELINE_DEPLOY_ERROR,
                    "OpenMetadata ingestion pipeline enable failed",
                    error);
        }
    }

    @Override
    public void triggerIngestionPipeline(String id) {
        pipelineControl("/v1/services/ingestionPipelines/trigger/" + encode(id),
                MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR);
    }

    @Override
    public List<OpenMetadataPipelineRun> listIngestionPipelineRuns(String fullyQualifiedName, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        JsonNode response = sdkRequest(
                "GET", "/v1/services/ingestionPipelines/" + encode(fullyQualifiedName)
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
        pipelineControl("/v1/services/ingestionPipelines/kill/" + encode(id),
                MetadataErrorCode.OM_PIPELINE_TRIGGER_ERROR);
    }

    @Override
    public void deleteIngestionPipeline(String id) {
        validateBaseUrl();
        try {
            sdkClient.ingestionPipelines().delete(id, Map.of("hardDelete", "true"));
        } catch (OpenMetadataException error) {
            if (!isNotFound(error)) {
                throw sdkFailure(MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                        "OpenMetadata ingestion pipeline deletion failed", error);
            }
        }
    }

    @Override
    public void deleteDatabaseServiceRecursively(String id) {
        validateBaseUrl();
        try {
            sdkClient.databaseServices().delete(id,
                    Map.of("recursive", "true", "hardDelete", "true"));
        } catch (OpenMetadataException error) {
            if (!isNotFound(error)) {
                throw sdkFailure(MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                        "OpenMetadata database service deletion failed", error);
            }
        }
    }

    private void pipelineControl(String path, MetadataErrorCode errorCode) {
        JsonNode response = sdkRequest("POST", path, null, false);
        int code = response.path("code").asInt(-1);
        if (code < 200 || code >= 300) {
            throw new MetadataIntegrationException(
                    errorCode,
                    "OpenMetadata PipelineServiceClient did not accept the pipeline operation");
        }
        // The 1.12.10 deploy/trigger/kill response contains code/platform and
        // does not consistently echo the managed-ingestion version.  The
        // version was already verified by assertFixedVersion before a
        // reconciliation or user operation.  If a response does echo a
        // version, still reject a mismatched patch explicitly.
        String version = response.path("version").asText("");
        if (!version.isBlank() && !properties.getExpectedIngestionPatch().equals(version)) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata IngestionPipeline managed build does not match the fixed 1.12.10.0 contract");
        }
    }

    /** Execute an unsupported-by-1.12.10 operation through the SDK network client. */
    private JsonNode sdkRequest(String method, String path, Object body, boolean absentOn404) {
        validateBaseUrl();
        try {
            String response = sdkClient.getHttpClient().executeForString(
                    HttpMethod.valueOf(method), path, body);
            return response == null || response.isBlank()
                    ? OBJECT_MAPPER.createObjectNode()
                    : OBJECT_MAPPER.readTree(response);
        } catch (OpenMetadataException error) {
            if (absentOn404 && isNotFound(error)) {
                return null;
            }
            throw sdkFailure(MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata SDK request failed", error);
        } catch (Exception error) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata SDK response could not be read", error);
        }
    }

    private void validateBaseUrl() {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()
                || baseUrl.contains(":8082")
                || baseUrl.contains("/airflow")) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata base URL is not configured as a safe /api endpoint");
        }
        String normalized = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        if (!normalized.endsWith("/api")) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_CONNECTION_ERROR,
                    "OpenMetadata base URL must end in /api");
        }
    }

    private static boolean isNotFound(OpenMetadataException error) {
        return error.getStatusCode() == 404;
    }

    private static MetadataIntegrationException sdkFailure(
            MetadataErrorCode code, String message, OpenMetadataException cause) {
        return new MetadataIntegrationException(code, message, cause);
    }

    private static OpenMetadataEntity entity(
            org.openmetadata.schema.EntityInterface value) {
        if (value == null || value.getId() == null
                || value.getFullyQualifiedName() == null
                || value.getFullyQualifiedName().isBlank()) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata response lacks the entity identity required for reconciliation");
        }
        return new OpenMetadataEntity(value.getId().toString(), value.getFullyQualifiedName());
    }

    private static OpenMetadataDatabase toDatabase(Database database) {
        if (database == null || database.getId() == null
                || blank(database.getFullyQualifiedName())) {
            return null;
        }
        String serviceFqn = referenceFqn(database.getService());
        if (serviceFqn.isBlank()) {
            return null;
        }
        return new OpenMetadataDatabase(
                database.getId().toString(), database.getFullyQualifiedName(), serviceFqn);
    }

    private static OpenMetadataDatabaseSchema toSchema(DatabaseSchema schema) {
        if (schema == null || schema.getId() == null
                || blank(schema.getFullyQualifiedName())) {
            return null;
        }
        OpenMetadataDatabaseSchema result = new OpenMetadataDatabaseSchema();
        result.setId(schema.getId().toString());
        result.setName(schema.getName());
        result.setFullyQualifiedName(schema.getFullyQualifiedName());
        result.setDatabaseFullyQualifiedName(referenceFqn(schema.getDatabase()));
        result.setServiceFullyQualifiedName(referenceFqn(schema.getService()));
        return result;
    }

    private static OpenMetadataTable toTable(
            org.openmetadata.schema.entity.data.Table table) {
        if (table == null || table.getId() == null || blank(table.getFullyQualifiedName())) {
            return null;
        }
        OpenMetadataTable result = new OpenMetadataTable();
        result.setId(table.getId().toString());
        result.setName(table.getName());
        result.setFullyQualifiedName(table.getFullyQualifiedName());
        result.setTableType(table.getTableType() == null ? null : table.getTableType().toString());
        result.setDescription(table.getDescription());
        result.setServiceFullyQualifiedName(referenceFqn(table.getService()));
        result.setDatabaseFullyQualifiedName(referenceFqn(table.getDatabase()));
        result.setSchemaFullyQualifiedName(referenceFqn(table.getDatabaseSchema()));

        List<OpenMetadataColumn> columns = new ArrayList<>();
        for (Column column : safeList(table.getColumns())) {
            OpenMetadataColumn parsed = toColumn(column);
            if (parsed != null) {
                columns.add(parsed);
            }
        }
        result.setColumns(columns);

        List<OpenMetadataTableConstraint> constraints = new ArrayList<>();
        for (TableConstraint constraint : safeList(table.getTableConstraints())) {
            if (constraint == null) {
                continue;
            }
            OpenMetadataTableConstraint parsed = new OpenMetadataTableConstraint();
            parsed.setConstraintType(constraint.getConstraintType() == null
                    ? null : constraint.getConstraintType().toString());
            parsed.setRelationshipType(constraint.getRelationshipType() == null
                    ? null : constraint.getRelationshipType().toString());
            parsed.setColumns(constraint.getColumns() == null
                    ? new ArrayList<>() : new ArrayList<>(constraint.getColumns()));
            parsed.setReferredColumns(constraint.getReferredColumns() == null
                    ? new ArrayList<>() : new ArrayList<>(constraint.getReferredColumns()));
            constraints.add(parsed);
        }
        result.setTableConstraints(constraints);
        if (table.getProfile() != null) {
            result.setProfile(toTableProfile(table));
        }
        return result;
    }

    private static OpenMetadataColumn toColumn(Column column) {
        if (column == null || blank(column.getName())) {
            return null;
        }
        OpenMetadataColumn result = new OpenMetadataColumn();
        result.setName(column.getName());
        result.setFullyQualifiedName(column.getFullyQualifiedName());
        result.setDataType(column.getDataType() == null ? null : column.getDataType().toString());
        result.setDataTypeDisplay(column.getDataTypeDisplay());
        result.setDataLength(longValue(column.getDataLength()));
        result.setPrecision(longValue(column.getPrecision()));
        result.setScale(longValue(column.getScale()));
        result.setDescription(column.getDescription());
        result.setConstraint(column.getConstraint() == null ? null : column.getConstraint().toString());
        result.setOrdinalPosition(column.getOrdinalPosition());
        if (column.getProfile() != null) {
            result.setProfile(toColumnProfile(column.getProfile()));
        }
        return result;
    }

    private static OpenMetadataTableProfile toTableProfile(
            org.openmetadata.schema.entity.data.Table table) {
        TableProfile profile = table.getProfile();
        OpenMetadataTableProfile result = new OpenMetadataTableProfile();
        result.setTableId(table.getId() == null ? null : table.getId().toString());
        result.setTableName(table.getName());
        result.setTableFullyQualifiedName(table.getFullyQualifiedName());
        result.setTimestamp(profile.getTimestamp());
        result.setProfileSample(longValue(profile.getProfileSample()));
        result.setProfileSampleType(profile.getProfileSampleType() == null
                ? null : profile.getProfileSampleType().toString());
        result.setRowCount(longValue(profile.getRowCount()));
        result.setColumnCount(longValue(profile.getColumnCount()));
        result.setSizeInByte(longValue(profile.getSizeInByte()));
        List<OpenMetadataColumnProfile> columns = new ArrayList<>();
        for (Column column : safeList(table.getColumns())) {
            if (column != null && column.getProfile() != null) {
                OpenMetadataColumnProfile parsed = toColumnProfile(column.getProfile());
                if (parsed != null) {
                    columns.add(parsed);
                }
            }
        }
        result.setColumns(columns);
        return result;
    }

    private static OpenMetadataColumnProfile toColumnProfile(ColumnProfile profile) {
        if (profile == null || blank(profile.getName())) {
            return null;
        }
        OpenMetadataColumnProfile result = new OpenMetadataColumnProfile();
        result.setName(profile.getName());
        result.setTimestamp(profile.getTimestamp());
        result.setValuesCount(longValue(profile.getValuesCount()));
        result.setValidCount(longValue(profile.getValidCount()));
        result.setDuplicateCount(longValue(profile.getDuplicateCount()));
        result.setNullCount(longValue(profile.getNullCount()));
        result.setMissingCount(longValue(profile.getMissingCount()));
        result.setUniqueCount(longValue(profile.getUniqueCount()));
        result.setDistinctCount(longValue(profile.getDistinctCount()));
        result.setMin(profile.getMin() == null ? null : OBJECT_MAPPER.valueToTree(profile.getMin()));
        result.setMax(profile.getMax() == null ? null : OBJECT_MAPPER.valueToTree(profile.getMax()));
        result.setMinLength(longValue(profile.getMinLength()));
        result.setMaxLength(longValue(profile.getMaxLength()));
        result.setMean(decimal(profile.getMean()));
        result.setNullProportion(decimal(profile.getNullProportion()));
        result.setDistinctProportion(decimal(profile.getDistinctProportion()));
        result.setUniqueProportion(decimal(profile.getUniqueProportion()));
        result.setValuesPercentage(decimal(profile.getValuesPercentage()));
        result.setMissingPercentage(decimal(profile.getMissingPercentage()));
        result.setSum(decimal(profile.getSum()));
        result.setStddev(decimal(profile.getStddev()));
        result.setVariance(decimal(profile.getVariance()));
        result.setMedian(decimal(profile.getMedian()));
        return result;
    }

    private static OpenMetadataTableProfile parseLatestProfile(JsonNode table) {
        JsonNode profile = table.get("profile");
        if (profile == null || profile.isNull()) {
            return null;
        }
        List<OpenMetadataColumnProfile> columns = new ArrayList<>();
        for (JsonNode column : table.path("columns")) {
            JsonNode columnProfile = column.get("profile");
            if (columnProfile != null && !columnProfile.isNull()) {
                OpenMetadataColumnProfile parsed = parseColumnProfile(columnProfile);
                if (parsed != null) {
                    columns.add(parsed);
                }
            }
        }
        OpenMetadataTableProfile result = new OpenMetadataTableProfile();
        result.setTableId(text(table, "id"));
        result.setTableName(text(table, "name"));
        result.setTableFullyQualifiedName(text(table, "fullyQualifiedName"));
        result.setTimestamp(nullableLong(profile, "timestamp"));
        result.setProfileSample(nullableLong(profile, "profileSample"));
        result.setProfileSampleType(text(profile, "profileSampleType"));
        result.setRowCount(nullableLong(profile, "rowCount"));
        result.setColumnCount(nullableLong(profile, "columnCount"));
        result.setSizeInByte(nullableLong(profile, "sizeInByte"));
        result.setColumns(columns);
        return result;
    }

    private static OpenMetadataColumnProfile parseColumnProfile(JsonNode json) {
        if (json == null || json.isNull() || text(json, "name").isBlank()) {
            return null;
        }
        OpenMetadataColumnProfile profile = new OpenMetadataColumnProfile();
        profile.setName(text(json, "name"));
        profile.setTimestamp(nullableLong(json, "timestamp"));
        profile.setValuesCount(nullableLong(json, "valuesCount"));
        profile.setValidCount(nullableLong(json, "validCount"));
        profile.setDuplicateCount(nullableLong(json, "duplicateCount"));
        profile.setNullCount(nullableLong(json, "nullCount"));
        profile.setMissingCount(nullableLong(json, "missingCount"));
        profile.setUniqueCount(nullableLong(json, "uniqueCount"));
        profile.setDistinctCount(nullableLong(json, "distinctCount"));
        profile.setMin(json.get("min"));
        profile.setMax(json.get("max"));
        profile.setMinLength(nullableLong(json, "minLength"));
        profile.setMaxLength(nullableLong(json, "maxLength"));
        profile.setMean(decimal(json, "mean"));
        profile.setNullProportion(decimal(json, "nullProportion"));
        profile.setDistinctProportion(decimal(json, "distinctProportion"));
        profile.setUniqueProportion(decimal(json, "uniqueProportion"));
        profile.setValuesPercentage(decimal(json, "valuesPercentage"));
        profile.setMissingPercentage(decimal(json, "missingPercentage"));
        profile.setSum(decimal(json, "sum"));
        profile.setStddev(decimal(json, "stddev"));
        profile.setVariance(decimal(json, "variance"));
        profile.setMedian(decimal(json, "median"));
        return profile;
    }

    private static <T> OpenMetadataPage<T> page(
            ListResponse<?> response, List<T> data) {
        if (response == null || response.getPaging() == null) {
            return new OpenMetadataPage<>(data, data.size(), null);
        }
        Integer total = response.getPaging().getTotal();
        return new OpenMetadataPage<>(
                data,
                total == null ? data.size() : total,
                response.getPaging().getAfter());
    }

    private static String referenceFqn(org.openmetadata.schema.type.EntityReference reference) {
        if (reference == null) {
            return "";
        }
        if (!blank(reference.getFullyQualifiedName())) {
            return reference.getFullyQualifiedName();
        }
        return reference.getName() == null ? "" : reference.getName();
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json == null ? null : json.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static Long nullableLong(JsonNode json, String field) {
        JsonNode value = json == null ? null : json.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static BigDecimal decimal(JsonNode json, String field) {
        JsonNode value = json == null ? null : json.get(field);
        return value == null || value.isNull() || !value.isNumber()
                ? null : value.decimalValue();
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static Long longValue(Number value) {
        return value == null ? null : value.longValue();
    }

    private static int safeLimit(int value, int max) {
        return Math.max(1, Math.min(value, max));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? Collections.emptyList() : values;
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
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
