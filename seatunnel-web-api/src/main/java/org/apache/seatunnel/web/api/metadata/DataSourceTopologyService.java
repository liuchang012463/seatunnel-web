package org.apache.seatunnel.web.api.metadata;

import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.MetadataDesiredState;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.common.utils.MetadataStableName;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceLineageVO;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceTopologyNodeVO;
import org.apache.seatunnel.web.spi.enums.DataSourceTopologyNodeType;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.openmetadata.schema.api.lineage.EsLineageData;
import org.openmetadata.schema.api.lineage.RelationshipRef;
import org.openmetadata.schema.type.Edge;
import org.openmetadata.schema.type.EntityLineage;
import org.openmetadata.schema.type.EntityReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Shallow local master-data tree with explicit lazy OpenMetadata children.
 * Database/Schema/Table nodes are never fetched by the initial tree call.
 */
@Slf4j
@Service
public class DataSourceTopologyService {

    private static final int PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 10_000;

    private final DataSourceDao dataSourceDao;
    private final DataSourceUnitDao dataSourceUnitDao;
    private final BusinessSystemDao businessSystemDao;
    private final MetadataBindingDao metadataBindingDao;
    private final OpenMetadataClient openMetadataClient;

    public DataSourceTopologyService(
            DataSourceDao dataSourceDao,
            DataSourceUnitDao dataSourceUnitDao,
            BusinessSystemDao businessSystemDao,
            MetadataBindingDao metadataBindingDao,
            OpenMetadataClient openMetadataClient) {
        this.dataSourceDao = dataSourceDao;
        this.dataSourceUnitDao = dataSourceUnitDao;
        this.businessSystemDao = businessSystemDao;
        this.metadataBindingDao = metadataBindingDao;
        this.openMetadataClient = openMetadataClient;
    }

    /** Maps the OM service graph to local data sources without scanning their asset catalogs. */
    public DataSourceLineageVO lineage() {
        List<LineageSource> sources = lineageSources();
        if (sources.isEmpty()) {
            return new DataSourceLineageVO(List.of(), List.of(), null);
        }
        long started = System.nanoTime();
        openMetadataClient.assertFixedVersion();
        List<EsLineageData> serviceEdges = openMetadataClient.getServiceLineage();
        String readAt = Instant.now().toString();
        Map<String, LineageSource> byServiceId = new HashMap<>();
        Map<String, LineageSource> byServiceFqn = new HashMap<>();
        List<DataSourceLineageVO.Node> nodes = new ArrayList<>();
        for (LineageSource source : sources) {
            if (StringUtils.hasText(source.binding().getOmServiceId())) {
                byServiceId.put(source.binding().getOmServiceId(), source);
            }
            byServiceFqn.put(source.serviceFqn(), source);
            nodes.add(new DataSourceLineageVO.Node(source.id(), source.name(),
                    source.dataSource().getDbType() == null ? null : source.dataSource().getDbType().name(),
                    source.tableCatalogAvailable()));
        }
        Map<String, DataSourceLineageVO.Link> links = new LinkedHashMap<>();
        for (EsLineageData edge : safe(serviceEdges)) {
            if (edge == null) {
                continue;
            }
            LineageSource from = sourceForReference(edge.getFromEntity(), byServiceId, byServiceFqn);
            LineageSource to = sourceForReference(edge.getToEntity(), byServiceId, byServiceFqn);
            if (from != null && to != null && !from.id().equals(to.id())) {
                // Asset counts are already computed by OM and must not be summed on duplicate edges.
                var link = new DataSourceLineageVO.Link(from.id(), to.id(), edge.getAssetEdges());
                links.merge(from.id() + ":" + to.id(), link,
                        (previous, duplicate) -> previous.assetCount() == null ? duplicate : previous);
            }
        }
        log.debug("Read data source lineage: nodes={}, edges={}, elapsedMs={}",
                nodes.size(), links.size(), (System.nanoTime() - started) / 1_000_000);
        return new DataSourceLineageVO(nodes, List.copyOf(links.values()), readAt);
    }

