package org.apache.seatunnel.web.api.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.DorisIdentifier;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.source.LakeSourceObjectResolver;
import org.apache.seatunnel.web.api.lake.source.SourceObjectSnapshot;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableVO;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.LakeUnmanagedTableBindingService;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeSourceObjectRef;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeUnmanagedTableBindDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Local projection service for explicitly bound, already-existing Doris tables. */
@Service
public class LakeUnmanagedTableBindingServiceImpl implements LakeUnmanagedTableBindingService {

    private final LakeOdsDatabaseBindingDao databaseBindingDao;
    private final LakeSourceObjectResolver sourceResolver;
    private final LakeDorisClientProvider dorisClientProvider;
    private final CurrentUserProvider currentUserProvider;
    private final LakeUnmanagedTableBindingPersistenceService persistenceService;

    @Autowired
    public LakeUnmanagedTableBindingServiceImpl(
            LakeOdsDatabaseBindingDao databaseBindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            LakeSourceObjectRefDao sourceObjectRefDao,
            LakeJobRelationDao jobRelationDao,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            LakeSourceObjectResolver sourceResolver,
            LakeDorisClientProvider dorisClientProvider,
            CurrentUserProvider currentUserProvider,
            LakeUnmanagedTableBindingPersistenceService persistenceService) {
        this.databaseBindingDao = Objects.requireNonNull(databaseBindingDao, "databaseBindingDao");
        this.sourceResolver = Objects.requireNonNull(sourceResolver, "sourceResolver");
        this.dorisClientProvider = Objects.requireNonNull(dorisClientProvider, "dorisClientProvider");
        this.currentUserProvider = Objects.requireNonNull(currentUserProvider, "currentUserProvider");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService");
    }

    /** Backwards-compatible constructor for direct unit-test callers. */
    public LakeUnmanagedTableBindingServiceImpl(
            LakeOdsDatabaseBindingDao databaseBindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            LakeSourceObjectRefDao sourceObjectRefDao,
            LakeJobRelationDao jobRelationDao,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            LakeSourceObjectResolver sourceResolver,
            LakeDorisClientProvider dorisClientProvider,
            CurrentUserProvider currentUserProvider) {
        this(databaseBindingDao, tableMappingDao, sourceObjectRefDao, jobRelationDao,
                lifecycleBindingDao, sourceResolver, dorisClientProvider, currentUserProvider,
                new LakeUnmanagedTableBindingPersistenceService(
                        databaseBindingDao, tableMappingDao, sourceObjectRefDao,
                        jobRelationDao, lifecycleBindingDao));
    }

    @Override
    public LakeManagedTableVO bind(LakeUnmanagedTableBindDTO request) {
        validateRequest(request);
        Integer userId = requireCurrentUserId();
        LakeOdsDatabaseBinding binding = requireReadyBinding(
                request.getOdsDatabaseBindingId(), request.getSourceDataSourceId());
        String targetTableName = normalizeTableName(request.getTargetTableName());

        SourceObjectSnapshot source = resolveSource(
                request.getSourceDataSourceId(), request.getOmEntityId());
        DorisLakeClient client = resolveDoris(binding.getLakeDataSourceId());
        ensureTargetExists(client, binding.getDatabaseName(), targetTableName);

        LakeUnmanagedTableBindingPersistenceService.PersistedBinding persisted =
                persistenceService.persistBind(binding, targetTableName, source, userId);
        return toVO(persisted.mapping(), persisted.sourceRef());
    }

    @Override
    public LakeManagedTableVO unbind(Long mappingId) {
        Integer userId = requireCurrentUserId();
        LakeUnmanagedTableBindingPersistenceService.PersistedBinding persisted =
                persistenceService.persistUnbind(mappingId, userId);
        return toVO(persisted.mapping(), persisted.sourceRef());
    }

    private SourceObjectSnapshot resolveSource(Long sourceDataSourceId, String omEntityId) {
        try {
            SourceObjectSnapshot source = sourceResolver.resolve(
                    sourceDataSourceId, omEntityId.trim());
            if (source == null || StringUtils.isBlank(source.omEntityId())
                    || !omEntityId.trim().equals(source.omEntityId().trim())
                    || StringUtils.isBlank(source.sourceSchemaHash())
                    || StringUtils.isBlank(source.snapshotJson())) {
                throw sourceUnknown();
            }
            return source;
        } catch (LakeServiceException exception) {
            if (LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING.equals(exception.getLakeErrorCode())) {
                throw new LakeServiceException(LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING,
                        "OpenMetadata source object is missing");
            }
            throw sourceUnknown();
        } catch (RuntimeException exception) {
            throw sourceUnknown();
        }
    }

