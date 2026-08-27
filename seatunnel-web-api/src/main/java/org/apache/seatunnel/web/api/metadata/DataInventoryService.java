package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumn;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataColumnProfile;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabaseSchema;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTableProfile;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataRunStatus;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.common.utils.MetadataStableName;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.DataInventoryFilterDTO;
import org.apache.seatunnel.web.spi.bean.vo.DataInventoryDistributionVO;
import org.apache.seatunnel.web.spi.bean.vo.DataInventoryProfileCoverageVO;
import org.apache.seatunnel.web.spi.bean.vo.DataInventorySummaryVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Aggregates the existing SeaTunnel master data with cursor-paged projections
 * from OpenMetadata 1.12.10.  It deliberately does not persist Database,
 * Schema, Table or Column mirrors.
 */
@Slf4j
@Service
public class DataInventoryService {

    private static final int PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 10_000;

    private final DataSourceDao dataSourceDao;
    private final DataSourceUnitDao dataSourceUnitDao;
    private final BusinessSystemDao businessSystemDao;
    private final MetadataBindingDao metadataBindingDao;
    private final OpenMetadataClient openMetadataClient;
    private final MetadataInventoryCache cache;

    @Autowired
    public DataInventoryService(
            DataSourceDao dataSourceDao,
            DataSourceUnitDao dataSourceUnitDao,
            BusinessSystemDao businessSystemDao,
            MetadataBindingDao metadataBindingDao,
            OpenMetadataClient openMetadataClient,
            MetadataInventoryCache cache) {
        this.dataSourceDao = dataSourceDao;
        this.dataSourceUnitDao = dataSourceUnitDao;
        this.businessSystemDao = businessSystemDao;
        this.metadataBindingDao = metadataBindingDao;
        this.openMetadataClient = openMetadataClient;
        this.cache = cache;
    }

    /** Test-friendly constructor with a real short-lived cache. */
    public DataInventoryService(
            DataSourceDao dataSourceDao,
            DataSourceUnitDao dataSourceUnitDao,
            BusinessSystemDao businessSystemDao,
            MetadataBindingDao metadataBindingDao,
            OpenMetadataClient openMetadataClient) {
        this(dataSourceDao, dataSourceUnitDao, businessSystemDao, metadataBindingDao,
                openMetadataClient, new MetadataInventoryCache());
    }

    public DataInventorySummaryVO summary(DataInventoryFilterDTO request) {
        return snapshot(normalize(request)).summary();
    }

    public List<DataInventoryDistributionVO> sourceTypeDistribution(DataInventoryFilterDTO request) {
        return snapshot(normalize(request)).sourceTypes();
    }

    public List<DataInventoryDistributionVO> unitDistribution(DataInventoryFilterDTO request) {
        return snapshot(normalize(request)).units();
    }

    public List<DataInventoryDistributionVO> businessSystemDistribution(DataInventoryFilterDTO request) {
        return snapshot(normalize(request)).businessSystems();
    }

    public DataInventoryProfileCoverageVO profileCoverage(DataInventoryFilterDTO request) {
        return snapshot(normalize(request)).coverage();
    }

    /** Invalidated after a successful scan/profile status refresh. */
    public void invalidateDataSource(Long dataSourceId) {
        cache.invalidateDataSource(dataSourceId);
    }

    /**
     * Collects only the rows required by the normalized XLSX export.  This is
     * intentionally uncached so an export always reflects the current OM
     * projection and never turns the cache into a metadata mirror.
     */
    public InventoryExportSnapshot collectForExport(DataInventoryFilterDTO request) {
        List<InventoryExportSourceRow> sources = new ArrayList<>();
        List<InventoryExportTableRow> tables = new ArrayList<>();
        List<InventoryExportColumnRow> columns = new ArrayList<>();
        List<InventoryExportFeatureRow> features = new ArrayList<>();
        streamForExport(request, new InventoryExportWriter() {
            @Override
            public void onSource(InventoryExportSourceRow row) {
                sources.add(row);
            }

            @Override
            public void onTable(InventoryExportTableRow row) {
                tables.add(row);
            }

            @Override
            public void onColumn(InventoryExportColumnRow row) {
                columns.add(row);
            }

            @Override
            public void onFeature(InventoryExportFeatureRow row) {
                features.add(row);
            }
        });
        return new InventoryExportSnapshot(sources, tables, columns, features);
    }

