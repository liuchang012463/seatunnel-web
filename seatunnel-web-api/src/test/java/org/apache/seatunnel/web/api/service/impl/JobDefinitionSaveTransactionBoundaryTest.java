package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.spi.bean.dto.batch.BatchFileSyncJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleIncrementalJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchScriptJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionBatchCreateCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingScriptJobSaveCommand;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobDefinitionSaveTransactionBoundaryTest {

    private final TransactionAttributeSource transactionAttributeSource =
            new AnnotationTransactionAttributeSource();

    @Test
    void everyBatchSaveEntryPointIsProxyVisibleTransactional() throws NoSuchMethodException {
        assertTransactional(BatchJobDefinitionServiceImpl.class, List.of(
                BatchScriptJobSaveCommand.class,
                BatchGuideSingleJobSaveCommand.class,
                BatchGuideSingleIncrementalJobSaveCommand.class,
                BatchFileSyncJobSaveCommand.class,
                BatchGuideMultiJobSaveCommand.class));
    }

    @Test
    void everyStreamingSaveEntryPointIsProxyVisibleTransactional() throws NoSuchMethodException {
        assertTransactional(StreamingJobDefinitionServiceImpl.class, List.of(
                StreamingScriptJobSaveCommand.class,
                StreamingGuideSingleJobSaveCommand.class,
                StreamingGuideMultiJobSaveCommand.class));
    }

    @Test
    void batchCreateRemainsTransactionalAsTheCopyOperationBoundary()
            throws NoSuchMethodException {
        assertTransactional(
                BatchJobDefinitionServiceImpl.class,
                List.of(JobDefinitionBatchCreateCommand.class),
                "batchCreate");
        assertTransactional(
                StreamingJobDefinitionServiceImpl.class,
                List.of(JobDefinitionBatchCreateCommand.class),
                "batchCreate");
    }

    private void assertTransactional(
            Class<?> serviceType, List<Class<?>> parameterTypes) throws NoSuchMethodException {
        assertTransactional(serviceType, parameterTypes, "saveOrUpdate");
    }

    private void assertTransactional(
            Class<?> serviceType,
            List<Class<?>> parameterTypes,
            String methodName) throws NoSuchMethodException {
        for (Class<?> parameterType : parameterTypes) {
            Method method = serviceType.getMethod(methodName, parameterType);
            TransactionAttribute attribute = transactionAttributeSource
                    .getTransactionAttribute(method, serviceType);
            assertNotNull(attribute, () -> serviceType.getSimpleName()
                    + "." + methodName + "(" + parameterType.getSimpleName()
                    + ") must expose a transaction to Spring's proxy metadata");
            assertTrue(attribute.rollbackOn(new Exception()), () -> serviceType.getSimpleName()
                    + "." + methodName + " must roll back checked exceptions");
        }
    }
}
