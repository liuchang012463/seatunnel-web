package org.apache.seatunnel.web.api.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeOperationLogRedactor;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionSummary;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationTransactionBoundary;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceTypes;
import org.apache.seatunnel.web.api.lake.operation.SpringLakeOperationTransactionBoundary;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationType;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeResourceOperation;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.repository.LakeLifecyclePolicyDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeResourceOperationDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Local persistence phases for lifecycle retention operations.
 *
 * <p>Every public method enters a short {@code REQUIRES_NEW} transaction via
 * {@link LakeOperationTransactionBoundary}.  No Doris client is referenced by
 * this class, which makes it impossible for a network call to accidentally be
 * held open by TX1 or TX2.</p>
 */
@Service
public class LakeLifecycleApplyPersistenceService {

    public static final String RESOURCE_TYPE = LakeResourceTypes.TABLE_LIFECYCLE;
    private static final String STALE_CODE = "LAKE_OPERATION_STALE";
    private static final String FAILURE_CODE = "LAKE_LIFECYCLE_OPERATION_FAILED";
    private static final String FAILURE_MESSAGE = "Lifecycle retention operation failed";
    private static final String STALE_MESSAGE = "Stale lifecycle operation result ignored";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LakeOdsTableMappingDao tableMappingDao;
    private final LakeLifecyclePolicyDao policyDao;
    private final LakeTableLifecycleBindingDao lifecycleBindingDao;
    private final LakeResourceOperationDao operationDao;
    private final LakeOperationTransactionBoundary transactionBoundary;
    private final Clock clock;

    @Autowired
    public LakeLifecycleApplyPersistenceService(
            LakeOdsTableMappingDao tableMappingDao,
            LakeLifecyclePolicyDao policyDao,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            LakeResourceOperationDao operationDao,
            SpringLakeOperationTransactionBoundary transactionBoundary) {
        this(tableMappingDao, policyDao, lifecycleBindingDao, operationDao,
                transactionBoundary, Clock.systemUTC());
    }