    /** Streams export rows into a bounded writer without retaining all metadata rows. */
    public void streamForExport(DataInventoryFilterDTO request, InventoryExportWriter writer) {
        InventoryFilter filter = normalize(request);
        SourceCatalog catalog = loadSources(filter);
        for (SourceContext source : catalog.sources()) {
            writer.onSource(toSourceRow(source));
            if (!isReadyOpenMetadataSource(source)) {
                continue;
            }
            try {
                walkPages(
                        after -> openMetadataClient.listDatabasesPage(
                                serviceFqn(source), PAGE_SIZE, after),
                        database -> {
                            if (!matchesDatabase(filter, database)) {
                                return;
                            }
                            walkPages(
                                    after -> openMetadataClient.listSchemasPage(
                                            database.fullyQualifiedName(), PAGE_SIZE, after),
                                    schema -> streamTables(source, schema, writer));
                        });
            } catch (Exception error) {
                log.warn("Inventory export skipped OpenMetadata source {}", source.source().getId(), error);
            }
        }
    }

    private AggregateSnapshot snapshot(InventoryFilter filter) {
        String key = filter.cacheKey();
        Object cached = cache.get(key);
        if (cached instanceof AggregateSnapshot aggregate) {
            return aggregate;
        }
        AggregateBuilder aggregate = new AggregateBuilder();
        SourceCatalog catalog = loadSources(filter);
        aggregate.addMasterData(catalog.unitIds(), catalog.systemIds());
        for (SourceContext source : catalog.sources()) {
            aggregate.addSource(source);
            if (!isReadyOpenMetadataSource(source)) {
                continue;
            }
            try {
                long[] matchedDatabases = {0L};
                long databaseTotal = walkPages(
                        after -> openMetadataClient.listDatabasesPage(
                                serviceFqn(source), PAGE_SIZE, after),
                        database -> {
                            if (!matchesDatabase(filter, database)) {
                                return;
                            }
                            matchedDatabases[0]++;
                            long schemaTotal = walkPages(
                                    after -> openMetadataClient.listSchemasPage(
                                            database.fullyQualifiedName(), PAGE_SIZE, after),
                                    schema -> {
                                        long tableTotal = walkPages(
                                                after -> openMetadataClient.listTablesPage(
                                                        schema.getFullyQualifiedName(), true, PAGE_SIZE, after),
                                                table -> aggregate.addTable(
                                                        source, database, schema, table, profile(source, table)));
                                        aggregate.tableCount = add(aggregate.tableCount, tableTotal);
                                    });
                            aggregate.schemaCount = add(aggregate.schemaCount, schemaTotal);
                        });
                aggregate.databaseCount = add(
                        aggregate.databaseCount,
                        filter.databaseFqn() == null ? databaseTotal : matchedDatabases[0]);
            } catch (Exception error) {
                // A single unavailable source must not make the DB-backed
                // source/unit dashboard disappear.
                log.warn("Inventory aggregation skipped OpenMetadata source {}", source.source().getId(), error);
            }
        }
        AggregateSnapshot result = aggregate.freeze();
        cache.put(key, result);
        return result;
    }

