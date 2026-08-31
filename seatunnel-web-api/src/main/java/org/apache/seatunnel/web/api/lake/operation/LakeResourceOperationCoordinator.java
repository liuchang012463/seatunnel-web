package org.apache.seatunnel.web.api.lake.operation;

import org.apache.seatunnel.web.api.lake.LakeOperationLogRedactor;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationType;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeResourceOperation;
import org.apache.seatunnel.web.dao.repository.LakeResourceOperationDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Durable three-phase coordinator for Doris operations.
 *
 * <p>{@link #begin(LakeOperationIntent)} and the finalize/fail methods are
 * local transactions. {@link #execute(LakeOperationHandle, LakeExternalOperation)}
 * intentionally has no transaction annotation and is the only method that
 * invokes external work.</p>
 */
@Component
public class LakeResourceOperationCoordinator {

    private static final int MAX_SUMMARY_LENGTH = 2_000;
    private static final int MAX_ERROR_CODE_LENGTH = 128;

    private final LakeResourceOperationDao operationDao;
    private final LakeResourceGateway resourceGateway;
    private final LakeOperationTransactionBoundary transactionBoundary;
    private final Clock clock;
    private final Duration operationStaleAfter;

    /** Production constructor; local phases use an explicit REQUIRES_NEW boundary. */
    @Autowired
    public LakeResourceOperationCoordinator(
            LakeResourceOperationDao operationDao,
            LakeResourceGateway resourceGateway,
            LakeProperties properties,
            SpringLakeOperationTransactionBoundary transactionBoundary) {
        this(operationDao, resourceGateway, properties, transactionBoundary, Clock.systemUTC());
    }

    /** Injectable boundary constructor for integration tests and embedders. */
    public LakeResourceOperationCoordinator(
            LakeResourceOperationDao operationDao,
            LakeResourceGateway resourceGateway,
            LakeProperties properties,
            LakeOperationTransactionBoundary transactionBoundary) {
        this(operationDao, resourceGateway, properties, transactionBoundary, Clock.systemUTC());
    }

    /** Visible for deterministic stale-lease tests. */
    public LakeResourceOperationCoordinator(
            LakeResourceOperationDao operationDao,
            LakeResourceGateway resourceGateway,
            LakeProperties properties,
            Clock clock) {
        this(operationDao, resourceGateway, properties,
                new DirectLakeOperationTransactionBoundary(), clock);
    }

    /** Visible for deterministic tests with a real transaction manager. */
    public LakeResourceOperationCoordinator(
            LakeResourceOperationDao operationDao,
            LakeResourceGateway resourceGateway,
            LakeProperties properties,
            LakeOperationTransactionBoundary transactionBoundary,
            Clock clock) {
        this.operationDao = Objects.requireNonNull(operationDao, "operationDao");
        this.resourceGateway = Objects.requireNonNull(resourceGateway, "resourceGateway");
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.operationStaleAfter = positiveDuration(
                properties.getOperationStaleAfter(), Duration.ofMinutes(15));
    }

    /** TX1: write intent and lease the existing resource row, then commit. */
    public LakeOperationHandle begin(LakeOperationIntent intent) {
        return transactionBoundary.requiresNew(() -> beginInTransaction(intent));
    }

    private LakeOperationHandle beginInTransaction(LakeOperationIntent intent) {
        validateIntent(intent);
        LakeResourceState current = requiredState(intent.getResourceType(), intent.getResourceId());
        ensureExpectedState(intent, current);
        if (current.operationToken() != null) {
            // A live lease belongs to another operation.  Only the explicit
            // stale-takeover path may replace it.
            throw new LakeOperationException("Lake resource is already leased");
        }
        if (current.deleted() && !intent.isRebuild()) {
            throw new LakeOperationException("Lake resource is deleted");
        }

        int generation = current.generation();
        if (intent.isRebuild()) {
            generation = Math.addExact(generation, 1);
        }
        if (intent.getGeneration() != null && intent.getGeneration() != generation) {
            throw new LakeOperationException("Lake resource generation changed");
        }
        String token = newToken();
        LakeResourceStatus pendingStatus = pendingStatus(intent.getOperationType());
        LakeResourceOperation operation = newOperation(intent, generation, token);
        if (operationDao.insert(operation) <= 0) {
            throw new LakeOperationException("Lake operation intent could not be persisted");
        }

        if (!resourceGateway.claim(
                current, token, generation, pendingStatus)) {
            throw new LakeOperationException("Lake resource lease was lost");
        }
        return new LakeOperationHandle(
                operation.getId(), intent.getResourceType(), intent.getResourceId(),
                generation, token, current.lockVersion() + 1);
    }

    /**
     * External phase. No local transaction is open here; callers can safely
     * execute Doris DDL and read actual metadata.
     */
    public <T> LakeOperationExecution<T> execute(
            LakeOperationHandle handle, LakeExternalOperation<T> externalOperation) {
        requireHandle(handle);
        Objects.requireNonNull(externalOperation, "externalOperation");
        markRunning(handle);
        try {
            return new LakeOperationExecution<>(handle, externalOperation.execute());
        } catch (LakeExternalOperationException exception) {
            fail(handle, exception.getErrorCode(), exception.getMessage());
            throw new LakeOperationException("Lake external operation failed");
        } catch (Exception exception) {
            fail(handle, safeErrorCode("EXTERNAL_OPERATION_FAILED"), exception.getMessage());
            throw new LakeOperationException("Lake external operation failed");
        }
    }

    /** TX2: compare token/version and publish the verified actual state. */
    public boolean finalizeSuccess(LakeOperationHandle handle, String summary) {
        return transactionBoundary.requiresNew(() -> finalizeSuccessInTransaction(handle, summary));
    }

    private boolean finalizeSuccessInTransaction(LakeOperationHandle handle, String summary) {
        requireHandle(handle);
        LakeResourceOperation operation = currentOpenOperation(handle);
        if (operation == null) {
            markIgnored(handle, "STALE_OPERATION", "Stale operation result ignored");
            return false;
        }
        if (!isCurrent(handle)) {
            markIgnored(handle, "STALE_OPERATION", "Stale operation result ignored");
            return false;
        }
        String safeSummary = safeSummary(summary);
        if (!resourceGateway.finalizeSuccess(handle, safeSummary)) {
            markIgnored(handle, "STALE_OPERATION", "Stale operation result ignored");
            return false;
        }
        if (!operationDao.updateStatusIfToken(
                handle.operationId(), handle.operationToken(), operation.getStatus(),
                LakeOperationStatus.SUCCEEDED, null, safeSummary)) {
            throw new LakeOperationException("Lake operation journal finalize failed");
        }
        return true;
    }

    /** TX2 failure path; stale failures cannot mutate a newer generation. */
    public boolean fail(LakeOperationHandle handle, String errorCode, String summary) {
        return transactionBoundary.requiresNew(() -> failInTransaction(handle, errorCode, summary));
    }

    private boolean failInTransaction(LakeOperationHandle handle, String errorCode, String summary) {
        requireHandle(handle);
        LakeResourceOperation operation = currentOpenOperation(handle);
        if (operation == null) {
            markIgnored(handle, "STALE_OPERATION", "Stale operation failure ignored");
            return false;
        }
        if (!isCurrent(handle)) {
            markIgnored(handle, "STALE_OPERATION", "Stale operation failure ignored");
            return false;
        }
        String safeCode = safeErrorCode(errorCode);
        String safeSummary = safeSummary(summary);
        if (!resourceGateway.finalizeFailure(handle, safeCode, safeSummary)) {
            markIgnored(handle, "STALE_OPERATION", "Stale operation failure ignored");
            return false;
        }
        if (!operationDao.updateStatusIfToken(
                handle.operationId(), handle.operationToken(), operation.getStatus(),
                LakeOperationStatus.FAILED, safeCode, safeSummary)) {
            throw new LakeOperationException("Lake operation journal failure update failed");
        }
        return true;
    }

    public boolean finalizeFailure(LakeOperationHandle handle, String errorCode, String summary) {
        return fail(handle, errorCode, summary);
    }

    /** Runs external work and immediately performs the local finalize phase. */
    public <T> boolean executeAndFinalize(
            LakeOperationHandle handle,
            LakeExternalOperation<T> externalOperation,
            Function<T, String> summaryFactory) {
        LakeOperationExecution<T> execution = execute(handle, externalOperation);
        String summary = summaryFactory == null ? null : summaryFactory.apply(execution.externalResult());
        return finalizeSuccess(handle, summary);
    }

    /**
     * Explicit Retry takeover for a stale PENDING/RUNNING operation. The old
     * token and lock version are compared before a new token is installed.
     */
    public LakeOperationHandle takeOverStale(
            LakeOperationHandle staleHandle, LakeOperationIntent intent) {
        return transactionBoundary.requiresNew(() -> takeOverStaleInTransaction(staleHandle, intent));
    }

    private LakeOperationHandle takeOverStaleInTransaction(
            LakeOperationHandle staleHandle, LakeOperationIntent intent) {
        requireHandle(staleHandle);
        validateIntent(intent);
        if (!staleHandle.resourceType().equals(intent.getResourceType())
                || !staleHandle.resourceId().equals(intent.getResourceId())) {
            throw new LakeOperationException("Retry resource does not match operation");
        }
        LakeResourceOperation oldOperation = operationDao.queryByOperationToken(staleHandle.operationToken());
        if (oldOperation == null || !isOpen(oldOperation.getStatus()) || !isStale(oldOperation.getStartedAt())) {
            throw new LakeOperationException("Lake operation is not stale");
        }
        LakeResourceState current = requiredState(staleHandle.resourceType(), staleHandle.resourceId());
        if (!sameLease(current, staleHandle)) {
            throw new LakeOperationException("Lake operation lease was already replaced");
        }

        // Retry takes over the existing resource generation.  A rebuild has
        // already advanced the generation in its original begin() call; a
        // second increment would make late callbacks indistinguishable from
        // a new requested rebuild.
        int generation = current.generation();
        String token = newToken();
        if (!resourceGateway.takeOver(
                staleHandle, token, generation, pendingStatus(intent.getOperationType()))) {
            throw new LakeOperationException("Lake operation takeover compare failed");
        }
        LakeOperationIntent retryIntent = copyIntent(intent);
        retryIntent.setGeneration(generation);
        LakeResourceOperation operation = newOperation(retryIntent, generation, token);
        operation.setOperationType(LakeOperationType.RETRY);
        if (operationDao.insert(operation) <= 0) {
            throw new LakeOperationException("Lake retry intent could not be persisted");
        }
        operationDao.updateStatusIfToken(
                oldOperation.getId(), oldOperation.getOperationToken(), oldOperation.getStatus(),
                LakeOperationStatus.IGNORED, "REPLACED_BY_RETRY", "Stale operation replaced by explicit Retry");
        return new LakeOperationHandle(
                operation.getId(), intent.getResourceType(), intent.getResourceId(), generation,
                token, staleHandle.lockVersion() + 1);
    }

    /** Query API returns a defensive, secret-safe copy. */
    public LakeResourceOperation query(Long operationId) {
        return sanitize(operationId == null ? null : operationDao.queryById(operationId));
    }

    public LakeResourceOperation queryByToken(String operationToken) {
        return sanitize(operationToken == null ? null : operationDao.queryByOperationToken(operationToken));
    }

    public List<LakeResourceOperation> queryByResource(String resourceType, Long resourceId) {
        return operationDao.queryByResource(resourceType, resourceId).stream()
                .map(LakeResourceOperationCoordinator::sanitize)
                .toList();
    }

    private LakeResourceOperation newOperation(
            LakeOperationIntent intent, int generation, String token) {
        LakeResourceOperation operation = new LakeResourceOperation();
        operation.initInsert();
        operation.setResourceType(intent.getResourceType());
        operation.setResourceId(intent.getResourceId());
        operation.setGeneration(generation);
        operation.setOperationType(intent.getOperationType());
        operation.setOperationToken(token);
        operation.setRequestHash(intent.getRequestHash());
        operation.setStatus(LakeOperationStatus.PENDING);
        operation.setStartedAt(Date.from(clock.instant()));
        operation.setOperatorId(intent.getOperatorId());
        return operation;
    }

    private void ensureExpectedState(LakeOperationIntent intent, LakeResourceState current) {
        if (intent.getGeneration() != null && !intent.isRebuild()
                && !intent.getGeneration().equals(current.generation())) {
            throw new LakeOperationException("Lake resource generation changed");
        }
        if (intent.getLockVersion() != null && !intent.getLockVersion().equals(current.lockVersion())) {
            throw new LakeOperationException("Lake resource version changed");
        }
        if (intent.getOperationToken() != null
                && !Objects.equals(intent.getOperationToken(), current.operationToken())) {
            throw new LakeOperationException("Lake resource lease changed");
        }
    }

    private LakeResourceState requiredState(String resourceType, Long resourceId) {
        LakeResourceState state = resourceGateway.get(resourceType, resourceId);
        if (state == null) {
            throw new LakeOperationException("Lake resource does not exist");
        }
        return state;
    }

    private void ensureCurrent(LakeOperationHandle handle) {
        if (!isCurrent(handle)) {
            throw new LakeOperationException("Lake operation lease is stale");
        }
    }

    /** Marks RUNNING in its own committed transaction before external work. */
    private void markRunning(LakeOperationHandle handle) {
        transactionBoundary.requiresNew(() -> {
            requireHandle(handle);
            ensureCurrent(handle);
            LakeResourceOperation operation = operationDao.queryById(handle.operationId());
            if (operation == null
                    || !Objects.equals(operation.getOperationToken(), handle.operationToken())
                    || operation.getStatus() != LakeOperationStatus.PENDING
                    || !operationDao.updateStatusIfToken(
                    handle.operationId(), handle.operationToken(), LakeOperationStatus.PENDING,
                    LakeOperationStatus.RUNNING, null, null)) {
                throw new LakeOperationException("Lake operation is no longer active");
            }
            return null;
        });
    }

    private boolean isCurrent(LakeOperationHandle handle) {
        return sameLease(resourceGateway.get(handle.resourceType(), handle.resourceId()), handle);
    }

    private static boolean sameLease(LakeResourceState state, LakeOperationHandle handle) {
        return state != null
                && Objects.equals(state.resourceType(), handle.resourceType())
                && Objects.equals(state.resourceId(), handle.resourceId())
                && Objects.equals(state.generation(), handle.generation())
                && Objects.equals(state.lockVersion(), handle.lockVersion())
                && Objects.equals(state.operationToken(), handle.operationToken());
    }

    private boolean isStale(Date startedAt) {
        return startedAt != null
                && startedAt.toInstant().plus(operationStaleAfter).isBefore(clock.instant());
    }

    private static boolean isOpen(LakeOperationStatus status) {
        return status == LakeOperationStatus.PENDING || status == LakeOperationStatus.RUNNING;
    }

    private static LakeResourceStatus pendingStatus(LakeOperationType operationType) {
        return switch (operationType) {
            case DROP_DATABASE, DROP_TABLE, DROP_CATALOG -> LakeResourceStatus.DELETING;
            default -> LakeResourceStatus.CREATING;
        };
    }

    private static LakeOperationIntent copyIntent(LakeOperationIntent intent) {
        LakeOperationIntent copy = new LakeOperationIntent(
                intent.getResourceType(), intent.getResourceId(), intent.getOperationType(),
                intent.getRequestHash(), intent.getOperatorId());
        copy.setLockVersion(intent.getLockVersion());
        copy.setOperationToken(intent.getOperationToken());
        copy.setRebuild(intent.isRebuild());
        return copy;
    }

    private static void validateIntent(LakeOperationIntent intent) {
        if (intent == null || intent.getResourceType() == null || intent.getResourceType().isBlank()
                || intent.getResourceId() == null || intent.getResourceId() <= 0
                || intent.getOperationType() == null) {
            throw new LakeOperationException("Lake operation intent is invalid");
        }
        if (intent.getRequestHash() != null && intent.getRequestHash().length() > 64) {
            throw new LakeOperationException("Lake operation request hash is invalid");
        }
    }

    private static void requireHandle(LakeOperationHandle handle) {
        if (handle == null) {
            throw new LakeOperationException("Lake operation handle is invalid");
        }
    }

    private static String newToken() {
        return UUID.randomUUID().toString();
    }

    private static Duration positiveDuration(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private static String safeErrorCode(String errorCode) {
        String safe = LakeOperationLogRedactor.summary(errorCode);
        if (safe == null || safe.isBlank()) {
            return "LAKE_OPERATION_FAILED";
        }
        return safe.substring(0, Math.min(MAX_ERROR_CODE_LENGTH, safe.length()));
    }

    private static String safeSummary(String summary) {
        String safe = LakeOperationLogRedactor.summary(summary);
        if (safe == null) {
            return null;
        }
        return safe.substring(0, Math.min(MAX_SUMMARY_LENGTH, safe.length()));
    }

    private boolean markIgnored(LakeOperationHandle handle, String code, String summary) {
        LakeResourceOperation operation = operationDao.queryById(handle.operationId());
        if (operation == null
                || !Objects.equals(operation.getOperationToken(), handle.operationToken())
                || !isOpen(operation.getStatus())) {
            return false;
        }
        return operationDao.updateStatusIfToken(
                handle.operationId(), handle.operationToken(), operation.getStatus(), LakeOperationStatus.IGNORED,
                safeErrorCode(code), safeSummary(summary));
    }

    private LakeResourceOperation currentOpenOperation(LakeOperationHandle handle) {
        LakeResourceOperation operation = operationDao.queryById(handle.operationId());
        return operation != null
                && Objects.equals(operation.getOperationToken(), handle.operationToken())
                && isOpen(operation.getStatus()) ? operation : null;
    }

    private static LakeResourceOperation sanitize(LakeResourceOperation source) {
        if (source == null) {
            return null;
        }
        LakeResourceOperation copy = new LakeResourceOperation();
        copy.setId(source.getId());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        copy.setResourceType(source.getResourceType());
        copy.setResourceId(source.getResourceId());
        copy.setGeneration(source.getGeneration());
        copy.setOperationType(source.getOperationType());
        copy.setOperationToken(source.getOperationToken());
        copy.setRequestHash(source.getRequestHash());
        copy.setStatus(source.getStatus());
        copy.setStartedAt(source.getStartedAt());
        copy.setFinishedAt(source.getFinishedAt());
        copy.setErrorCode(safeErrorCode(source.getErrorCode()));
        copy.setErrorSummary(safeSummary(source.getErrorSummary()));
        copy.setOperatorId(source.getOperatorId());
        return copy;
    }
}
