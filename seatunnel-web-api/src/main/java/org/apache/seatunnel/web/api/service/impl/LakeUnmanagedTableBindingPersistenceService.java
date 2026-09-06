package org.apache.seatunnel.web.api.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.source.SourceObjectSnapshot;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.common.enums.LakeSourceObjectType;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeSourceObjectRef;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Short local transaction for an explicit UNMANAGED table projection.
 *
 * <p>All OpenMetadata and Doris reads happen in the orchestration service
 * before these methods are entered. The methods repeat local identity and
 * tombstone checks, then perform only local inserts or an optimistic CAS.</p>
 */
@Service
public class LakeUnmanagedTableBindingPersistenceService {

    private final LakeOdsDatabaseBindingDao databaseBindingDao;
    private final LakeOdsTableMappingDao tableMappingDao;
    private final LakeSourceObjectRefDao sourceObjectRefDao;
    private final LakeJobRelationDao jobRelationDao;
    private final LakeTableLifecycleBindingDao lifecycleBindingDao;

    @Autowired
    public LakeUnmanagedTableBindingPersistenceService(
            LakeOdsDatabaseBindingDao databaseBindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            LakeSourceObjectRefDao sourceObjectRefDao,
            LakeJobRelationDao jobRelationDao,
            LakeTableLifecycleBindingDao lifecycleBindingDao) {
        this.databaseBindingDao = Objects.requireNonNull(databaseBindingDao, "databaseBindingDao");
        this.tableMappingDao = Objects.requireNonNull(tableMappingDao, "tableMappingDao");
        this.sourceObjectRefDao = Objects.requireNonNull(sourceObjectRefDao, "sourceObjectRefDao");
        this.jobRelationDao = Objects.requireNonNull(jobRelationDao, "jobRelationDao");
        this.lifecycleBindingDao = Objects.requireNonNull(lifecycleBindingDao, "lifecycleBindingDao");
    }

    /** Constructor retained for direct callers that do not persist. */
    public LakeUnmanagedTableBindingPersistenceService() {
        this.databaseBindingDao = null;
        this.tableMappingDao = null;
        this.sourceObjectRefDao = null;
        this.jobRelationDao = null;
        this.lifecycleBindingDao = null;
    }

    @Transactional(rollbackFor = Exception.class)
    public PersistedBinding persistBind(
            LakeOdsDatabaseBinding expectedBinding,
            String targetTableName,
            SourceObjectSnapshot source,
            Integer userId) {
        requireDependencies();
        if (expectedBinding == null || source == null || userId == null || userId <= 0
                || StringUtils.isBlank(targetTableName)) {
            throw invalid("UNMANAGED table binding request is invalid");
        }
        LakeOdsDatabaseBinding binding = requireReadyBinding(
                expectedBinding.getId(), expectedBinding.getSourceDataSourceId());
        if (!sameBindingIdentity(expectedBinding, binding)) {
            throw conflict("ODS database binding changed during bind");
        }
        LakeSourceObjectRef sourceRef = findOrRejectSourceRef(
                binding.getSourceDataSourceId(), source.omEntityId());
        LakeOdsTableMapping existing = findOrRejectMapping(binding, targetTableName, sourceRef);
        if (existing != null) {
            return new PersistedBinding(existing, sourceRef);
        }
        if (sourceRef == null) {
            sourceRef = insertSourceRef(source, binding.getSourceDataSourceId(), userId);
        }
        LakeOdsTableMapping mapping = newMapping(binding, targetTableName, sourceRef, source, userId);
        return new PersistedBinding(
                insertMapping(mapping, binding, targetTableName, sourceRef), sourceRef);
    }