    public DataSourceLineageVO.TableLineage tableLineage(Long dataSourceId, UUID tableId) {
        List<LineageSource> sources = lineageSources();
        LineageSource selected = sources.stream()
                .filter(source -> source.dataSource().getId().equals(dataSourceId))
                .filter(LineageSource::tableCatalogAvailable)
                .findFirst()
                .orElseThrow(() -> invalid("数据源的库表目录尚未就绪"));
        long started = System.nanoTime();
        openMetadataClient.assertFixedVersion();
        EntityLineage lineage = openMetadataClient.getTableLineage(tableId);
        EntityReference root = lineage == null ? null : lineage.getEntity();
        if (root == null || Boolean.TRUE.equals(root.getDeleted())
                || !tableId.equals(root.getId()) || !belongsTo(root, selected)) {
            throw invalid("所选表已不存在或不属于该数据源");
        }

        Map<UUID, DataSourceLineageVO.Asset> assets = new HashMap<>();
        for (EntityReference entity : safe(lineage.getNodes())) {
            if (entity == null || entity.getId() == null || Boolean.TRUE.equals(entity.getDeleted())) {
                continue;
            }
            // The SDK uses a shared bot. Only expose neighbors within the same registered-source scope as the graph.
            sources.stream().filter(source -> belongsTo(entity, source)).findFirst().ifPresent(owner ->
                    assets.put(entity.getId(), new DataSourceLineageVO.Asset(entity.getId().toString(),
                            StringUtils.hasText(entity.getDisplayName()) ? entity.getDisplayName() : entity.getName(),
                            entity.getFullyQualifiedName(), owner.name())));
        }
        List<DataSourceLineageVO.Asset> upstream = relatedAssets(lineage.getUpstreamEdges(), tableId, assets, true);
        List<DataSourceLineageVO.Asset> downstream = relatedAssets(lineage.getDownstreamEdges(), tableId, assets, false);
        log.debug("Read table lineage: dataSourceId={}, tableId={}, upstream={}, downstream={}, elapsedMs={}",
                dataSourceId, tableId, upstream.size(), downstream.size(), (System.nanoTime() - started) / 1_000_000);
        return new DataSourceLineageVO.TableLineage(upstream, downstream);
    }

    private List<LineageSource> lineageSources() {
        Map<Long, DataSource> available = new HashMap<>();
        for (DataSource source : safe(dataSourceDao.queryAll())) {
            if (source != null && source.getId() != null && source.getStatus() != DataSourceLifecycleStatus.REVOKED) {
                available.put(source.getId(), source);
            }
        }
        if (available.isEmpty()) {
            return List.of();
        }
        return safe(metadataBindingDao.queryByDataSourceIds(available.keySet())).stream()
                .filter(binding -> binding != null && available.containsKey(binding.getDataSourceId())
                        && binding.getDesiredState() == MetadataDesiredState.ACTIVE
                        && (StringUtils.hasText(binding.getOmServiceId()) || StringUtils.hasText(binding.getOmServiceFqn())))
                .map(binding -> new LineageSource(available.get(binding.getDataSourceId()), binding))
                .sorted(Comparator.comparing(LineageSource::name).thenComparing(LineageSource::id))
                .toList();
    }

    private static LineageSource sourceForReference(RelationshipRef ref,
            Map<String, LineageSource> byId, Map<String, LineageSource> byFqn) {
        if (ref == null) {
            return null;
        }
        LineageSource source = ref.getId() == null ? null : byId.get(ref.getId().toString());
        return source != null ? source : byFqn.get(ref.getFullyQualifiedName());
    }

    private static boolean belongsTo(EntityReference entity, LineageSource source) {
        String fqn = entity.getFullyQualifiedName();
        return fqn != null && fqn.startsWith(source.serviceFqn() + ".");
    }