    private DorisLakeClient resolveDoris(Long lakeDataSourceId) {
        try {
            DorisLakeClient client = dorisClientProvider.get(lakeDataSourceId);
            if (client == null) {
                throw new IllegalStateException();
            }
            return client;
        } catch (RuntimeException exception) {
            throw dorisUnavailable();
        }
    }

    private void ensureTargetExists(DorisLakeClient client, String databaseName, String tableName) {
        boolean exists;
        try {
            exists = client.tableExists(databaseName, tableName);
        } catch (RuntimeException exception) {
            throw dorisUnavailable();
        }
        if (!exists) {
            throw conflict("Doris target table does not exist");
        }
    }

    private LakeOdsDatabaseBinding requireReadyBinding(Long bindingId, Long sourceDataSourceId) {
        if (bindingId == null || bindingId <= 0) {
            throw conflict("ODS database binding does not exist");
        }
        LakeOdsDatabaseBinding binding = databaseBindingDao.queryActiveById(bindingId);
        if (binding == null || Boolean.TRUE.equals(binding.getDeleted())
                || binding.getResourceStatus() != LakeResourceStatus.READY) {
            throw conflict("ODS database binding is not ready");
        }
        if (!Objects.equals(binding.getSourceDataSourceId(), sourceDataSourceId)
                || binding.getLakeDataSourceId() == null
                || StringUtils.isBlank(binding.getDatabaseName())) {
            throw conflict("ODS database binding does not belong to this source");
        }
        return binding;
    }

    private LakeManagedTableVO toVO(LakeOdsTableMapping mapping, LakeSourceObjectRef sourceRef) {
        LakeManagedTableVO result = new LakeManagedTableVO();
        result.setId(mapping.getId());
        result.setSourceObjectRefId(mapping.getSourceObjectRefId());
        result.setSourceDataSourceId(sourceRef == null ? null : sourceRef.getSourceDataSourceId());
        result.setOmEntityId(sourceRef == null ? null : sourceRef.getOmEntityId());
        result.setOmFqn(sourceRef == null ? null : sourceRef.getOmFqn());
        result.setOdsDatabaseBindingId(mapping.getOdsDatabaseBindingId());
        result.setLakeDataSourceId(mapping.getLakeDataSourceId());
        result.setDatabaseName(mapping.getDatabaseName());
        result.setTargetTableName(mapping.getTargetTableName());
        result.setManagementLevel(mapping.getManagementLevel());
        result.setTableModel(mapping.getTableModel());
        result.setResourceStatus(mapping.getResourceStatus());
        result.setGeneration(mapping.getGeneration());
        result.setLockVersion(mapping.getLockVersion());
        result.setSourceSchemaHash(mapping.getSourceSchemaHash());
        result.setSourceSnapshotJson(mapping.getSourceSnapshotJson());
        result.setSourceConsistencyStatus(mapping.getSourceConsistencyStatus());
        result.setTargetConsistencyStatus(mapping.getTargetConsistencyStatus());
        result.setTaskConsistencyStatus(mapping.getTaskConsistencyStatus());
        result.setActualTableExists(mapping.getActualTableExists());
        result.setErrorCode(mapping.getErrorCode());
        result.setErrorMessage(mapping.getErrorMessage());
        result.setLastReconcileAt(mapping.getLastReconcileAt());
        result.setCreateUserId(mapping.getCreateUserId());
        result.setUpdateUserId(mapping.getUpdateUserId());
        result.setDeleted(mapping.getDeleted());
        result.setCreateTime(mapping.getCreateTime());
        result.setUpdateTime(mapping.getUpdateTime());
        return result;
    }

    private static void validateRequest(LakeUnmanagedTableBindDTO request) {
        if (request == null || request.getOdsDatabaseBindingId() == null
                || request.getOdsDatabaseBindingId() <= 0
                || request.getSourceDataSourceId() == null
                || request.getSourceDataSourceId() <= 0
                || StringUtils.isBlank(request.getTargetTableName())
                || StringUtils.isBlank(request.getOmEntityId())) {
            throw invalid("odsDatabaseBindingId, targetTableName, sourceDataSourceId and omEntityId are required");
        }
    }

    private Integer requireCurrentUserId() {
        Integer userId = currentUserProvider.getCurrentUserId();
        if (userId == null || userId <= 0) {
            throw invalid("Authenticated user is required");
        }
        return userId;
    }

    private static String normalizeTableName(String value) {
        try {
            return DorisIdentifier.normalize(value);
        } catch (IllegalArgumentException exception) {
            throw invalid("targetTableName is not a valid Doris identifier");
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

    private static LakeServiceException sourceUnknown() {
        return new LakeServiceException(LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN,
                "OpenMetadata source object is unavailable");
    }

    private static LakeServiceException dorisUnavailable() {
        return new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                "Doris target is unavailable");
    }
}
