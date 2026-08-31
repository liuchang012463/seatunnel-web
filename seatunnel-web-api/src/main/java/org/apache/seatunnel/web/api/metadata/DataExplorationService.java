package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumn;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumnProfile;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabaseSchema;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTableConstraint;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTableProfile;
import org.apache.seatunnel.web.api.service.DataSourceCatalogService;
import org.apache.seatunnel.web.common.QueryResult;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.common.utils.MetadataStableName;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationColumnProfileVO;
import org.apache.seatunnel.web.spi.bean.dto.DataExplorationMetadataUpdateDTO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationColumnVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationConstraintVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationDatabaseVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationErColumnVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationErDiagramVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationErEdgeVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationErEndpointVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationErNodeVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationProfileVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationMetadataJobVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationSchemaVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationTableDetailVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationTableMetricsVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationTablePageVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationTableVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OpenMetadata exploration facade. Database/schema/table/profile details are
 * transient projections; none are copied into SeaTunnel Web tables. The
 * mutable methods below still write only through the official SDK boundary.
 */
@Slf4j
@Service
public class DataExplorationService {

    private static final int MAX_OM_PAGE_SIZE = 1000;
    private static final int MAX_OM_PAGES = 10_000;

    private final DataSourceDao dataSourceDao;
    private final MetadataBindingDao metadataBindingDao;
    private final OpenMetadataClient openMetadataClient;
    private final DataSourceCatalogService dataSourceCatalogService;
    private final OpenMetadataProperties openMetadataProperties;
    private final MetadataExtensionClient metadataExtensionClient;

    /** Constructor used by the Spring application. */
    @Autowired
    public DataExplorationService(
            DataSourceDao dataSourceDao,
            MetadataBindingDao metadataBindingDao,
            OpenMetadataClient openMetadataClient,
            DataSourceCatalogService dataSourceCatalogService,
            OpenMetadataProperties openMetadataProperties,
            MetadataExtensionClient metadataExtensionClient) {
        this.dataSourceDao = dataSourceDao;
        this.metadataBindingDao = metadataBindingDao;
        this.openMetadataClient = openMetadataClient;
        this.dataSourceCatalogService = dataSourceCatalogService;
        this.openMetadataProperties = openMetadataProperties;
        this.metadataExtensionClient = metadataExtensionClient;
    }

    /** Compatibility constructor for callers that do not use completion jobs. */
    public DataExplorationService(
            DataSourceDao dataSourceDao,
            MetadataBindingDao metadataBindingDao,
            OpenMetadataClient openMetadataClient,
            DataSourceCatalogService dataSourceCatalogService,
            OpenMetadataProperties openMetadataProperties) {
        this(dataSourceDao, metadataBindingDao, openMetadataClient, dataSourceCatalogService,
                openMetadataProperties, null);
    }

    /** Test-friendly constructor that enables the explicitly requested facade. */
    public DataExplorationService(
            DataSourceDao dataSourceDao,
            MetadataBindingDao metadataBindingDao,
            OpenMetadataClient openMetadataClient,
            DataSourceCatalogService dataSourceCatalogService) {
        this(dataSourceDao, metadataBindingDao, openMetadataClient, dataSourceCatalogService, enabledProperties());
    }

    public List<DataExplorationDatabaseVO> listDatabases(Long dataSourceId) {
        ExplorationContext context = context(dataSourceId);
        List<DataExplorationDatabaseVO> result = new ArrayList<>();
        for (OpenMetadataDatabase database : collectPages(
                after -> openMetadataClient.listDatabasesPage(context.serviceFqn(), MAX_OM_PAGE_SIZE, after))) {
            if (database == null || !context.serviceFqn().equals(database.serviceFullyQualifiedName())) {
                continue;
            }
            DataExplorationDatabaseVO item = new DataExplorationDatabaseVO();
            item.setId(database.id());
            item.setFullyQualifiedName(database.fullyQualifiedName());
            item.setName(lastPart(database.fullyQualifiedName()));
            result.add(item);
        }
        return result;
    }