    /** Visible for deterministic unit tests with an explicit transaction boundary. */
    public LakeLifecycleApplyPersistenceService(
            LakeOdsTableMappingDao tableMappingDao,
            LakeLifecyclePolicyDao policyDao,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            LakeResourceOperationDao operationDao,
            LakeOperationTransactionBoundary transactionBoundary,
            Clock clock) {
        this.tableMappingDao = Objects.requireNonNull(tableMappingDao, "tableMappingDao");
        this.policyDao = Objects.requireNonNull(policyDao, "policyDao");
        this.lifecycleBindingDao = Objects.requireNonNull(
                lifecycleBindingDao, "lifecycleBindingDao");
        this.operationDao = Objects.requireNonNull(operationDao, "operationDao");
        this.transactionBoundary = Objects.requireNonNull(
                transactionBoundary, "transactionBoundary");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * TX1: re-read all local identities, reserve a unique operation token, and
     * publish a PENDING lifecycle binding.  An exact active binding is returned
     * as an idempotent result without creating an operation or touching Doris.
     */
    public StartResult start(StartRequest request) {
        try {
            return transactionBoundary.requiresNew(() -> startInTransaction(request));
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle operation could not be prepared");
        }
    }

    /** TX2 success: publish verified actual state only if the lease is current. */
    public boolean finalizeSuccess(
            OperationHandle handle,
            Integer actualRetentionCount,
            DorisPartitionSummary summary,
            Integer userId) {
        if (!positive(actualRetentionCount) || summary == null || userId == null || userId <= 0) {
            throw invalid("Lifecycle operation result is invalid");
        }
        String summaryJson = summaryJson(summary);
        return transactionBoundary.requiresNew(() -> finalizeSuccessInTransaction(
                handle, actualRetentionCount, summaryJson, summary.observedAt(), userId));
    }

    /** TX2 failure: move the current lease to ERROR without retaining secrets. */
    public boolean finalizeFailure(OperationHandle handle, String errorCode, Integer userId) {
        if (userId == null || userId <= 0) {
            throw invalid("Authenticated user is required");
        }
        String safeCode = safeErrorCode(errorCode);
        return transactionBoundary.requiresNew(
                () -> finalizeFailureInTransaction(handle, safeCode, userId));
    }

    private StartResult startInTransaction(StartRequest request) {
        validateRequest(request);
        LakeOdsTableMapping mapping = readActiveMapping(request.mappingId());
        verifyMapping(mapping, request);
        LakeLifecyclePolicy policy = readActivePolicy(request);
        verifyPolicy(policy, request);
        LakeTableLifecycleBinding existing = lifecycleBindingDao
                .queryByTableMappingId(request.mappingId());

        if (request.apply()) {
            if (existing != null) {
                if (isExactActiveCandidate(existing, request)) {
                    return new StartResult(null, existing, true);
                }
                // Applying an already-bound policy is allowed to repair a
                // stale Doris observation, but only when the frozen binding
                // identity is exactly the requested identity.  A different
                // desired policy must use the explicit update endpoint.
                if (isSameDesiredBinding(existing, request)) {
                    requireAvailableBinding(existing);
                    return updatePending(mapping, existing, request);
                }
                throw conflict("Lifecycle binding already exists with another desired state");
            }
            return insertPending(mapping, request);
        }

        if (existing == null) {
            throw conflict("Lifecycle binding does not exist");
        }
        verifyExpectedBinding(existing, request);
        return updatePending(mapping, existing, request);
    }

    private StartResult insertPending(LakeOdsTableMapping mapping, StartRequest request) {
        String operationToken = newToken();
        LakeTableLifecycleBinding binding = new LakeTableLifecycleBinding();
        binding.initInsert();
        binding.setTableMappingId(mapping.getId());
        binding.setPolicyId(request.policyId());
        binding.setPolicyVersion(request.policyVersion());
        binding.setPartitionColumn(request.partitionColumn());
        binding.setGranularity(request.granularity());
        binding.setRetentionCount(request.retentionCount());
        binding.setActualRetentionCount(null);
        binding.setActualPartitionSummaryJson(null);
        binding.setLastObservedAt(null);
        binding.setPolicySnapshotJson(policySnapshot(request));
        binding.setStatus(LakeLifecycleBindingStatus.PENDING);
        binding.setOperationToken(operationToken);
        binding.setGeneration(1);
        binding.setLockVersion(1);
        binding.setErrorCode(null);
        binding.setErrorMessage(null);
        binding.setCreateUserId(request.operatorId());
        binding.setUpdateUserId(request.operatorId());
        // Persist the binding first so the operation journal always receives
        // the durable lifecycle binding id as resourceId.  Both writes are
        // enclosed by the same REQUIRES_NEW transaction and therefore roll
        // back together if either write fails.
        try {
            if (lifecycleBindingDao.insert(binding) <= 0) {
                throw conflict("Lifecycle binding could not be persisted");
            }
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle binding concurrently changed");
        }
        LakeResourceOperation operation = newOperation(
                binding.getId(), binding.getGeneration(), request, operationToken);
        if (operationDao.insert(operation) <= 0) {
            throw conflict("Lifecycle operation could not be persisted");
        }
        return new StartResult(
                handle(mapping, binding, operation, operationToken, request.operatorId()),
                binding, false);
    }

    private StartResult updatePending(
            LakeOdsTableMapping mapping,
            LakeTableLifecycleBinding binding,
            StartRequest request) {
        Integer expectedLockVersion = binding.getLockVersion();
        if (!positive(expectedLockVersion)) {
            throw conflict("Lifecycle binding version is unavailable");
        }
        String operationToken = newToken();
        LakeResourceOperation operation = newOperation(
                binding.getId(), binding.getGeneration(), request, operationToken);
        if (operationDao.insert(operation) <= 0) {
            throw conflict("Lifecycle operation could not be persisted");
        }

        binding.setPolicyId(request.policyId());
        binding.setPolicyVersion(request.policyVersion());
        binding.setPartitionColumn(request.partitionColumn());
        binding.setGranularity(request.granularity());
        binding.setRetentionCount(request.retentionCount());
        binding.setPolicySnapshotJson(policySnapshot(request));
        binding.setStatus(LakeLifecycleBindingStatus.PENDING);
        binding.setOperationToken(operationToken);
        binding.setErrorCode(null);
        binding.setErrorMessage(null);
        binding.setUpdateUserId(request.operatorId());
        binding.setUpdateTime(Date.from(clock.instant()));
        if (!lifecycleBindingDao.updateIfTokenAndVersion(
                binding, null, expectedLockVersion)) {
            throw conflict("Lifecycle binding concurrently changed");
        }
        return new StartResult(
                handle(mapping, binding, operation, operationToken, request.operatorId()),
                binding, false);
    }

    private boolean finalizeSuccessInTransaction(
            OperationHandle handle,
            Integer actualRetentionCount,
            String summaryJson,
            java.time.Instant observedAt,
            Integer userId) {
        LakeResourceOperation operation = currentOperation(handle);
        LakeTableLifecycleBinding binding = currentBinding(handle);
        if (operation == null || binding == null) {
            markIgnored(handle);
            return false;
        }
        binding.setActualRetentionCount(actualRetentionCount);
        binding.setActualPartitionSummaryJson(summaryJson);
        binding.setLastObservedAt(Date.from(observedAt));
        binding.setStatus(LakeLifecycleBindingStatus.ACTIVE);
        binding.setOperationToken(null);
        binding.setErrorCode(null);
        binding.setErrorMessage(null);
        binding.setUpdateUserId(userId);
        binding.setUpdateTime(Date.from(clock.instant()));
        if (!lifecycleBindingDao.updateIfTokenAndVersion(
                binding, handle.operationToken(), handle.bindingLockVersion())) {
            markIgnored(handle);
            return false;
        }
        if (!operationDao.updateStatusIfToken(
                operation.getId(), handle.operationToken(), LakeOperationStatus.PENDING,
                LakeOperationStatus.SUCCEEDED, null, "Lifecycle retention verified")) {
            throw conflict("Lifecycle operation journal could not be finalized");
        }
        return true;
    }

    private boolean finalizeFailureInTransaction(
            OperationHandle handle, String errorCode, Integer userId) {
        LakeResourceOperation operation = currentOperation(handle);
        LakeTableLifecycleBinding binding = currentBinding(handle);
        if (operation == null || binding == null) {
            markIgnored(handle);
            return false;
        }
        binding.setStatus(LakeLifecycleBindingStatus.ERROR);
        binding.setOperationToken(null);
        binding.setErrorCode(errorCode);
        binding.setErrorMessage(FAILURE_MESSAGE);
        binding.setUpdateUserId(userId);
        binding.setUpdateTime(Date.from(clock.instant()));
        if (!lifecycleBindingDao.updateIfTokenAndVersion(
                binding, handle.operationToken(), handle.bindingLockVersion())) {
            markIgnored(handle);
            return false;
        }
        if (!operationDao.updateStatusIfToken(
                operation.getId(), handle.operationToken(), LakeOperationStatus.PENDING,
                LakeOperationStatus.FAILED, errorCode, FAILURE_MESSAGE)) {
            throw conflict("Lifecycle operation journal could not be finalized");
        }
        return true;
    }

    private LakeResourceOperation currentOperation(OperationHandle handle) {
        if (handle == null || !positive(handle.operationId())
                || StringUtils.isBlank(handle.operationToken())) {
            return null;
        }
        LakeResourceOperation operation = operationDao.queryById(handle.operationId());
        return operation != null
                && Objects.equals(operation.getOperationToken(), handle.operationToken())
                && Objects.equals(operation.getGeneration(), handle.bindingGeneration())
                && operation.getStatus() == LakeOperationStatus.PENDING ? operation : null;
    }

    private LakeTableLifecycleBinding currentBinding(OperationHandle handle) {
        if (handle == null || !positive(handle.bindingId())
                || !positive(handle.mappingId()) || !positive(handle.bindingGeneration())
                || !positive(handle.bindingLockVersion())) {
            return null;
        }
        LakeTableLifecycleBinding binding = lifecycleBindingDao.queryById(handle.bindingId());
        return binding != null
                && Objects.equals(binding.getTableMappingId(), handle.mappingId())
                && Objects.equals(binding.getGeneration(), handle.bindingGeneration())
                && Objects.equals(binding.getLockVersion(), handle.bindingLockVersion())
                && Objects.equals(binding.getOperationToken(), handle.operationToken())
                && binding.getStatus() == LakeLifecycleBindingStatus.PENDING ? binding : null;
    }

    private void markIgnored(OperationHandle handle) {
        if (handle == null || !positive(handle.operationId())
                || StringUtils.isBlank(handle.operationToken())) {
            return;
        }
        operationDao.updateStatusIfToken(
                handle.operationId(), handle.operationToken(), LakeOperationStatus.PENDING,
                LakeOperationStatus.IGNORED, STALE_CODE, STALE_MESSAGE);
    }

    private LakeResourceOperation newOperation(
            Long bindingId,
            Integer bindingGeneration,
            StartRequest request,
            String operationToken) {
        LakeResourceOperation operation = new LakeResourceOperation();
        operation.initInsert();
        operation.setResourceType(RESOURCE_TYPE);
        operation.setResourceId(bindingId);
        operation.setGeneration(bindingGeneration);
        operation.setOperationType(LakeOperationType.ALTER_RETENTION);
        operation.setOperationToken(operationToken);
        operation.setRequestHash(request.requestHash());
        operation.setStatus(LakeOperationStatus.PENDING);
        operation.setStartedAt(Date.from(clock.instant()));
        operation.setOperatorId(request.operatorId());
        return operation;
    }

    private static OperationHandle handle(
            LakeOdsTableMapping mapping,
            LakeTableLifecycleBinding binding,
            LakeResourceOperation operation,
            String operationToken,
            Integer operatorId) {
        return new OperationHandle(
                operation.getId(), mapping.getId(), mapping.getGeneration(),
                binding.getId(), binding.getGeneration(), binding.getLockVersion(),
                operationToken, operatorId);
    }

    private LakeOdsTableMapping readActiveMapping(Long mappingId) {
        LakeOdsTableMapping mapping = tableMappingDao.queryActiveById(mappingId);
        if (mapping == null) {
            mapping = tableMappingDao.queryByIdIncludingDeleted(mappingId);
        }
        if (mapping == null || Boolean.TRUE.equals(mapping.getDeleted())) {
            throw conflict("Lake table mapping does not exist");
        }
        return mapping;
    }

    private LakeLifecyclePolicy readActivePolicy(StartRequest request) {
        LakeLifecyclePolicy policy = policyDao.queryById(request.policyId());
        if (policy == null) {
            throw conflict("Lifecycle policy does not exist");
        }
        return policy;
    }

    private static void verifyMapping(LakeOdsTableMapping mapping, StartRequest request) {
        if (mapping.getManagementLevel() != LakeManagementLevel.MANAGED
                || mapping.getResourceStatus() != LakeResourceStatus.READY
                || StringUtils.isNotBlank(mapping.getOperationToken())
                || !Objects.equals(mapping.getGeneration(), request.mappingGeneration())
                || !Objects.equals(mapping.getLockVersion(), request.mappingLockVersion())) {
            throw stale("Lake table mapping changed before lifecycle operation");
        }
    }

    private static void verifyPolicy(LakeLifecyclePolicy policy, StartRequest request) {
        if (policy.getStatus() != LakeLifecyclePolicyStatus.ACTIVE
                || !Objects.equals(policy.getVersion(), request.policyVersion())
                || policy.getGranularity() != request.granularity()
                || !Objects.equals(policy.getRetentionCount(), request.retentionCount())) {
            throw conflict("Lifecycle policy changed before operation");
        }
    }

    private static void verifyExpectedBinding(
            LakeTableLifecycleBinding binding, StartRequest request) {
        if (binding.getStatus() != LakeLifecycleBindingStatus.ACTIVE
                && binding.getStatus() != LakeLifecycleBindingStatus.ERROR) {
            throw stale("Lifecycle binding is not ready for update");
        }
        if (StringUtils.isNotBlank(binding.getOperationToken())
                || !Objects.equals(binding.getId(), request.expectedBindingId())
                || !Objects.equals(binding.getGeneration(), request.expectedBindingGeneration())
                || !Objects.equals(binding.getLockVersion(), request.expectedBindingLockVersion())
                || request.expectedCurrentRetentionCount() != null
                && !Objects.equals(binding.getRetentionCount(), request.expectedCurrentRetentionCount())) {
            throw stale("Lifecycle binding changed before operation");
        }
    }

    private static boolean isExactActiveCandidate(
            LakeTableLifecycleBinding binding, StartRequest request) {
        return binding.getStatus() == LakeLifecycleBindingStatus.ACTIVE
                && StringUtils.isBlank(binding.getOperationToken())
                && Objects.equals(binding.getTableMappingId(), request.mappingId())
                && Objects.equals(binding.getPolicyId(), request.policyId())
                && Objects.equals(binding.getPolicyVersion(), request.policyVersion())
                && Objects.equals(binding.getPartitionColumn(), request.partitionColumn())
                && binding.getGranularity() == request.granularity()
                && Objects.equals(binding.getRetentionCount(), request.retentionCount())
                && Objects.equals(binding.getActualRetentionCount(), request.retentionCount())
                && StringUtils.isNotBlank(binding.getPolicySnapshotJson())
                && binding.getPolicySnapshotJson().equals(policySnapshot(request))
                && StringUtils.isNotBlank(binding.getActualPartitionSummaryJson())
                && binding.getLastObservedAt() != null
                // Cached desired state is not enough for apply idempotency.
                // The coordinator supplies the result of its fresh Doris
                // read-through observation in these two fields.
                && Objects.equals(binding.getRetentionCount(),
                        request.freshActualRetentionCount())
                && request.freshSummaryComplete();
    }

    private static boolean isSameDesiredBinding(
            LakeTableLifecycleBinding binding, StartRequest request) {
        return Objects.equals(binding.getTableMappingId(), request.mappingId())
                && Objects.equals(binding.getPolicyId(), request.policyId())
                && Objects.equals(binding.getPolicyVersion(), request.policyVersion())
                && Objects.equals(binding.getPartitionColumn(), request.partitionColumn())
                && binding.getGranularity() == request.granularity()
                && Objects.equals(binding.getRetentionCount(), request.retentionCount())
                && StringUtils.isNotBlank(binding.getPolicySnapshotJson())
                && binding.getPolicySnapshotJson().equals(policySnapshot(request));
    }

    private static void requireAvailableBinding(LakeTableLifecycleBinding binding) {
        if (binding.getStatus() != LakeLifecycleBindingStatus.ACTIVE
                && binding.getStatus() != LakeLifecycleBindingStatus.ERROR) {
            throw stale("Lifecycle binding is not ready for operation");
        }
        if (StringUtils.isNotBlank(binding.getOperationToken())
                || !positive(binding.getGeneration())
                || !positive(binding.getLockVersion())) {
            throw stale("Lifecycle binding is currently being changed");
        }
    }

    private static String policySnapshot(StartRequest request) {
        try {
            return MAPPER.writeValueAsString(new PolicySnapshot(
                    request.policyId(), request.policyVersion(), request.granularity().name(),
                    request.retentionCount()));
        } catch (JsonProcessingException exception) {
            throw invalid("Lifecycle policy snapshot is invalid");
        }
    }

    static String summaryJson(DorisPartitionSummary summary) {
        try {
            return MAPPER.writeValueAsString(new CachedSummary(
                    summary.total(), summary.historical(), summary.current(), summary.future(),
                    summary.unknown(), summary.partitionNames(), summary.observedAt().toString(),
                    summary.historicalNames(), summary.currentNames(), summary.futureNames(),
                    summary.unknownNames()));
        } catch (JsonProcessingException exception) {
            throw invalid("Lifecycle partition summary is invalid");
        }
    }

    private static void validateRequest(StartRequest request) {
        if (request == null || !positive(request.mappingId()) || !positive(request.policyId())
                || !positive(request.policyVersion()) || StringUtils.isBlank(request.partitionColumn())
                || request.granularity() == null || !positive(request.retentionCount())
                || !positive(request.mappingGeneration()) || !positive(request.mappingLockVersion())
                || !positive(request.operatorId())
                || request.requestHash() != null && request.requestHash().length() > 64) {
            throw invalid("Lifecycle operation request is invalid");
        }
        if (!request.apply()
                && (!positive(request.expectedBindingId())
                || !positive(request.expectedBindingGeneration())
                || !positive(request.expectedBindingLockVersion()))) {
            throw invalid("Lifecycle binding identity is required");
        }
    }

    private static String newToken() {
        return UUID.randomUUID().toString();
    }

    private static String safeErrorCode(String errorCode) {
        String safe = LakeOperationLogRedactor.summary(errorCode);
        if (safe == null || !safe.matches("[A-Z][A-Z0-9_]{0,127}")) {
            return FAILURE_CODE;
        }
        return safe;
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

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    public record StartRequest(
            Long mappingId,
            Long policyId,
            Integer policyVersion,
            String partitionColumn,
            LakePartitionGranularity granularity,
            Integer retentionCount,
            Integer mappingGeneration,
            Integer mappingLockVersion,
            Long expectedBindingId,
            Integer expectedBindingGeneration,
            Integer expectedBindingLockVersion,
            Integer expectedCurrentRetentionCount,
            String requestHash,
            Integer operatorId,
            boolean apply,
            Integer freshActualRetentionCount,
            boolean freshSummaryComplete) {
        public StartRequest(
                Long mappingId,
                Long policyId,
                Integer policyVersion,
                String partitionColumn,
                LakePartitionGranularity granularity,
                Integer retentionCount,
                Integer mappingGeneration,
                Integer mappingLockVersion,
                Long expectedBindingId,
                Integer expectedBindingGeneration,
                Integer expectedBindingLockVersion,
                Integer expectedCurrentRetentionCount,
                String requestHash,
                Integer operatorId,
                boolean apply) {
            this(mappingId, policyId, policyVersion, partitionColumn, granularity,
                    retentionCount, mappingGeneration, mappingLockVersion,
                    expectedBindingId, expectedBindingGeneration,
                    expectedBindingLockVersion, expectedCurrentRetentionCount,
                    requestHash, operatorId, apply, null, false);
        }

        public StartRequest withFreshObservation(
                Integer actualRetentionCount, boolean summaryComplete) {
            return new StartRequest(
                    mappingId, policyId, policyVersion, partitionColumn, granularity,
                    retentionCount, mappingGeneration, mappingLockVersion,
                    expectedBindingId, expectedBindingGeneration,
                    expectedBindingLockVersion, expectedCurrentRetentionCount,
                    requestHash, operatorId, apply, actualRetentionCount,
                    summaryComplete);
        }
    }

    public record OperationHandle(
            Long operationId,
            Long mappingId,
            Integer mappingGeneration,
            Long bindingId,
            Integer bindingGeneration,
            Integer bindingLockVersion,
            String operationToken,
            Integer operatorId) {
    }

    public record StartResult(
            OperationHandle handle,
            LakeTableLifecycleBinding binding,
            boolean idempotent) {
    }

    private record PolicySnapshot(
            Long policyId, Integer version, String granularity, Integer retentionCount) {
    }

    private record CachedSummary(
            int total,
            int historical,
            int current,
            int future,
            int unknown,
            List<String> partitionNames,
            String observedAt,
            List<String> historicalPartitionNames,
            List<String> currentPartitionNames,
            List<String> futurePartitionNames,
            List<String> unknownPartitionNames) {
    }
}
