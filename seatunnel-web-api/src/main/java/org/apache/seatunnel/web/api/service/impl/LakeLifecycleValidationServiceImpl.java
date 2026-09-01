package org.apache.seatunnel.web.api.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.contract.DorisTypeBase;
import org.apache.seatunnel.web.api.lake.contract.TargetColumn;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.contract.TargetContractCanonicalizer;
import org.apache.seatunnel.web.api.lake.contract.TargetContractValidator;
import org.apache.seatunnel.web.api.lake.contract.TargetPartition;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionMetadata;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionSummary;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionSummarizer;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleBindingSnapshotVO;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleMappingSnapshotVO;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleValidateVO;
import org.apache.seatunnel.web.api.service.LakeLifecycleValidationService;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.repository.LakeLifecyclePolicyDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleValidateDTO;
import org.apache.seatunnel.web.spi.bean.vo.LakeLifecyclePolicyVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Read-only lifecycle eligibility and Doris observation boundary.
 *
 * <p>The POST path performs all remote reads after local eligibility checks.
 * Neither it nor the cached GET path has a transaction or a persistence
 * operation; lifecycle application is deliberately a later task.</p>
 */
@Service
public class LakeLifecycleValidationServiceImpl implements LakeLifecycleValidationService {

    public static final String VALID = "LAKE_LIFECYCLE_VALID";
    public static final String MAPPING_NOT_MANAGED = "LAKE_LIFECYCLE_MAPPING_NOT_MANAGED";
    public static final String MAPPING_NOT_READY = "LAKE_LIFECYCLE_MAPPING_NOT_READY";
    public static final String MAPPING_OPERATION_IN_PROGRESS =
            "LAKE_LIFECYCLE_MAPPING_OPERATION_IN_PROGRESS";
    public static final String MAPPING_INVALID = "LAKE_LIFECYCLE_MAPPING_INVALID";
    public static final String TARGET_CONTRACT_INVALID = "LAKE_LIFECYCLE_TARGET_CONTRACT_INVALID";
    public static final String SOURCE_NULLABILITY_UNKNOWN =
            "LAKE_LIFECYCLE_SOURCE_NULLABILITY_UNKNOWN";
    public static final String SOURCE_COLUMN_NULLABLE = "LAKE_LIFECYCLE_SOURCE_COLUMN_NULLABLE";
    public static final String POLICY_NOT_ACTIVE = "LAKE_LIFECYCLE_POLICY_NOT_ACTIVE";
    public static final String POLICY_DISABLED = "LAKE_LIFECYCLE_POLICY_DISABLED";
    public static final String POLICY_INVALID = "LAKE_LIFECYCLE_POLICY_INVALID";
    public static final String GRANULARITY_MISMATCH = "LAKE_LIFECYCLE_GRANULARITY_MISMATCH";
    public static final String TARGET_TABLE_MISSING = "LAKE_TARGET_TABLE_MISSING";
    public static final String STRUCTURAL_DRIFT = "LAKE_LIFECYCLE_STRUCTURAL_DRIFT";
    public static final String STRUCTURAL_UNKNOWN = "LAKE_LIFECYCLE_STRUCTURAL_UNKNOWN";
    public static final String ACTUAL_RETENTION_UNKNOWN =
            "LAKE_LIFECYCLE_ACTUAL_RETENTION_UNKNOWN";
    public static final String PARTITION_OBSERVATION_UNKNOWN =
            "LAKE_LIFECYCLE_PARTITION_OBSERVATION_UNKNOWN";
    public static final String NOT_BOUND = "LAKE_LIFECYCLE_NOT_BOUND";
    public static final String CACHE_UNAVAILABLE = "LAKE_LIFECYCLE_CACHE_UNAVAILABLE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LakeOdsTableMappingDao tableMappingDao;
    private final LakeLifecyclePolicyDao policyDao;
    private final LakeTableLifecycleBindingDao lifecycleBindingDao;
    private final LakeDorisClientProvider dorisClientProvider;
    private final DorisPartitionSummarizer partitionSummarizer;

