package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.api.metadata.client.OpenMetadataClient;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabase;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataDatabaseSchema;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataPage;
import org.apache.seatunnel.web.api.metadata.client.OpenMetadataTable;
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
import org.apache.seatunnel.web.spi.bean.vo.DataSourceTopologyNodeVO;
import org.apache.seatunnel.web.spi.enums.DataSourceTopologyNodeType;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Shallow local master-data tree with explicit lazy OpenMetadata children.
 * Database/Schema/Table nodes are never fetched by the initial tree call.
 */
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
                    "OpenMetadata 1.12.10 topology is supported only for MYSQL, POSTGRE_SQL and DORIS");
        }
        MetadataSourceBinding binding = metadataBindingDao.queryByDataSourceId(dataSourceId);
        if (binding == null || binding.getDesiredState() != MetadataDesiredState.ACTIVE
                || binding.getSyncStatus() != MetadataSyncStatus.READY) {
            throw invalid("metadata synchronization is not ready");
        }
        String serviceFqn = binding.getOmServiceFqn();
        if (serviceFqn == null || serviceFqn.isBlank()) {
            serviceFqn = MetadataStableName.serviceFqn(dataSourceId);
        }
        openMetadataClient.assertFixedVersion();
        return new SourceContext(source, serviceFqn);
    }

    private SourceContext sourceForService(String serviceFqn) {
        for (MetadataSourceBinding binding : safe(metadataBindingDao.queryAll())) {
            if (binding == null || serviceFqn == null || binding.getDataSourceId() == null) {
                continue;
            }
            String boundService = binding.getOmServiceFqn();
            if (boundService == null || boundService.isBlank()) {
                boundService = MetadataStableName.serviceFqn(binding.getDataSourceId());
            }
            if (serviceFqn.equals(boundService)) {
                return sourceContext(binding.getDataSourceId());
            }
        }
        throw invalid("OpenMetadata service is not bound to a data source");
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
        return dbType == DbType.MYSQL || dbType == DbType.POSTGRE_SQL || dbType == DbType.DORIS;
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
}