    private SourceCatalog loadSources(InventoryFilter filter) {
        List<DataSource> dataSources = dataSourceDao.queryAll();
        List<DataSourceUnit> units = dataSourceUnitDao.queryAll();
        List<BusinessSystem> systems = businessSystemDao.queryAll();
        List<MetadataSourceBinding> bindings = metadataBindingDao.queryAll();
        Map<Long, DataSourceUnit> unitById = new HashMap<>();
        for (DataSourceUnit unit : safe(units)) {
            if (unit != null && unit.getId() != null) {
                unitById.put(unit.getId(), unit);
            }
        }
        Map<Long, BusinessSystem> systemById = new HashMap<>();
        for (BusinessSystem system : safe(systems)) {
            if (system != null && system.getId() != null) {
                systemById.put(system.getId(), system);
            }
        }
        Map<Long, MetadataSourceBinding> bindingBySourceId = new HashMap<>();
        for (MetadataSourceBinding binding : safe(bindings)) {
            if (binding != null && binding.getDataSourceId() != null) {
                bindingBySourceId.put(binding.getDataSourceId(), binding);
            }
        }
        List<SourceContext> selected = new ArrayList<>();
        for (DataSource source : safe(dataSources)) {
            if (source == null || source.getId() == null
                    || source.getStatus() == DataSourceLifecycleStatus.REVOKED) {
                continue;
            }
            BusinessSystem system = source.getBusinessSystemId() == null
                    ? null : systemById.get(source.getBusinessSystemId());
            DataSourceUnit unit = system == null ? null : unitById.get(system.getUnitId());
            if (!filter.matches(source, system, unit)) {
                continue;
            }
            selected.add(new SourceContext(source, system, unit, bindingBySourceId.get(source.getId())));
        }
        Set<Long> selectedUnitIds = new HashSet<>();
        Set<Long> selectedSystemIds = new HashSet<>();
        for (SourceContext source : selected) {
            if (source.unit() != null && source.unit().getId() != null) {
                selectedUnitIds.add(source.unit().getId());
            }
            if (source.system() != null && source.system().getId() != null) {
                selectedSystemIds.add(source.system().getId());
            }
        }
        if (filter.dataSourceId() == null && filter.businessSystemId() == null) {
            for (DataSourceUnit unit : safe(units)) {
                if (unit != null && unit.getId() != null && Integer.valueOf(1).equals(unit.getStatus())
                        && (filter.unitId() == null || filter.unitId().equals(unit.getId()))) {
                    selectedUnitIds.add(unit.getId());
                }
            }
            for (BusinessSystem system : safe(systems)) {
                if (system != null && system.getId() != null && Integer.valueOf(1).equals(system.getStatus())
                        && (filter.unitId() == null || filter.unitId().equals(system.getUnitId()))) {
                    selectedSystemIds.add(system.getId());
                }
            }
        }
        return new SourceCatalog(selected, selectedUnitIds, selectedSystemIds);
    }

    private void streamTables(
            SourceContext source,
            OpenMetadataDatabaseSchema schema,
            InventoryExportWriter writer) {
        if (schema == null || schema.getFullyQualifiedName() == null) {
            return;
        }
        walkPages(
                after -> openMetadataClient.listTablesPage(
                        schema.getFullyQualifiedName(), true, PAGE_SIZE, after),
                table -> {
                    if (table == null) {
                        return;
                    }
                    OpenMetadataTableProfile profile = profile(source, table);
                    writer.onTable(toTableRow(source, schema, table, profile));
                    Map<String, OpenMetadataColumnProfile> profileByName = profileByName(profile);
                    for (OpenMetadataColumn column : safe(table.getColumns())) {
                        if (column == null || column.getName() == null) {
                            continue;
                        }
                        writer.onColumn(toColumnRow(schema, table, column));
                        OpenMetadataColumnProfile columnProfile = profileByName.get(lower(column.getName()));
                        if (columnProfile == null) {
                            columnProfile = column.getProfile();
                        }
                        writer.onFeature(toFeatureRow(table, column, columnProfile));
                    }
                });
    }

    private InventoryExportSourceRow toSourceRow(SourceContext source) {
        MetadataSourceBinding binding = source.binding();
        return new InventoryExportSourceRow(
                unitName(source),
                systemName(source),
                source.source().getName(),
                source.source().getDbType() == null ? null : source.source().getDbType().name(),
                status(binding == null ? null : binding.getScanStatus()),
                binding == null ? null : binding.getScanLastSuccessTime(),
                status(binding == null ? null : binding.getProfileStatus()),
                binding == null ? null : binding.getProfileLastSuccessTime());
    }

