package org.apache.seatunnel.web.api.lake.operation;

import lombok.NonNull;
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

import java.util.Objects;

/**
 * DAO-backed gateway for the four v1.4 resource entities.
 *
 * <p>The normal update methods include {@code deleted=false}; the explicit
 * including-deleted variant is used only for a rebuild, so a deleted row can
 * be reopened and reused instead of colliding with a unique key.</p>
 */
public class DaoLakeResourceGateway implements LakeResourceGateway {

    private final LakeSourceObjectRefDao sourceObjectRefDao;
    private final LakeOdsDatabaseBindingDao odsDatabaseBindingDao;
    private final LakeOdsTableMappingDao odsTableMappingDao;
    private final LakeExternalCatalogBindingDao externalCatalogBindingDao;

    public DaoLakeResourceGateway(
            @NonNull LakeSourceObjectRefDao sourceObjectRefDao,
            @NonNull LakeOdsDatabaseBindingDao odsDatabaseBindingDao,
            @NonNull LakeOdsTableMappingDao odsTableMappingDao,
            @NonNull LakeExternalCatalogBindingDao externalCatalogBindingDao) {
        this.sourceObjectRefDao = sourceObjectRefDao;
        this.odsDatabaseBindingDao = odsDatabaseBindingDao;
        this.odsTableMappingDao = odsTableMappingDao;
        this.externalCatalogBindingDao = externalCatalogBindingDao;
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
        Objects.requireNonNull(handle, "handle");
        LakeResourceState expected = get(handle.resourceType(), handle.resourceId());
        if (!sameLease(expected, handle)) {
            return false;
        }
        return update(expected, entity -> {
            boolean deleting = entity.getResourceStatus() == LakeResourceStatus.DELETING;
            entity.setResourceStatus(deleting ? LakeResourceStatus.DELETED : LakeResourceStatus.READY);
            entity.setDeleted(deleting);
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
        return update(expected, entity -> {
            entity.setResourceStatus(LakeResourceStatus.ERROR);
            entity.setOperationToken(null);
            entity.setErrorCode(errorCode);
            entity.setErrorMessage(summary);
            entity.setLastReconcileAt(new java.util.Date());
            entity.initUpdate();
        });
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
