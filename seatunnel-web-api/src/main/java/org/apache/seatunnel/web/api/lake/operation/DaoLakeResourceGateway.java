package org.apache.seatunnel.web.api.lake.operation;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.catalog.LakeCatalogOperationResult;
import lombok.NonNull;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeExternalCatalogBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeResourceEntity;
import org.apache.seatunnel.web.dao.entity.LakeSourceObjectRef;
import org.apache.seatunnel.web.dao.repository.LakeExternalCatalogBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * DAO-backed gateway for the four v1.4 resource entities.
 *
 * <p>The normal update methods include {@code deleted=false}; the explicit
 * including-deleted variant is used only for a rebuild, so a deleted row can
 * be reopened and reused instead of colliding with a unique key.</p>
 */
@Component
public class DaoLakeResourceGateway implements LakeResourceGateway {

    private final LakeSourceObjectRefDao sourceObjectRefDao;
    private final LakeOdsDatabaseBindingDao odsDatabaseBindingDao;
    private final LakeOdsTableMappingDao odsTableMappingDao;
    private final LakeExternalCatalogBindingDao externalCatalogBindingDao;
    private final LakeTableLifecycleBindingDao lifecycleBindingDao;

    /** Spring constructor; lifecycle publication is finalized with the table lease. */
    @org.springframework.beans.factory.annotation.Autowired
    public DaoLakeResourceGateway(
            @NonNull LakeSourceObjectRefDao sourceObjectRefDao,
            @NonNull LakeOdsDatabaseBindingDao odsDatabaseBindingDao,
            @NonNull LakeOdsTableMappingDao odsTableMappingDao,
            @NonNull LakeExternalCatalogBindingDao externalCatalogBindingDao,
            LakeTableLifecycleBindingDao lifecycleBindingDao) {
        this.sourceObjectRefDao = sourceObjectRefDao;
        this.odsDatabaseBindingDao = odsDatabaseBindingDao;
        this.odsTableMappingDao = odsTableMappingDao;
        this.externalCatalogBindingDao = externalCatalogBindingDao;
        this.lifecycleBindingDao = lifecycleBindingDao;
    }

    /** Backwards-compatible constructor for gateway-focused tests without lifecycle rows. */
    public DaoLakeResourceGateway(
            @NonNull LakeSourceObjectRefDao sourceObjectRefDao,
            @NonNull LakeOdsDatabaseBindingDao odsDatabaseBindingDao,
            @NonNull LakeOdsTableMappingDao odsTableMappingDao,
            @NonNull LakeExternalCatalogBindingDao externalCatalogBindingDao) {
        this(sourceObjectRefDao, odsDatabaseBindingDao, odsTableMappingDao,
                externalCatalogBindingDao, null);
    }

    @Override
    public LakeResourceState get(String resourceType, Long resourceId) {
        String type = LakeResourceTypes.normalize(resourceType);
        LakeResourceEntity entity = switch (type) {
            case LakeResourceTypes.SOURCE_OBJECT_REF -> sourceObjectRefDao.queryByIdIncludingDeleted(resourceId);
            case LakeResourceTypes.ODS_DATABASE_BINDING -> odsDatabaseBindingDao.queryByIdIncludingDeleted(resourceId);
            case LakeResourceTypes.ODS_TABLE_MAPPING -> odsTableMappingDao.queryByIdIncludingDeleted(resourceId);
            case LakeResourceTypes.EXTERNAL_CATALOG_BINDING -> externalCatalogBindingDao.queryByIdIncludingDeleted(resourceId);
            default -> throw new IllegalArgumentException("Unsupported lake resource type");
        };
        return entity == null ? null : toState(type, entity);
    }

