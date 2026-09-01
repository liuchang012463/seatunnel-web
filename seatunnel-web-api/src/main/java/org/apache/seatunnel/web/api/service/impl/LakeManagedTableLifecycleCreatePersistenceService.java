package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.operation.LakeManagedTableOperationPublication;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationHandle;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationTransactionBoundary;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceTypes;
import org.apache.seatunnel.web.api.lake.operation.SpringLakeOperationTransactionBoundary;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationType;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeResourceOperation;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeResourceOperationDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;

/**
 * Atomic TX1 for a MANAGED table create that carries lifecycle intent.
 *
 * <p>The mapping, PENDING lifecycle binding and operation journal are written
 * and the mapping lease is claimed in one short local transaction.  The
 * returned handle is then safe to pass to the normal external-operation
 * coordinator; no Doris call is made while this transaction is open.</p>
 */
@Service
public class LakeManagedTableLifecycleCreatePersistenceService {

    private final LakeOdsTableMappingDao tableMappingDao;
    private final LakeTableLifecycleBindingDao lifecycleBindingDao;
    private final LakeResourceOperationDao operationDao;
    private final LakeOperationTransactionBoundary transactionBoundary;
    private final Clock clock;

    @Autowired
    public LakeManagedTableLifecycleCreatePersistenceService(
            LakeOdsTableMappingDao tableMappingDao,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            LakeResourceOperationDao operationDao,
            SpringLakeOperationTransactionBoundary transactionBoundary) {
        this(tableMappingDao, lifecycleBindingDao, operationDao,
                transactionBoundary, Clock.systemUTC());
    }

