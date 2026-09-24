package org.apache.seatunnel.web.api.fileresource;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs upload side-effects only after the surrounding DB transaction commits.
 *
 * <p>When no synchronization is active (for example the legacy non-transactional
 * {@code upload} path), the action runs immediately.</p>
 */
public final class FileResourceUploadCommitHooks {

    private FileResourceUploadCommitHooks() {
    }

    public static void runAfterCommit(Runnable action) {
        if (action == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