    @Override
    public boolean claim(LakeResourceState expected, String operationToken,
                         Integer newGeneration, LakeResourceStatus pendingStatus) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(operationToken, "operationToken");
        Objects.requireNonNull(newGeneration, "newGeneration");
        Objects.requireNonNull(pendingStatus, "pendingStatus");
        if (newGeneration < 1) {
            return false;
        }
        return update(expected, entity -> {
            entity.setOperationToken(operationToken);
            entity.setGeneration(newGeneration);
            entity.setResourceStatus(pendingStatus);
            entity.setDeleted(false);
            entity.initUpdate();
        });
    }

    @Override
    public boolean finalizeSuccess(LakeOperationHandle handle, String summary) {
        return finalizeSuccess(handle, summary, null);
    }

    @Override
    public boolean finalizeSuccess(
            LakeOperationHandle handle, String summary, Object publication) {
        Objects.requireNonNull(handle, "handle");
        LakeResourceState expected = get(handle.resourceType(), handle.resourceId());
        if (!sameLease(expected, handle)) {
            return false;
        }
        LakeManagedTableOperationPublication lifecyclePublication =
                publication instanceof LakeManagedTableOperationPublication value ? value : null;
        if (lifecyclePublication != null && !lifecycleReadyForFinalize(
                handle, lifecyclePublication)) {
            return false;
        }
        return update(expected, entity -> {
            boolean deleting = entity.getResourceStatus() == LakeResourceStatus.DELETING;
            entity.setResourceStatus(deleting ? LakeResourceStatus.DELETED : LakeResourceStatus.READY);
            entity.setDeleted(deleting);
            if (entity instanceof LakeOdsTableMapping table) {
                table.setActualTableExists(!deleting);
                table.setTargetConsistencyStatus(deleting
                        ? null : LakeConsistencyStatus.CONSISTENT);
            }
            if (entity instanceof LakeExternalCatalogBinding catalog
                    && publication instanceof LakeCatalogOperationResult result) {
                if (result.desiredSpecJson() != null) {
                    catalog.setDesiredSpecJson(result.desiredSpecJson());
                }
                if (result.desiredSpecHash() != null) {
                    catalog.setDesiredSpecHash(result.desiredSpecHash());
                }
                if (result.credentialRevision() != null) {
                    catalog.setCredentialRevision(result.credentialRevision());
                }
                if (result.driverChecksum() != null) {
                    catalog.setDriverChecksum(result.driverChecksum());
                }
                if (result.actualSnapshotJson() != null) {
                    catalog.setActualSnapshotJson(result.actualSnapshotJson());
                    catalog.setLastObservedAt(new java.util.Date());
                }
                if (result.validationStatus() != null) {
                    catalog.setValidationStatus(result.validationStatus());
                }
                if (result.resourceStatus() != null) {
                    catalog.setResourceStatus(result.resourceStatus());
                    catalog.setDeleted(result.resourceStatus() == LakeResourceStatus.DELETED);
                }
            }
            if (lifecyclePublication != null && entity instanceof LakeOdsTableMapping) {
                if (!finalizeLifecycleSuccess(handle, lifecyclePublication)) {
                    throw new IllegalStateException("Lifecycle binding lease was lost");
                }
            }
            entity.setOperationToken(null);
            entity.setErrorCode(null);
            entity.setErrorMessage(null);
            entity.setLastReconcileAt(new java.util.Date());
            entity.initUpdate();
        });
    }

    @Override
    public boolean finalizeFailure(LakeOperationHandle handle, String errorCode, String summary) {
        Objects.requireNonNull(handle, "handle");
        LakeResourceState expected = get(handle.resourceType(), handle.resourceId());
        if (!sameLease(expected, handle)) {
            return false;
        }
        if (!lifecycleReadyForFailure(handle)) {
            return false;
        }
        return update(expected, entity -> {
            entity.setResourceStatus(failureStatus(errorCode));
            entity.setOperationToken(null);
            entity.setErrorCode(errorCode);
            entity.setErrorMessage(summary);
            entity.setLastReconcileAt(new java.util.Date());
            if (entity instanceof LakeOdsTableMapping && lifecycleBindingDao != null) {
                if (!finalizeLifecycleFailure(handle, errorCode, summary)) {
                    throw new IllegalStateException("Lifecycle binding failure lease was lost");
                }
            }
            entity.initUpdate();
        });
    }

    private boolean lifecycleReadyForFinalize(
            LakeOperationHandle handle, LakeManagedTableOperationPublication publication) {
        if (lifecycleBindingDao == null || publication.lifecycleBindingId() == null
                || publication.lifecycleLockVersion() == null) {
            return false;
        }
        org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding binding =
                lifecycleBindingDao.queryByTableMappingId(handle.resourceId());
        return binding != null
                && Objects.equals(binding.getId(), publication.lifecycleBindingId())
                && Objects.equals(binding.getLockVersion(), publication.lifecycleLockVersion())
                && Objects.equals(binding.getOperationToken(), handle.operationToken())
                && binding.getStatus() == LakeLifecycleBindingStatus.PENDING;
    }

    private boolean finalizeLifecycleSuccess(
            LakeOperationHandle handle, LakeManagedTableOperationPublication publication) {
        if (lifecycleBindingDao == null) {
            return false;
        }
        org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding binding =
                lifecycleBindingDao.queryByTableMappingId(handle.resourceId());
        if (binding == null
                || !Objects.equals(binding.getId(), publication.lifecycleBindingId())
                || !Objects.equals(binding.getLockVersion(), publication.lifecycleLockVersion())
                || !Objects.equals(binding.getOperationToken(), handle.operationToken())) {
            return false;
        }
        binding.setStatus(LakeLifecycleBindingStatus.ACTIVE);
        binding.setActualRetentionCount(publication.retentionCount());
        binding.setLastObservedAt(new java.util.Date());
        binding.setOperationToken(null);
        binding.setErrorCode(null);
        binding.setErrorMessage(null);
        binding.setUpdateTime(new java.util.Date());
        return lifecycleBindingDao.updateIfTokenAndVersion(
                binding, handle.operationToken(), publication.lifecycleLockVersion());
    }

    private boolean lifecycleReadyForFailure(LakeOperationHandle handle) {
        if (lifecycleBindingDao == null || !LakeResourceTypes.ODS_TABLE_MAPPING
                .equals(LakeResourceTypes.normalize(handle.resourceType()))) {
            return true;
        }
        org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding binding =
                lifecycleBindingDao.queryByTableMappingId(handle.resourceId());
        return binding == null || Objects.equals(binding.getOperationToken(), handle.operationToken());
    }

    private boolean finalizeLifecycleFailure(
            LakeOperationHandle handle, String errorCode, String summary) {
        org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding binding =
                lifecycleBindingDao.queryByTableMappingId(handle.resourceId());
        if (binding == null || !Objects.equals(binding.getOperationToken(), handle.operationToken())) {
            return true;
        }
        binding.setStatus(LakeLifecycleBindingStatus.ERROR);
        binding.setOperationToken(null);
        binding.setErrorCode(errorCode);
        binding.setErrorMessage(summary);
        binding.setUpdateTime(new java.util.Date());
        return lifecycleBindingDao.updateIfTokenAndVersion(
                binding, handle.operationToken(), binding.getLockVersion());
    }

    private static LakeResourceStatus failureStatus(String errorCode) {
        if (LakeErrorCode.LAKE_DATABASE_MISSING.equals(errorCode)
                || LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING.equals(errorCode)) {
            return LakeResourceStatus.MISSING;
        }
        if (LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN.equals(errorCode)
                || LakeErrorCode.LAKE_DORIS_UNAVAILABLE.equals(errorCode)) {
            return LakeResourceStatus.UNKNOWN;
        }
        return LakeResourceStatus.ERROR;
    }

    @Override
    public boolean takeOver(LakeOperationHandle staleHandle, String newOperationToken,
                            Integer newGeneration, LakeResourceStatus pendingStatus) {
        Objects.requireNonNull(staleHandle, "staleHandle");
        Objects.requireNonNull(newOperationToken, "newOperationToken");
        Objects.requireNonNull(newGeneration, "newGeneration");
        Objects.requireNonNull(pendingStatus, "pendingStatus");
        LakeResourceState expected = get(staleHandle.resourceType(), staleHandle.resourceId());
        if (!sameLease(expected, staleHandle)) {
            return false;
        }
        return update(expected, entity -> {
            entity.setOperationToken(newOperationToken);
            entity.setGeneration(newGeneration);
            entity.setResourceStatus(pendingStatus);
            entity.setDeleted(false);
            entity.initUpdate();
        });
    }

    private boolean update(LakeResourceState expected, java.util.function.Consumer<LakeResourceEntity> mutation) {
        String type = LakeResourceTypes.normalize(expected.resourceType());
        LakeResourceEntity entity = switch (type) {
            case LakeResourceTypes.SOURCE_OBJECT_REF -> sourceObjectRefDao.queryByIdIncludingDeleted(expected.resourceId());
            case LakeResourceTypes.ODS_DATABASE_BINDING -> odsDatabaseBindingDao.queryByIdIncludingDeleted(expected.resourceId());
            case LakeResourceTypes.ODS_TABLE_MAPPING -> odsTableMappingDao.queryByIdIncludingDeleted(expected.resourceId());
            case LakeResourceTypes.EXTERNAL_CATALOG_BINDING -> externalCatalogBindingDao.queryByIdIncludingDeleted(expected.resourceId());
            default -> throw new IllegalArgumentException("Unsupported lake resource type");
        };
        if (!sameSnapshot(expected, entity)) {
            return false;
        }
        mutation.accept(entity);
        String token = expected.operationToken();
        if (entity instanceof LakeSourceObjectRef source) {
            return expected.deleted()
                    ? sourceObjectRefDao.updateIfTokenAndVersionIncludingDeleted(source, token, expected.lockVersion())
                    : sourceObjectRefDao.updateIfTokenAndVersion(source, token, expected.lockVersion());
        }
        if (entity instanceof LakeOdsDatabaseBinding database) {
            return expected.deleted()
                    ? odsDatabaseBindingDao.updateIfTokenAndVersionIncludingDeleted(database, token, expected.lockVersion())
                    : odsDatabaseBindingDao.updateIfTokenAndVersion(database, token, expected.lockVersion());
        }
        if (entity instanceof LakeOdsTableMapping table) {
            return expected.deleted()
                    ? odsTableMappingDao.updateIfTokenAndVersionIncludingDeleted(table, token, expected.lockVersion())
                    : odsTableMappingDao.updateIfTokenAndVersion(table, token, expected.lockVersion());
        }
        if (entity instanceof LakeExternalCatalogBinding catalog) {
            return expected.deleted()
                    ? externalCatalogBindingDao.updateIfTokenAndVersionIncludingDeleted(catalog, token, expected.lockVersion())
                    : externalCatalogBindingDao.updateIfTokenAndVersion(catalog, token, expected.lockVersion());
        }
        return false;
    }

    private static LakeResourceState toState(String type, LakeResourceEntity entity) {
        return new LakeResourceState(type, entity.getId(), entity.getGeneration(),
                entity.getLockVersion(), entity.getOperationToken(), entity.getResourceStatus(),
                Boolean.TRUE.equals(entity.getDeleted()));
    }

    private static boolean sameSnapshot(LakeResourceState expected, LakeResourceEntity actual) {
        return actual != null
                && Objects.equals(actual.getId(), expected.resourceId())
                && Objects.equals(actual.getGeneration(), expected.generation())
                && Objects.equals(actual.getLockVersion(), expected.lockVersion())
                && Objects.equals(actual.getOperationToken(), expected.operationToken())
                && Boolean.TRUE.equals(actual.getDeleted()) == expected.deleted();
    }

    private static boolean sameLease(LakeResourceState state, LakeOperationHandle handle) {
        return state != null
                && Objects.equals(state.resourceType(), LakeResourceTypes.normalize(handle.resourceType()))
                && Objects.equals(state.resourceId(), handle.resourceId())
                && Objects.equals(state.generation(), handle.generation())
                && Objects.equals(state.lockVersion(), handle.lockVersion())
                && Objects.equals(state.operationToken(), handle.operationToken());
    }
}
