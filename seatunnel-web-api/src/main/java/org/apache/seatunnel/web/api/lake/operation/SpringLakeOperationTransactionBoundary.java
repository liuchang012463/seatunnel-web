package org.apache.seatunnel.web.api.lake.operation;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Production transaction boundary.  Each invocation starts and commits its
 * own local transaction, or rolls it back when the action raises a runtime
 * exception.
 */
@Component
public class SpringLakeOperationTransactionBoundary implements LakeOperationTransactionBoundary {

    private final TransactionTemplate transactionTemplate;

    public SpringLakeOperationTransactionBoundary(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T requiresNew(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        return transactionTemplate.execute(status -> action.get());
    }
}