    public List<DataExplorationSchemaVO> listSchemas(Long dataSourceId, String databaseFqn) {
        ExplorationContext context = context(dataSourceId);
        OpenMetadataDatabase database = requireOwnedDatabase(context, databaseFqn);
        List<DataExplorationSchemaVO> result = new ArrayList<>();
        for (OpenMetadataDatabaseSchema schema : collectPages(
                after -> openMetadataClient.listSchemasPage(
                        database.fullyQualifiedName(), MAX_OM_PAGE_SIZE, after))) {
            if (schema == null
                    || !database.fullyQualifiedName().equals(schema.getDatabaseFullyQualifiedName())
                    || !context.serviceFqn().equals(schema.getServiceFullyQualifiedName())) {
                continue;
            }
            DataExplorationSchemaVO item = new DataExplorationSchemaVO();
            item.setId(schema.getId());
            item.setName(blankToDefault(schema.getName(), lastPart(schema.getFullyQualifiedName())));
            item.setFullyQualifiedName(schema.getFullyQualifiedName());
            item.setDatabaseFullyQualifiedName(schema.getDatabaseFullyQualifiedName());
            result.add(item);
        }
        return result;
    }

    public DataExplorationTablePageVO listTables(
            Long dataSourceId,
            String databaseFqn,
            String schemaFqn,
            int pageNo,
            int pageSize) {
        ExplorationContext context = context(dataSourceId);
        OpenMetadataDatabase database = requireOwnedDatabase(context, databaseFqn);
        requireOwnedSchema(context, database, schemaFqn);
        int safePageNo = Math.max(1, pageNo);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        int offset = (safePageNo - 1) * safePageSize;
        int pageLimit = Math.min(MAX_OM_PAGE_SIZE, Math.max(safePageSize, safePageNo * safePageSize));
        List<DataExplorationTableVO> records = new ArrayList<>();
        long[] matched = {0L};
        long[] total = {0L};
        String after = null;
        Set<String> seen = new HashSet<>();
        for (int pageNumber = 0; pageNumber < MAX_OM_PAGES; pageNumber++) {
            OpenMetadataPage<OpenMetadataTable> page = openMetadataClient.listTablesPage(
                    schemaFqn, true, pageLimit, after);
            if (page == null) {
                break;
            }
            total[0] = Math.max(total[0], page.total());
            for (OpenMetadataTable table : safe(page.data())) {
                if (table == null || !context.serviceFqn().equals(table.getServiceFullyQualifiedName())) {
                    continue;
                }
                if (matched[0] >= offset && records.size() < safePageSize) {
                    records.add(toTable(table));
                }
                matched[0]++;
            }
            String next = page.after();
            boolean enough = records.size() >= safePageSize && total[0] > 0;
            if (enough || next == null || next.isBlank() || !seen.add(next)) {
                break;
            }
            after = next;
        }
        DataExplorationTablePageVO page = new DataExplorationTablePageVO();
        page.setRecords(records);
        page.setTotal(total[0] > 0 ? total[0] : matched[0]);
        page.setPageNo(safePageNo);
        page.setPageSize(safePageSize);
        return page;
    }

    public DataExplorationTableDetailVO getTable(Long dataSourceId, String tableId) {
        ExplorationContext context = context(dataSourceId);
        OpenMetadataTable table = requireOwnedTable(context, tableId);
        return toTableDetail(table);
    }

