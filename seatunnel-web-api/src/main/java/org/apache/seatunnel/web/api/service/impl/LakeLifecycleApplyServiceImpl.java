package org.apache.seatunnel.web.api.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.contract.TargetContractCanonicalizer;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionMetadata;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionSummarizer;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionSummary;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleBindingSnapshotVO;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleConfirmationTokenService;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleMappingSnapshotVO;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleValidateVO;
import org.apache.seatunnel.web.api.service.LakeLifecycleApplyService;
import org.apache.seatunnel.web.api.service.LakeLifecycleValidationService;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleApplyDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleRetentionUpdateDTO;
import org.apache.seatunnel.web.spi.bean.vo.LakeLifecyclePolicyVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Three-phase lifecycle retention coordinator.
 *
 * <p>This class deliberately has no transaction annotation.  It performs the
 * read-through validation first, delegates each local phase to the dedicated
 * REQUIRES_NEW persistence bean, and invokes Doris only between those phases.</p>
 */
@Service
public class LakeLifecycleApplyServiceImpl implements LakeLifecycleApplyService {

    public static final String TARGET_TABLE_MISSING =
            LakeLifecycleValidationServiceImpl.TARGET_TABLE_MISSING;
    public static final String STRUCTURAL_DRIFT =
            LakeLifecycleValidationServiceImpl.STRUCTURAL_DRIFT;
    public static final String STRUCTURAL_UNKNOWN =
            LakeLifecycleValidationServiceImpl.STRUCTURAL_UNKNOWN;
    public static final String ACTUAL_RETENTION_UNKNOWN =
            LakeLifecycleValidationServiceImpl.ACTUAL_RETENTION_UNKNOWN;
    public static final String PARTITION_OBSERVATION_UNKNOWN =
            LakeLifecycleValidationServiceImpl.PARTITION_OBSERVATION_UNKNOWN;
    public static final String RETENTION_VERIFY_MISMATCH =
            "LAKE_LIFECYCLE_RETENTION_VERIFY_MISMATCH";

    private final LakeLifecycleValidationService validationService;
    private final LakeLifecycleApplyPersistenceService persistenceService;
    private final LakeTableLifecycleBindingDao lifecycleBindingDao;
    private final LakeLifecycleConfirmationTokenService tokenService;
    private final LakeDorisClientProvider dorisClientProvider;
    private final CurrentUserProvider currentUserProvider;
    private final DorisPartitionSummarizer partitionSummarizer;

    @Autowired
    public LakeLifecycleApplyServiceImpl(
            LakeLifecycleValidationService validationService,
            LakeLifecycleApplyPersistenceService persistenceService,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            LakeLifecycleConfirmationTokenService tokenService,
            LakeDorisClientProvider dorisClientProvider,
            CurrentUserProvider currentUserProvider) {
        this(validationService, persistenceService, lifecycleBindingDao, tokenService,
                dorisClientProvider, currentUserProvider, new DorisPartitionSummarizer());
    }

    /** Visible for deterministic coordinator tests. */
    public LakeLifecycleApplyServiceImpl(
            LakeLifecycleValidationService validationService,
            LakeLifecycleApplyPersistenceService persistenceService,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            LakeLifecycleConfirmationTokenService tokenService,
            LakeDorisClientProvider dorisClientProvider,
            CurrentUserProvider currentUserProvider,
            DorisPartitionSummarizer partitionSummarizer) {
        this.validationService = Objects.requireNonNull(validationService, "validationService");
        this.persistenceService = Objects.requireNonNull(
                persistenceService, "persistenceService");
        this.lifecycleBindingDao = Objects.requireNonNull(
                lifecycleBindingDao, "lifecycleBindingDao");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.dorisClientProvider = Objects.requireNonNull(
                dorisClientProvider, "dorisClientProvider");
        this.currentUserProvider = Objects.requireNonNull(
                currentUserProvider, "currentUserProvider");
        this.partitionSummarizer = Objects.requireNonNull(
                partitionSummarizer, "partitionSummarizer");
    }

