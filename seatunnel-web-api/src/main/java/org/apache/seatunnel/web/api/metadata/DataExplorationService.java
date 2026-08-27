package org.apache.seatunnel.web.api.metadata;

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
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationColumnVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationConstraintVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationDatabaseVO;
import org.apache.seatunnel.web.spi.bean.vo.DataExplorationProfileVO;
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
 * Read-only OpenMetadata exploration facade. Database/schema/table/profile
 * details are transient projections; none are copied into SeaTunnel Web
 * tables. Preview is the sole operation delegated to the existing local
 * DataSource Catalog after the OpenMetadata service ownership check.
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

    /** Constructor used by the Spring application. */
    @Autowired
    public DataExplorationService(
            DataSourceDao dataSourceDao,
            MetadataBindingDao metadataBindingDao,
            OpenMetadataClient openMetadataClient,
            DataSourceCatalogService dataSourceCatalogService,
            OpenMetadataProperties openMetadataProperties) {
        this.dataSourceDao = dataSourceDao;
        this.metadataBindingDao = metadataBindingDao;
        this.openMetadataClient = openMetadataClient;
        this.dataSourceCatalogService = dataSourceCatalogService;
        this.openMetadataProperties = openMetadataProperties;
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
        result.setFullyQualifiedName(table.getFullyQualifiedName());
        result.setTableType(table.getTableType());
        result.setDescription(table.getDescription());
        result.setColumnCount(table.getColumns() == null ? 0 : table.getColumns().size());
        result.setProfileAvailable(table.getProfile() != null && table.getProfile().getTimestamp() != null);
        result.setProfileTime(table.getProfile() == null ? null : table.getProfile().getTimestamp());
        return result;
    }

    private DataExplorationTableDetailVO toTableDetail(OpenMetadataTable table) {
        DataExplorationTableDetailVO result = new DataExplorationTableDetailVO();
        result.setId(table.getId());
        result.setName(table.getName());
        result.setFullyQualifiedName(table.getFullyQualifiedName());
        result.setTableType(table.getTableType());
        result.setDescription(table.getDescription());
        result.setServiceFullyQualifiedName(table.getServiceFullyQualifiedName());
        result.setDatabaseFullyQualifiedName(table.getDatabaseFullyQualifiedName());
        result.setSchemaFullyQualifiedName(table.getSchemaFullyQualifiedName());
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
        String tableName = table.getName();
        String schema = lastPart(table.getSchemaFullyQualifiedName());
        if (schema.isBlank()) {
            schema = lastPart(table.getDatabaseFullyQualifiedName());
        }
        return schema.isBlank() ? tableName : schema + "." + tableName;
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
