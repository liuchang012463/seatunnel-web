package org.apache.seatunnel.web.api.fileresource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileResourceUploadCommitHooksTest {

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void runsImmediatelyWhenNoTransactionSynchronization() {
        AtomicInteger calls = new AtomicInteger();
        FileResourceUploadCommitHooks.runAfterCommit(calls::incrementAndGet);
        assertEquals(1, calls.get());
    }

    @Test
    void defersUntilAfterCommitWhenSynchronizationActive() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger calls = new AtomicInteger();

        FileResourceUploadCommitHooks.runAfterCommit(calls::incrementAndGet);
        assertEquals(0, calls.get(), "must not publish before commit");

        List<TransactionSynchronization> syncs =
                new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        assertEquals(1, syncs.size());
        syncs.get(0).afterCommit();
        assertEquals(1, calls.get());
    }

    @Test
    void ignoresNullAction() {
        TransactionSynchronizationManager.initSynchronization();
        FileResourceUploadCommitHooks.runAfterCommit(null);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }
}