    private InventoryExportTableRow toTableRow(
            SourceContext source,
            OpenMetadataDatabaseSchema schema,
            OpenMetadataTable table,
            OpenMetadataTableProfile profile) {
        return new InventoryExportTableRow(
                unitName(source),
                systemName(source),
                source.source().getName(),
                databaseName(schema, table),
                schemaName(schema, table),
                table.getName(),
                table.getTableType(),
                table.getDescription(),
                table.getColumns() == null ? 0 : table.getColumns().size(),
                profile == null ? null : profile.getRowCount());
    }

    private InventoryExportColumnRow toColumnRow(
            OpenMetadataDatabaseSchema schema,
            OpenMetadataTable table,
            OpenMetadataColumn column) {
        String constraint = column.getConstraint();
        String nullable = "NOT_NULL".equalsIgnoreCase(constraint) ? "NO"
                : "NULL".equalsIgnoreCase(constraint) ? "YES" : "UNKNOWN";
        return new InventoryExportColumnRow(
                databaseName(schema, table), schemaName(schema, table), table.getName(), column.getName(),
                column.getDataTypeDisplay() == null || column.getDataTypeDisplay().isBlank()
                        ? column.getDataType() : column.getDataTypeDisplay(),
                nullable, constraint, column.getDescription());
    }

    private InventoryExportFeatureRow toFeatureRow(
            OpenMetadataTable table,
            OpenMetadataColumn column,
            OpenMetadataColumnProfile profile) {
        return new InventoryExportFeatureRow(
                table.getName(),
                column.getName(),
                value(profile, OpenMetadataColumnProfile::getValuesCount),
                value(profile, OpenMetadataColumnProfile::getNullCount),
                value(profile, OpenMetadataColumnProfile::getNullProportion),
                value(profile, OpenMetadataColumnProfile::getDistinctCount),
                value(profile, OpenMetadataColumnProfile::getDistinctProportion),
                value(profile, OpenMetadataColumnProfile::getUniqueCount),
                value(profile, OpenMetadataColumnProfile::getUniqueProportion),
                profile == null || profile.getMin() == null ? null : profile.getMin().toString(),
                profile == null || profile.getMax() == null ? null : profile.getMax().toString(),
                value(profile, OpenMetadataColumnProfile::getMean),
                value(profile, OpenMetadataColumnProfile::getMinLength),
                value(profile, OpenMetadataColumnProfile::getMaxLength),
                qualityStatus(column, profile),
                profile == null ? null : profile.getTimestamp());
    }

    private OpenMetadataTableProfile profile(SourceContext source, OpenMetadataTable table) {
        if (table == null) {
            return null;
        }
        if (table.getProfile() != null && table.getProfile().getTimestamp() != null) {
            return table.getProfile();
        }
        MetadataSourceBinding binding = source.binding();
        if (binding == null
                || (binding.getProfileStatus() != MetadataRunStatus.SUCCESS
                && binding.getProfileLastSuccessTime() == null)) {
            return null;
        }
        try {
            return openMetadataClient.getLatestTableProfile(table.getFullyQualifiedName());
        } catch (Exception error) {
            log.debug("Could not read profile for table {}", table.getFullyQualifiedName(), error);
            return null;
        }
    }

    private static String qualityStatus(OpenMetadataColumn column, OpenMetadataColumnProfile profile) {
        if (profile == null) {
            return "NO_PROFILE";
        }
        String constraint = column == null ? null : column.getConstraint();
        if ("NOT_NULL".equalsIgnoreCase(constraint) && profile.getNullCount() != null
                && profile.getNullCount() == 0) {
            return "NORMAL";
        }
        if ("NOT_NULL".equalsIgnoreCase(constraint) && profile.getNullCount() != null) {
            return "ABNORMAL";
        }
        return "NO_RULE";
    }

    private static Map<String, OpenMetadataColumnProfile> profileByName(OpenMetadataTableProfile profile) {
        if (profile == null || profile.getColumns() == null) {
            return Collections.emptyMap();
        }
        Map<String, OpenMetadataColumnProfile> result = new HashMap<>();
        for (OpenMetadataColumnProfile column : profile.getColumns()) {
            if (column != null && column.getName() != null) {
                result.putIfAbsent(lower(column.getName()), column);
            }
        }
        return result;
    }