    /** Visible for deterministic persistence tests. */
    public LakeManagedTableLifecycleCreatePersistenceService(
            LakeOdsTableMappingDao tableMappingDao,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            LakeResourceOperationDao operationDao,
            LakeOperationTransactionBoundary transactionBoundary,
            Clock clock) {
        this.tableMappingDao = Objects.requireNonNull(tableMappingDao, "tableMappingDao");
        this.lifecycleBindingDao = Objects.requireNonNull(
                lifecycleBindingDao, "lifecycleBindingDao");
        this.operationDao = Objects.requireNonNull(operationDao, "operationDao");
        this.transactionBoundary = Objects.requireNonNull(
                transactionBoundary, "transactionBoundary");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public StartResult start(
            LakeOdsTableMapping candidate,
            LifecycleSpec lifecycle,
            Integer operatorId) {
        if (candidate == null || lifecycle == null || operatorId == null || operatorId <= 0) {
            throw invalid("lifecycle create request");
        }
        try {
            return transactionBoundary.requiresNew(
                    () -> startInTransaction(candidate, lifecycle, operatorId));
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("lifecycle create persistence");
        }
    }

    private StartResult startInTransaction(
            LakeOdsTableMapping candidate,
            LifecycleSpec lifecycle,
            Integer operatorId) {
        LakeOdsTableMapping mapping = persistMapping(candidate, operatorId);
        LakeTableLifecycleBinding binding = prepareBinding(mapping, lifecycle, operatorId);
        String operationToken = UUID.randomUUID().toString();
        binding.setOperationToken(operationToken);
        if (binding.getId() == null) {
            binding.initInsert();
            binding.setOperationToken(operationToken);
            try {
                if (lifecycleBindingDao.insert(binding) <= 0) {
                    throw invalid("lifecycle binding");
                }
            } catch (DuplicateKeyException exception) {
                throw invalid("lifecycle binding already exists");
            }
        } else {
            Integer expected = positive(binding.getLockVersion()) != null ? binding.getLockVersion() : 1;
            binding.setLockVersion(expected);
            if (!lifecycleBindingDao.updateIfTokenAndVersion(binding, null, expected)) {
                throw invalid("lifecycle binding changed concurrently");
            }
        }

        Integer mappingVersion = positive(mapping.getLockVersion()) != null
                ? mapping.getLockVersion() : 1;
        mapping.setOperationToken(operationToken);
        mapping.setResourceStatus(LakeResourceStatus.PENDING_CREATE);
        mapping.setDeleted(false);
        mapping.setUpdateUserId(operatorId);
        mapping.initUpdate();
        boolean claimed = mapping.getId() != null
                && tableMappingDao.updateIfTokenAndVersionIncludingDeleted(
                mapping, null, mappingVersion);
        if (!claimed) {
            throw invalid("MANAGED mapping changed concurrently");
        }

        LakeResourceOperation operation = new LakeResourceOperation();
        operation.initInsert();
        operation.setResourceType(LakeResourceTypes.ODS_TABLE_MAPPING);
        operation.setResourceId(mapping.getId());
        operation.setGeneration(mapping.getGeneration());
        operation.setOperationType(LakeOperationType.CREATE_TABLE);
        operation.setOperationToken(operationToken);
        operation.setRequestHash(mapping.getTargetContractHash());
        operation.setStatus(LakeOperationStatus.PENDING);
        operation.setStartedAt(Date.from(clock.instant()));
        operation.setOperatorId(operatorId);
        if (operationDao.insert(operation) <= 0) {
            throw invalid("lake operation journal");
        }
        Integer handleVersion = mapping.getLockVersion() == null
                ? mappingVersion + 1 : mapping.getLockVersion();
        return new StartResult(
                mapping,
                new LakeOperationHandle(
                        operation.getId(), LakeResourceTypes.ODS_TABLE_MAPPING,
                        mapping.getId(), mapping.getGeneration(), operationToken, handleVersion),
                new LakeManagedTableOperationPublication(
                        binding.getId(), binding.getLockVersion(), lifecycle.retentionCount()));
    }

    private LakeOdsTableMapping persistMapping(
            LakeOdsTableMapping candidate, Integer operatorId) {
        LakeOdsTableMapping mapping = candidate;
        if (mapping.getId() == null) {
            if (tableMappingDao.queryByBindingIdAndTargetTableIncludingDeleted(
                    mapping.getOdsDatabaseBindingId(), mapping.getTargetTableName()) != null
                    || tableMappingDao.queryByBindingIdAndSourceObjectIncludingDeleted(
                    mapping.getOdsDatabaseBindingId(), mapping.getSourceObjectRefId()) != null) {
                throw invalid("MANAGED mapping already exists");
            }
            mapping.setCreateUserId(operatorId);
            mapping.setUpdateUserId(operatorId);
            mapping.setGeneration(positive(mapping.getGeneration()) != null ? mapping.getGeneration() : 1);
            mapping.setLockVersion(positive(mapping.getLockVersion()) != null ? mapping.getLockVersion() : 1);
            mapping.setDeleted(false);
            mapping.setResourceStatus(LakeResourceStatus.PENDING_CREATE);
            try {
                if (tableMappingDao.insert(mapping) <= 0) {
                    throw invalid("MANAGED mapping");
                }
            } catch (DuplicateKeyException exception) {
                throw invalid("MANAGED mapping already exists");
            }
            return mapping;
        }

        LakeOdsTableMapping current = tableMappingDao.queryByIdIncludingDeleted(mapping.getId());
        if (current == null || !Boolean.TRUE.equals(current.getDeleted())
                || current.getOperationToken() != null) {
            throw invalid("deleted MANAGED mapping is not reusable");
        }
        Integer expectedVersion = positive(current.getLockVersion()) != null ? current.getLockVersion() : 1;
        mapping.setGeneration((positive(current.getGeneration()) != null ? current.getGeneration() : 1) + 1);
        mapping.setLockVersion(expectedVersion);
        mapping.setDeleted(false);
        mapping.setResourceStatus(LakeResourceStatus.PENDING_CREATE);
        mapping.setOperationToken(null);
        mapping.setUpdateUserId(operatorId);
        mapping.initUpdate();
        if (!tableMappingDao.updateIfTokenAndVersionIncludingDeleted(
                mapping, null, expectedVersion)) {
            throw invalid("deleted MANAGED mapping changed concurrently");
        }
        return mapping;
    }

    private LakeTableLifecycleBinding prepareBinding(
            LakeOdsTableMapping mapping,
            LifecycleSpec lifecycle,
            Integer operatorId) {
        LakeTableLifecycleBinding binding = lifecycleBindingDao
                .queryByTableMappingId(mapping.getId());
        if (binding == null) {
            binding = new LakeTableLifecycleBinding();
            binding.setTableMappingId(mapping.getId());
            binding.setGeneration(1);
            binding.setLockVersion(1);
            binding.setCreateUserId(operatorId);
        } else if (binding.getOperationToken() != null) {
            throw invalid("lifecycle binding is currently being changed");
        }
        binding.setPolicyId(lifecycle.policyId());
        binding.setPolicyVersion(lifecycle.policyVersion());
        binding.setPartitionColumn(lifecycle.partitionColumn());
        binding.setGranularity(lifecycle.granularity());
        binding.setRetentionCount(lifecycle.retentionCount());
        binding.setActualRetentionCount(null);
        binding.setActualPartitionSummaryJson(null);
        binding.setLastObservedAt(null);
        binding.setPolicySnapshotJson(lifecycle.policySnapshotJson());
        binding.setStatus(LakeLifecycleBindingStatus.PENDING);
        binding.setErrorCode(null);
        binding.setErrorMessage(null);
        binding.setUpdateUserId(operatorId);
        binding.setUpdateTime(Date.from(clock.instant()));
        return binding;
    }

    private static Integer positive(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private static LakeServiceException invalid(String field) {
        return new LakeServiceException(
                LakeErrorCode.LAKE_RESOURCE_CONFLICT,
                "Lifecycle create persistence failed: " + field);
    }

    public record LifecycleSpec(
            Long policyId,
            Integer policyVersion,
            String partitionColumn,
            org.apache.seatunnel.web.common.enums.LakePartitionGranularity granularity,
            Integer retentionCount,
            String policySnapshotJson) {
    }

    public record StartResult(
            LakeOdsTableMapping mapping,
            LakeOperationHandle handle,
            LakeManagedTableOperationPublication publication) {
    }
}
