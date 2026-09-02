package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.ods.LakeOdsMasterDataResolver;
import org.apache.seatunnel.web.api.lake.ods.OdsDatabaseName;
import org.apache.seatunnel.web.api.lake.operation.LakeExternalOperationException;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationException;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationHandle;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationIntent;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceOperationCoordinator;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceTypes;
import org.apache.seatunnel.web.api.service.LakeOdsDatabaseService;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationType;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.BusinessSystem;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.DataSourceUnit;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeResourceOperation;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeOdsDatabaseCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakePhysicalDataSourcePageDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeOdsDatabaseVO;
import org.apache.seatunnel.web.spi.bean.vo.LakePhysicalDataSourceVO;
import org.apache.seatunnel.web.spi.bean.vo.LakePhysicalSummaryVO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Task 5 ODS database backend. External Doris work is always coordinator-bound. */
@Service
public class LakeOdsDatabaseServiceImpl implements LakeOdsDatabaseService {

    private final DataSourceDao dataSourceDao;
    private final BusinessSystemDao businessSystemDao;
    private final DataSourceUnitDao dataSourceUnitDao;
    private final LakeOdsDatabaseBindingDao bindingDao;
    private final LakeOdsTableMappingDao tableMappingDao;
    private final LakeJobRelationDao jobRelationDao;
    private final LakeOdsMasterDataResolver masterDataResolver;
    private final LakeDorisClientProvider dorisClientProvider;
    private final LakeResourceOperationCoordinator coordinator;
    private final CurrentUserProvider currentUserProvider;
    private final org.apache.seatunnel.web.api.lake.LakeProperties lakeProperties;

    @Autowired
    public LakeOdsDatabaseServiceImpl(
            DataSourceDao dataSourceDao,
            BusinessSystemDao businessSystemDao,
            DataSourceUnitDao dataSourceUnitDao,
            LakeOdsDatabaseBindingDao bindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            LakeJobRelationDao jobRelationDao,
            LakeOdsMasterDataResolver masterDataResolver,
            LakeDorisClientProvider dorisClientProvider,
            LakeResourceOperationCoordinator coordinator,
            CurrentUserProvider currentUserProvider,
            org.apache.seatunnel.web.api.lake.LakeProperties lakeProperties) {
        this.dataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.businessSystemDao = Objects.requireNonNull(businessSystemDao, "businessSystemDao");
        this.dataSourceUnitDao = Objects.requireNonNull(dataSourceUnitDao, "dataSourceUnitDao");
        this.bindingDao = Objects.requireNonNull(bindingDao, "bindingDao");
        this.tableMappingDao = Objects.requireNonNull(tableMappingDao, "tableMappingDao");
        this.jobRelationDao = Objects.requireNonNull(jobRelationDao, "jobRelationDao");
        this.masterDataResolver = Objects.requireNonNull(masterDataResolver, "masterDataResolver");
        this.dorisClientProvider = Objects.requireNonNull(dorisClientProvider, "dorisClientProvider");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.currentUserProvider = Objects.requireNonNull(currentUserProvider, "currentUserProvider");
        this.lakeProperties = Objects.requireNonNull(lakeProperties, "lakeProperties");
    }

