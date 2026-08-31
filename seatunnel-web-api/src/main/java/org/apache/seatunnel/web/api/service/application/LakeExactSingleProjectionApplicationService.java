package org.apache.seatunnel.web.api.service.application;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.job.LakeExactSingleProjectionPlanner;
import org.apache.seatunnel.web.api.lake.source.LakeSourceObjectResolver;
import org.apache.seatunnel.web.api.lake.source.SourceObjectSnapshot;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.common.enums.LakeSourceObjectType;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeSourceObjectRef;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Applies a prepared exact-single lake projection to local state.
 *
 * <p>{@link #prepare(JobDefinitionSaveCommand)} is the only phase that reads
 * OpenMetadata.  It returns an immutable plan plus a fresh source snapshot;
 * it does not write a job, source reference, mapping, or Doris.  The apply
 * phase is deliberately limited to the two local projection rows.  It does
 * not create a target contract or execute DDL.</p>
 */
@Service
public class LakeExactSingleProjectionApplicationService {

    private final LakeExactSingleProjectionPlanner planner;
    private final LakeSourceObjectResolver sourceResolver;
    private final LakeSourceObjectRefDao sourceObjectRefDao;
    private final LakeOdsTableMappingDao tableMappingDao;
    private final CurrentUserProvider currentUserProvider;

    public LakeExactSingleProjectionApplicationService(
            LakeExactSingleProjectionPlanner planner,
            LakeSourceObjectResolver sourceResolver,
            LakeSourceObjectRefDao sourceObjectRefDao,
            LakeOdsTableMappingDao tableMappingDao,
            CurrentUserProvider currentUserProvider) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.sourceResolver = Objects.requireNonNull(sourceResolver, "sourceResolver");
        this.sourceObjectRefDao = Objects.requireNonNull(sourceObjectRefDao, "sourceObjectRefDao");
        this.tableMappingDao = Objects.requireNonNull(tableMappingDao, "tableMappingDao");
        this.currentUserProvider = currentUserProvider;
    }

    /** Test-friendly constructor for callers that pass the user explicitly to apply. */
    public LakeExactSingleProjectionApplicationService(
            LakeExactSingleProjectionPlanner planner,
            LakeSourceObjectResolver sourceResolver,
            LakeSourceObjectRefDao sourceObjectRefDao,
            LakeOdsTableMappingDao tableMappingDao) {
        this(planner, sourceResolver, sourceObjectRefDao, tableMappingDao, null);
    }

    /**
     * Plan and, for a new exact-single projection, fetch a fresh OM snapshot.
     * A null result means the command is not a lake exact-single command.
     */
    public PreparedProjection prepare(JobDefinitionSaveCommand command) {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan;
        try {
            plan = planner.plan(command);
        } catch (LakeServiceException exception) {
            throw stableException(exception.getLakeErrorCode());
        } catch (RuntimeException exception) {
            throw invalid();
        }

        if (plan == null
                || plan.decision() == LakeExactSingleProjectionPlanner.Decision.NOT_APPLICABLE) {
            return null;
        }
        if (plan.decision() == LakeExactSingleProjectionPlanner.Decision.UNKNOWN
                || plan.decision() == LakeExactSingleProjectionPlanner.Decision.REJECT) {
            throw stablePlanFailure(plan);
        }

        if (!isCreateDecision(plan.decision())) {
            // Existing mappings are authoritative.  The apply phase must not
            // refresh or otherwise mutate them.
            return new PreparedProjection(plan, null);
        }

        LakeExactSingleProjectionPlanner.Endpoint sourceEndpoint = plan.sourceEndpoint();
        if (sourceEndpoint == null
                || sourceEndpoint.dataSourceId() == null
                || sourceEndpoint.dataSourceId() <= 0
                || StringUtils.isBlank(sourceEndpoint.omEntityId())) {
            throw invalid();
        }

        SourceObjectSnapshot source;
        try {
            source = sourceResolver.resolve(
                    sourceEndpoint.dataSourceId(), sourceEndpoint.omEntityId().trim());
        } catch (LakeServiceException exception) {
            throw stableSourceException(exception.getLakeErrorCode());
        } catch (RuntimeException exception) {
            // Resolver/provider messages can contain endpoint or credential
            // details.  Only the stable UNKNOWN identity leaves this layer.
            throw sourceUnknown();
        }
        if (source == null
                || StringUtils.isBlank(source.omEntityId())
                || !sourceEndpoint.omEntityId().trim().equals(source.omEntityId().trim())) {
            throw sourceUnknown();
        }
        return new PreparedProjection(plan, source);
    }

    /**
     * Apply a prepared projection using the authenticated user, if present.
     * The caller normally invokes this from its existing save transaction.
     */
    @Transactional(rollbackFor = Exception.class)
    public LakeOdsTableMapping applyPrepared(PreparedProjection prepared) {
        Integer userId = currentUserProvider == null
                ? null : currentUserProvider.getCurrentUserId();
        return applyPrepared(prepared, userId);
    }

    /**
     * Apply only the local source-reference and mapping projection.
     *
     * <p>Every identity read is completed before the first insert/update.  If
     * another writer wins a unique key between that read and an insert, the
     * row is read again and accepted only when its complete identity matches;
     * a tombstone is never reopened.</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public LakeOdsTableMapping applyPrepared(
            PreparedProjection prepared, Integer userId) {
        if (prepared == null || prepared.plan() == null
                || prepared.plan().decision()
                == LakeExactSingleProjectionPlanner.Decision.NOT_APPLICABLE) {
            return null;
        }
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = prepared.plan();
        if (!isCreateDecision(plan.decision())) {
            if (plan.decision() == LakeExactSingleProjectionPlanner.Decision.UNKNOWN
                    || plan.decision() == LakeExactSingleProjectionPlanner.Decision.REJECT) {
                throw stablePlanFailure(plan);
            }
            // REUSE_* plans intentionally do not change mapping state.
            return null;
        }

        Identity identity = identityOf(prepared);
        Preflight preflight = readBeforeWrite(identity);

        // An active mapping that appeared after prepare is safe to reuse only
        // when its binding, target and source identity all match.
        if (preflight.activeMapping() != null) {
            validateMappingIdentity(preflight.activeMapping(), identity, preflight.sourceRef());
            return preflight.activeMapping();
        }

        LakeSourceObjectRef sourceRef = preflight.sourceRef();
        if (sourceRef == null) {
            sourceRef = insertSourceRef(identity, prepared.sourceSnapshot(), userId);
        } else {
            sourceRef = refreshSourceRef(sourceRef, identity, prepared.sourceSnapshot(), userId);
        }

        LakeOdsTableMapping mapping = createMapping(
                identity, sourceRef, plan.decision(), prepared.sourceSnapshot(), userId);
        return insertMappingWithConcurrencyCheck(mapping, identity, sourceRef);
    }

    private Preflight readBeforeWrite(Identity identity) {
        LakeSourceObjectRef activeSource = safeRead(
                () -> sourceObjectRefDao.queryByOmEntityId(identity.omEntityId()),
                "source object reference state is unavailable");
        LakeSourceObjectRef historicalSource = activeSource == null
                ? safeRead(
                () -> sourceObjectRefDao.queryByOmEntityIdIncludingDeleted(identity.omEntityId()),
                "source object reference state is unavailable")
                : null;
        if (historicalSource != null) {
            if (Boolean.TRUE.equals(historicalSource.getDeleted())) {
                throw conflict("source object reference was deleted");
            }
            throw conflict("source object reference state is inconsistent");
        }
        validateSourceIdentity(activeSource, identity);

        LakeOdsTableMapping activeMapping = safeRead(
                () -> tableMappingDao.queryByBindingIdAndTargetTable(
                        identity.bindingId(), identity.targetTableName()),
                "lake table mapping state is unavailable");
        LakeOdsTableMapping historicalMapping = activeMapping == null
                ? safeRead(
                () -> tableMappingDao.queryByBindingIdAndTargetTableIncludingDeleted(
                        identity.bindingId(), identity.targetTableName()),
                "lake table mapping state is unavailable")
                : null;
        if (historicalMapping != null) {
            if (Boolean.TRUE.equals(historicalMapping.getDeleted())) {
                throw conflict("lake table mapping was deleted");
            }
            throw conflict("lake table mapping state is inconsistent");
        }
        if (activeMapping != null) {
            // The source ref may be missing only in a concurrently incomplete
            // view.  Do not manufacture an unrelated ref for that mapping.
            if (activeSource == null && activeMapping.getSourceObjectRefId() != null) {
                LakeSourceObjectRef referenced = safeRead(
                        () -> sourceObjectRefDao.queryByIdIncludingDeleted(
                                activeMapping.getSourceObjectRefId()),
                        "source object reference state is unavailable");
                validateSourceIdentity(referenced, identity);
                activeSource = referenced;
            }
            validateMappingIdentity(activeMapping, identity, activeSource);
        }
        return new Preflight(activeSource, activeMapping);
    }

    private LakeSourceObjectRef insertSourceRef(
            Identity identity, SourceObjectSnapshot source, Integer userId) {
        LakeSourceObjectRef reference = new LakeSourceObjectRef();
        reference.initInsert();
        reference.setGeneration(1);
        reference.setLockVersion(1);
        reference.setCreateUserId(userId);
        fillSourceRef(reference, identity, source, userId);
        try {
            if (sourceObjectRefDao.insert(reference) <= 0) {
                throw conflict("source object reference could not be persisted");
            }
            return reference;
        } catch (DataIntegrityViolationException exception) {
            return recoverConcurrentSourceRef(identity);
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("source object reference could not be persisted");
        }
    }

    private LakeSourceObjectRef recoverConcurrentSourceRef(Identity identity) {
        LakeSourceObjectRef active = safeRead(
                () -> sourceObjectRefDao.queryByOmEntityId(identity.omEntityId()),
                "source object reference state is unavailable");
        if (active != null) {
            validateSourceIdentity(active, identity);
            return active;
        }
        LakeSourceObjectRef historical = safeRead(
                () -> sourceObjectRefDao.queryByOmEntityIdIncludingDeleted(identity.omEntityId()),
                "source object reference state is unavailable");
        if (historical != null && Boolean.TRUE.equals(historical.getDeleted())) {
            throw conflict("source object reference was deleted");
        }
        throw conflict("source object reference concurrently changed");
    }

    private LakeSourceObjectRef refreshSourceRef(
            LakeSourceObjectRef reference,
            Identity identity,
            SourceObjectSnapshot source,
            Integer userId) {
        validateSourceIdentity(reference, identity);
        if (!sourceChanged(reference, identity, source)) {
            return reference;
        }
        fillSourceRef(reference, identity, source, userId);
        try {
            if (!sourceObjectRefDao.updateById(reference)) {
                throw conflict("source object reference could not be updated");
            }
            return reference;
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("source object reference could not be updated");
        }
    }

    private LakeOdsTableMapping createMapping(
            Identity identity,
            LakeSourceObjectRef sourceRef,
            LakeExactSingleProjectionPlanner.Decision decision,
            SourceObjectSnapshot source,
            Integer userId) {
        if (sourceRef == null || sourceRef.getId() == null) {
            throw conflict("source object reference identity is unavailable");
        }
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.initInsert();
        mapping.setGeneration(1);
        mapping.setLockVersion(1);
        mapping.setCreateUserId(userId);
        mapping.setUpdateUserId(userId);
        mapping.setDeleted(false);
        mapping.setOperationToken(null);
        mapping.setErrorCode(null);
        mapping.setErrorMessage(null);
        mapping.setSourceObjectRefId(sourceRef.getId());
        mapping.setOdsDatabaseBindingId(identity.bindingId());
        mapping.setLakeDataSourceId(identity.lakeDataSourceId());
        mapping.setDatabaseName(identity.databaseName());
        mapping.setTargetTableName(identity.targetTableName());
        mapping.setManagementLevel(decision == LakeExactSingleProjectionPlanner.Decision.CREATE_AUTO_PENDING
                ? LakeManagementLevel.AUTO_CREATED : LakeManagementLevel.UNMANAGED);
        // Target contract fields stay null.  Contract construction belongs to
        // the managed-table flow and is intentionally outside this projection.
        mapping.setTargetContractHash(null);
        mapping.setTargetContractJson(null);
        mapping.setFieldMappingsJson(null);
        mapping.setSourceSchemaHash(source.sourceSchemaHash());
        mapping.setSourceSnapshotJson(source.snapshotJson());
        boolean actualExists = decision
                == LakeExactSingleProjectionPlanner.Decision.CREATE_UNMANAGED_READY;
        mapping.setResourceStatus(actualExists
                ? LakeResourceStatus.READY : LakeResourceStatus.PENDING_CREATE);
        mapping.setActualTableExists(actualExists);
        mapping.setSourceConsistencyStatus(LakeConsistencyStatus.CONSISTENT);
        mapping.setTargetConsistencyStatus(actualExists
                ? LakeConsistencyStatus.CONSISTENT : LakeConsistencyStatus.UNKNOWN);
        mapping.setTaskConsistencyStatus(LakeConsistencyStatus.UNBOUND);
        return mapping;
    }

    private LakeOdsTableMapping insertMappingWithConcurrencyCheck(
            LakeOdsTableMapping mapping,
            Identity identity,
            LakeSourceObjectRef sourceRef) {
        try {
            if (tableMappingDao.insert(mapping) <= 0) {
                throw conflict("lake table mapping could not be persisted");
            }
            return mapping;
        } catch (DataIntegrityViolationException exception) {
            LakeOdsTableMapping active = safeRead(
                    () -> tableMappingDao.queryByBindingIdAndTargetTable(
                            identity.bindingId(), identity.targetTableName()),
                    "lake table mapping state is unavailable");
            if (active != null) {
                validateMappingIdentity(active, identity, sourceRef);
                return active;
            }
            LakeOdsTableMapping historical = safeRead(
                    () -> tableMappingDao.queryByBindingIdAndTargetTableIncludingDeleted(
                            identity.bindingId(), identity.targetTableName()),
                    "lake table mapping state is unavailable");
            if (historical != null && Boolean.TRUE.equals(historical.getDeleted())) {
                throw conflict("lake table mapping was deleted");
            }
            throw conflict("lake table mapping concurrently changed");
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("lake table mapping could not be persisted");
        }
    }

    private Identity identityOf(PreparedProjection prepared) {
        LakeExactSingleProjectionPlanner.ProjectionPlan plan = prepared.plan();
        LakeExactSingleProjectionPlanner.BindingSnapshot binding = plan.binding();
        LakeExactSingleProjectionPlanner.Endpoint source = plan.sourceEndpoint();
        if (binding == null || source == null
                || binding.id() == null || binding.id() <= 0
                || binding.lakeDataSourceId() == null || binding.lakeDataSourceId() <= 0
                || source.dataSourceId() == null || source.dataSourceId() <= 0
                || StringUtils.isBlank(source.omEntityId())
                || StringUtils.isBlank(plan.databaseName())
                || StringUtils.isBlank(plan.targetTableName())
                || prepared.sourceSnapshot() == null
                || StringUtils.isBlank(prepared.sourceSnapshot().omEntityId())
                || StringUtils.isBlank(prepared.sourceSnapshot().sourceSchemaHash())
                || StringUtils.isBlank(prepared.sourceSnapshot().snapshotJson())) {
            throw invalid();
        }
        if (!Objects.equals(binding.sourceDataSourceId(), source.dataSourceId())
                || !Objects.equals(binding.lakeDataSourceId(), plan.sinkDataSourceId())
                || !source.omEntityId().trim().equals(prepared.sourceSnapshot().omEntityId().trim())) {
            throw invalid();
        }
        return new Identity(
                binding.id(),
                binding.lakeDataSourceId(),
                source.dataSourceId(),
                source.omEntityId().trim(),
                plan.databaseName(),
                plan.targetTableName());
    }

    private void validateSourceIdentity(LakeSourceObjectRef reference, Identity identity) {
        if (reference == null) {
            return;
        }
        if (Boolean.TRUE.equals(reference.getDeleted())) {
            throw conflict("source object reference was deleted");
        }
        if (!Objects.equals(identity.sourceDataSourceId(), reference.getSourceDataSourceId())
                || !identity.omEntityId().equals(StringUtils.trimToEmpty(reference.getOmEntityId()))) {
            throw conflict("source object reference belongs to another source");
        }
        if (reference.getId() == null || reference.getId() <= 0) {
            throw conflict("source object reference identity is unavailable");
        }
    }

    private void validateMappingIdentity(
            LakeOdsTableMapping mapping,
            Identity identity,
            LakeSourceObjectRef sourceRef) {
        if (mapping == null) {
            return;
        }
        if (Boolean.TRUE.equals(mapping.getDeleted())) {
            throw conflict("lake table mapping was deleted");
        }
        if (!Objects.equals(identity.bindingId(), mapping.getOdsDatabaseBindingId())
                || !Objects.equals(identity.targetTableName(), mapping.getTargetTableName())
                || !Objects.equals(identity.lakeDataSourceId(), mapping.getLakeDataSourceId())
                || !Objects.equals(identity.databaseName(), mapping.getDatabaseName())
                || sourceRef == null
                || !Objects.equals(sourceRef.getId(), mapping.getSourceObjectRefId())) {
            throw conflict("lake table mapping belongs to another source");
        }
    }

    private boolean sourceChanged(
            LakeSourceObjectRef reference,
            Identity identity,
            SourceObjectSnapshot source) {
        return !Objects.equals(identity.sourceDataSourceId(), reference.getSourceDataSourceId())
                || !Objects.equals(identity.omEntityId(), reference.getOmEntityId())
                || !Objects.equals(source.omFqn(), reference.getOmFqn())
                || reference.getObjectType() != LakeSourceObjectType.TABLE
                || !Objects.equals(source.sourceSchemaHash(), reference.getSourceSchemaHash())
                || !Objects.equals(source.snapshotJson(), reference.getSourceSnapshotJson())
                || reference.getResourceStatus() != LakeResourceStatus.READY
                || Boolean.TRUE.equals(reference.getDeleted());
    }

    private void fillSourceRef(
            LakeSourceObjectRef reference,
            Identity identity,
            SourceObjectSnapshot source,
            Integer userId) {
        reference.setSourceDataSourceId(identity.sourceDataSourceId());
        reference.setOmEntityId(identity.omEntityId());
        reference.setOmFqn(source.omFqn());
        reference.setObjectType(LakeSourceObjectType.TABLE);
        reference.setSourceSchemaHash(source.sourceSchemaHash());
        reference.setSourceSnapshotJson(source.snapshotJson());
        reference.setResourceStatus(LakeResourceStatus.READY);
        reference.setDeleted(false);
        reference.setOperationToken(null);
        reference.setErrorCode(null);
        reference.setErrorMessage(null);
        reference.setUpdateUserId(userId);
        reference.initUpdate();
    }

    private boolean isCreateDecision(LakeExactSingleProjectionPlanner.Decision decision) {
        return decision == LakeExactSingleProjectionPlanner.Decision.CREATE_AUTO_PENDING
                || decision == LakeExactSingleProjectionPlanner.Decision.CREATE_UNMANAGED_READY;
    }

    private LakeServiceException stablePlanFailure(
            LakeExactSingleProjectionPlanner.ProjectionPlan plan) {
        String code = plan.failureCode();
        if (LakeErrorCode.LAKE_DORIS_UNAVAILABLE.equals(code)) {
            return new LakeServiceException(
                    LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "lake Doris state is unavailable");
        }
        if (LakeErrorCode.LAKE_RESOURCE_CONFLICT.equals(code)) {
            return conflict("lake projection resource conflict");
        }
        return invalid();
    }

    private LakeServiceException stableSourceException(String code) {
        if (LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING.equals(code)) {
            return new LakeServiceException(
                    LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING,
                    "lake source object is missing");
        }
        return sourceUnknown();
    }

    private LakeServiceException stableException(String code) {
        if (LakeErrorCode.LAKE_DORIS_UNAVAILABLE.equals(code)) {
            return new LakeServiceException(
                    LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "lake Doris state is unavailable");
        }
        if (LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING.equals(code)) {
            return new LakeServiceException(
                    LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING,
                    "lake source object is missing");
        }
        if (LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN.equals(code)) {
            return sourceUnknown();
        }
        if (LakeErrorCode.LAKE_RESOURCE_CONFLICT.equals(code)) {
            return conflict("lake projection resource conflict");
        }
        return invalid();
    }

    private <T> T safeRead(ReadOperation<T> operation, String message) {
        try {
            return operation.read();
        } catch (RuntimeException exception) {
            throw conflict(message);
        }
    }

    private LakeServiceException invalid() {
        return new LakeServiceException(
                LakeErrorCode.LAKE_REQUEST_INVALID,
                "lake projection request is invalid");
    }

    private LakeServiceException conflict(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_RESOURCE_CONFLICT, message);
    }

    private LakeServiceException sourceUnknown() {
        return new LakeServiceException(
                LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN,
                "lake source object state is unavailable");
    }

    public record PreparedProjection(
            LakeExactSingleProjectionPlanner.ProjectionPlan plan,
            SourceObjectSnapshot sourceSnapshot) {
    }

    private record Identity(
            Long bindingId,
            Long lakeDataSourceId,
            Long sourceDataSourceId,
            String omEntityId,
            String databaseName,
            String targetTableName) {
    }

    private record Preflight(
            LakeSourceObjectRef sourceRef,
            LakeOdsTableMapping activeMapping) {
    }

    @FunctionalInterface
    private interface ReadOperation<T> {
        T read();
    }
}