    @Autowired
    public LakeLifecycleValidationServiceImpl(
            LakeOdsTableMappingDao tableMappingDao,
            LakeLifecyclePolicyDao policyDao,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            LakeDorisClientProvider dorisClientProvider) {
        this(tableMappingDao, policyDao, lifecycleBindingDao, dorisClientProvider,
                new DorisPartitionSummarizer());
    }

    /** Constructor for deterministic unit tests and services with a fixed clock. */
    public LakeLifecycleValidationServiceImpl(
            LakeOdsTableMappingDao tableMappingDao,
            LakeLifecyclePolicyDao policyDao,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            LakeDorisClientProvider dorisClientProvider,
            DorisPartitionSummarizer partitionSummarizer) {
        this.tableMappingDao = Objects.requireNonNull(tableMappingDao, "tableMappingDao");
        this.policyDao = Objects.requireNonNull(policyDao, "policyDao");
        this.lifecycleBindingDao = Objects.requireNonNull(
                lifecycleBindingDao, "lifecycleBindingDao");
        this.dorisClientProvider = Objects.requireNonNull(
                dorisClientProvider, "dorisClientProvider");
        this.partitionSummarizer = Objects.requireNonNull(
                partitionSummarizer, "partitionSummarizer");
    }

    @Override
    public LakeLifecycleValidateVO validate(LakeLifecycleValidateDTO request) {
        if (request == null || !positive(request.getMappingId()) || !positive(request.getPolicyId())) {
            throw invalid("mappingId and policyId are required");
        }

        LakeOdsTableMapping mapping = requireMapping(request.getMappingId());
        LakeLifecyclePolicy policy = requirePolicy(request.getPolicyId());
        LakeTableLifecycleBinding binding = readBinding(mapping.getId());
        TargetContract contract = readStoredContract(mapping);
        LakeLifecycleValidateVO result = baseResult(mapping, policy, binding, contract);
        List<String> reasons = new ArrayList<>();

        validateMapping(mapping, reasons);
        ContractCheck contractCheck = validateContract(contract, reasons);
        if (contractCheck.contract() != null) {
            result.setPartitionColumn(contractCheck.partitionColumn());
            result.setGranularity(contractCheck.granularity());
            result.setStructuralMatch(null);
            validateSourceNullability(mapping, contractCheck.partitionSourceName(), reasons);
        }
        validatePolicy(policy, contractCheck.granularity(), reasons);
        result.setExistingBindingPolicyDiff(binding != null
                && !Objects.equals(binding.getPolicyId(), policy.getId()));

        if (!reasons.isEmpty()) {
            return invalidResult(result, reasons);
        }
        return observeDoris(result, mapping, contractCheck.contract(), policy);
    }

    @Override
    public LakeLifecycleValidateVO detail(Long mappingId) {
        if (!positive(mappingId)) {
            throw invalid("mappingId is required");
        }
        LakeOdsTableMapping mapping = requireMapping(mappingId);
        LakeTableLifecycleBinding binding = readBinding(mapping.getId());
        LakeLifecyclePolicy policy = binding == null || binding.getPolicyId() == null
                ? null : readPolicy(binding.getPolicyId());
        TargetContract contract = readStoredContract(mapping);
        LakeLifecycleValidateVO result = baseResult(mapping, policy, binding, contract);
        if (binding == null) {
            result.setCode(NOT_BOUND);
            result.setReasons(List.of(NOT_BOUND));
            result.setValid(false);
            return result;
        }
        result.setPartitionColumn(binding.getPartitionColumn());
        result.setGranularity(binding.getGranularity());
        // The requested policy is not available on the cache-only endpoint. Keep
        // the binding's frozen desired state visible; it is authoritative for
        // cached validity even when the reusable policy row has changed.
        result.setPolicyId(binding.getPolicyId());
        result.setDesiredRetentionCount(binding.getRetentionCount());
        result.setActualRetentionCount(binding.getActualRetentionCount());
        result.setObservedAt(toInstant(binding.getLastObservedAt()));
        result.setPartitionSummary(readCachedSummary(binding.getActualPartitionSummaryJson()));
        result.setStructuralMatch(cachedStructuralMatch(mapping));
        boolean cachedValid = isCurrentValidCache(mapping, binding, result);
        result.setValid(cachedValid);
        if (cachedValid) {
            result.setCode(VALID);
            result.setReasons(List.of());
        } else if (mapping.getTargetConsistencyStatus() == LakeConsistencyStatus.DRIFT) {
            result.setCode(STRUCTURAL_DRIFT);
            result.setReasons(List.of(STRUCTURAL_DRIFT));
        } else {
            result.setCode(CACHE_UNAVAILABLE);
            result.setReasons(List.of(CACHE_UNAVAILABLE));
        }
        return result;
    }