    @Override
    public LakeLifecycleValidateVO apply(LakeLifecycleApplyDTO request) {
        validateApplyRequest(request);
        Integer userId = requireCurrentUserId();
        LakeLifecycleValidateVO validated = readThrough(request.getMappingId(), request.getPolicyId());
        requireValidValidation(validated);
        LakeLifecycleApplyPersistenceService.StartRequest start = startRequest(
                validated, request.getMappingId(), request.getPolicyId(), userId, true, null);
        LakeLifecycleApplyPersistenceService.StartResult started = persistenceService.start(start);
        if (started.idempotent()) {
            LakeTableLifecycleBinding current = requireCurrentBinding(request.getMappingId());
            return completed(validated, current,
                    validated.getActualRetentionCount(), validated.getPartitionSummary());
        }
        return execute(started, validated, userId);
    }

    @Override
    public LakeLifecycleValidateVO update(
            Long mappingId, LakeLifecycleRetentionUpdateDTO request) {
        validateUpdateRequest(mappingId, request);
        Integer userId = requireCurrentUserId();
        LakeLifecycleValidateVO validated = readThrough(mappingId, request.getPolicyId());
        requireValidValidation(validated);

        LakeTableLifecycleBinding binding = readBinding(mappingId);
        requireUpdatableBinding(binding);
        LakeLifecycleBindingSnapshotVO bindingSnapshot = toSnapshot(binding);
        Integer currentDesired = binding.getRetentionCount();
        Integer requestedRetention = validated.getDesiredRetentionCount();
        if (currentDesired == null || currentDesired <= 0 || requestedRetention == null
                || requestedRetention <= 0) {
            throw conflict("Lifecycle binding desired retention is invalid");
        }
        if (requestedRetention < currentDesired) {
            if (!request.isConfirmed()) {
                throw invalid("confirmed=true is required when reducing retention");
            }
            confirmDecrease(request, validated, mappingId, bindingSnapshot,
                    currentDesired, requestedRetention, userId);
        }

        LakeLifecycleApplyPersistenceService.StartRequest start = startRequest(
                validated, mappingId, request.getPolicyId(), userId, false, binding);
        LakeLifecycleApplyPersistenceService.StartResult started = persistenceService.start(start);
        if (started.idempotent()) {
            LakeTableLifecycleBinding current = requireCurrentBinding(mappingId);
            return completed(validated, current,
                    validated.getActualRetentionCount(), validated.getPartitionSummary());
        }
        return execute(started, validated, userId);
    }

    private LakeLifecycleValidateVO readThrough(Long mappingId, Long policyId) {
        try {
            org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleValidateDTO request =
                    new org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleValidateDTO();
            request.setMappingId(mappingId);
            request.setPolicyId(policyId);
            return validationService.validate(request);
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle validation could not be completed");
        }
    }

    private LakeLifecycleValidateVO execute(
            LakeLifecycleApplyPersistenceService.StartResult started,
            LakeLifecycleValidateVO validated,
            Integer userId) {
        LakeLifecycleApplyPersistenceService.OperationHandle handle = started.handle();
        Observation observation;
        try {
            observation = executeExternal(validated, started.binding().getRetentionCount());
        } catch (ExternalFailure exception) {
            failAndThrow(handle, exception.code(), userId);
            return null;
        } catch (LakeServiceException exception) {
            failAndThrow(handle, exception.getLakeErrorCode(), userId);
            return null;
        } catch (RuntimeException exception) {
            failAndThrow(handle, LakeErrorCode.LAKE_DORIS_UNAVAILABLE, userId);
            return null;
        }

        // A false result means TX2 lost the lease.  Persistence has already
        // marked that journal row IGNORED; never turn the stale completion into
        // an ERROR transition by calling finalizeFailure here.
        if (!persistenceService.finalizeSuccess(
                handle, observation.actualRetentionCount(), observation.summary(), userId)) {
            throw stale("Lifecycle operation result is stale");
        }
        LakeTableLifecycleBinding current = requireCurrentBinding(validated.getMappingId());
        return completed(validated, current,
                observation.actualRetentionCount(), observation.summary());
    }