    private static List<DataSourceLineageVO.Asset> relatedAssets(List<Edge> edges, UUID tableId,
            Map<UUID, DataSourceLineageVO.Asset> assets, boolean upstream) {
        return safe(edges).stream()
                .filter(edge -> edge != null && tableId.equals(upstream ? edge.getToEntity() : edge.getFromEntity()))
                .map(edge -> assets.get(upstream ? edge.getFromEntity() : edge.getToEntity()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(DataSourceLineageVO.Asset::fullyQualifiedName))
                .toList();
    }

    /** Returns Unit -> BusinessSystem -> DataSource only; OM levels are lazy. */
    public List<DataSourceTopologyNodeVO> tree(Long unitId, Long businessSystemId, Long dataSourceId) {
        Map<Long, DataSourceUnit> units = byId(dataSourceUnitDao.queryAll());
        Map<Long, BusinessSystem> systems = byId(businessSystemDao.queryAll());
        List<DataSource> sources = dataSourceDao.queryAll();
        Map<Long, List<DataSource>> sourcesBySystem = new HashMap<>();
        for (DataSource source : safe(sources)) {
            if (source == null || source.getId() == null
                    || source.getStatus() == DataSourceLifecycleStatus.REVOKED
                    || source.getBusinessSystemId() == null) {
                continue;
            }
            sourcesBySystem.computeIfAbsent(source.getBusinessSystemId(), ignored -> new ArrayList<>()).add(source);
        }
        Map<Long, List<BusinessSystem>> systemsByUnit = new HashMap<>();
        for (BusinessSystem system : systems.values()) {
            if (system != null && system.getId() != null && system.getUnitId() != null
                    && Integer.valueOf(1).equals(system.getStatus())) {
                systemsByUnit.computeIfAbsent(system.getUnitId(), ignored -> new ArrayList<>()).add(system);
            }
        }
        List<DataSourceTopologyNodeVO> result = new ArrayList<>();
        for (DataSourceUnit unit : units.values()) {
            if (unit == null || unit.getId() == null || !Integer.valueOf(1).equals(unit.getStatus())
                    || (unitId != null && !unitId.equals(unit.getId()))) {
                continue;
            }
            DataSourceTopologyNodeVO unitNode = node(String.valueOf(unit.getId()),
                    DataSourceTopologyNodeType.UNIT, unit.getUnitName());
            for (BusinessSystem system : systemsByUnit.getOrDefault(unit.getId(), List.of())) {
                if (businessSystemId != null && !businessSystemId.equals(system.getId())) {
                    continue;
                }
                DataSourceTopologyNodeVO systemNode = node(String.valueOf(system.getId()),
                        DataSourceTopologyNodeType.BUSINESS_SYSTEM, system.getSystemName());
                for (DataSource source : sourcesBySystem.getOrDefault(system.getId(), List.of())) {
                    if (dataSourceId != null && !dataSourceId.equals(source.getId())) {
                        continue;
                    }
                    systemNode.getChildren().add(node(String.valueOf(source.getId()),
                            DataSourceTopologyNodeType.DATA_SOURCE, source.getName()));
                }
                if (!systemNode.getChildren().isEmpty() || dataSourceId == null) {
                    unitNode.getChildren().add(systemNode);
                }
            }
            if (!unitNode.getChildren().isEmpty() || (businessSystemId == null && dataSourceId == null)) {
                result.add(unitNode);
            }
        }
        // Historical rows can have no canonical unit/system. They are not
        // inserted into a second hierarchy; the master-data gate keeps them
        // out of the topology until assigned through the existing UI.
        return result;
    }

    /** Loads exactly one level below the requested node. */
    public List<DataSourceTopologyNodeVO> children(
            DataSourceTopologyNodeType nodeType, String nodeId) {
        if (nodeType == null || nodeId == null || nodeId.isBlank()) {
            throw invalid("nodeType/nodeId");
        }
        return switch (nodeType) {
            case UNIT -> unitChildren(parseId(nodeId));
            case BUSINESS_SYSTEM -> systemChildren(parseId(nodeId));
            case DATA_SOURCE -> dataSourceChildren(parseId(nodeId));
            case DATABASE -> databaseChildren(nodeId);
            case SCHEMA -> schemaChildren(nodeId);
            case TABLE -> List.of();
        };
    }

    private List<DataSourceTopologyNodeVO> unitChildren(Long unitId) {
        List<DataSourceTopologyNodeVO> result = new ArrayList<>();
        for (BusinessSystem system : safe(businessSystemDao.queryByUnitId(unitId))) {
            if (system != null && system.getId() != null && Integer.valueOf(1).equals(system.getStatus())) {
                result.add(node(String.valueOf(system.getId()), DataSourceTopologyNodeType.BUSINESS_SYSTEM,
                        system.getSystemName()));
            }
        }
        return result;
    }

    private List<DataSourceTopologyNodeVO> systemChildren(Long systemId) {
        List<DataSourceTopologyNodeVO> result = new ArrayList<>();
        for (DataSource source : safe(dataSourceDao.queryAll())) {
            if (source != null && source.getId() != null && systemId.equals(source.getBusinessSystemId())
                    && source.getStatus() != DataSourceLifecycleStatus.REVOKED) {
                result.add(node(String.valueOf(source.getId()), DataSourceTopologyNodeType.DATA_SOURCE,
                        source.getName()));
            }
        }
        return result;
    }

    private List<DataSourceTopologyNodeVO> dataSourceChildren(Long dataSourceId) {
        SourceContext source = sourceContext(dataSourceId);
        List<DataSourceTopologyNodeVO> result = new ArrayList<>();
        walkPages(after -> openMetadataClient.listDatabasesPage(source.serviceFqn(), PAGE_SIZE, after),
                database -> {
                    if (database != null && source.serviceFqn().equals(database.serviceFullyQualifiedName())) {
                        result.add(node(database.fullyQualifiedName(),
                                DataSourceTopologyNodeType.DATABASE, lastPart(database.fullyQualifiedName())));
                    }
                });
        return result;
    }

    private List<DataSourceTopologyNodeVO> databaseChildren(String databaseFqn) {
        OpenMetadataDatabase database = openMetadataClient.findDatabase(databaseFqn).orElse(null);
        if (database == null) {
            throw invalid("database node is not found");
        }
        sourceForService(database.serviceFullyQualifiedName());
        List<DataSourceTopologyNodeVO> result = new ArrayList<>();
        walkPages(after -> openMetadataClient.listSchemasPage(database.fullyQualifiedName(), PAGE_SIZE, after),
                schema -> result.add(node(database.fullyQualifiedName() + "|" + schema.getFullyQualifiedName(),
                        DataSourceTopologyNodeType.SCHEMA,
                        schema.getName() == null || schema.getName().isBlank()
                                ? lastPart(schema.getFullyQualifiedName()) : schema.getName())));
        return result;
    }

    private List<DataSourceTopologyNodeVO> schemaChildren(String schemaNodeId) {
        int separator = schemaNodeId.indexOf('|');
        if (separator <= 0 || separator >= schemaNodeId.length() - 1) {
            throw invalid("schema node id");
        }
        String databaseFqn = schemaNodeId.substring(0, separator);
        String schemaFqn = schemaNodeId.substring(separator + 1);
        OpenMetadataDatabase database = openMetadataClient.findDatabase(databaseFqn).orElse(null);
        if (database == null) {
            throw invalid("database node is not found");
        }
        sourceForService(database.serviceFullyQualifiedName());
        boolean[] owned = {false};
        walkPages(after -> openMetadataClient.listSchemasPage(databaseFqn, PAGE_SIZE, after),
                schema -> {
                    if (schema != null && schemaFqn.equals(schema.getFullyQualifiedName())) {
                        owned[0] = true;
                    }
                });
        if (!owned[0]) {
            throw invalid("schema node is not found");
        }
        List<DataSourceTopologyNodeVO> result = new ArrayList<>();
        walkPages(after -> openMetadataClient.listTablesPage(schemaFqn, false, PAGE_SIZE, after),
                table -> result.add(node(table.getId(), DataSourceTopologyNodeType.TABLE, table.getName())));
        return result;
    }

    private SourceContext sourceContext(Long dataSourceId) {
        DataSource source = dataSourceDao.queryById(dataSourceId);
        if (source == null || source.getStatus() == DataSourceLifecycleStatus.REVOKED) {
            throw invalid("data source is unavailable");
        }
        if (!supported(source.getDbType())) {
            throw new MetadataIntegrationException(
                    MetadataErrorCode.CONNECTOR_NOT_SUPPORTED,
                    "OpenMetadata 2.0.4 topology is supported only for MYSQL, POSTGRE_SQL, ZEONEDB, JDBC(PostgreSQL), DORIS, ORACLE, DAMENG, KINGBASE and VASTBASE");
        }
        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(dataSourceId);
        if (binding == null || binding.getDesiredState() != MetadataDesiredState.ACTIVE
                || binding.getSyncStatus() != MetadataSyncStatus.READY) {
            throw invalid("元数据同步尚未就绪，请稍后重试。");
        }
        openMetadataClient.assertFixedVersion();
        return new SourceContext(source, boundServiceFqn(binding));
    }

    private SourceContext sourceForService(String serviceFqn) {
        for (MetadataSourceBinding binding : safe(metadataBindingDao.queryAll())) {
            if (binding == null || serviceFqn == null || binding.getDataSourceId() == null) {
                continue;
            }
            if (serviceFqn.equals(boundServiceFqn(binding))) {
                return sourceContext(binding.getDataSourceId());
            }
        }
        throw invalid("OpenMetadata service is not bound to a data source");
    }

    private static String boundServiceFqn(MetadataSourceBinding binding) {
        return StringUtils.hasText(binding.getOmServiceFqn())
                ? binding.getOmServiceFqn() : MetadataStableName.serviceFqn(binding.getDataSourceId());
    }

    private static DataSourceTopologyNodeVO node(
            String id, DataSourceTopologyNodeType type, String name) {
        DataSourceTopologyNodeVO node = new DataSourceTopologyNodeVO();
        node.setId(id);
        node.setNodeType(type);
        node.setName(name == null || name.isBlank() ? id : name);
        return node;
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException error) {
            throw invalid("nodeId");
        }
    }

