package org.apache.seatunnel.web.api.metadata.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.metadata.MetadataErrorCode;
import org.apache.seatunnel.web.api.metadata.MetadataIntegrationException;
import org.apache.seatunnel.web.api.metadata.OpenMetadataProperties;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.math.BigDecimal;

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

    @Autowired
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
    public OpenMetadataHealth health() {
        String serverVersion = null;
        boolean openMetadataUp = false;
        try {
            JsonNode versionResponse = request("GET", "/v1/system/version", null, false);
            serverVersion = versionResponse.path("version").asText(null);
            openMetadataUp = true;
        } catch (Exception ignored) {
            return new OpenMetadataHealth(false, false, null, null);
        }
        try {
            JsonNode ingestionServiceStatus = request(
                    "GET", "/v1/services/ingestionPipelines/status", null, false);
            int code = ingestionServiceStatus.path("code").asInt(-1);
            return new OpenMetadataHealth(
                    openMetadataUp,
                    code >= 200 && code < 300,
                    serverVersion,
                    ingestionServiceStatus.path("version").asText(null));
        } catch (Exception ignored) {
            return new OpenMetadataHealth(openMetadataUp, false, serverVersion, null);
        }
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
        return listDatabasesPage(serviceFullyQualifiedName, limit, null).data();
    }

    @Override
    public OpenMetadataPage<OpenMetadataDatabase> listDatabasesPage(
            String serviceFullyQualifiedName, int limit, String after) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        JsonNode response = request(
                "GET",
                "/v1/databases?service=" + encode(serviceFullyQualifiedName)
                        + "&include=non-deleted&limit=" + safeLimit
                        + cursor(after),
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
        return page(response, databases);
    }

    @Override
    public List<OpenMetadataDatabaseSchema> listSchemas(String databaseFullyQualifiedName, int limit) {
        return listSchemasPage(databaseFullyQualifiedName, limit, null).data();
    }

    @Override
    public OpenMetadataPage<OpenMetadataDatabaseSchema> listSchemasPage(
            String databaseFullyQualifiedName, int limit, String after) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        JsonNode response = request(
                "GET",
                "/v1/databaseSchemas?database=" + encode(databaseFullyQualifiedName)
                        + "&limit=" + safeLimit + "&include=non-deleted"
                        + cursor(after),
                null,
                false);
        List<OpenMetadataDatabaseSchema> schemas = new ArrayList<>();
        for (JsonNode schema : response.path("data")) {
            OpenMetadataDatabaseSchema parsed = parseSchema(schema);
            if (parsed != null) {
                schemas.add(parsed);
            }
        }
        return page(response, schemas);
    }

    @Override
    public List<OpenMetadataTable> listTables(
            String schemaFullyQualifiedName, boolean includeColumns, int limit) {
        return listTablesPage(schemaFullyQualifiedName, includeColumns, limit, null).data();
    }

    @Override
    public OpenMetadataPage<OpenMetadataTable> listTablesPage(
            String schemaFullyQualifiedName, boolean includeColumns, int limit, String after) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        String fields = includeColumns ? "columns,tableConstraints" : "tableConstraints";
        JsonNode response = request(
                "GET",
                "/v1/tables?databaseSchema=" + encode(schemaFullyQualifiedName)
                        + "&fields=" + fields
                        + "&include=non-deleted&limit=" + safeLimit
                        + cursor(after),
                null,
                false);
        List<OpenMetadataTable> tables = new ArrayList<>();
        for (JsonNode table : response.path("data")) {
            OpenMetadataTable parsed = parseTable(table);
            if (parsed != null) {
                tables.add(parsed);
            }
        }
        return page(response, tables);
    }

    @Override
    public OpenMetadataTable getTable(String tableId) {
        JsonNode response = request(
                "GET",
                "/v1/tables/" + encode(tableId)
                        + "?fields=columns,tableConstraints&include=non-deleted",
                null,
                true);
        return response == null ? null : parseRequiredTable(response);
    }

    @Override
    public OpenMetadataTableProfile getLatestTableProfile(String tableFullyQualifiedName) {
        JsonNode response = request(
                "GET",
                "/v1/tables/" + encode(tableFullyQualifiedName)
                        + "/tableProfile/latest?includeColumnProfile=true",
                null,
                true);
        return response == null ? null : parseLatestProfile(response, response);
    }

    @Override
    public List<OpenMetadataColumnProfile> listColumnProfiles(
            String columnOrTableFullyQualifiedName, long startTs, long endTs) {
        JsonNode response = request(
                "GET",
                "/v1/tables/" + encode(columnOrTableFullyQualifiedName)
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
        return request("GET", "/v1/tables/" + encode(tableId) + "/tableProfilerConfig", null, true);
    }

    @Override
    public JsonNode updateTableProfilerConfig(String tableId, JsonNode profilerConfig) {
        if (profilerConfig == null || profilerConfig.isNull()) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata table profiler config cannot be empty");
        }
        return request(
                "PUT",
                "/v1/tables/" + encode(tableId) + "/tableProfilerConfig",
                profilerConfig,
                false);
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

    private static OpenMetadataDatabaseSchema parseSchema(JsonNode json) {
        if (json == null || json.isNull()) {
            return null;
        }
        String id = text(json, "id");
        String fqn = text(json, "fullyQualifiedName");
        if (id.isBlank() || fqn.isBlank()) {
            return null;
        }
        OpenMetadataDatabaseSchema schema = new OpenMetadataDatabaseSchema();
        schema.setId(id);
        schema.setName(text(json, "name"));
        schema.setFullyQualifiedName(fqn);
        schema.setDatabaseFullyQualifiedName(referenceFqn(json, "database"));
        schema.setServiceFullyQualifiedName(referenceFqn(json, "service"));
        return schema;
    }

    private static OpenMetadataTable parseRequiredTable(JsonNode json) {
        OpenMetadataTable table = parseTable(json);
        if (table == null) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.OM_SERVICE_SYNC_ERROR,
                    "OpenMetadata table response lacks its identity");
        }
        return table;
    }

    private static OpenMetadataTable parseTable(JsonNode json) {
        if (json == null || json.isNull()) {
            return null;
        }
        String id = text(json, "id");
        String fqn = text(json, "fullyQualifiedName");
        if (id.isBlank() || fqn.isBlank()) {
            return null;
        }
        OpenMetadataTable table = new OpenMetadataTable();
        table.setId(id);
        table.setName(text(json, "name"));
        table.setFullyQualifiedName(fqn);
        table.setTableType(text(json, "tableType"));
        table.setDescription(text(json, "description"));
        table.setServiceFullyQualifiedName(referenceFqn(json, "service"));
        table.setDatabaseFullyQualifiedName(referenceFqn(json, "database"));
        table.setSchemaFullyQualifiedName(referenceFqn(json, "databaseSchema"));
        List<OpenMetadataColumn> columns = new ArrayList<>();
        for (JsonNode column : json.path("columns")) {
            OpenMetadataColumn parsed = parseColumn(column);
            if (parsed != null) {
                columns.add(parsed);
            }
        }
        table.setColumns(columns);
        List<OpenMetadataTableConstraint> constraints = new ArrayList<>();
        for (JsonNode constraint : json.path("tableConstraints")) {
            OpenMetadataTableConstraint parsed = parseConstraint(constraint);
            if (parsed != null) {
                constraints.add(parsed);
            }
        }
        table.setTableConstraints(constraints);
        JsonNode profile = json.get("profile");
        if (profile != null && !profile.isNull()) {
            table.setProfile(parseProfile(json, profile, columns));
        }
        return table;
    }

    private static OpenMetadataColumn parseColumn(JsonNode json) {
        if (json == null || json.isNull() || text(json, "name").isBlank()) {
            return null;
        }
        OpenMetadataColumn column = new OpenMetadataColumn();
        column.setName(text(json, "name"));
        column.setFullyQualifiedName(text(json, "fullyQualifiedName"));
        column.setDataType(text(json, "dataType"));
        column.setDataTypeDisplay(text(json, "dataTypeDisplay"));
        column.setDataLength(nullableLong(json, "dataLength"));
        column.setPrecision(nullableLong(json, "precision"));
        column.setScale(nullableLong(json, "scale"));
        column.setDescription(text(json, "description"));
        column.setConstraint(text(json, "constraint"));
        column.setOrdinalPosition(nullableInteger(json, "ordinalPosition"));
        JsonNode profile = json.get("profile");
        if (profile != null && !profile.isNull()) {
            column.setProfile(parseColumnProfile(profile));
        }
        return column;
    }

    private static OpenMetadataTableConstraint parseConstraint(JsonNode json) {
        if (json == null || json.isNull()) {
            return null;
        }
        OpenMetadataTableConstraint constraint = new OpenMetadataTableConstraint();
        constraint.setConstraintType(text(json, "constraintType"));
        constraint.setRelationshipType(text(json, "relationshipType"));
        constraint.setColumns(stringList(json.path("columns")));
        constraint.setReferredColumns(stringList(json.path("referredColumns")));
        return constraint;
    }

    private static OpenMetadataTableProfile parseLatestProfile(JsonNode table, JsonNode profileNode) {
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
        return parseProfile(table, profile, columns);
    }

    private static OpenMetadataTableProfile parseProfile(
            JsonNode table, JsonNode profile, List<?> parsedColumns) {
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
        List<OpenMetadataColumnProfile> columns = new ArrayList<>();
        for (Object parsedColumn : parsedColumns) {
            if (parsedColumn instanceof OpenMetadataColumnProfile columnProfile) {
                columns.add(columnProfile);
            }
        }
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

    private static List<String> stringList(JsonNode values) {
        if (values == null || !values.isArray()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                result.add(value.asText());
            }
        }
        return result;
    }

    private static String referenceFqn(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.isTextual() ? value.asText() : text(value, "fullyQualifiedName");
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json == null ? null : json.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static Integer nullableInteger(JsonNode json, String field) {
        JsonNode value = json == null ? null : json.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static BigDecimal decimal(JsonNode json, String field) {
        JsonNode value = json == null ? null : json.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.decimalValue();
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

    private static String cursor(String after) {
        return after == null || after.isBlank() ? "" : "&after=" + encode(after);
    }

    private static <T> OpenMetadataPage<T> page(JsonNode response, List<T> data) {
        JsonNode paging = response == null ? null : response.path("paging");
        long total = paging == null || paging.isMissingNode()
                ? data.size() : paging.path("total").asLong(data.size());
        String after = paging == null || paging.isMissingNode()
                ? null : paging.path("after").asText(null);
        return new OpenMetadataPage<>(data, total, after);
    }
}