    private boolean isCurrentValidCache(
            LakeOdsTableMapping mapping,
            LakeTableLifecycleBinding binding,
            LakeLifecycleValidateVO result) {
        return mapping.getManagementLevel() == LakeManagementLevel.MANAGED
                && mapping.getResourceStatus() == LakeResourceStatus.READY
                && StringUtils.isBlank(mapping.getOperationToken())
                && binding.getStatus() == LakeLifecycleBindingStatus.ACTIVE
                && StringUtils.isBlank(binding.getOperationToken())
                // The binding's frozen desired state remains authoritative even
                // when the reusable policy row is later edited or disabled.
                && validPolicySnapshot(binding)
                && binding.getRetentionCount() != null
                && binding.getActualRetentionCount() != null
                && binding.getRetentionCount().equals(binding.getActualRetentionCount())
                && binding.getLastObservedAt() != null
                && result.getObservedAt() != null
                && result.getPartitionSummary() != null
                && Boolean.TRUE.equals(result.getStructuralMatch());
    }

    private static boolean validPolicySnapshot(LakeTableLifecycleBinding binding) {
        if (StringUtils.isBlank(binding.getPolicySnapshotJson())
                || binding.getPolicyId() == null || binding.getPolicyVersion() == null
                || binding.getGranularity() == null || binding.getRetentionCount() == null
                || binding.getRetentionCount() <= 0) {
            return false;
        }
        try {
            JsonNode snapshot = MAPPER.readTree(binding.getPolicySnapshotJson());
            return snapshot != null
                    && snapshot.path("policyId").asLong(Long.MIN_VALUE) == binding.getPolicyId()
                    && snapshot.path("version").asInt(Integer.MIN_VALUE) == binding.getPolicyVersion()
                    && binding.getGranularity().name().equalsIgnoreCase(
                    snapshot.path("granularity").asText())
                    && snapshot.path("retentionCount").asInt(Integer.MIN_VALUE)
                    == binding.getRetentionCount();
        } catch (Exception exception) {
            return false;
        }
    }