    private static boolean supported(DbType dbType) {
        return dbType == DbType.MYSQL || dbType == DbType.POSTGRE_SQL || dbType == DbType.ZEONEDB
                || dbType == DbType.JDBC
                || dbType == DbType.DORIS || dbType == DbType.ORACLE
                || dbType == DbType.DAMENG || dbType == DbType.KINGBASE
                || dbType == DbType.VASTBASE;
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

    private static <T> void walkPages(
            Function<String, OpenMetadataPage<T>> loader, Consumer<T> consumer) {
        String after = null;
        Set<String> seen = new HashSet<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            OpenMetadataPage<T> response = loader.apply(after);
            if (response == null) {
                return;
            }
            for (T item : safe(response.data())) {
                consumer.accept(item);
            }
            String next = response.after();
            if (next == null || next.isBlank() || !seen.add(next)) {
                return;
            }
            after = next;
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> byId(List<V> values) {
        Map<K, V> result = new HashMap<>();
        for (V value : safe(values)) {
            if (value instanceof DataSourceUnit unit && unit.getId() != null) {
                result.put((K) unit.getId(), value);
            } else if (value instanceof BusinessSystem system && system.getId() != null) {
                result.put((K) system.getId(), value);
            }
        }
        return result;
    }

    private static ServiceException invalid(String field) {
        return new ServiceException(org.apache.seatunnel.web.spi.enums.Status.REQUEST_PARAMS_NOT_VALID_ERROR, field);
    }

    private record SourceContext(DataSource source, String serviceFqn) {
    }

    private record LineageSource(DataSource dataSource, MetadataSourceBinding binding) {
        String id() {
            return dataSource.getId().toString();
        }

        String name() {
            return StringUtils.hasText(dataSource.getName()) ? dataSource.getName() : id();
        }

        String serviceFqn() {
            return boundServiceFqn(binding);
        }

        boolean tableCatalogAvailable() {
            return binding.getSyncStatus() == MetadataSyncStatus.READY && supported(dataSource.getDbType());
        }
    }
}