    @Transactional(rollbackFor = Exception.class)
    public PersistedBinding persistUnbind(Long mappingId, Integer userId) {
        requireDependencies();
        if (mappingId == null || mappingId <= 0 || userId == null || userId <= 0) {
            throw conflict("Lake table mapping does not exist");
        }
        LakeOdsTableMapping mapping = tableMappingDao.queryByIdIncludingDeleted(mappingId);
        if (mapping == null) {
            throw conflict("Lake table mapping does not exist");
        }
        LakeSourceObjectRef sourceRef = mapping.getSourceObjectRefId() == null ? null
                : sourceObjectRefDao.queryByIdIncludingDeleted(mapping.getSourceObjectRefId());
        if (Boolean.TRUE.equals(mapping.getDeleted())) {
            return new PersistedBinding(mapping, sourceRef);
        }
        if (mapping.getManagementLevel() != LakeManagementLevel.UNMANAGED) {
            throw conflict("Only UNMANAGED table mappings can be unbound");
        }
        if (mapping.getOperationToken() != null
                || mapping.getResourceStatus() != LakeResourceStatus.READY) {
            throw stale("The UNMANAGED table mapping is currently being changed");
        }
        if (hasBlockingRelation(mapping) || hasBlockingLifecycle(mapping)) {
            throw conflict("The UNMANAGED table mapping is still referenced");
        }
        Integer lockVersion = mapping.getLockVersion();
        if (lockVersion == null) {
            throw conflict("The UNMANAGED table mapping version is unavailable");
        }
        mapping.setDeleted(true);
        mapping.setResourceStatus(LakeResourceStatus.DELETED);
        // A soft delete keeps the current generation. Rebuild/reset, not
        // unbind, is the operation that allocates a new generation.
        mapping.setUpdateUserId(userId);
        mapping.initUpdate();
        if (!tableMappingDao.updateIfTokenAndVersionIncludingDeleted(
                mapping, null, lockVersion)) {
            throw conflict("The UNMANAGED table mapping changed during unbind");
        }
        return new PersistedBinding(mapping, sourceRef);
    }

    private LakeSourceObjectRef findOrRejectSourceRef(Long sourceDataSourceId, String omEntityId) {
        if (sourceDataSourceId == null || StringUtils.isBlank(omEntityId)) {
            throw conflict("The source object identity is incomplete");
        }
        LakeSourceObjectRef active = sourceObjectRefDao
                .queryBySourceDataSourceIdAndOmEntityId(sourceDataSourceId, omEntityId);
        LakeSourceObjectRef historical = sourceObjectRefDao
                .queryByOmEntityIdIncludingDeleted(omEntityId);
        LakeSourceObjectRef reference = historical == null ? active : historical;
        if (reference == null) {
            return null;
        }
        if (Boolean.TRUE.equals(reference.getDeleted())
                || isMissingStatus(reference.getResourceStatus())) {
            throw conflict("The source object reference is a deleted tombstone");
        }
        if (!Objects.equals(sourceDataSourceId, reference.getSourceDataSourceId())
                || !Objects.equals(omEntityId.trim(), StringUtils.trimToEmpty(reference.getOmEntityId()))
                || reference.getObjectType() != null
                && reference.getObjectType() != LakeSourceObjectType.TABLE) {
            throw conflict("The source object reference belongs to another source");
        }
        if (reference.getOperationToken() != null
                || reference.getResourceStatus() != null
                && reference.getResourceStatus() != LakeResourceStatus.READY) {
            throw stale("The source object reference is currently being changed");
        }
        return reference;
    }

    private LakeOdsTableMapping findOrRejectMapping(
            LakeOdsDatabaseBinding binding,
            String targetTableName,
            LakeSourceObjectRef sourceRef) {
        LakeOdsTableMapping activeByTarget = tableMappingDao
                .queryByBindingIdAndTargetTable(binding.getId(), targetTableName);
        LakeOdsTableMapping historicalByTarget = activeByTarget == null
                ? tableMappingDao.queryByBindingIdAndTargetTableIncludingDeleted(
                binding.getId(), targetTableName) : null;
        if (historicalByTarget != null) {
            if (Boolean.TRUE.equals(historicalByTarget.getDeleted())) {
                throw conflict("The target table mapping is a deleted tombstone");
            }
            activeByTarget = historicalByTarget;
        }
        if (activeByTarget != null) {
            if (isSameUnmanagedMapping(activeByTarget, binding, targetTableName, sourceRef)) {
                return activeByTarget;
            }
            throw conflict("The target table is already mapped");
        }
        if (sourceRef == null) {
            return null;
        }
        LakeOdsTableMapping activeBySource = tableMappingDao
                .queryByBindingIdAndSourceObject(binding.getId(), sourceRef.getId());
        LakeOdsTableMapping historicalBySource = activeBySource == null
                ? tableMappingDao.queryByBindingIdAndSourceObjectIncludingDeleted(
                binding.getId(), sourceRef.getId()) : null;
        if (historicalBySource != null) {
            if (Boolean.TRUE.equals(historicalBySource.getDeleted())) {
                throw conflict("The source table mapping is a deleted tombstone");
            }
            activeBySource = historicalBySource;
        }
        if (activeBySource != null) {
            if (isSameUnmanagedMapping(activeBySource, binding, targetTableName, sourceRef)) {
                return activeBySource;
            }
            throw conflict("The source object is already mapped to another target");
        }
        return null;
    }