    private boolean isReadyOpenMetadataSource(SourceContext source) {
        DataSource dataSource = source.source();
        MetadataSourceBinding binding = source.binding();
        return isSupported(dataSource.getDbType())
                && binding != null
                && binding.getDesiredState() == MetadataDesiredState.ACTIVE
                && binding.getSyncStatus() == MetadataSyncStatus.READY
                && !serviceFqn(source).isBlank();
    }

    private String serviceFqn(SourceContext source) {
        String configured = source.binding() == null ? null : source.binding().getOmServiceFqn();
        return configured == null || configured.isBlank()
                ? MetadataStableName.serviceFqn(source.source().getId()) : configured;
    }

    private static boolean matchesDatabase(InventoryFilter filter, OpenMetadataDatabase database) {
        return database != null && database.fullyQualifiedName() != null
                && (filter.databaseFqn() == null
                || filter.databaseFqn().equals(database.fullyQualifiedName()));
    }

    private static String databaseName(OpenMetadataDatabaseSchema schema, OpenMetadataTable table) {
        String database = table == null ? null : table.getDatabaseFullyQualifiedName();
        if (database == null || database.isBlank()) {
            database = schema == null ? null : schema.getDatabaseFullyQualifiedName();
        }
        return lastPart(database);
    }

    private static String schemaName(OpenMetadataDatabaseSchema schema, OpenMetadataTable table) {
        String schemaName = schema == null ? null : schema.getName();
        return schemaName == null || schemaName.isBlank()
                ? lastPart(table.getSchemaFullyQualifiedName()) : schemaName;
    }

    private static String unitName(SourceContext source) {
        return source.unit() == null ? "未分配单位" : source.unit().getUnitName();
    }

    private static String systemName(SourceContext source) {
        return source.system() == null ? "未分配业务系统" : source.system().getSystemName();
    }

    private static String status(MetadataRunStatus status) {
        return status == null ? MetadataRunStatus.NEVER.name() : status.name();
    }

    private static <T> T value(OpenMetadataColumnProfile profile, Function<OpenMetadataColumnProfile, T> getter) {
        return profile == null ? null : getter.apply(profile);
    }

