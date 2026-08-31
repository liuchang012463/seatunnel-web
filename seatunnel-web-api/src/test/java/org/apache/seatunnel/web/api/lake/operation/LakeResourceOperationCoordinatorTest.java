package org.apache.seatunnel.web.api.lake.operation;

import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationType;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeResourceOperation;
import org.apache.seatunnel.web.dao.repository.LakeResourceOperationDao;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeResourceOperationCoordinatorTest {

    @Test
    void externalPhaseRunsAfterIntentAndFinalizeAdvancesVersion() {
        FakeJournal journal = new FakeJournal();
        FakeResourceGateway resources = new FakeResourceGateway();
        LakeResourceOperationCoordinator coordinator = coordinator(journal, resources, Instant.parse("2026-01-01T00:00:00Z"));
        LakeOperationHandle handle = coordinator.begin(intent(LakeOperationType.CREATE_TABLE));
        assertEquals(2, resources.state.lockVersion());
        AtomicBoolean called = new AtomicBoolean();

        LakeOperationExecution<String> execution = coordinator.execute(handle, () -> {
            called.set(true);
            return "actual matches desired";
        });
        assertTrue(called.get());
        assertEquals("actual matches desired", execution.externalResult());
        assertTrue(coordinator.finalizeSuccess(handle, "safe result"));
        assertEquals(LakeResourceStatus.READY, resources.state.status());
        assertEquals(3, resources.state.lockVersion());
        assertEquals(LakeOperationStatus.SUCCEEDED, journal.byToken(handle.operationToken()).getStatus());
    }

    @Test
    void beginCannotStealAnExistingLease() {
        FakeJournal journal = new FakeJournal();
        FakeResourceGateway resources = new FakeResourceGateway();
        resources.state = new LakeResourceState(
                "TABLE", 10L, 1, 1, "owned-by-other", LakeResourceStatus.CREATING, false);
        LakeResourceOperationCoordinator coordinator = coordinator(
                journal, resources, Instant.parse("2026-01-01T00:00:00Z"));

        org.junit.jupiter.api.Assertions.assertThrows(
                LakeOperationException.class,
                () -> coordinator.begin(intent(LakeOperationType.CREATE_TABLE)));
        assertTrue(journal.records.isEmpty());
        assertEquals("owned-by-other", resources.state.operationToken());
    }

    @Test
    void oldCallbackCannotOverwriteNewLeaseAfterStaleTakeover() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        FakeJournal journal = new FakeJournal();
        FakeResourceGateway resources = new FakeResourceGateway();
        LakeResourceOperationCoordinator first = coordinator(journal, resources, start);
        LakeOperationHandle oldHandle = first.begin(intent(LakeOperationType.CREATE_TABLE));

        LakeProperties properties = new LakeProperties();
        properties.setOperationStaleAfter(Duration.ofMinutes(1));
        LakeResourceOperationCoordinator retryCoordinator = new LakeResourceOperationCoordinator(
                journal, resources, properties, Clock.fixed(start.plus(Duration.ofMinutes(2)), ZoneOffset.UTC));
        LakeOperationIntent retryIntent = intent(LakeOperationType.CREATE_TABLE);
        retryIntent.setRebuild(true);
        LakeOperationHandle newHandle = retryCoordinator.takeOverStale(oldHandle, retryIntent);
        assertNotEquals(oldHandle.operationToken(), newHandle.operationToken());
        assertEquals(oldHandle.generation(), newHandle.generation());
        assertEquals(3, resources.state.lockVersion());

        assertFalse(first.finalizeSuccess(oldHandle, "old callback"));
        assertEquals(newHandle.operationToken(), resources.state.operationToken());
        assertEquals(3, resources.state.lockVersion());
        assertEquals(LakeOperationStatus.IGNORED, journal.byToken(oldHandle.operationToken()).getStatus());
    }

    @Test
    void externalSuccessDoesNotPublishWhenLocalCasFinalizeFails() {
        FakeJournal journal = new FakeJournal();
        FakeResourceGateway resources = new FakeResourceGateway();
        resources.failFinalize = true;
        LakeResourceOperationCoordinator coordinator = coordinator(journal, resources,
                Instant.parse("2026-01-01T00:00:00Z"));
        LakeOperationHandle handle = coordinator.begin(intent(LakeOperationType.CREATE_TABLE));
        AtomicBoolean called = new AtomicBoolean();
        coordinator.execute(handle, () -> {
            called.set(true);
            return "external success";
        });
        assertTrue(called.get());
        assertFalse(coordinator.finalizeSuccess(handle, "external success"));
        assertEquals(LakeResourceStatus.CREATING, resources.state.status());
        assertEquals(2, resources.state.lockVersion());
    }

    @Test
    void repeatedFinalizeCannotRewriteTerminalJournalState() {
        FakeJournal journal = new FakeJournal();
        FakeResourceGateway resources = new FakeResourceGateway();
        LakeResourceOperationCoordinator coordinator = coordinator(
                journal, resources, Instant.parse("2026-01-01T00:00:00Z"));
        LakeOperationHandle handle = coordinator.begin(intent(LakeOperationType.CREATE_TABLE));
        coordinator.execute(handle, () -> "actual");
        assertTrue(coordinator.finalizeSuccess(handle, "first"));
        assertFalse(coordinator.finalizeSuccess(handle, "late callback"));
        assertEquals(LakeOperationStatus.SUCCEEDED, journal.byToken(handle.operationToken()).getStatus());
        assertEquals(3, resources.state.lockVersion());
    }

    @Test
    void externalCallbackRunsOutsideEveryLocalTransactionBoundary() {
        FakeJournal journal = new FakeJournal();
        FakeResourceGateway resources = new FakeResourceGateway();
        RecordingBoundary boundary = new RecordingBoundary();
        LakeResourceOperationCoordinator coordinator = new LakeResourceOperationCoordinator(
                journal, resources, new LakeProperties(), boundary,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        LakeOperationHandle handle = coordinator.begin(intent(LakeOperationType.CREATE_TABLE));

        coordinator.execute(handle, () -> {
            assertFalse(boundary.inTransaction);
            return "external result";
        });
        assertEquals(2, boundary.transactionCount);
    }

    private static LakeResourceOperationCoordinator coordinator(
            FakeJournal journal, FakeResourceGateway resources, Instant now) {
        LakeProperties properties = new LakeProperties();
        properties.setOperationStaleAfter(Duration.ofMinutes(1));
        return new LakeResourceOperationCoordinator(
                journal, resources, properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static LakeOperationIntent intent(LakeOperationType operationType) {
        return new LakeOperationIntent("TABLE", 10L, operationType, "0123456789abcdef", 1);
    }

    private static final class FakeResourceGateway implements LakeResourceGateway {
        private LakeResourceState state = new LakeResourceState(
                "TABLE", 10L, 1, 1, null, LakeResourceStatus.PENDING_CREATE, false);
        private boolean failFinalize;

        @Override
        public LakeResourceState get(String resourceType, Long resourceId) {
            return state;
        }

        @Override
        public boolean claim(LakeResourceState expected, String operationToken,
                             Integer newGeneration, LakeResourceStatus pendingStatus) {
            if (!same(expected)) {
                return false;
            }
            state = new LakeResourceState("TABLE", 10L, newGeneration,
                    expected.lockVersion() + 1, operationToken, pendingStatus, false);
            return true;
        }

        @Override
        public boolean finalizeSuccess(LakeOperationHandle handle, String summary) {
            if (failFinalize || !same(handle)) {
                return false;
            }
            state = new LakeResourceState("TABLE", 10L, state.generation(),
                    state.lockVersion() + 1, null, LakeResourceStatus.READY, false);
            return true;
        }

        @Override
        public boolean finalizeFailure(LakeOperationHandle handle, String errorCode, String summary) {
            if (!same(handle)) {
                return false;
            }
            state = new LakeResourceState("TABLE", 10L, state.generation(),
                    state.lockVersion() + 1, null, LakeResourceStatus.ERROR, false);
            return true;
        }

        @Override
        public boolean takeOver(LakeOperationHandle staleHandle, String newOperationToken,
                                Integer newGeneration, LakeResourceStatus pendingStatus) {
            if (!same(staleHandle)) {
                return false;
            }
            state = new LakeResourceState("TABLE", 10L, newGeneration,
                    state.lockVersion() + 1, newOperationToken, pendingStatus, false);
            return true;
        }

        private boolean same(LakeResourceState expected) {
            return state.generation().equals(expected.generation())
                    && state.lockVersion().equals(expected.lockVersion())
                    && java.util.Objects.equals(state.operationToken(), expected.operationToken());
        }

        private boolean same(LakeOperationHandle handle) {
            return state.generation().equals(handle.generation())
                    && state.lockVersion().equals(handle.lockVersion())
                    && java.util.Objects.equals(state.operationToken(), handle.operationToken());
        }
    }

    private static final class FakeJournal implements LakeResourceOperationDao {
        private final List<LakeResourceOperation> records = new ArrayList<>();

        @Override
        public LakeResourceOperation queryByOperationToken(String operationToken) {
            return byToken(operationToken);
        }

        @Override
        public List<LakeResourceOperation> queryByResource(String resourceType, Long resourceId) {
            return records;
        }

        @Override
        public List<LakeResourceOperation> queryByStatus(LakeOperationStatus status) {
            return records.stream().filter(item -> item.getStatus() == status).toList();
        }

        @Override
        public boolean updateStatusIfToken(Long id, String operationToken, LakeOperationStatus status,
                                           String errorCode, String errorSummary) {
            return updateStatusIfToken(id, operationToken, null, status, errorCode, errorSummary);
        }

        @Override
        public boolean updateStatusIfToken(Long id, String operationToken, LakeOperationStatus expectedStatus,
                                           LakeOperationStatus status, String errorCode, String errorSummary) {
            LakeResourceOperation operation = byToken(operationToken);
            if (operation == null || !operation.getId().equals(id)) {
                return false;
            }
            if (expectedStatus != null && operation.getStatus() != expectedStatus) {
                return false;
            }
            operation.setStatus(status);
            operation.setErrorCode(errorCode);
            operation.setErrorSummary(errorSummary);
            return true;
        }

        @Override
        public int insert(LakeResourceOperation model) {
            records.add(model);
            return 1;
        }

        private LakeResourceOperation byToken(String token) {
            return records.stream()
                    .filter(item -> java.util.Objects.equals(item.getOperationToken(), token))
                    .findFirst()
                    .orElse(null);
        }

        // The coordinator only uses the methods above. These IDao methods make
        // this deterministic fake self-contained without involving a database.
        @Override public LakeResourceOperation queryById(java.io.Serializable id) {
            return records.stream().filter(item -> item.getId().equals(id)).findFirst().orElse(null);
        }
        @Override public java.util.Optional<LakeResourceOperation> queryOptionalById(java.io.Serializable id) {
            return java.util.Optional.ofNullable(queryById(id));
        }
        @Override public List<LakeResourceOperation> queryByIds(java.util.Collection<? extends java.io.Serializable> ids) { return records; }
        @Override public List<LakeResourceOperation> queryAll() { return records; }
        @Override public List<LakeResourceOperation> queryByCondition(LakeResourceOperation condition) { return records; }
        @Override public void insertBatch(java.util.Collection<LakeResourceOperation> models) { records.addAll(models); }
        @Override public boolean updateById(LakeResourceOperation model) { return true; }
        @Override public boolean deleteById(java.io.Serializable id) { return true; }
        @Override public boolean deleteByIds(java.util.Collection<? extends java.io.Serializable> ids) { return true; }
        @Override public boolean deleteByCondition(LakeResourceOperation condition) { return true; }
        @Override public List<LakeResourceOperation> selectList(com.baomidou.mybatisplus.core.conditions.Wrapper<LakeResourceOperation> wrapper) { return records; }
        @Override public com.baomidou.mybatisplus.core.metadata.IPage<LakeResourceOperation> selectPage(
                com.baomidou.mybatisplus.core.metadata.IPage<LakeResourceOperation> page,
                com.baomidou.mybatisplus.core.conditions.Wrapper<LakeResourceOperation> wrapper) { return page; }
    }

    private static final class RecordingBoundary implements LakeOperationTransactionBoundary {
        private boolean inTransaction;
        private int transactionCount;

        @Override
        public <T> T requiresNew(Supplier<T> action) {
            transactionCount++;
            inTransaction = true;
            try {
                return action.get();
            } finally {
                inTransaction = false;
            }
        }
    }
}