    private boolean isSameUnmanagedMapping(
            LakeOdsTableMapping mapping,
            LakeOdsDatabaseBinding binding,
            String targetTableName,
            LakeSourceObjectRef sourceRef) {
        return !Boolean.TRUE.equals(mapping.getDeleted())
                && mapping.getManagementLevel() == LakeManagementLevel.UNMANAGED
                && mapping.getOperationToken() == null
                && Objects.equals(binding.getId(), mapping.getOdsDatabaseBindingId())
                && Objects.equals(binding.getLakeDataSourceId(), mapping.getLakeDataSourceId())
                && Objects.equals(binding.getDatabaseName(), mapping.getDatabaseName())
                && Objects.equals(targetTableName, mapping.getTargetTableName())
                && sourceRef != null
                && Objects.equals(sourceRef.getId(), mapping.getSourceObjectRefId());
    }

    private LakeSourceObjectRef insertSourceRef(
            SourceObjectSnapshot source, Long sourceDataSourceId, Integer userId) {
        LakeSourceObjectRef reference = new LakeSourceObjectRef();
        reference.initInsert();
        reference.setGeneration(1);
        reference.setLockVersion(1);
        reference.setCreateUserId(userId);
        reference.setUpdateUserId(userId);
        reference.setSourceDataSourceId(sourceDataSourceId);
        reference.setOmEntityId(source.omEntityId().trim());
        reference.setOmFqn(source.omFqn());
        reference.setObjectType(LakeSourceObjectType.TABLE);
        reference.setSourceSchemaHash(source.sourceSchemaHash());
        reference.setSourceSnapshotJson(source.snapshotJson());
        reference.setResourceStatus(LakeResourceStatus.READY);
        reference.setOperationToken(null);
        reference.setErrorCode(null);
        reference.setErrorMessage(null);
        reference.setDeleted(false);
        try {
            if (sourceObjectRefDao.insert(reference) <= 0) {
                throw conflict("Source object reference could not be persisted");
            }
            return reference;
        } catch (DataIntegrityViolationException exception) {
            return recoverSourceRef(sourceDataSourceId, source.omEntityId());
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("Source object reference could not be persisted");
        }
    }

    private LakeSourceObjectRef recoverSourceRef(Long sourceDataSourceId, String omEntityId) {
        LakeSourceObjectRef active = sourceObjectRefDao
                .queryBySourceDataSourceIdAndOmEntityId(sourceDataSourceId, omEntityId);
        if (active != null) {
            return findOrRejectSourceRef(sourceDataSourceId, omEntityId);
        }
        LakeSourceObjectRef historical = sourceObjectRefDao.queryByOmEntityIdIncludingDeleted(omEntityId);
        if (historical != null && Boolean.TRUE.equals(historical.getDeleted())) {
            throw conflict("The source object reference is a deleted tombstone");
        }
        throw conflict("Source object reference concurrently changed");
    }

    private LakeOdsTableMapping newMapping(
            LakeOdsDatabaseBinding binding,
            String targetTableName,
            LakeSourceObjectRef sourceRef,
            SourceObjectSnapshot source,
            Integer userId) {
        if (sourceRef.getId() == null || sourceRef.getId() <= 0) {
            throw conflict("Source object reference identity is unavailable");
        }
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.initInsert();
        mapping.setGeneration(1);
        mapping.setLockVersion(1);
        mapping.setCreateUserId(userId);
        mapping.setUpdateUserId(userId);
        mapping.setSourceObjectRefId(sourceRef.getId());
        mapping.setOdsDatabaseBindingId(binding.getId());
        mapping.setLakeDataSourceId(binding.getLakeDataSourceId());
        mapping.setDatabaseName(binding.getDatabaseName());
        mapping.setTargetTableName(targetTableName);
        mapping.setManagementLevel(LakeManagementLevel.UNMANAGED);
        mapping.setResourceStatus(LakeResourceStatus.READY);
        mapping.setActualTableExists(true);
        mapping.setSourceSchemaHash(source.sourceSchemaHash());
        mapping.setSourceSnapshotJson(source.snapshotJson());
        mapping.setSourceConsistencyStatus(LakeConsistencyStatus.CONSISTENT);
        mapping.setTargetConsistencyStatus(LakeConsistencyStatus.UNKNOWN);
        mapping.setTaskConsistencyStatus(LakeConsistencyStatus.UNBOUND);
        mapping.setTargetContractHash(null);
        mapping.setTargetContractJson(null);
        mapping.setActualContractJson(null);
        mapping.setFieldMappingsJson(null);
        mapping.setOperationToken(null);
        mapping.setErrorCode(null);
        mapping.setErrorMessage(null);
        mapping.setDeleted(false);
        return mapping;
    }