    /** Updates only explicitly supplied governance fields on an owned table. */
    public DataExplorationTableDetailVO updateMetadata(
            Long dataSourceId, String tableId, DataExplorationMetadataUpdateDTO request) {
        ExplorationContext context = context(dataSourceId);
        OpenMetadataTable table = requireOwnedTable(context, tableId);
        if (request == null) {
            throw invalid("metadata");
        }
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode patch = mapper.createArrayNode();
        if (request.getDisplayName() != null) {
            addPatch(patch, "add", "/displayName", mapper.getNodeFactory().textNode(request.getDisplayName().trim()));
        }
        if (request.getDescription() != null) {
            addPatch(patch, "add", "/description", mapper.getNodeFactory().textNode(request.getDescription()));
        }
        if (request.getRetentionPeriod() != null) {
            addPatch(patch, request.getRetentionPeriod().isBlank() ? "replace" : "add", "/retentionPeriod",
                    request.getRetentionPeriod().isBlank()
                    ? mapper.getNodeFactory().nullNode()
                    : mapper.getNodeFactory().textNode(request.getRetentionPeriod().trim()));
        }
        if (request.getTags() != null) {
            ArrayNode tags = mapper.createArrayNode();
            for (String tag : request.getTags()) {
                if (tag == null || tag.isBlank()) {
                    continue;
                }
                ObjectNode tagValue = mapper.createObjectNode();
                tagValue.put("tagFQN", tag.trim());
                tagValue.put("labelType", "Manual");
                tagValue.put("state", "Confirmed");
                tags.add(tagValue);
            }
            addPatch(patch, "replace", "/tags", tags);
        }
        if (request.getDomainId() != null) {
            ArrayNode domains = mapper.createArrayNode();
            if (!request.getDomainId().isBlank()) {
                ObjectNode domain = mapper.createObjectNode();
                domain.put("id", request.getDomainId().trim());
                domain.put("type", "domain");
                domains.add(domain);
            }
            addPatch(patch, "add", "/domains", domains);
        }
        if (patch.isEmpty()) {
            throw invalid("metadata must contain at least one editable field");
        }
        OpenMetadataTable updated = openMetadataClient.patchTable(table.getId(), patch);
        return toTableDetail(updated == null ? requireOwnedTable(context, tableId) : updated);
    }

    /** Starts the asynchronous description completion task for one owned table. */
    public DataExplorationMetadataJobVO startMetadataCompletion(Long dataSourceId, String tableId) {
        ExplorationContext context = context(dataSourceId);
        OpenMetadataTable table = requireOwnedTable(context, tableId);
        return toMetadataJob(requireMetadataExtensionClient().startGenerate(table.getFullyQualifiedName()));
    }

    /** Reads a completion task only in the context of its owned source/table. */
    public DataExplorationMetadataJobVO getMetadataCompletion(
            Long dataSourceId, String tableId, String jobId) {
        ExplorationContext context = context(dataSourceId);
        OpenMetadataTable table = requireOwnedTable(context, tableId);
        DataExplorationMetadataJobVO result = toMetadataJob(requireMetadataExtensionClient().getJob(jobId));
        if (result.getFullyQualifiedName() != null
                && !result.getFullyQualifiedName().isBlank()
                && !result.getFullyQualifiedName().equals(table.getFullyQualifiedName())) {
            throw invalid("metadata completion job does not belong to this table");
        }
        return result;
    }