    private Observation executeExternal(
            LakeLifecycleValidateVO validated, Integer requestedRetention) {
        LakeLifecycleMappingSnapshotVO mapping = validated.getMappingSnapshot();
        TargetContract expected = mapping == null ? null : mapping.getTargetContract();
        if (mapping == null || expected == null || requestedRetention == null
                || requestedRetention <= 0 || StringUtils.isBlank(mapping.getDatabaseName())
                || StringUtils.isBlank(mapping.getTargetTableName())
                || mapping.getLakeDataSourceId() == null
                || mapping.getLakeDataSourceId() <= 0) {
            throw external(STRUCTURAL_UNKNOWN);
        }

        DorisLakeClient client;
        try {
            client = dorisClientProvider.get(mapping.getLakeDataSourceId());
        } catch (RuntimeException exception) {
            throw external(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
        }
        if (client == null) {
            throw external(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
        }

        String database = mapping.getDatabaseName().trim();
        String table = mapping.getTargetTableName().trim();
        boolean exists;
        try {
            exists = client.tableExists(database, table);
        } catch (RuntimeException exception) {
            throw external(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
        }
        if (!exists) {
            throw external(TARGET_TABLE_MISSING);
        }

        TargetContract actual;
        try {
            actual = client.readContract(database, table);
            if (actual == null) {
                throw external(STRUCTURAL_UNKNOWN);
            }
            if (!TargetContractCanonicalizer.canonicalHash(expected)
                    .equals(TargetContractCanonicalizer.canonicalHash(actual))) {
                throw external(STRUCTURAL_DRIFT);
            }
        } catch (ExternalFailure exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw external(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
        }

        try {
            client.alterTableProperties(database, table,
                    Map.of("partition.retention_count", String.valueOf(requestedRetention)));
        } catch (RuntimeException exception) {
            throw external(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
        }

        Integer actualRetention = readActualRetention(client, mapping);
        if (actualRetention == null) {
            throw external(ACTUAL_RETENTION_UNKNOWN);
        }
        if (!Objects.equals(actualRetention, requestedRetention)) {
            throw external(RETENTION_VERIFY_MISMATCH);
        }

        List<DorisPartitionMetadata> partitions;
        try {
            partitions = client.listPartitions(database, table);
        } catch (RuntimeException exception) {
            throw external(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
        }
        if (partitions == null) {
            throw external(PARTITION_OBSERVATION_UNKNOWN);
        }
        DorisPartitionSummary summary;
        try {
            LakePartitionGranularity granularity = validatedGranularity(validated);
            if (granularity == null) {
                throw external(PARTITION_OBSERVATION_UNKNOWN);
            }
            summary = partitionSummarizer.summarize(granularity, partitions);
        } catch (ExternalFailure exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw external(PARTITION_OBSERVATION_UNKNOWN);
        }
        if (summary == null) {
            throw external(PARTITION_OBSERVATION_UNKNOWN);
        }
        return new Observation(actualRetention, summary);
    }

    private Integer readActualRetention(
            DorisLakeClient client, LakeLifecycleMappingSnapshotVO mapping) {
        try {
            Map<String, String> properties = client.readTableProperties(
                    mapping.getDatabaseName().trim(), mapping.getTargetTableName().trim());
            if (properties == null) {
                return null;
            }
            String value = null;
            int matches = 0;
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                if (entry.getKey() != null && "partition.retention_count".equals(
                        entry.getKey().trim().toLowerCase(java.util.Locale.ROOT))) {
                    matches++;
                    if (value == null) {
                        value = entry.getValue();
                    } else if (!Objects.equals(value, entry.getValue())) {
                        return null;
                    }
                }
            }
            if (matches != 1 || value == null || value.isBlank()) {
                return null;
            }
            try {
                int parsed = Integer.parseInt(value.trim());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException exception) {
                return null;
            }
        } catch (RuntimeException exception) {
            throw external(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
        }
    }

    private void confirmDecrease(
            LakeLifecycleRetentionUpdateDTO request,
            LakeLifecycleValidateVO validated,
            Long mappingId,
            LakeLifecycleBindingSnapshotVO binding,
            Integer currentDesired,
            Integer requestedRetention,
            Integer userId) {
        if (StringUtils.isBlank(request.effectivePlanFingerprint())) {
            throw invalid("A plan fingerprint is required for retention decrease");
        }
        String fingerprint = request.effectivePlanFingerprint();
        LakeLifecycleConfirmationTokenService.Payload payload;
        try {
            payload = tokenService.verify(fingerprint, userId);
        } catch (RuntimeException exception) {
            throw invalid("Lifecycle plan fingerprint is invalid");
        }
        LakeLifecycleMappingSnapshotVO mapping = validated.getMappingSnapshot();
        Integer policyVersion = validated.getPolicySnapshot() == null
                ? null : validated.getPolicySnapshot().getVersion();
        if (!same(payload.mappingId(), mappingId)
                || !same(payload.mappingGeneration(), mapping == null ? null : mapping.getGeneration())
                || !same(payload.mappingLockVersion(), mapping == null ? null : mapping.getLockVersion())
                || !same(payload.bindingId(), binding.getId())
                || !same(payload.bindingGeneration(), binding.getGeneration())
                || !same(payload.bindingLockVersion(), binding.getLockVersion())
                || !same(payload.currentDesiredRetentionCount(), currentDesired)
                || !same(payload.policyId(), validated.getPolicyId())
                || !same(payload.policyVersion(), policyVersion)
                || !same(payload.newRetentionCount(), requestedRetention)) {
            throw conflict("Lifecycle plan fingerprint is stale");
        }
        if (!LakeLifecycleRetentionPreviewServiceImpl.hasExactHistoricalObservation(
                validated.getPartitionSummary())) {
            throw conflict("Lifecycle retention impact is no longer known");
        }
        List<String> impacted = LakeLifecycleRetentionPreviewServiceImpl
                .impactedHistoricalPartitionNames(validated.getPartitionSummary(), requestedRetention);
        String impactHash = LakeLifecycleRetentionPreviewServiceImpl.observedImpactHash(
                mapping, binding, validated, requestedRetention, impacted);
        if (!Objects.equals(payload.observedImpactHash(), impactHash)) {
            throw conflict("Lifecycle retention impact is stale");
        }
        if (!tokenService.consume(fingerprint, payload)) {
            throw invalid("Lifecycle plan fingerprint has already been used");
        }
    }

    private LakeLifecycleApplyPersistenceService.StartRequest startRequest(
            LakeLifecycleValidateVO validated,
            Long mappingId,
            Long policyId,
            Integer userId,
            boolean apply,
            LakeTableLifecycleBinding expectedBinding) {
        LakeLifecycleMappingSnapshotVO mapping = validated.getMappingSnapshot();
        LakeLifecyclePolicyVO policy = validated.getPolicySnapshot();
        if (mapping == null || policy == null || mapping.getGeneration() == null
                || mapping.getLockVersion() == null || validated.getPartitionColumn() == null
                || validated.getGranularity() == null || policy.getVersion() == null
                || validated.getDesiredRetentionCount() == null) {
            throw conflict("Lifecycle validation identity is incomplete");
        }
        return new LakeLifecycleApplyPersistenceService.StartRequest(
                mappingId,
                policyId,
                policy.getVersion(),
                validated.getPartitionColumn(),
                validated.getGranularity(),
                validated.getDesiredRetentionCount(),
                mapping.getGeneration(),
                mapping.getLockVersion(),
                expectedBinding == null ? null : expectedBinding.getId(),
                expectedBinding == null ? null : expectedBinding.getGeneration(),
                expectedBinding == null ? null : expectedBinding.getLockVersion(),
                expectedBinding == null ? null : expectedBinding.getRetentionCount(),
                requestHash(mappingId, policyId, policy.getVersion(),
                        validated.getDesiredRetentionCount()),
                userId,
                apply,
                validated.getActualRetentionCount(),
                freshSummaryComplete(validated.getPartitionSummary()));
    }

    private static String requestHash(
            Long mappingId, Long policyId, Integer policyVersion, Integer retentionCount) {
        StringBuilder value = new StringBuilder();
        append(value, mappingId);
        append(value, policyId);
        append(value, policyVersion);
        append(value, retentionCount);
        return TargetContractCanonicalizer.sha256(value.toString());
    }

    private static void append(StringBuilder value, Object field) {
        if (field == null) {
            value.append("-1:");
            return;
        }
        String text = String.valueOf(field);
        value.append(text.length()).append(':').append(text);
    }

    private LakeLifecycleValidateVO completed(
            LakeLifecycleValidateVO validated,
            LakeTableLifecycleBinding binding,
            Integer actualRetentionCount,
            DorisPartitionSummary summary) {
        if (summary == null) {
            throw conflict("Lifecycle partition observation is unavailable");
        }
        validated.setActualRetentionCount(actualRetentionCount);
        validated.setPartitionSummary(summary);
        validated.setObservedAt(summary.observedAt());
        validated.setValid(true);
        validated.setCode(LakeLifecycleValidationServiceImpl.VALID);
        validated.setReasons(List.of());
        LakeLifecycleBindingSnapshotVO snapshot = binding == null ? null : toSnapshot(binding);
        if (snapshot != null) {
            snapshot.setStatus(LakeLifecycleBindingStatus.ACTIVE);
            snapshot.setActualRetentionCount(actualRetentionCount);
            snapshot.setActualPartitionSummaryJson(
                    LakeLifecycleApplyPersistenceService.summaryJson(summary));
            snapshot.setLastObservedAt(java.util.Date.from(summary.observedAt()));
        }
        validated.setExistingBinding(snapshot);
        return validated;
    }

    private LakeTableLifecycleBinding readBinding(Long mappingId) {
        try {
            return lifecycleBindingDao.queryByTableMappingId(mappingId);
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle binding cannot be read");
        }
    }

    private LakeTableLifecycleBinding requireCurrentBinding(Long mappingId) {
        LakeTableLifecycleBinding binding = readBinding(mappingId);
        if (binding == null || !Objects.equals(binding.getTableMappingId(), mappingId)) {
            throw stale("Lifecycle binding result is no longer current");
        }
        return binding;
    }

    private static boolean freshSummaryComplete(DorisPartitionSummary summary) {
        if (summary == null || summary.unknown() != 0
                || summary.partitionNames().size() != summary.total()
                || summary.historicalNames().size() != summary.historical()
                || summary.currentNames().size() != summary.current()
                || summary.futureNames().size() != summary.future()) {
            return false;
        }
        return distinct(summary.partitionNames())
                && distinct(summary.historicalNames())
                && distinct(summary.currentNames())
                && distinct(summary.futureNames());
    }

    private static boolean distinct(List<String> values) {
        return values.stream().allMatch(Objects::nonNull)
                && values.stream().distinct().count() == values.size();
    }

    private static LakeLifecycleBindingSnapshotVO toSnapshot(
            LakeTableLifecycleBinding binding) {
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
        result.setLastObservedAt(binding.getLastObservedAt());
        result.setPolicySnapshotJson(binding.getPolicySnapshotJson());
        result.setStatus(binding.getStatus());
        result.setGeneration(binding.getGeneration());
        result.setLockVersion(binding.getLockVersion());
        result.setErrorCode(binding.getErrorCode());
        result.setCreateTime(binding.getCreateTime());
        result.setUpdateTime(binding.getUpdateTime());
        return result;
    }

    private static LakePartitionGranularity validatedGranularity(
            LakeLifecycleValidateVO validated) {
        if (validated.getGranularity() != null) {
            return validated.getGranularity();
        }
        return validated.getPolicySnapshot() == null
                ? null : validated.getPolicySnapshot().getGranularity();
    }

    private static void requireValidValidation(LakeLifecycleValidateVO validated) {
        if (validated == null) {
            throw conflict("Lifecycle validation returned no result");
        }
        if (!validated.isValid()) {
            String code = validated.getCode();
            if (LakeErrorCode.LAKE_DORIS_UNAVAILABLE.equals(code)) {
                throw new LakeServiceException(code, "Lake Doris is unavailable");
            }
            throw conflict("Lifecycle table is not eligible");
        }
    }

    private static void requireUpdatableBinding(LakeTableLifecycleBinding binding) {
        if (binding == null) {
            throw conflict("Lifecycle binding does not exist");
        }
        if (binding.getStatus() != LakeLifecycleBindingStatus.ACTIVE
                && binding.getStatus() != LakeLifecycleBindingStatus.ERROR) {
            throw stale("Lifecycle binding is not ready for update");
        }
        if (StringUtils.isNotBlank(binding.getOperationToken())) {
            throw stale("Lifecycle binding is currently being changed");
        }
    }

    private Integer requireCurrentUserId() {
        try {
            Integer userId = currentUserProvider.getCurrentUserId();
            if (userId == null || userId <= 0) {
                throw invalid("Authenticated user is required");
            }
            return userId;
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("Authenticated user is required");
        }
    }

    private void failAndThrow(
            LakeLifecycleApplyPersistenceService.OperationHandle handle,
            String errorCode,
            Integer userId) {
        try {
            persistenceService.finalizeFailure(handle, errorCode, userId);
        } catch (RuntimeException ignored) {
            // Preserve the stable external failure; a later retry can inspect
            // the still-leased binding and operation journal.
        }
        throw classified(errorCode);
    }

    private static ExternalFailure external(String code) {
        return new ExternalFailure(code);
    }

    private static LakeServiceException classified(String code) {
        String safe = code == null || code.isBlank()
                ? LakeErrorCode.LAKE_DORIS_UNAVAILABLE : code;
        if (LakeErrorCode.LAKE_DORIS_UNAVAILABLE.equals(safe)) {
            return new LakeServiceException(safe, "Lake Doris is unavailable");
        }
        if (LakeErrorCode.LAKE_OPERATION_STALE.equals(safe)) {
            return new LakeServiceException(safe, "Lifecycle operation is stale");
        }
        return new LakeServiceException(safe, "Lifecycle retention operation failed");
    }

    private static void validateApplyRequest(LakeLifecycleApplyDTO request) {
        if (request == null || !positive(request.getMappingId()) || !positive(request.getPolicyId())) {
            throw invalid("mappingId and policyId are required");
        }
    }

    private static void validateUpdateRequest(
            Long mappingId, LakeLifecycleRetentionUpdateDTO request) {
        if (!positive(mappingId) || request == null || !positive(request.getPolicyId())) {
            throw invalid("mappingId and policyId are required");
        }
    }

    private static boolean same(Object left, Object right) {
        return Objects.equals(left, right);
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

    private static LakeServiceException stale(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_OPERATION_STALE, message);
    }

    private record Observation(Integer actualRetentionCount, DorisPartitionSummary summary) {
    }

    private static final class ExternalFailure extends RuntimeException {

        private final String code;

        private ExternalFailure(String code) {
            super(null, null, false, false);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