    private LakeOdsTableMapping insertMapping(
            LakeOdsTableMapping mapping,
            LakeOdsDatabaseBinding binding,
            String targetTableName,
            LakeSourceObjectRef sourceRef) {
        try {
            if (tableMappingDao.insert(mapping) <= 0) {
                throw conflict("UNMANAGED table mapping could not be persisted");
            }
            return mapping;
        } catch (DataIntegrityViolationException exception) {
            LakeOdsTableMapping active = tableMappingDao
                    .queryByBindingIdAndTargetTable(binding.getId(), targetTableName);
            if (active != null && isSameUnmanagedMapping(active, binding, targetTableName, sourceRef)) {
                return active;
            }
            LakeOdsTableMapping historical = tableMappingDao
                    .queryByBindingIdAndTargetTableIncludingDeleted(binding.getId(), targetTableName);
            if (historical != null && Boolean.TRUE.equals(historical.getDeleted())) {
                throw conflict("The target table mapping is a deleted tombstone");
            }
            throw conflict("UNMANAGED table mapping concurrently changed");
        } catch (LakeServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("UNMANAGED table mapping could not be persisted");
        }
    }

    private LakeOdsDatabaseBinding requireReadyBinding(Long bindingId, Long sourceDataSourceId) {
        LakeOdsDatabaseBinding binding = databaseBindingDao.queryActiveById(bindingId);
        if (binding == null || Boolean.TRUE.equals(binding.getDeleted())
                || binding.getResourceStatus() != LakeResourceStatus.READY
                || !Objects.equals(binding.getSourceDataSourceId(), sourceDataSourceId)) {
            throw conflict("ODS database binding changed during bind");
        }
        return binding;
    }

    private static boolean sameBindingIdentity(
            LakeOdsDatabaseBinding expected, LakeOdsDatabaseBinding actual) {
        return actual != null
                && Objects.equals(expected.getId(), actual.getId())
                && Objects.equals(expected.getSourceDataSourceId(), actual.getSourceDataSourceId())
                && Objects.equals(expected.getLakeDataSourceId(), actual.getLakeDataSourceId())
                && Objects.equals(expected.getDatabaseName(), actual.getDatabaseName());
    }

    private boolean hasBlockingRelation(LakeOdsTableMapping mapping) {
        if (mapping.getOdsDatabaseBindingId() == null) {
            return false;
        }
        List<LakeJobRelation> relations = jobRelationDao
                .queryByOdsDatabaseBindingId(mapping.getOdsDatabaseBindingId());
        return relations != null && relations.stream().anyMatch(relation -> relation != null
                && relation.getRelationStatus() == LakeRelationStatus.ACTIVE
                && relation.getRelationScope() == LakeRelationScope.TABLE
                && Objects.equals(mapping.getId(), relation.getTableMappingId()));
    }

    private boolean hasBlockingLifecycle(LakeOdsTableMapping mapping) {
        LakeTableLifecycleBinding lifecycle = lifecycleBindingDao
                .queryByTableMappingId(mapping.getId());
        return lifecycle != null && lifecycle.getStatus() != LakeLifecycleBindingStatus.DISABLED;
    }

    private void requireDependencies() {
        if (databaseBindingDao == null || tableMappingDao == null || sourceObjectRefDao == null
                || jobRelationDao == null || lifecycleBindingDao == null) {
            throw invalid("UNMANAGED table binding persistence is unavailable");
        }
    }

    private static boolean isMissingStatus(LakeResourceStatus status) {
        return status == LakeResourceStatus.MISSING
                || status == LakeResourceStatus.DELETING
                || status == LakeResourceStatus.DELETED;
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

    /** Result containing the mapping and source reference written/reused together. */
    public record PersistedBinding(LakeOdsTableMapping mapping, LakeSourceObjectRef sourceRef) {
    }
}