    /**
     * Builds the same ER projection as the open_metadata_extension service,
     * but keeps the OpenMetadata call inside this application's official
     * 1.12.10 SDK boundary. Omitting the schema uses the Database table
     * collection directly, exactly as the extension endpoint does; a schema
     * FQN may be supplied for a smaller view.
     */
    public DataExplorationErDiagramVO getErDiagram(
            Long dataSourceId, String databaseFqn, String schemaFqn) {
        ExplorationContext context = context(dataSourceId);
        OpenMetadataDatabase database = requireOwnedDatabase(context, databaseFqn);
        List<OpenMetadataTable> tables;
        if (schemaFqn == null || schemaFqn.isBlank()) {
            tables = collectPages(after -> openMetadataClient.listTablesByDatabasePage(
                    database.fullyQualifiedName(), true, MAX_OM_PAGE_SIZE, after));
        } else {
            requireOwnedSchema(context, database, schemaFqn);
            tables = collectPages(after -> openMetadataClient.listTablesPage(
                    schemaFqn, true, MAX_OM_PAGE_SIZE, after));
        }

        Map<String, OpenMetadataTable> tablesByFqn = new LinkedHashMap<>();
        for (OpenMetadataTable table : tables) {
            if (table == null
                    || table.getFullyQualifiedName() == null
                    || !context.serviceFqn().equals(serviceFqn(table))) {
                continue;
            }
            String tableDatabaseFqn = table.getDatabaseFullyQualifiedName();
            if (tableDatabaseFqn != null
                    && !tableDatabaseFqn.isBlank()
                    && !database.fullyQualifiedName().equals(tableDatabaseFqn)) {
                continue;
            }
            tablesByFqn.putIfAbsent(table.getFullyQualifiedName(), table);
        }

        DataExplorationErDiagramVO result = new DataExplorationErDiagramVO();
        result.setDatabaseFqn(database.fullyQualifiedName());
        result.setSchemaFullyQualifiedName(schemaFqn);
        Map<String, String> nodeIdByFqn = new LinkedHashMap<>();
        for (OpenMetadataTable table : tablesByFqn.values()) {
            DataExplorationErNodeVO node = toErNode(table);
            result.getNodes().add(node);
            nodeIdByFqn.put(table.getFullyQualifiedName(), node.getId());
        }

        Set<String> seenEdgeIds = new HashSet<>();
        for (OpenMetadataTable table : tablesByFqn.values()) {
            for (OpenMetadataTableConstraint constraint : safe(table.getTableConstraints())) {
                if (!isForeignKey(constraint)) {
                    continue;
                }
                List<String> sourceColumns = safe(constraint.getColumns());
                List<String> referredColumns = safe(constraint.getReferredColumns());
                if (sourceColumns.isEmpty() || referredColumns.isEmpty()) {
                    continue;
                }
                String targetFqn = targetTableFqn(referredColumns, nodeIdByFqn);
                String targetNodeId = targetFqn == null ? null : nodeIdByFqn.get(targetFqn);
                if (targetNodeId == null || targetNodeId.equals(table.getId())) {
                    // The extension intentionally skips relationships that do
                    // not resolve to a table in the requested database.
                    continue;
                }
                List<String> targetColumns = referredColumns.stream()
                        .map(DataExplorationService::lastPart)
                        .filter(value -> !value.isBlank())
                        .toList();
                if (targetColumns.isEmpty()) {
                    continue;
                }
                String edgeId = "fk-" + table.getId() + "." + sourceColumns
                        + "->" + targetNodeId + "." + targetColumns;
                if (!seenEdgeIds.add(edgeId)) {
                    continue;
                }
                DataExplorationErEdgeVO edge = new DataExplorationErEdgeVO();
                edge.setId(edgeId);
                edge.setType("FOREIGN_KEY");
                edge.setSource(new DataExplorationErEndpointVO(table.getId(), sourceColumns));
                edge.setTarget(new DataExplorationErEndpointVO(targetNodeId, targetColumns));
                result.getEdges().add(edge);
            }
        }
        return result;
    }

    public DataExplorationProfileVO getProfile(Long dataSourceId, String tableId) {
        ExplorationContext context = context(dataSourceId);
        OpenMetadataTable table = requireOwnedTable(context, tableId);
        OpenMetadataTableProfile profile = openMetadataClient.getLatestTableProfile(table.getFullyQualifiedName());
        return toProfile(table, profile);
    }

    /**
     * Validates the OpenMetadata table service ownership before delegating to
     * the existing DataSource Catalog Top20 implementation. The caller cannot
     * override the table path with a different table id.
     */
    public QueryResult preview(Long dataSourceId, String tableId, Map<String, Object> requestBody) {
        ExplorationContext context = context(dataSourceId);
        OpenMetadataTable table = requireOwnedTable(context, tableId);
        Map<String, Object> request = requestBody == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(requestBody);
        request.put("read_mode", "TABLE");
        request.put("table_path", localTablePath(table));
        return dataSourceCatalogService.getTop20Data(dataSourceId, request);
    }

    private DataExplorationTableVO toTable(OpenMetadataTable table) {
        DataExplorationTableVO result = new DataExplorationTableVO();
        result.setId(table.getId());
        result.setName(table.getName());
        result.setDisplayName(table.getDisplayName());
        result.setFullyQualifiedName(table.getFullyQualifiedName());
        result.setTableType(table.getTableType());
        result.setDescription(table.getDescription());
        result.setColumnCount(table.getColumns() == null ? 0 : table.getColumns().size());
        result.setProfileAvailable(table.getProfile() != null && table.getProfile().getTimestamp() != null);
        result.setProfileTime(table.getProfile() == null ? null : table.getProfile().getTimestamp());
        return result;
    }