    @Override
    public PaginationResult<LakePhysicalDataSourceVO> page(LakePhysicalDataSourcePageDTO request) {
        LakePhysicalDataSourcePageDTO safe = request == null ? new LakePhysicalDataSourcePageDTO() : request;
        DataSourceDTO query = new DataSourceDTO();
        query.setName(safe.getKeyword());
        query.setPageNo(safe.getPageNo() == null || safe.getPageNo() < 1 ? 1 : safe.getPageNo());
        query.setPageSize(safe.getPageSize() == null || safe.getPageSize() < 1 ? 10 : safe.getPageSize());
        IPage<DataSource> page = queryPage(query, safe.getResourceStatus());
        List<DataSource> records = page.getRecords() == null ? List.of() : page.getRecords();
        List<LakePhysicalDataSourceVO> result = records.stream()
                .map(this::toSourceVO)
                .toList();
        return PaginationResult.buildSuc(result, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    public LakePhysicalSummaryVO summary() {
        List<LakeOdsDatabaseBinding> bindings = bindingDao.queryAll();
        List<LakeOdsTableMapping> mappings = tableMappingDao.queryAll();
        Set<Long> activeBindingIds = bindings == null ? Set.of() : bindings.stream()
                .filter(Objects::nonNull)
                .filter(binding -> !Boolean.TRUE.equals(binding.getDeleted()))
                .map(LakeOdsDatabaseBinding::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        LakePhysicalSummaryVO result = new LakePhysicalSummaryVO();
        result.setBoundDataSourceCount(activeBindingIds.size());
        result.setOdsTableCount(mappings == null ? 0 : mappings.stream()
                .filter(Objects::nonNull)
                .filter(mapping -> !Boolean.TRUE.equals(mapping.getDeleted()))
                .filter(mapping -> activeBindingIds.contains(mapping.getOdsDatabaseBindingId()))
                .count());
        long bindingExceptions = bindings == null ? 0 : bindings.stream()
                .filter(Objects::nonNull)
                .filter(binding -> !Boolean.TRUE.equals(binding.getDeleted()))
                .filter(binding -> binding.getResourceStatus() != LakeResourceStatus.READY)
                .count();
        long mappingExceptions = mappings == null ? 0 : mappings.stream()
                .filter(Objects::nonNull)
                .filter(mapping -> !Boolean.TRUE.equals(mapping.getDeleted()))
                .filter(mapping -> activeBindingIds.contains(mapping.getOdsDatabaseBindingId()))
                .filter(mapping -> mapping.getResourceStatus() != LakeResourceStatus.READY
                        || Boolean.FALSE.equals(mapping.getActualTableExists())
                        || mapping.getTargetConsistencyStatus() == LakeConsistencyStatus.DRIFT
                        || mapping.getSourceConsistencyStatus() == LakeConsistencyStatus.DRIFT
                        || mapping.getTaskConsistencyStatus() == LakeConsistencyStatus.DRIFT)
                .count();
        result.setPendingExceptionCount(bindingExceptions + mappingExceptions);
        return result;
    }

    private IPage<DataSource> queryPage(DataSourceDTO query, String resourceStatus) {
        if (resourceStatus == null || resourceStatus.isBlank()) {
            return dataSourceDao.queryPage(query);
        }
        LakeResourceStatus status;
        try {
            status = LakeResourceStatus.valueOf(resourceStatus.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new LakeServiceException(LakeErrorCode.LAKE_REQUEST_INVALID,
                    "resourceStatus is invalid");
        }
        return dataSourceDao.queryPageByLakeResourceStatus(query, status);
    }

    @Override
    public LakePhysicalDataSourceVO sourceDetail(Long sourceDataSourceId) {
        return toSourceVO(requireSource(sourceDataSourceId));
    }

    @Override
    public LakeOdsDatabaseVO create(Long sourceDataSourceId, LakeOdsDatabaseCreateDTO request) {
        if (request == null || request.getCustomName() == null || request.getCustomName().isBlank()) {
            throw new LakeServiceException(LakeErrorCode.LAKE_MASTER_DATA_CODE_INVALID,
                    "customName must not be blank");
        }
        requireSource(sourceDataSourceId);
        OdsDatabaseName name = masterDataResolver.resolve(sourceDataSourceId, request.getCustomName());
        Long lakeDataSourceId = requireConfiguredLakeDataSource();
        LakeOdsDatabaseBinding active = bindingDao.queryBySourceDataSourceId(sourceDataSourceId);
        if (active != null) {
            throw conflict("Source data source already has an active ODS database binding");
        }

        LakeOdsDatabaseBinding target = bindingDao
                .queryByLakeDataSourceIdAndDatabaseNameIncludingDeleted(
                        lakeDataSourceId, name.databaseName());
        if (target != null && !Objects.equals(target.getSourceDataSourceId(), sourceDataSourceId)) {
            throw new LakeServiceException(LakeErrorCode.LAKE_DATABASE_NAME_CONFLICT,
                    "Doris database name is already reserved by another source");
        }

        DorisLakeClient client = dorisClientProvider.get(lakeDataSourceId);
        if (databaseExists(client, name.databaseName())) {
            throw new LakeServiceException(LakeErrorCode.LAKE_DATABASE_NAME_CONFLICT,
                    "Doris database name is already in use");
        }

        LakeOdsDatabaseBinding binding = bindingDao.queryBySourceDataSourceIdIncludingDeleted(sourceDataSourceId);
        boolean rebuild = binding != null && Boolean.TRUE.equals(binding.getDeleted());
        if (binding == null) {
            binding = new LakeOdsDatabaseBinding();
            binding.initInsert();
            binding.setResourceStatus(LakeResourceStatus.PENDING_CREATE);
            binding.setLockVersion(1);
            binding.setGeneration(1);
            binding.setDeleted(false);
            fillBinding(binding, sourceDataSourceId, lakeDataSourceId, name);
            Integer currentUserId = currentUserProvider.getCurrentUserId();
            binding.setCreateUserId(currentUserId);
            binding.setUpdateUserId(currentUserId);
            try {
                if (bindingDao.insert(binding) <= 0) {
                    throw conflict("ODS database binding could not be persisted");
                }
            } catch (DuplicateKeyException exception) {
                throw conflict("ODS database binding already exists");
            }
        } else if (!name.databaseName().equalsIgnoreCase(binding.getDatabaseName())) {
            throw conflict("A deleted ODS binding cannot be renamed during rebuild");
        }
        fillBinding(binding, sourceDataSourceId, lakeDataSourceId, name);
        LakeOperationHandle handle = begin(binding, LakeOperationType.CREATE_DATABASE, rebuild);
        return createExternally(binding, handle, client, false);
    }

    @Override
    public LakeOdsDatabaseVO detail(Long id) {
        return toVO(requireBindingIncludingDeleted(id));
    }

    @Override
    public LakeOdsDatabaseVO retry(Long id) {
        LakeOdsDatabaseBinding binding = requireBindingIncludingDeleted(id);
        if (!Boolean.TRUE.equals(binding.getDeleted())
                && binding.getResourceStatus() == LakeResourceStatus.READY
                && binding.getOperationToken() == null) {
            return toVO(binding);
        }
        DorisLakeClient client = dorisClientProvider.get(binding.getLakeDataSourceId());
        boolean actualExists = databaseExists(client, binding.getDatabaseName());
        LakeOperationHandle handle;
        if (binding.getOperationToken() != null) {
            LakeResourceOperation operation = latestOpenOperation(binding);
            if (operation == null) {
                throw stale("The active ODS operation cannot be retried");
            }
            LakeOperationHandle staleHandle = new LakeOperationHandle(
                    operation.getId(), LakeResourceTypes.ODS_DATABASE_BINDING, binding.getId(),
                    binding.getGeneration(), binding.getOperationToken(), binding.getLockVersion());
            LakeOperationIntent retryIntent = intent(binding, LakeOperationType.CREATE_DATABASE);
            retryIntent.setGeneration(binding.getGeneration());
            retryIntent.setLockVersion(binding.getLockVersion());
            retryIntent.setOperationToken(binding.getOperationToken());
            try {
                handle = coordinator.takeOverStale(staleHandle, retryIntent);
            } catch (LakeOperationException exception) {
                throw stale("The ODS operation is not stale or was already replaced");
            }
        } else {
            handle = begin(binding, LakeOperationType.CREATE_DATABASE, Boolean.TRUE.equals(binding.getDeleted()));
        }
        return createExternally(binding, handle, client, actualExists);
    }

    @Override
    public LakeOdsDatabaseVO reconcile(Long id) {
        LakeOdsDatabaseBinding binding = requireBindingIncludingDeleted(id);
        if (Boolean.TRUE.equals(binding.getDeleted())) {
            return toVO(binding);
        }
        DorisLakeClient client = dorisClientProvider.get(binding.getLakeDataSourceId());
        LakeOperationHandle handle = begin(binding, LakeOperationType.RECONCILE, false);
        AtomicReference<String> errorCode = new AtomicReference<>();
        try {
            boolean exists = executeExists(handle, client, binding.getDatabaseName(), errorCode);
            if (!exists) {
                if (!coordinator.fail(handle, LakeErrorCode.LAKE_DATABASE_MISSING,
                        "Doris database is missing")) {
                    throw stale("The reconcile result is stale");
                }
            } else if (!coordinator.finalizeSuccess(handle, "Doris database exists")) {
                throw stale("The reconcile result is stale");
            }
            return detail(id);
        } catch (LakeExternalOperationException exception) {
            throw classifiedExternal(exception.getErrorCode(), "Doris database reconcile is unavailable");
        } catch (LakeOperationException exception) {
            throw classifiedExternal(errorCode.get(), "Doris database reconcile is unavailable");
        }
    }

    @Override
    public void delete(Long id) {
        LakeOdsDatabaseBinding binding = requireBindingIncludingDeleted(id);
        if (Boolean.TRUE.equals(binding.getDeleted())) {
            return;
        }
        ensureDeleteAllowed(binding.getId());
        DorisLakeClient client = dorisClientProvider.get(binding.getLakeDataSourceId());
        LakeOperationHandle handle = begin(binding, LakeOperationType.DROP_DATABASE, false);
        AtomicReference<String> errorCode = new AtomicReference<>();
        try {
            coordinator.execute(handle, () -> {
                try {
                    client.dropDatabase(binding.getDatabaseName());
                    if (client.databaseExists(binding.getDatabaseName())) {
                        errorCode.set(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
                        throw new LakeExternalOperationException(
                                LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                                "Doris database deletion could not be verified");
                    }
                    return Boolean.TRUE;
                } catch (LakeExternalOperationException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    errorCode.set(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
                    throw new LakeExternalOperationException(
                            LakeErrorCode.LAKE_DORIS_UNAVAILABLE, "Doris database operation is unavailable");
                }
            });
            if (!coordinator.finalizeSuccess(handle, "Doris database deleted")) {
                throw stale("The delete result is stale");
            }
        } catch (LakeExternalOperationException exception) {
            throw classifiedExternal(exception.getErrorCode(), "Doris database delete is unavailable");
        } catch (LakeOperationException exception) {
            throw classifiedExternal(errorCode.get(), "Doris database delete is unavailable");
        }
    }

    private LakeOdsDatabaseVO createExternally(
            LakeOdsDatabaseBinding binding, LakeOperationHandle handle,
            DorisLakeClient client, boolean actualExists) {
        AtomicReference<String> errorCode = new AtomicReference<>();
        try {
            coordinator.execute(handle, () -> {
                try {
                    if (!actualExists) {
                        client.createDatabase(binding.getDatabaseName());
                    }
                    if (!client.databaseExists(binding.getDatabaseName())) {
                        errorCode.set(LakeErrorCode.LAKE_DATABASE_MISSING);
                        throw new LakeExternalOperationException(
                                LakeErrorCode.LAKE_DATABASE_MISSING, "Doris database is missing after create");
                    }
                    return Boolean.TRUE;
                } catch (LakeExternalOperationException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    errorCode.set(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
                    throw new LakeExternalOperationException(
                            LakeErrorCode.LAKE_DORIS_UNAVAILABLE, "Doris database operation is unavailable");
                }
            });
            if (!coordinator.finalizeSuccess(handle, "Doris database exists")) {
                throw stale("The create result is stale");
            }
            return detail(binding.getId());
        } catch (LakeExternalOperationException exception) {
            throw classifiedExternal(exception.getErrorCode(), "Doris database create is unavailable");
        } catch (LakeOperationException exception) {
            throw classifiedExternal(errorCode.get(), "Doris database create is unavailable");
        }
    }

    private boolean executeExists(
            LakeOperationHandle handle, DorisLakeClient client, String databaseName,
            AtomicReference<String> errorCode) {
        try {
            return coordinator.execute(handle, () -> {
                try {
                    return client.databaseExists(databaseName);
                } catch (RuntimeException exception) {
                    errorCode.set(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
                    throw new LakeExternalOperationException(
                            LakeErrorCode.LAKE_DORIS_UNAVAILABLE, "Doris database lookup is unavailable");
                }
            }).externalResult();
        } catch (LakeOperationException exception) {
            throw exception;
        }
    }

    private boolean databaseExists(DorisLakeClient client, String databaseName) {
        try {
            return client.databaseExists(databaseName);
        } catch (RuntimeException exception) {
            throw new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "Doris database lookup is unavailable");
        }
    }

    private LakeOperationHandle begin(
            LakeOdsDatabaseBinding binding, LakeOperationType operationType, boolean rebuild) {
        LakeOperationIntent intent = intent(binding, operationType);
        intent.setRebuild(rebuild);
        try {
            return coordinator.begin(intent);
        } catch (LakeOperationException exception) {
            throw conflict("The ODS database is currently being changed");
        }
    }

    private LakeOperationIntent intent(
            LakeOdsDatabaseBinding binding, LakeOperationType operationType) {
        LakeOperationIntent intent = new LakeOperationIntent(
                LakeResourceTypes.ODS_DATABASE_BINDING,
                binding.getId(), operationType,
                requestHash(binding.getDatabaseName()), currentUserProvider.getCurrentUserId());
        return intent;
    }

    private static String requestHash(String databaseName) {
        return org.apache.seatunnel.web.api.lake.source.SourceSchemaCanonicalizer.sha256(
                "ODS_DATABASE\u0000" + databaseName);
    }

    private LakeOdsDatabaseBinding requireBindingIncludingDeleted(Long id) {
        if (id == null || id <= 0) {
            throw new LakeServiceException(LakeErrorCode.LAKE_DATABASE_MISSING,
                    "ODS database binding does not exist");
        }
        LakeOdsDatabaseBinding binding = bindingDao.queryByIdIncludingDeleted(id);
        if (binding == null) {
            throw new LakeServiceException(LakeErrorCode.LAKE_DATABASE_MISSING,
                    "ODS database binding does not exist");
        }
        return binding;
    }

    private DataSource requireSource(Long id) {
        if (id == null || id <= 0) {
            throw new org.apache.seatunnel.web.core.exceptions.ServiceException(
                    org.apache.seatunnel.web.spi.enums.Status.DATASOURCE_NOT_EXIST);
        }
        DataSource source = dataSourceDao.queryById(id);
        if (source == null || source.getStatus() == DataSourceLifecycleStatus.REVOKED) {
            throw new org.apache.seatunnel.web.core.exceptions.ServiceException(
                    org.apache.seatunnel.web.spi.enums.Status.DATASOURCE_NOT_EXIST);
        }
        return source;
    }

    private Long requireConfiguredLakeDataSource() {
        if (!lakeProperties.isEnabled() || lakeProperties.getDataSourceId() == null) {
            throw new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "Lake Doris data source is not configured");
        }
        DataSource lake = dataSourceDao.queryById(lakeProperties.getDataSourceId());
        if (lake == null || lake.getDbType() != DbType.DORIS
                || (lake.getStatus() != null
                && lake.getStatus() != DataSourceLifecycleStatus.ENABLED)) {
            throw new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "Lake Doris data source is unavailable");
        }
        return lake.getId();
    }

    private void fillBinding(
            LakeOdsDatabaseBinding binding, Long sourceDataSourceId,
            Long lakeDataSourceId, OdsDatabaseName name) {
        binding.setSourceDataSourceId(sourceDataSourceId);
        binding.setLakeDataSourceId(lakeDataSourceId);
        binding.setUnitCode(name.unitCode());
        binding.setSystemCode(name.systemCode());
        binding.setDatabaseName(name.databaseName());
        if (binding.getResourceStatus() == null) {
            binding.setResourceStatus(LakeResourceStatus.PENDING_CREATE);
        }
        if (binding.getLockVersion() == null) {
            binding.setLockVersion(1);
        }
        if (binding.getGeneration() == null) {
            binding.setGeneration(1);
        }
        if (binding.getDeleted() == null) {
            binding.setDeleted(false);
        }
    }

    private LakeResourceOperation latestOpenOperation(LakeOdsDatabaseBinding binding) {
        return coordinator.queryByResource(LakeResourceTypes.ODS_DATABASE_BINDING, binding.getId()).stream()
                .filter(operation -> operation.getOperationToken() != null
                        && Objects.equals(operation.getOperationToken(), binding.getOperationToken())
                        && (operation.getStatus() == LakeOperationStatus.PENDING
                        || operation.getStatus() == LakeOperationStatus.RUNNING))
                .max(Comparator.comparing(LakeResourceOperation::getStartedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private void ensureDeleteAllowed(Long bindingId) {
        List<LakeOdsTableMapping> mappings = tableMappingDao.queryByOdsDatabaseBindingId(bindingId);
        if (mappings != null && mappings.stream().anyMatch(item -> !Boolean.TRUE.equals(item.getDeleted()))) {
            throw new LakeServiceException(LakeErrorCode.LAKE_DATABASE_IN_USE,
                    "ODS database still has active table mappings");
        }
        List<LakeJobRelation> relations = jobRelationDao.queryByOdsDatabaseBindingId(bindingId);
        if (relations != null && relations.stream().anyMatch(item ->
                item.getRelationStatus() == LakeRelationStatus.ACTIVE
                        && item.getRelationScope() == LakeRelationScope.NAMESPACE)) {
            throw new LakeServiceException(LakeErrorCode.LAKE_DATABASE_IN_USE,
                    "ODS database still has an active namespace job relation");
        }
    }

    private LakePhysicalDataSourceVO toSourceVO(DataSource source) {
        LakePhysicalDataSourceVO result = new LakePhysicalDataSourceVO();
        result.setSourceDataSourceId(source.getId());
        result.setSourceDataSourceName(source.getName());
        result.setDbType(source.getDbType() == null ? null : source.getDbType().getCode());
        result.setBusinessSystemId(source.getBusinessSystemId());
        if (source.getBusinessSystemId() != null) {
            BusinessSystem system = businessSystemDao.queryById(source.getBusinessSystemId());
            if (system != null) {
                result.setSystemCode(system.getSystemCode());
                result.setUnitId(system.getUnitId());
                if (system.getUnitId() != null) {
                    DataSourceUnit unit = dataSourceUnitDao.queryById(system.getUnitId());
                    if (unit != null) {
                        result.setUnitCode(unit.getUnitCode());
                    }
                }
            }
        }
        LakeOdsDatabaseBinding binding = bindingDao.queryBySourceDataSourceId(source.getId());
        if (binding != null) {
            result.setOdsDatabaseBindingId(binding.getId());
            result.setOdsDatabase(toVO(binding));
        }
        return result;
    }

    private LakeOdsDatabaseVO toVO(LakeOdsDatabaseBinding source) {
        LakeOdsDatabaseVO result = new LakeOdsDatabaseVO();
        result.setId(source.getId());
        result.setLakeDataSourceId(source.getLakeDataSourceId());
        result.setSourceDataSourceId(source.getSourceDataSourceId());
        result.setUnitCode(source.getUnitCode());
        result.setSystemCode(source.getSystemCode());
        result.setDatabaseName(source.getDatabaseName());
        result.setResourceStatus(source.getResourceStatus());
        result.setGeneration(source.getGeneration());
        result.setLockVersion(source.getLockVersion());
        result.setErrorCode(source.getErrorCode());
        result.setErrorMessage(source.getErrorMessage());
        result.setLastReconcileAt(source.getLastReconcileAt());
        result.setCreateUserId(source.getCreateUserId());
        result.setUpdateUserId(source.getUpdateUserId());
        result.setDeleted(source.getDeleted());
        result.setCreateTime(source.getCreateTime());
        result.setUpdateTime(source.getUpdateTime());
        return result;
    }

    private static LakeServiceException conflict(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_RESOURCE_CONFLICT, message);
    }

    private static LakeServiceException stale(String message) {
        return new LakeServiceException(LakeErrorCode.LAKE_OPERATION_STALE, message);
    }

    private static LakeServiceException classifiedExternal(String code, String fallback) {
        return new LakeServiceException(code == null ? LakeErrorCode.LAKE_DORIS_UNAVAILABLE : code, fallback);
    }
}