    private LakeLifecycleValidateVO observeDoris(
            LakeLifecycleValidateVO result,
            LakeOdsTableMapping mapping,
            TargetContract expected,
            LakeLifecyclePolicy policy) {
        DorisLakeClient client;
        try {
            client = dorisClientProvider.get(mapping.getLakeDataSourceId());
        } catch (RuntimeException exception) {
            return invalidResult(result, List.of(LakeErrorCode.LAKE_DORIS_UNAVAILABLE));
        }
        if (client == null) {
            return invalidResult(result, List.of(LakeErrorCode.LAKE_DORIS_UNAVAILABLE));
        }

        String database = mapping.getDatabaseName().trim();
        String table = mapping.getTargetTableName().trim();
        boolean exists;
        try {
            exists = client.tableExists(database, table);
        } catch (RuntimeException exception) {
            return invalidResult(result, List.of(LakeErrorCode.LAKE_DORIS_UNAVAILABLE));
        }
        if (!exists) {
            result.setStructuralMatch(false);
            return invalidResult(result, List.of(TARGET_TABLE_MISSING));
        }

        TargetContract actual;
        try {
            actual = client.readContract(database, table);
        } catch (RuntimeException exception) {
            return invalidResult(result, List.of(LakeErrorCode.LAKE_DORIS_UNAVAILABLE));
        }
        if (actual == null) {
            return invalidResult(result, List.of(STRUCTURAL_UNKNOWN));
        }
        try {
            if (!TargetContractCanonicalizer.canonicalHash(expected)
                    .equals(TargetContractCanonicalizer.canonicalHash(actual))) {
                result.setStructuralMatch(false);
                return invalidResult(result, List.of(STRUCTURAL_DRIFT));
            }
        } catch (RuntimeException exception) {
            return invalidResult(result, List.of(STRUCTURAL_UNKNOWN));
        }
        result.setStructuralMatch(true);

        PropertyObservation property;
        try {
            property = readRetention(client.readTableProperties(database, table));
        } catch (RuntimeException exception) {
            return invalidResult(result, List.of(LakeErrorCode.LAKE_DORIS_UNAVAILABLE));
        }
        if (property.unknown()) {
            return invalidResult(result, List.of(ACTUAL_RETENTION_UNKNOWN));
        }
        result.setActualRetentionCount(property.value());

        List<DorisPartitionMetadata> partitions;
        try {
            partitions = client.listPartitions(database, table);
        } catch (RuntimeException exception) {
            return invalidResult(result, List.of(LakeErrorCode.LAKE_DORIS_UNAVAILABLE));
        }
        if (partitions == null) {
            return invalidResult(result, List.of(PARTITION_OBSERVATION_UNKNOWN));
        }
        DorisPartitionSummary summary;
        try {
            summary = partitionSummarizer.summarize(expected, partitions);
        } catch (RuntimeException exception) {
            return invalidResult(result, List.of(PARTITION_OBSERVATION_UNKNOWN));
        }
        result.setPartitionSummary(summary);
        result.setObservedAt(summary.observedAt());
        result.setDesiredRetentionCount(policy.getRetentionCount());
        result.setValid(true);
        result.setCode(VALID);
        result.setReasons(List.of());
        return result;
    }

    private ContractCheck validateContract(TargetContract contract, List<String> reasons) {
        if (contract == null) {
            reasons.add(TARGET_CONTRACT_INVALID);
            return ContractCheck.empty();
        }
        TargetPartition partition = contract.getPartition();
        if (partition == null || !Boolean.TRUE.equals(partition.getEnabled())) {
            reasons.add(LakeErrorCode.LAKE_LIFECYCLE_REQUIRES_PREPARTITIONED_TABLE);
            return ContractCheck.empty();
        }
        LakePartitionGranularity granularity = parseGranularity(partition.getGranularity());
        TargetColumn targetColumn = contract.getColumns().stream()
                .filter(column -> column != null
                        && Objects.equals(column.getTargetName(), partition.getColumn()))
                .findFirst().orElse(null);
        if (granularity == null || targetColumn == null
                || targetColumn.getTargetType() == null
                || targetColumn.getTargetType().getBase() == null
                || (targetColumn.getTargetType().getBase().canonical() != DorisTypeBase.DATE
                && targetColumn.getTargetType().getBase().canonical() != DorisTypeBase.DATETIME)
                || !Boolean.FALSE.equals(targetColumn.getNullable())
                || contract.getTableModel() == null
                || (contract.getTableModel().name().equals("UNIQUE")
                && !contract.getKeyColumns().contains(partition.getColumn()))) {
            reasons.add(TARGET_CONTRACT_INVALID);
            return ContractCheck.empty();
        }
        return new ContractCheck(contract, partition.getColumn(), granularity,
                targetColumn.getSourceName());
    }

    private void validateMapping(LakeOdsTableMapping mapping, List<String> reasons) {
        if (mapping.getManagementLevel() != LakeManagementLevel.MANAGED) {
            reasons.add(MAPPING_NOT_MANAGED);
        }
        if (mapping.getResourceStatus() != LakeResourceStatus.READY) {
            reasons.add(MAPPING_NOT_READY);
        }
        if (StringUtils.isNotBlank(mapping.getOperationToken())) {
            reasons.add(MAPPING_OPERATION_IN_PROGRESS);
        }
        if (mapping.getLakeDataSourceId() == null || mapping.getLakeDataSourceId() <= 0
                || StringUtils.isBlank(mapping.getDatabaseName())
                || StringUtils.isBlank(mapping.getTargetTableName())) {
            reasons.add(MAPPING_INVALID);
        }
    }