    private DataExplorationErNodeVO toErNode(OpenMetadataTable table) {
        DataExplorationErNodeVO node = new DataExplorationErNodeVO();
        node.setId(table.getId());
        node.setName(table.getName());
        node.setDisplayName(table.getDisplayName());
        node.setDescription(table.getDescription());
        node.setFullyQualifiedName(table.getFullyQualifiedName());

        Map<String, Set<String>> constraintByColumn = new LinkedHashMap<>();
        for (OpenMetadataColumn column : safe(table.getColumns())) {
            if (column != null && column.getName() != null) {
                constraintByColumn.put(column.getName().toLowerCase(Locale.ROOT), new java.util.LinkedHashSet<>());
                String columnConstraint = normalizeConstraint(column.getConstraint());
                if (!columnConstraint.isBlank()) {
                    constraintByColumn.get(column.getName().toLowerCase(Locale.ROOT)).add(columnConstraint);
                }
            }
        }
        for (OpenMetadataTableConstraint constraint : safe(table.getTableConstraints())) {
            String constraintType = normalizeConstraint(constraint == null ? null : constraint.getConstraintType());
            if (constraintType.isBlank()) {
                continue;
            }
            for (String columnName : safe(constraint.getColumns())) {
                Set<String> labels = constraintByColumn.get(
                        columnName == null ? "" : columnName.toLowerCase(Locale.ROOT));
                if (labels != null) {
                    labels.add(constraintType);
                }
            }
        }
        for (OpenMetadataColumn column : safe(table.getColumns())) {
            if (column == null || column.getName() == null) {
                continue;
            }
            DataExplorationErColumnVO item = new DataExplorationErColumnVO();
            item.setId(column.getFullyQualifiedName() == null
                    ? table.getFullyQualifiedName() + "." + column.getName()
                    : column.getFullyQualifiedName());
            item.setName(column.getName());
            item.setDisplayName(column.getDisplayName());
            item.setDescription(column.getDescription());
            item.setDataType(blankToDefault(column.getDataTypeDisplay(), column.getDataType()));
            item.setConstraints(new ArrayList<>(constraintByColumn.getOrDefault(
                    column.getName().toLowerCase(Locale.ROOT), Set.of())));
            node.getColumns().add(item);
        }
        return node;
    }

    private static boolean isForeignKey(OpenMetadataTableConstraint constraint) {
        return constraint != null && "FOREIGN_KEY".equals(normalizeConstraint(constraint.getConstraintType()));
    }

    private static String normalizeConstraint(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        return switch (normalized) {
            case "PRIMARYKEY" -> "PRIMARY_KEY";
            case "FOREIGNKEY" -> "FOREIGN_KEY";
            case "NOTNULL" -> "NOT_NULL";
            case "UNIQUE" -> "UNIQUE";
            default -> value.trim().toUpperCase(Locale.ROOT);
        };
    }

    private static String targetTableFqn(
            List<String> referredColumns, Map<String, String> nodeIdByFqn) {
        Set<String> candidates = new HashSet<>();
        for (String referredColumn : referredColumns) {
            if (referredColumn == null || referredColumn.isBlank()) {
                continue;
            }
            int separator = referredColumn.lastIndexOf('.');
            if (separator <= 0) {
                return null;
            }
            candidates.add(referredColumn.substring(0, separator));
        }
        if (candidates.size() != 1) {
            return null;
        }
        String candidate = candidates.iterator().next();
        return nodeIdByFqn.containsKey(candidate) ? candidate : null;
    }

    private static String serviceFqn(OpenMetadataTable table) {
        if (table == null) {
            return "";
        }
        return table.getServiceFullyQualifiedName() == null || table.getServiceFullyQualifiedName().isBlank()
                ? firstPart(table.getFullyQualifiedName()) : table.getServiceFullyQualifiedName();
    }