    private static boolean isSupported(DbType dbType) {
        return dbType == DbType.MYSQL || dbType == DbType.POSTGRE_SQL || dbType == DbType.JDBC
                || dbType == DbType.DORIS || dbType == DbType.ORACLE
                || dbType == DbType.DAMENG || dbType == DbType.KINGBASE;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String lastPart(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static InventoryFilter normalize(DataInventoryFilterDTO request) {
        if (request == null) {
            return new InventoryFilter(null, null, null, null);
        }
        String databaseFqn = request.getDatabaseFqn();
        databaseFqn = databaseFqn == null || databaseFqn.isBlank() ? null : databaseFqn.trim();
        return new InventoryFilter(
                request.getUnitId(), request.getBusinessSystemId(), request.getDataSourceId(), databaseFqn);
    }

    private static <T> long walkPages(
            Function<String, OpenMetadataPage<T>> loader, Consumer<T> consumer) {
        String after = null;
        Set<String> seen = new HashSet<>();
        long total = 0L;
        long local = 0L;
        for (int pageNumber = 0; pageNumber < MAX_PAGES; pageNumber++) {
            OpenMetadataPage<T> page = loader.apply(after);
            if (page == null) {
                break;
            }
            total = Math.max(total, page.total());
            for (T item : safe(page.data())) {
                local++;
                consumer.accept(item);
            }
            String next = page.after();
            if (next == null || next.isBlank() || !seen.add(next)) {
                break;
            }
            after = next;
        }
        return total == 0L ? local : total;
    }

    private record InventoryFilter(Long unitId, Long businessSystemId, Long dataSourceId, String databaseFqn) {
        String cacheKey() {
            return "inventory:" + String.valueOf(unitId) + ':' + String.valueOf(businessSystemId)
                    + ':' + String.valueOf(dataSourceId) + ':' + String.valueOf(databaseFqn);
        }

        boolean matches(DataSource source, BusinessSystem system, DataSourceUnit unit) {
            return (dataSourceId == null || dataSourceId.equals(source.getId()))
                    && (businessSystemId == null
                    || (system != null && businessSystemId.equals(system.getId())))
                    && (unitId == null || (unit != null && unitId.equals(unit.getId())));
        }
    }

    private record SourceContext(
            DataSource source, BusinessSystem system, DataSourceUnit unit, MetadataSourceBinding binding) {
    }

    private record SourceCatalog(
            List<SourceContext> sources, Set<Long> unitIds, Set<Long> systemIds) {
    }

    private record AggregateSnapshot(
            DataInventorySummaryVO summary,
            List<DataInventoryDistributionVO> sourceTypes,
            List<DataInventoryDistributionVO> units,
            List<DataInventoryDistributionVO> businessSystems,
            DataInventoryProfileCoverageVO coverage) {
    }

    private static final class AggregateBuilder {
        private final Set<Long> unitIds = new HashSet<>();
        private final Set<Long> systemIds = new HashSet<>();
        private final Set<String> profiledDatabases = new HashSet<>();
        private final Map<String, Bucket> sourceTypes = new HashMap<>();
        private final Map<String, Bucket> units = new HashMap<>();
        private final Map<String, Bucket> systems = new HashMap<>();
        private long dataSourceCount;
        private long databaseCount;
        private long schemaCount;
        private long tableCount;
        private long columnCount;
        private long profiledTableCount;
        private long knownRowCount;

        void addSource(SourceContext source) {
            dataSourceCount++;
            DataSource dataSource = source.source();
            String sourceType = dataSource.getDbType() == null ? "UNKNOWN" : dataSource.getDbType().name();
            bucket(sourceTypes, sourceType, sourceType).count++;
            String unitKey = source.unit() == null || source.unit().getId() == null
                    ? "UNASSIGNED" : String.valueOf(source.unit().getId());
            String systemKey = source.system() == null || source.system().getId() == null
                    ? "UNASSIGNED" : String.valueOf(source.system().getId());
            bucket(units, unitKey, unitName(source)).count++;
            bucket(systems, systemKey, systemName(source)).count++;
            if (source.unit() != null && source.unit().getId() != null) {
                unitIds.add(source.unit().getId());
            }
            if (source.system() != null && source.system().getId() != null) {
                systemIds.add(source.system().getId());
            }
        }

        void addMasterData(Set<Long> masterUnitIds, Set<Long> masterSystemIds) {
            if (masterUnitIds != null) {
                unitIds.addAll(masterUnitIds);
            }
            if (masterSystemIds != null) {
                systemIds.addAll(masterSystemIds);
            }
        }

        void addTable(SourceContext source, OpenMetadataDatabase database,
                      OpenMetadataDatabaseSchema schema, OpenMetadataTable table,
                      OpenMetadataTableProfile profile) {
            if (table == null) {
                return;
            }
            columnCount = add(columnCount, table.getColumns() == null ? 0 : table.getColumns().size());
            if (profile != null && profile.getTimestamp() != null) {
                profiledTableCount++;
                if (database != null && database.fullyQualifiedName() != null) {
                    profiledDatabases.add(database.fullyQualifiedName());
                }
                if (profile.getRowCount() != null && profile.getRowCount() >= 0) {
                    knownRowCount = add(knownRowCount, profile.getRowCount());
                }
            }
        }

        AggregateSnapshot freeze() {
            DataInventorySummaryVO summary = new DataInventorySummaryVO();
            summary.setUnitCount(unitIds.size());
            summary.setBusinessSystemCount(systemIds.size());
            summary.setDataSourceCount(dataSourceCount);
            summary.setDatabaseCount(databaseCount);
            summary.setSchemaCount(schemaCount);
            summary.setTableCount(tableCount);
            summary.setColumnCount(columnCount);
            summary.setProfiledDatabaseCount(profiledDatabases.size());
            summary.setProfiledTableCount(profiledTableCount);
            summary.setKnownRowCount(knownRowCount);
            DataInventoryProfileCoverageVO coverage = new DataInventoryProfileCoverageVO();
            coverage.setDatabaseCount(databaseCount);
            coverage.setProfiledDatabaseCount(profiledDatabases.size());
            coverage.setTableCount(tableCount);
            coverage.setProfiledTableCount(profiledTableCount);
            coverage.setKnownRowCount(knownRowCount);
            coverage.setTableCoveragePercent(tableCount == 0 ? 0D : profiledTableCount * 100D / tableCount);
            return new AggregateSnapshot(
                    summary,
                    freezeBuckets(sourceTypes),
                    freezeBuckets(units),
                    freezeBuckets(systems),
                    coverage);
        }

        private static Bucket bucket(Map<String, Bucket> values, String key, String name) {
            return values.computeIfAbsent(key, ignored -> new Bucket(key, name));
        }

        private static List<DataInventoryDistributionVO> freezeBuckets(Map<String, Bucket> buckets) {
            List<DataInventoryDistributionVO> result = new ArrayList<>();
            for (Bucket bucket : buckets.values()) {
                DataInventoryDistributionVO item = new DataInventoryDistributionVO();
                item.setKey(bucket.key());
                item.setName(bucket.name());
                item.setCount(bucket.count());
                result.add(item);
            }
            result.sort(Comparator.comparingLong(DataInventoryDistributionVO::getCount)
                    .reversed().thenComparing(DataInventoryDistributionVO::getName,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
            return List.copyOf(result);
        }

        private static final class Bucket {
            private final String key;
            private final String name;
            private long count;

            private Bucket(String key, String name) {
                this.key = key;
                this.name = name == null || name.isBlank() ? key : name;
            }

            String key() {
                return key;
            }

            String name() {
                return name;
            }

            long count() {
                return count;
            }
        }
    }

    public record InventoryExportSnapshot(
            List<InventoryExportSourceRow> sources,
            List<InventoryExportTableRow> tables,
            List<InventoryExportColumnRow> columns,
            List<InventoryExportFeatureRow> features) {
        public InventoryExportSnapshot {
            sources = List.copyOf(sources == null ? List.of() : sources);
            tables = List.copyOf(tables == null ? List.of() : tables);
            columns = List.copyOf(columns == null ? List.of() : columns);
            features = List.copyOf(features == null ? List.of() : features);
        }
    }

    /** Callback contract used by SXSSF export to keep row data off the heap. */
    public interface InventoryExportWriter {
        default void onSource(InventoryExportSourceRow row) {
        }

        default void onTable(InventoryExportTableRow row) {
        }

        default void onColumn(InventoryExportColumnRow row) {
        }

        default void onFeature(InventoryExportFeatureRow row) {
        }
    }

    public record InventoryExportSourceRow(
            String unit,
            String businessSystem,
            String dataSource,
            String sourceType,
            String scanStatus,
            Date scanLastSuccessTime,
            String profileStatus,
            Date profileLastSuccessTime) {
    }

    public record InventoryExportTableRow(
            String unit,
            String businessSystem,
            String dataSource,
            String database,
            String schema,
            String table,
            String tableType,
            String description,
            int columnCount,
            Long rowCount) {
    }

    public record InventoryExportColumnRow(
            String database,
            String schema,
            String table,
            String column,
            String dataType,
            String nullable,
            String constraint,
            String description) {
    }

    public record InventoryExportFeatureRow(
            String table,
            String column,
            Long valuesCount,
            Long nullCount,
            BigDecimal nullProportion,
            Long distinctCount,
            BigDecimal distinctProportion,
            Long uniqueCount,
            BigDecimal uniqueProportion,
            String min,
            String max,
            BigDecimal mean,
            Long minLength,
            Long maxLength,
            String qualityStatus,
            Long profileTime) {
    }

    private static long add(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