    private void validatePolicy(
            LakeLifecyclePolicy policy,
            LakePartitionGranularity contractGranularity,
            List<String> reasons) {
        if (policy.getStatus() == LakeLifecyclePolicyStatus.DISABLED) {
            reasons.add(POLICY_DISABLED);
        } else if (policy.getStatus() != LakeLifecyclePolicyStatus.ACTIVE) {
            reasons.add(POLICY_NOT_ACTIVE);
        }
        if (policy.getGranularity() == null || policy.getRetentionCount() == null
                || policy.getRetentionCount() <= 0) {
            reasons.add(POLICY_INVALID);
        }
        if (contractGranularity != null && policy.getGranularity() != null
                && policy.getGranularity() != contractGranularity) {
            reasons.add(GRANULARITY_MISMATCH);
        }
    }

    private void validateSourceNullability(
            LakeOdsTableMapping mapping, String sourceName, List<String> reasons) {
        Nullability nullability = sourceNullability(mapping.getSourceSnapshotJson(), sourceName);
        if (nullability == Nullability.NULLABLE) {
            reasons.add(SOURCE_COLUMN_NULLABLE);
        } else if (nullability != Nullability.NOT_NULL) {
            reasons.add(SOURCE_NULLABILITY_UNKNOWN);
        }
    }

    private Nullability sourceNullability(String json, String sourceName) {
        if (StringUtils.isBlank(json) || StringUtils.isBlank(sourceName)) {
            return Nullability.UNKNOWN;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode columns = root == null ? null : root.path("schema").path("columns");
            if (columns == null || !columns.isArray()) {
                columns = root == null ? null : root.path("columns");
            }
            if (columns == null || !columns.isArray()) {
                return Nullability.UNKNOWN;
            }
            String expected = normaliseName(sourceName);
            Nullability result = null;
            for (JsonNode column : columns) {
                if (column == null || !expected.equals(normaliseName(text(column, "name")))) {
                    continue;
                }
                JsonNode nullable = column.get("nullable");
                Nullability current = nullable != null && nullable.isBoolean()
                        ? nullable.booleanValue() ? Nullability.NULLABLE : Nullability.NOT_NULL
                        : Nullability.UNKNOWN;
                if (result != null && result != current) {
                    return Nullability.UNKNOWN;
                }
                result = current;
            }
            return result == null ? Nullability.UNKNOWN : result;
        } catch (Exception exception) {
            return Nullability.UNKNOWN;
        }
    }

    private LakeLifecycleValidateVO baseResult(
            LakeOdsTableMapping mapping,
            LakeLifecyclePolicy policy,
            LakeTableLifecycleBinding binding,
            TargetContract contract) {
        LakeLifecycleValidateVO result = new LakeLifecycleValidateVO();
        result.setMappingId(mapping.getId());
        result.setPolicyId(policy == null ? null : policy.getId());
        result.setDesiredRetentionCount(policy == null ? null : policy.getRetentionCount());
        result.setMappingSnapshot(toMappingSnapshot(mapping, contract));
        result.setPolicySnapshot(policy == null ? null : toPolicySnapshot(policy));
        result.setExistingBinding(binding == null ? null : toBindingSnapshot(binding));
        result.setStructuralMatch(null);
        return result;
    }

    private LakeLifecycleValidateVO invalidResult(
            LakeLifecycleValidateVO result, List<String> reasons) {
        List<String> safe = reasons == null || reasons.isEmpty()
                ? List.of(MAPPING_INVALID) : List.copyOf(reasons);
        result.setValid(false);
        result.setCode(safe.get(0));
        result.setReasons(safe);
        return result;
    }

    private LakeOdsTableMapping requireMapping(Long id) {
        LakeOdsTableMapping mapping;
        try {
            mapping = tableMappingDao.queryActiveById(id);
            if (mapping == null) {
                mapping = tableMappingDao.queryByIdIncludingDeleted(id);
            }
        } catch (RuntimeException exception) {
            throw conflict("Lake table mapping cannot be read");
        }
        if (mapping == null) {
            throw conflict("Lake table mapping does not exist");
        }
        if (Boolean.TRUE.equals(mapping.getDeleted())) {
            throw conflict("Lake table mapping is deleted");
        }
        return mapping;
    }