    private DataExplorationTableDetailVO toTableDetail(OpenMetadataTable table) {
        DataExplorationTableDetailVO result = new DataExplorationTableDetailVO();
        result.setId(table.getId());
        result.setName(table.getName());
        result.setDisplayName(table.getDisplayName());
        result.setFullyQualifiedName(table.getFullyQualifiedName());
        result.setTableType(table.getTableType());
        result.setDescription(table.getDescription());
        result.setRetentionPeriod(table.getRetentionPeriod());
        result.setServiceFullyQualifiedName(table.getServiceFullyQualifiedName());
        result.setDatabaseFullyQualifiedName(table.getDatabaseFullyQualifiedName());
        result.setSchemaFullyQualifiedName(table.getSchemaFullyQualifiedName());
        result.setTags(new ArrayList<>(safe(table.getTags())));
        result.setDomains(new ArrayList<>(safe(table.getDomains())));
        List<DataExplorationColumnVO> columns = new ArrayList<>();
        for (OpenMetadataColumn column : safe(table.getColumns())) {
            DataExplorationColumnVO item = new DataExplorationColumnVO();
            item.setName(column.getName());
            item.setFullyQualifiedName(column.getFullyQualifiedName());
            item.setDataType(column.getDataType());
            item.setDataTypeDisplay(column.getDataTypeDisplay());
            item.setDataLength(column.getDataLength());
            item.setPrecision(column.getPrecision());
            item.setScale(column.getScale());
            item.setDescription(column.getDescription());
            item.setConstraint(column.getConstraint());
            item.setOrdinalPosition(column.getOrdinalPosition());
            columns.add(item);
        }
        result.setColumns(columns);
        List<DataExplorationConstraintVO> constraints = new ArrayList<>();
        for (OpenMetadataTableConstraint constraint : safe(table.getTableConstraints())) {
            DataExplorationConstraintVO item = new DataExplorationConstraintVO();
            item.setConstraintType(constraint.getConstraintType());
            item.setColumns(new ArrayList<>(safe(constraint.getColumns())));
            item.setReferredColumns(new ArrayList<>(safe(constraint.getReferredColumns())));
            item.setRelationshipType(constraint.getRelationshipType());
            constraints.add(item);
        }
        result.setTableConstraints(constraints);
        return result;
    }

