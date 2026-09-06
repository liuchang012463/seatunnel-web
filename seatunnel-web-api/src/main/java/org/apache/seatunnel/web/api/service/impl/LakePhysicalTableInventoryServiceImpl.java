package org.apache.seatunnel.web.api.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.inventory.LakePhysicalTableInventoryRelationVO;
import org.apache.seatunnel.web.api.lake.inventory.LakePhysicalTableInventoryTableVO;
import org.apache.seatunnel.web.api.lake.inventory.LakePhysicalTableInventoryVO;
import org.apache.seatunnel.web.api.service.LakePhysicalTableInventoryService;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a read-only inventory from the active ODS binding and one Doris
 * table-list call.  Discovered names are response-only and never become
 * source references, mappings, or relations.
 */
@Service
public class LakePhysicalTableInventoryServiceImpl implements LakePhysicalTableInventoryService {

    private static final Comparator<LakePhysicalTableInventoryTableVO> TABLE_ORDER =
            Comparator.comparing(
                    LakePhysicalTableInventoryTableVO::getTargetTableName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                    .thenComparing(
                            LakePhysicalTableInventoryTableVO::getTargetTableName,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(
                            LakePhysicalTableInventoryTableVO::getMappingId,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    private static final Comparator<LakePhysicalTableInventoryRelationVO> RELATION_ORDER =
            Comparator.comparing(
                    LakePhysicalTableInventoryRelationVO::getJobId,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(
                            relation -> enumName(relation.getJobRuntimeType()),
                            Comparator.nullsLast(String::compareTo))
                    .thenComparing(
                            LakePhysicalTableInventoryRelationVO::getJobVersion,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(
                            LakePhysicalTableInventoryRelationVO::getRelationId,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(
                            LakePhysicalTableInventoryRelationVO::getTableMappingId,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    private final LakeOdsDatabaseBindingDao bindingDao;
    private final LakeOdsTableMappingDao tableMappingDao;
    private final LakeJobRelationDao relationDao;
    private final LakeDorisClientProvider dorisClientProvider;

    @Autowired
    public LakePhysicalTableInventoryServiceImpl(
            LakeOdsDatabaseBindingDao bindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            LakeJobRelationDao relationDao,
            LakeDorisClientProvider dorisClientProvider) {
        this.bindingDao = Objects.requireNonNull(bindingDao, "bindingDao");
        this.tableMappingDao = Objects.requireNonNull(tableMappingDao, "tableMappingDao");
        this.relationDao = Objects.requireNonNull(relationDao, "relationDao");
        this.dorisClientProvider = Objects.requireNonNull(
                dorisClientProvider, "dorisClientProvider");
    }

    @Override
    public LakePhysicalTableInventoryVO inventory(Long odsDatabaseBindingId) {
        LakeOdsDatabaseBinding binding = requireReadyBinding(odsDatabaseBindingId);
        DorisLakeClient client = resolveDoris(binding.getLakeDataSourceId());
        Map<String, String> actualTables = listActualTables(client, binding.getDatabaseName());
        List<LakeOdsTableMapping> mappings = queryMappings(odsDatabaseBindingId);
        List<LakeJobRelation> relations = queryRelations(odsDatabaseBindingId);

        LakePhysicalTableInventoryVO result = new LakePhysicalTableInventoryVO();
        result.setOdsDatabaseBindingId(binding.getId());
        result.setDatabaseName(binding.getDatabaseName().trim());
        result.setActualTableNames(new ArrayList<>(actualTables.values()));

        Set<String> registeredNames = new HashSet<>();
        List<LakePhysicalTableInventoryTableVO> registered = new ArrayList<>();
        for (LakeOdsTableMapping mapping : mappings) {
            if (mapping == null || Boolean.TRUE.equals(mapping.getDeleted())) {
                continue;
            }
            registered.add(toRegisteredTable(mapping, actualTables, registeredNames));
        }
        registered.sort(TABLE_ORDER);
        result.setRegisteredTables(registered);

        List<LakePhysicalTableInventoryTableVO> discovered = new ArrayList<>();
        for (Map.Entry<String, String> actual : actualTables.entrySet()) {
            if (!registeredNames.contains(actual.getKey())) {
                discovered.add(toDiscoveredTable(actual.getValue()));
            }
        }
        discovered.sort(TABLE_ORDER);
        result.setDiscoveredTables(discovered);

        List<LakePhysicalTableInventoryRelationVO> tableRelations = new ArrayList<>();
        List<LakePhysicalTableInventoryRelationVO> namespaceRelations = new ArrayList<>();
        for (LakeJobRelation relation : relations) {
            if (relation == null || relation.getRelationScope() == null) {
                continue;
            }
            LakePhysicalTableInventoryRelationVO item = toRelation(relation);
            if (relation.getRelationScope() == LakeRelationScope.TABLE) {
                tableRelations.add(item);
            } else if (relation.getRelationScope() == LakeRelationScope.NAMESPACE) {
                namespaceRelations.add(item);
            }
        }
        tableRelations.sort(RELATION_ORDER);
        namespaceRelations.sort(RELATION_ORDER);
        result.setTableRelations(tableRelations);
        result.setNamespaceRelations(namespaceRelations);
        return result;
    }

    private LakeOdsDatabaseBinding requireReadyBinding(Long bindingId) {
        if (bindingId == null || bindingId <= 0) {
            throw conflict("ODS database binding does not exist");
        }
        LakeOdsDatabaseBinding binding;
        try {
            binding = bindingDao.queryActiveById(bindingId);
        } catch (RuntimeException exception) {
            throw conflict("ODS database binding cannot be read");
        }
        if (binding == null || Boolean.TRUE.equals(binding.getDeleted())
                || binding.getResourceStatus() != LakeResourceStatus.READY
                || binding.getLakeDataSourceId() == null
                || StringUtils.isBlank(binding.getDatabaseName())) {
            throw conflict("ODS database binding is not ready");
        }
        return binding;
    }

    private DorisLakeClient resolveDoris(Long lakeDataSourceId) {
        try {
            DorisLakeClient client = dorisClientProvider.get(lakeDataSourceId);
            if (client == null) {
                throw new IllegalStateException();
            }
            return client;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private Map<String, String> listActualTables(DorisLakeClient client, String databaseName) {
        List<String> names;
        try {
            names = client.listTables(databaseName.trim());
        } catch (RuntimeException exception) {
            throw unavailable();
        }
        if (names == null) {
            throw unavailable();
        }
        Map<String, String> actualTables = new TreeMap<>();
        for (String name : names) {
            if (StringUtils.isBlank(name)) {
                continue;
            }
            String trimmed = name.trim();
            String normalized;
            try {
                normalized = DorisIdentifier.normalize(trimmed);
            } catch (IllegalArgumentException exception) {
                // Inventory is observational: preserve legacy/raw names even
                // when a later bind command would reject them.  The fallback
                // key is only used for case-insensitive set comparison.
                normalized = trimmed.toLowerCase(Locale.ROOT);
            }
            actualTables.merge(normalized, trimmed, LakePhysicalTableInventoryServiceImpl::stableName);
        }
        return actualTables;
    }

    private List<LakeOdsTableMapping> queryMappings(Long bindingId) {
        try {
            List<LakeOdsTableMapping> mappings = tableMappingDao
                    .queryByOdsDatabaseBindingId(bindingId);
            return mappings == null ? List.of() : mappings;
        } catch (RuntimeException exception) {
            throw conflict("Lake table mappings cannot be read");
        }
    }

    private List<LakeJobRelation> queryRelations(Long bindingId) {
        try {
            List<LakeJobRelation> relations = relationDao.queryByOdsDatabaseBindingId(bindingId);
            return relations == null ? List.of() : relations;
        } catch (RuntimeException exception) {
            throw conflict("Lake job relations cannot be read");
        }
    }

    private LakePhysicalTableInventoryTableVO toRegisteredTable(
            LakeOdsTableMapping mapping,
            Map<String, String> actualTables,
            Set<String> registeredNames) {
        String target = mapping.getTargetTableName();
        String normalized = normalizeForComparison(target);
        if (normalized != null) {
            registeredNames.add(normalized);
        }
        LakePhysicalTableInventoryTableVO result = new LakePhysicalTableInventoryTableVO();
        result.setMappingId(mapping.getId());
        result.setSourceObjectRefId(mapping.getSourceObjectRefId());
        result.setTargetTableName(target);
        result.setManagementLevel(mapping.getManagementLevel());
        result.setResourceStatus(mapping.getResourceStatus());
        result.setSourceBound(mapping.getSourceObjectRefId() != null);
        result.setActualExists(normalized != null && actualTables.containsKey(normalized));
        return result;
    }

    private LakePhysicalTableInventoryTableVO toDiscoveredTable(String actualName) {
        LakePhysicalTableInventoryTableVO result = new LakePhysicalTableInventoryTableVO();
        result.setTargetTableName(actualName);
        result.setManagementLevel(org.apache.seatunnel.web.common.enums.LakeManagementLevel.UNMANAGED);
        result.setSourceBound(false);
        result.setActualExists(true);
        return result;
    }

    private LakePhysicalTableInventoryRelationVO toRelation(LakeJobRelation relation) {
        LakePhysicalTableInventoryRelationVO result = new LakePhysicalTableInventoryRelationVO();
        result.setRelationId(relation.getId());
        result.setJobId(relation.getJobId());
        result.setJobRuntimeType(relation.getJobRuntimeType());
        result.setJobVersion(relation.getJobVersion());
        result.setRelationStatus(relation.getRelationStatus());
        result.setRelationScope(relation.getRelationScope());
        result.setTableMappingId(relation.getTableMappingId());
        result.setSourceEndpointSnapshot(relation.getSourceEndpointSnapshot());
        result.setSinkEndpointSnapshot(relation.getSinkEndpointSnapshot());
        result.setSchemaSaveModeSnapshot(relation.getSchemaSaveModeSnapshot());
        return result;
    }

    private static String normalizeForComparison(String name) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        try {
            return DorisIdentifier.normalize(name);
        } catch (IllegalArgumentException exception) {
            return name.trim().toLowerCase(Locale.ROOT);
        }
    }

    private static String stableName(String first, String second) {
        int caseInsensitiveOrder = first.compareToIgnoreCase(second);
        return caseInsensitiveOrder < 0
                || caseInsensitiveOrder == 0 && first.compareTo(second) <= 0
                ? first : second;
    }

    private static String enumName(LakeJobRuntimeType value) {
        return value == null ? null : value.name();
    }

    private static LakeServiceException conflict(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_RESOURCE_CONFLICT, message);
    }

    private static LakeServiceException unavailable() {
        return new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                "Doris table inventory is unavailable");
    }
}