    private LakeLifecyclePolicy requirePolicy(Long id) {
        LakeLifecyclePolicy policy = readPolicy(id);
        if (policy == null) {
            throw conflict("Lifecycle policy does not exist");
        }
        return policy;
    }

    private LakeLifecyclePolicy readPolicy(Long id) {
        if (!positive(id)) {
            return null;
        }
        try {
            return policyDao.queryById(id);
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle policy cannot be read");
        }
    }

    private LakeTableLifecycleBinding readBinding(Long mappingId) {
        try {
            return lifecycleBindingDao.queryByTableMappingId(mappingId);
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle binding cannot be read");
        }
    }

    private TargetContract readStoredContract(LakeOdsTableMapping mapping) {
        if (StringUtils.isBlank(mapping.getTargetContractJson())
                || StringUtils.isBlank(mapping.getTargetContractHash())) {
            return null;
        }
        try {
            TargetContract contract = TargetContractValidator.validateAndNormalize(
                    MAPPER.readValue(mapping.getTargetContractJson(), TargetContract.class));
            return mapping.getTargetContractHash().trim().equals(
                    TargetContractCanonicalizer.canonicalHash(contract)) ? contract : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private LakeLifecycleMappingSnapshotVO toMappingSnapshot(
            LakeOdsTableMapping mapping, TargetContract contract) {
        LakeLifecycleMappingSnapshotVO result = new LakeLifecycleMappingSnapshotVO();
        result.setId(mapping.getId());
        result.setSourceObjectRefId(mapping.getSourceObjectRefId());
        result.setOdsDatabaseBindingId(mapping.getOdsDatabaseBindingId());
        result.setLakeDataSourceId(mapping.getLakeDataSourceId());
        result.setDatabaseName(mapping.getDatabaseName());
        result.setTargetTableName(mapping.getTargetTableName());
        result.setManagementLevel(mapping.getManagementLevel());
        result.setResourceStatus(mapping.getResourceStatus());
        result.setGeneration(mapping.getGeneration());
        result.setLockVersion(mapping.getLockVersion());
        result.setSourceSchemaHash(mapping.getSourceSchemaHash());
        result.setSourceSnapshotJson(mapping.getSourceSnapshotJson());
        result.setTargetContractHash(mapping.getTargetContractHash());
        result.setTargetContract(contract);
        result.setSourceConsistencyStatus(mapping.getSourceConsistencyStatus());
        result.setTargetConsistencyStatus(mapping.getTargetConsistencyStatus());
        result.setTaskConsistencyStatus(mapping.getTaskConsistencyStatus());
        result.setActualTableExists(mapping.getActualTableExists());
        result.setLastReconcileAt(copyDate(mapping.getLastReconcileAt()));
        result.setCreateUserId(mapping.getCreateUserId());
        result.setUpdateUserId(mapping.getUpdateUserId());
        result.setDeleted(mapping.getDeleted());
        result.setCreateTime(copyDate(mapping.getCreateTime()));
        result.setUpdateTime(copyDate(mapping.getUpdateTime()));
        return result;
    }

    private LakeLifecycleBindingSnapshotVO toBindingSnapshot(LakeTableLifecycleBinding binding) {
        LakeLifecycleBindingSnapshotVO result = new LakeLifecycleBindingSnapshotVO();
        result.setId(binding.getId());
        result.setTableMappingId(binding.getTableMappingId());
        result.setPolicyId(binding.getPolicyId());
        result.setPolicyVersion(binding.getPolicyVersion());
        result.setPartitionColumn(binding.getPartitionColumn());
        result.setGranularity(binding.getGranularity());
        result.setRetentionCount(binding.getRetentionCount());
        result.setActualRetentionCount(binding.getActualRetentionCount());
        result.setActualPartitionSummaryJson(binding.getActualPartitionSummaryJson());
        result.setLastObservedAt(copyDate(binding.getLastObservedAt()));
        result.setPolicySnapshotJson(binding.getPolicySnapshotJson());
        result.setStatus(binding.getStatus());
        result.setGeneration(binding.getGeneration());
        result.setLockVersion(binding.getLockVersion());
        result.setErrorCode(binding.getErrorCode());
        result.setCreateTime(copyDate(binding.getCreateTime()));
        result.setUpdateTime(copyDate(binding.getUpdateTime()));
        return result;
    }

    private LakeLifecyclePolicyVO toPolicySnapshot(LakeLifecyclePolicy policy) {
        LakeLifecyclePolicyVO result = new LakeLifecyclePolicyVO();
        result.setId(policy.getId());
        result.setPolicyName(policy.getPolicyName());
        result.setVersion(policy.getVersion());
        result.setStatus(policy.getStatus());
        result.setGranularity(policy.getGranularity());
        result.setRetentionCount(policy.getRetentionCount());
        result.setDescription(policy.getDescription());
        result.setCreateUserId(policy.getCreateUserId());
        result.setUpdateUserId(policy.getUpdateUserId());
        result.setCreateTime(copyDate(policy.getCreateTime()));
        result.setUpdateTime(copyDate(policy.getUpdateTime()));
        return result;
    }

    private DorisPartitionSummary readCachedSummary(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            Instant observedAt = root.hasNonNull("observedAt")
                    ? Instant.parse(root.get("observedAt").asText()) : null;
            if (observedAt == null) {
                return null;
            }
            return new DorisPartitionSummary(
                    root.path("total").asInt(-1),
                    root.path("historical").asInt(-1),
                    root.path("current").asInt(-1),
                    root.path("future").asInt(-1),
                    root.path("unknown").asInt(-1),
                    strings(root.get("partitionNames")), observedAt,
                    strings(root.get("historicalPartitionNames")),
                    strings(root.get("currentPartitionNames")),
                    strings(root.get("futurePartitionNames")),
                    strings(root.get("unknownPartitionNames")));
        } catch (Exception exception) {
            return null;
        }
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return List.copyOf(values);
    }

    private static Boolean cachedStructuralMatch(LakeOdsTableMapping mapping) {
        if (mapping.getTargetConsistencyStatus() == LakeConsistencyStatus.CONSISTENT) {
            return true;
        }
        if (mapping.getTargetConsistencyStatus() == LakeConsistencyStatus.DRIFT
                || mapping.getActualTableExists() == Boolean.FALSE) {
            return false;
        }
        return null;
    }

    private static LakePartitionGranularity parseGranularity(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return LakePartitionGranularity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static PropertyObservation readRetention(java.util.Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return new PropertyObservation(null, false);
        }
        String value = null;
        for (java.util.Map.Entry<String, String> entry : properties.entrySet()) {
            if (entry.getKey() != null
                    && "partition.retention_count".equals(entry.getKey().trim()
                    .toLowerCase(Locale.ROOT))) {
                value = entry.getValue();
                break;
            }
        }
        if (value == null || value.isBlank()) {
            return new PropertyObservation(null, value != null);
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? new PropertyObservation(parsed, false)
                    : new PropertyObservation(null, true);
        } catch (NumberFormatException exception) {
            return new PropertyObservation(null, true);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static String normaliseName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Instant toInstant(Date value) {
        return value == null ? null : value.toInstant();
    }

    private static Date copyDate(Date value) {
        return value == null ? null : new Date(value.getTime());
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static LakeServiceException invalid(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_REQUEST_INVALID, message);
    }

    private static LakeServiceException conflict(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_RESOURCE_CONFLICT, message);
    }

    private record ContractCheck(
            TargetContract contract,
            String partitionColumn,
            LakePartitionGranularity granularity,
            String partitionSourceName) {
        private static ContractCheck empty() {
            return new ContractCheck(null, null, null, null);
        }
    }

    private record PropertyObservation(Integer value, boolean unknown) {
    }

    private enum Nullability {
        NOT_NULL,
        NULLABLE,
        UNKNOWN
    }
}