    private DataExplorationMetadataJobVO toMetadataJob(JsonNode node) {
        DataExplorationMetadataJobVO result = new DataExplorationMetadataJobVO();
        result.setJobId(text(node, "job_id", "jobId"));
        result.setStatus(text(node, "status"));
        result.setType(text(node, "type"));
        result.setFullyQualifiedName(text(node, "fqn", "fullyQualifiedName"));
        result.setLevel(text(node, "level"));
        if (node != null && node.hasNonNull("total_tables")) {
            result.setTotalTables(node.get("total_tables").asInt());
        } else if (node != null && node.hasNonNull("totalTables")) {
            result.setTotalTables(node.get("totalTables").asInt());
        }
        JsonNode progress = node == null ? null : node.get("progress");
        if (progress != null && progress.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            progress.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue()));
            result.setProgress(values);
        }
        if (node != null && node.has("result") && !node.get("result").isNull()) {
            result.setResult(node.get("result"));
        }
        result.setError(text(node, "error"));
        result.setCreatedAt(text(node, "created_at", "createdAt"));
        result.setUpdatedAt(text(node, "updated_at", "updatedAt"));
        return result;
    }

    private static String text(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private MetadataExtensionClient requireMetadataExtensionClient() {
        if (metadataExtensionClient == null) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.METADATA_EXTENSION_NOT_CONFIGURED,
                    "Metadata completion service is not configured");
        }
        return metadataExtensionClient;
    }

    private static void addPatch(ArrayNode patch, String operationName, String path, JsonNode value) {
        ObjectNode operation = patch.objectNode();
        operation.put("op", operationName);
        operation.put("path", path);
        operation.set("value", value);
        patch.add(operation);
    }

    private DataExplorationProfileVO toProfile(
            OpenMetadataTable table, OpenMetadataTableProfile profile) {
        DataExplorationProfileVO result = new DataExplorationProfileVO();
        if (profile == null) {
            result.setTable(null);
            result.setProfileTime(null);
        } else {
            result.setProfileTime(profile.getTimestamp());
            DataExplorationTableMetricsVO tableMetrics = new DataExplorationTableMetricsVO();
            tableMetrics.setRowCount(profile.getRowCount());
            tableMetrics.setColumnCount(profile.getColumnCount());
            tableMetrics.setSizeInByte(profile.getSizeInByte());
            result.setTable(tableMetrics);
        }

        Map<String, OpenMetadataColumnProfile> profiles = safe(profile == null ? null : profile.getColumns()).stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getName() != null)
                .collect(Collectors.toMap(item -> item.getName().toLowerCase(Locale.ROOT), Function.identity(), (left, right) -> left));
        for (OpenMetadataColumn column : safe(table.getColumns())) {
            OpenMetadataColumnProfile columnProfile = profiles.get(
                    column.getName() == null ? "" : column.getName().toLowerCase(Locale.ROOT));
            DataExplorationColumnProfileVO item = toColumnProfile(column, columnProfile, table.getTableConstraints());
            result.getColumns().add(item);
        }
        // A profile can contain a column that is not currently present in the
        // table response (for example while a metadata scan is converging).
        // Preserve the known profile instead of silently dropping its metrics.
        for (OpenMetadataColumnProfile columnProfile : profiles.values()) {
            boolean present = safe(table.getColumns()).stream()
                    .anyMatch(column -> columnProfile.getName().equalsIgnoreCase(column.getName()));
            if (!present) {
                result.getColumns().add(toColumnProfile(null, columnProfile, table.getTableConstraints()));
            }
        }
        return result;
    }

    private DataExplorationColumnProfileVO toColumnProfile(
            OpenMetadataColumn column,
            OpenMetadataColumnProfile profile,
            List<OpenMetadataTableConstraint> constraints) {
        DataExplorationColumnProfileVO result = new DataExplorationColumnProfileVO();
        result.setName(column == null ? profile == null ? null : profile.getName() : column.getName());
        result.setDataType(column == null ? null : column.getDataType());
        result.setConstraint(column == null ? null : column.getConstraint());
        if (profile != null) {
            result.setProfileTime(profile.getTimestamp());
            result.setValuesCount(profile.getValuesCount());
            result.setValidCount(profile.getValidCount());
            result.setDuplicateCount(profile.getDuplicateCount());
            result.setNullCount(profile.getNullCount());
            result.setMissingCount(profile.getMissingCount());
            result.setDistinctCount(profile.getDistinctCount());
            result.setUniqueCount(profile.getUniqueCount());
            result.setNullProportion(profile.getNullProportion());
            result.setDistinctProportion(profile.getDistinctProportion());
            result.setUniqueProportion(profile.getUniqueProportion());
            result.setMin(profile.getMin());
            result.setMax(profile.getMax());
            result.setMean(profile.getMean());
            result.setMinLength(profile.getMinLength());
            result.setMaxLength(profile.getMaxLength());
        }
        ExplorationQualityResult quality = DataExplorationQualityEvaluator.evaluate(
                result.getConstraint(), result.getName(), profile, constraints);
        result.setQualityStatus(quality.getQualityStatus());
        result.setQualityReason(quality.getQualityReason());
        return result;
    }

    private OpenMetadataDatabase requireOwnedDatabase(ExplorationContext context, String databaseFqn) {
        if (databaseFqn == null || databaseFqn.isBlank()) {
            throw invalid("databaseFqn");
        }
        OpenMetadataDatabase database = openMetadataClient.findDatabase(databaseFqn).orElse(null);
        if (database == null || !context.serviceFqn().equals(database.serviceFullyQualifiedName())) {
            throw invalid("databaseFqn does not belong to this data source");
        }
        return database;
    }

    private void requireOwnedSchema(
            ExplorationContext context, OpenMetadataDatabase database, String schemaFqn) {
        if (schemaFqn == null || schemaFqn.isBlank()) {
            throw invalid("schemaFqn");
        }
        boolean owned = collectPages(after -> openMetadataClient.listSchemasPage(
                        database.fullyQualifiedName(), MAX_OM_PAGE_SIZE, after)).stream()
                .filter(Objects::nonNull)
                .anyMatch(schema -> schemaFqn.equals(schema.getFullyQualifiedName())
                        && database.fullyQualifiedName().equals(schema.getDatabaseFullyQualifiedName())
                        && context.serviceFqn().equals(schema.getServiceFullyQualifiedName()));
        if (!owned) {
            throw invalid("schemaFqn does not belong to this data source");
        }
    }

    private OpenMetadataTable requireOwnedTable(ExplorationContext context, String tableId) {
        if (tableId == null || tableId.isBlank()) {
            throw invalid("tableId");
        }
        OpenMetadataTable table = openMetadataClient.getTable(tableId);
        if (table == null) {
            throw new ServiceException(org.apache.seatunnel.web.spi.enums.Status.DATASOURCE_TABLE_NOT_FOUND, tableId);
        }
        String serviceFqn = table.getServiceFullyQualifiedName();
        if (serviceFqn == null || serviceFqn.isBlank()) {
            serviceFqn = firstPart(table.getFullyQualifiedName());
        }
        if (!context.serviceFqn().equals(serviceFqn)) {
            throw invalid("table does not belong to this data source");
        }
        return table;
    }

    private ExplorationContext context(Long dataSourceId) {
        if (!openMetadataProperties.isEnabled()) {
            throw invalid("OpenMetadata integration is disabled");
        }
        if (dataSourceId == null || dataSourceId <= 0) {
            throw invalid("dataSourceId");
        }
        DataSource source = dataSourceDao.queryById(dataSourceId);
        if (source == null || source.getStatus() == DataSourceLifecycleStatus.REVOKED) {
            throw invalid("data source is unavailable");
        }
        if (source.getDbType() != DbType.MYSQL
                && source.getDbType() != DbType.POSTGRE_SQL
                && source.getDbType() != DbType.JDBC
                && source.getDbType() != DbType.DORIS
                && source.getDbType() != DbType.ORACLE
                && source.getDbType() != DbType.DAMENG
                && source.getDbType() != DbType.KINGBASE) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.CONNECTOR_NOT_SUPPORTED,
                    "OpenMetadata 1.12.10 exploration is supported only for MYSQL, POSTGRE_SQL, JDBC(PostgreSQL), DORIS, ORACLE, DAMENG and KINGBASE");
        }
        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(dataSourceId);
        if (binding == null
                || binding.getDesiredState() != MetadataDesiredState.ACTIVE
                || binding.getSyncStatus() != MetadataSyncStatus.READY) {
            throw invalid("metadata synchronization is not ready");
        }
        String serviceFqn = binding.getOmServiceFqn();
        if (serviceFqn == null || serviceFqn.isBlank()) {
            serviceFqn = MetadataStableName.serviceFqn(dataSourceId);
        }
        openMetadataClient.assertFixedVersion();
        return new ExplorationContext(dataSourceId, serviceFqn);
    }

    private static String localTablePath(OpenMetadataTable table) {
        // QueryRequest combines table_path with the database/schema already
        // present in the registered datasource connection. Passing an OM
        // schema-qualified path here would be quoted as one literal table
        // name (for example `schema.orders`) by the existing preview layer.
        return table.getName();
    }

    private static String firstPart(String value) {
        if (value == null) {
            return "";
        }
        int separator = value.indexOf('.');
        return separator < 0 ? value : value.substring(0, separator);
    }

    private static String lastPart(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private static <T> List<T> collectPages(Function<String, OpenMetadataPage<T>> loader) {
        List<T> result = new ArrayList<>();
        String after = null;
        Set<String> seen = new HashSet<>();
        for (int pageNumber = 0; pageNumber < MAX_OM_PAGES; pageNumber++) {
            OpenMetadataPage<T> page = loader.apply(after);
            if (page == null) {
                break;
            }
            result.addAll(safe(page.data()));
            String next = page.after();
            if (next == null || next.isBlank() || !seen.add(next)) {
                break;
            }
            after = next;
        }
        return result;
    }

    private static ServiceException invalid(String field) {
        return new ServiceException(org.apache.seatunnel.web.spi.enums.Status.REQUEST_PARAMS_NOT_VALID_ERROR, field);
    }

    private static OpenMetadataProperties enabledProperties() {
        OpenMetadataProperties properties = new OpenMetadataProperties();
        properties.setEnabled(true);
        return properties;
    }

    private record ExplorationContext(Long dataSourceId, String serviceFqn) {
    }
}
