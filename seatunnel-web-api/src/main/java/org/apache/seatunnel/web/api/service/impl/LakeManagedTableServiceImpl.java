package org.apache.seatunnel.web.api.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeProperties;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.contract.TargetContractCanonicalizer;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.DorisDdlBuilder;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.operation.LakeExternalOperationException;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationHandle;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationIntent;
import org.apache.seatunnel.web.api.lake.operation.LakeOperationException;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceOperationCoordinator;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceTypes;
import org.apache.seatunnel.web.api.lake.source.LakeSourceObjectResolver;
import org.apache.seatunnel.web.api.lake.source.SourceObjectSnapshot;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableContractFactory;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableDeleteImpactVO;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableFieldMapping;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTablePreviewVO;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableRelationImpactVO;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableVO;
import org.apache.seatunnel.web.api.lake.table.LakePreviewTokenService;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.LakeManagedTableService;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationType;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeResourceOperation;
import org.apache.seatunnel.web.dao.entity.LakeSourceObjectRef;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableDeleteDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTablePreviewDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Task 6 MANAGED ODS table backend. */
@Service
public class LakeManagedTableServiceImpl implements LakeManagedTableService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSourceDao dataSourceDao;
    private final LakeOdsDatabaseBindingDao databaseBindingDao;
    private final LakeOdsTableMappingDao tableMappingDao;
    private final LakeSourceObjectRefDao sourceObjectRefDao;
    private final LakeJobRelationDao jobRelationDao;
    private final LakeTableLifecycleBindingDao lifecycleBindingDao;
    private final LakeSourceObjectResolver sourceResolver;
    private final LakeDorisClientProvider dorisClientProvider;
    private final LakeResourceOperationCoordinator coordinator;
    private final CurrentUserProvider currentUserProvider;
    private final LakePreviewTokenService previewTokenService;
    private final LakeManagedTableContractFactory contractFactory;
    private final DorisDdlBuilder ddlBuilder;
    private final LakeProperties lakeProperties;

    @Autowired
    public LakeManagedTableServiceImpl(
            DataSourceDao dataSourceDao,
            LakeOdsDatabaseBindingDao databaseBindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            LakeSourceObjectRefDao sourceObjectRefDao,
            LakeJobRelationDao jobRelationDao,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            LakeSourceObjectResolver sourceResolver,
            LakeDorisClientProvider dorisClientProvider,
            LakeResourceOperationCoordinator coordinator,
            CurrentUserProvider currentUserProvider,
            LakePreviewTokenService previewTokenService,
            LakeProperties lakeProperties) {
        this(dataSourceDao, databaseBindingDao, tableMappingDao, sourceObjectRefDao,
                jobRelationDao, lifecycleBindingDao, sourceResolver, dorisClientProvider,
                coordinator, currentUserProvider, previewTokenService, lakeProperties,
                new LakeManagedTableContractFactory(), new DorisDdlBuilder());
    }

    /** Visible for unit tests that provide deterministic factories. */
    public LakeManagedTableServiceImpl(
            DataSourceDao dataSourceDao,
            LakeOdsDatabaseBindingDao databaseBindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            LakeSourceObjectRefDao sourceObjectRefDao,
            LakeJobRelationDao jobRelationDao,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            LakeSourceObjectResolver sourceResolver,
            LakeDorisClientProvider dorisClientProvider,
            LakeResourceOperationCoordinator coordinator,
            CurrentUserProvider currentUserProvider,
            LakePreviewTokenService previewTokenService,
            LakeProperties lakeProperties,
            LakeManagedTableContractFactory contractFactory,
            DorisDdlBuilder ddlBuilder) {
        this.dataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.databaseBindingDao = Objects.requireNonNull(databaseBindingDao, "databaseBindingDao");
        this.tableMappingDao = Objects.requireNonNull(tableMappingDao, "tableMappingDao");
        this.sourceObjectRefDao = Objects.requireNonNull(sourceObjectRefDao, "sourceObjectRefDao");
        this.jobRelationDao = Objects.requireNonNull(jobRelationDao, "jobRelationDao");
        this.lifecycleBindingDao = Objects.requireNonNull(lifecycleBindingDao, "lifecycleBindingDao");
        this.sourceResolver = Objects.requireNonNull(sourceResolver, "sourceResolver");
        this.dorisClientProvider = Objects.requireNonNull(dorisClientProvider, "dorisClientProvider");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.currentUserProvider = Objects.requireNonNull(currentUserProvider, "currentUserProvider");
        this.previewTokenService = Objects.requireNonNull(previewTokenService, "previewTokenService");
        this.lakeProperties = Objects.requireNonNull(lakeProperties, "lakeProperties");
        this.contractFactory = Objects.requireNonNull(contractFactory, "contractFactory");
        this.ddlBuilder = Objects.requireNonNull(ddlBuilder, "ddlBuilder");
    }

    @Override
    public LakeManagedTablePreviewVO preview(LakeManagedTablePreviewDTO request) {
        validateRequest(request);
        Integer userId = requireCurrentUserId();
        LakeOdsDatabaseBinding binding = requireReadyBinding(
                request.getOdsDatabaseBindingId(), request.getSourceDataSourceId());
        SourceObjectSnapshot source = sourceResolver.resolve(
                request.getSourceDataSourceId(), request.getOmEntityId());
        String targetTableName = normalizeTableName(request.getTargetTableName());
        DorisLakeClient client = dorisClientProvider.get(binding.getLakeDataSourceId());
        boolean actualExists = tableExists(client, binding.getDatabaseName(), targetTableName);

        LakeSourceObjectRef existingRef = sourceObjectRefDao
                .queryByOmEntityIdIncludingDeleted(source.omEntityId());
        if (existingRef != null && !Objects.equals(
                existingRef.getSourceDataSourceId(), request.getSourceDataSourceId())) {
            return invalidPreview(request, "OpenMetadata table is already owned by another source");
        }
        LakeOdsTableMapping existingByTarget = tableMappingDao
                .queryByBindingIdAndTargetTableIncludingDeleted(binding.getId(), targetTableName);
        LakeOdsTableMapping existingBySource = existingRef == null ? null
                : tableMappingDao.queryByBindingIdAndSourceObjectIncludingDeleted(
                binding.getId(), existingRef.getId());
        if (existingByTarget != null && !sameSource(existingByTarget, existingRef)) {
            return invalidPreview(request, "Doris target table is already mapped");
        }
        if (existingBySource != null && !Boolean.TRUE.equals(existingBySource.getDeleted())) {
            return invalidPreview(request, "OpenMetadata table already has an active MANAGED mapping");
        }
        if (existingBySource != null && !targetTableName.equalsIgnoreCase(existingBySource.getTargetTableName())) {
            return invalidPreview(request, "A deleted MANAGED mapping cannot be renamed during rebuild");
        }
        if (actualExists) {
            // P0 has no adoption path.  An actual table is never silently
            // attached to a MANAGED contract from a browser request.
            return invalidPreview(request,
                    "Doris target table already exists; explicit adoption is not supported");
        }

        TargetContract contract;
        try {
            contract = contractFactory.build(source, request);
        } catch (IllegalArgumentException exception) {
            return invalidPreview(request, exception.getMessage());
        }
        String contractJson = writeJson(contract, "Target contract could not be serialized");
        List<LakeManagedTableFieldMapping> mappings = contractFactory.fieldMappings(contract);
        String mappingsJson = writeJson(mappings, "Field mappings could not be serialized");
        String contractHash = TargetContractCanonicalizer.canonicalHash(contract);
        String token = previewTokenService.issue(
                userId, request.getSourceDataSourceId(), source.omEntityId(), binding.getId(),
                existingBySource == null ? null : existingBySource.getId(), targetTableName,
                source.sourceSchemaHash(), contractHash, contractJson, mappingsJson);

        LakeManagedTablePreviewVO result = new LakeManagedTablePreviewVO();
        result.setValid(true);
        result.setPreviewToken(token);
        result.setSourceDataSourceId(request.getSourceDataSourceId());
        result.setOmEntityId(source.omEntityId());
        result.setOdsDatabaseBindingId(binding.getId());
        result.setTargetTableName(targetTableName);
        result.setSourceSchemaHash(source.sourceSchemaHash());
        result.setTargetContractHash(contractHash);
        result.setTargetContract(contract);
        result.setFieldMappings(mappings);
        result.setDdl(ddlBuilder.build(binding.getDatabaseName(), targetTableName, contract));
        return result;
    }

    @Override
    public LakeManagedTableVO create(LakeManagedTableCreateDTO request) {
        if (request == null || request.getPreviewToken() == null
                || request.getPreviewToken().isBlank()) {
            throw invalid("previewToken is required");
        }
        Integer userId = requireCurrentUserId();
        String token = request.getPreviewToken();
        LakePreviewTokenService.Payload payload;
        try {
            payload = previewTokenService.verify(token, userId);
        } catch (IllegalArgumentException exception) {
            throw invalid("previewToken is invalid or expired");
        }
        LakeOdsDatabaseBinding binding = requireReadyBinding(
                payload.odsDatabaseBindingId(), payload.sourceDataSourceId());
        SourceObjectSnapshot source = sourceResolver.resolve(
                payload.sourceDataSourceId(), payload.omEntityId());
        if (!Objects.equals(payload.sourceSchemaHash(), source.sourceSchemaHash())) {
            throw conflict("Source metadata changed after preview; request a new preview");
        }
        TargetContract contract = readContract(payload.targetContractJson());
        String contractHash = TargetContractCanonicalizer.canonicalHash(contract);
        if (!Objects.equals(payload.targetContractHash(), contractHash)) {
            throw invalid("previewToken contract is invalid");
        }
        try {
            contractFactory.validateAgainstSource(contract, source);
        } catch (IllegalArgumentException exception) {
            throw conflict("Source metadata changed after preview; request a new preview");
        }
        List<LakeManagedTableFieldMapping> mappings = contractFactory.fieldMappings(contract);
        if (!Objects.equals(payload.fieldMappingsJson(), writeJson(mappings,
                "Field mappings could not be serialized"))) {
            throw invalid("previewToken mappings are invalid");
        }
        String targetTableName = normalizeTableName(payload.targetTableName());
        DorisLakeClient client = dorisClientProvider.get(binding.getLakeDataSourceId());
        if (tableExists(client, binding.getDatabaseName(), targetTableName)) {
            throw conflict("Doris target table already exists; explicit adoption is not supported");
        }
        if (!previewTokenService.consume(token, payload)) {
            throw invalid("previewToken has already been used");
        }

        LakeSourceObjectRef sourceRef = ensureSourceRef(source, payload.sourceDataSourceId(), userId);
        LakeOdsTableMapping mapping = prepareMapping(
                payload.mappingId(), sourceRef, binding, targetTableName, contract, mappings, userId);
        LakeOperationHandle handle = begin(mapping, LakeOperationType.CREATE_TABLE,
                payload.mappingId() != null);
        return executeCreate(mapping, handle, client, contract, false);
    }

    @Override
    public LakeManagedTableVO detail(Long id) {
        return toVO(requireMappingIncludingDeleted(id));
    }

    @Override
    public LakeManagedTableVO retry(Long id) {
        LakeOdsTableMapping mapping = requireMappingIncludingDeleted(id);
        if (Boolean.TRUE.equals(mapping.getDeleted())) {
            throw conflict("Deleted MANAGED table requires a new preview before create");
        }
        LakeOdsDatabaseBinding binding = requireReadyBinding(
                mapping.getOdsDatabaseBindingId(), null);
        TargetContract contract = readStoredContract(mapping);
        DorisLakeClient client = dorisClientProvider.get(binding.getLakeDataSourceId());
        boolean actualExists = tableExists(client, mapping.getDatabaseName(), mapping.getTargetTableName());
        if (actualExists && !actualMatches(client, mapping, contract)) {
            throw conflict("Actual Doris table differs from the MANAGED contract; adoption is not supported");
        }
        if (actualExists && mapping.getResourceStatus() == LakeResourceStatus.READY
                && mapping.getOperationToken() == null) {
            return toVO(mapping);
        }
        LakeOperationHandle handle = retryHandle(mapping);
        return executeCreate(mapping, handle, client, contract, actualExists);
    }

    @Override
    public LakeManagedTableDeleteImpactVO deleteImpact(Long id) {
        LakeOdsTableMapping mapping = requireMappingIncludingDeleted(id);
        LakeManagedTableDeleteImpactVO result = new LakeManagedTableDeleteImpactVO();
        result.setMappingId(mapping.getId());
        result.setTargetTableName(mapping.getTargetTableName());
        result.setLifecycleBound(false);
        if (Boolean.TRUE.equals(mapping.getDeleted())) {
            result.setAllowed(true);
            result.setActualTableExists(false);
            result.setImpactHash(impactHash(result));
            return result;
        }
        LakeOdsDatabaseBinding binding = requireReadyBinding(mapping.getOdsDatabaseBindingId(), null);
        DorisLakeClient client = dorisClientProvider.get(binding.getLakeDataSourceId());
        result.setActualTableExists(tableExists(client, mapping.getDatabaseName(), mapping.getTargetTableName()));

        LakeTableLifecycleBinding lifecycle = lifecycleBindingDao.queryByTableMappingId(mapping.getId());
        boolean lifecycleBound = lifecycle != null;
        result.setLifecycleBound(lifecycleBound);
        if (lifecycleBound && lifecycle.getStatus() != LakeLifecycleBindingStatus.DISABLED) {
            result.getBlockers().add("An active lifecycle binding must be disabled first");
        }
        List<LakeJobRelation> relations = jobRelationDao.queryByOdsDatabaseBindingId(binding.getId());
        if (relations != null) {
            for (LakeJobRelation relation : relations) {
                if (relation == null || relation.getRelationStatus() != LakeRelationStatus.ACTIVE) {
                    continue;
                }
                if (Objects.equals(relation.getTableMappingId(), mapping.getId())
                        || relation.getRelationScope() == LakeRelationScope.NAMESPACE) {
                    result.getRelations().add(toImpact(relation));
                    result.getBlockers().add("An active job relation still references this table");
                }
            }
        }
        if (mapping.getOperationToken() != null) {
            result.getBlockers().add("The MANAGED table is currently being changed");
        }
        result.setAllowed(result.getBlockers().isEmpty());
        result.setImpactHash(impactHash(result));
        return result;
    }

    @Override
    public void delete(Long id, LakeManagedTableDeleteDTO request) {
        if (request == null || request.getTargetTableName() == null
                || request.getTargetTableName().isBlank()) {
            throw invalid("targetTableName confirmation is required");
        }
        LakeOdsTableMapping mapping = requireMappingIncludingDeleted(id);
        if (Boolean.TRUE.equals(mapping.getDeleted())) {
            return;
        }
        String confirmedName = normalizeTableName(request.getTargetTableName());
        if (!confirmedName.equalsIgnoreCase(mapping.getTargetTableName())) {
            throw invalid("targetTableName confirmation does not match");
        }
        LakeManagedTableDeleteImpactVO impact = deleteImpact(id);
        if (request.getImpactHash() == null || !request.getImpactHash().equals(impact.getImpactHash())) {
            throw conflict("Delete impact is stale; request a new impact preview");
        }
        if (!impact.isAllowed()) {
            throw conflict(impact.getBlockers().isEmpty()
                    ? "MANAGED table cannot be deleted" : impact.getBlockers().get(0));
        }
        LakeOdsDatabaseBinding binding = requireReadyBinding(mapping.getOdsDatabaseBindingId(), null);
        DorisLakeClient client = dorisClientProvider.get(binding.getLakeDataSourceId());
        LakeOperationHandle handle = begin(mapping, LakeOperationType.DROP_TABLE, false);
        AtomicReference<String> errorCode = new AtomicReference<>();
        try {
            coordinator.execute(handle, () -> {
                try {
                    client.dropTable(mapping.getDatabaseName(), mapping.getTargetTableName());
                    if (client.tableExists(mapping.getDatabaseName(), mapping.getTargetTableName())) {
                        errorCode.set(LakeErrorCode.LAKE_RESOURCE_CONFLICT);
                        throw new LakeExternalOperationException(
                                LakeErrorCode.LAKE_RESOURCE_CONFLICT,
                                "Doris table deletion could not be verified");
                    }
                    return Boolean.TRUE;
                } catch (LakeExternalOperationException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    errorCode.set(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
                    throw new LakeExternalOperationException(
                            LakeErrorCode.LAKE_DORIS_UNAVAILABLE, "Doris table deletion is unavailable");
                }
            });
            if (!coordinator.finalizeSuccess(handle, "Doris table deleted")) {
                throw stale("The table delete result is stale");
            }
        } catch (LakeExternalOperationException exception) {
            throw classifiedExternal(exception.getErrorCode(), "Doris table delete is unavailable");
        } catch (LakeOperationException exception) {
            throw classifiedExternal(errorCode.get(), "Doris table delete is unavailable");
        }
    }

    private LakeManagedTableVO executeCreate(
            LakeOdsTableMapping mapping,
            LakeOperationHandle handle,
            DorisLakeClient client,
            TargetContract contract,
            boolean actualExists) {
        AtomicReference<String> errorCode = new AtomicReference<>();
        try {
            coordinator.execute(handle, () -> {
                try {
                    if (!actualExists) {
                        // The client receives the validated structured
                        // contract.  No request field contains executable SQL.
                        client.createTable(mapping.getDatabaseName(), mapping.getTargetTableName(), contract);
                    }
                    if (!client.tableExists(mapping.getDatabaseName(), mapping.getTargetTableName())) {
                        errorCode.set(LakeErrorCode.LAKE_RESOURCE_CONFLICT);
                        throw new LakeExternalOperationException(
                                LakeErrorCode.LAKE_RESOURCE_CONFLICT,
                                "Doris table is missing after create");
                    }
                    TargetContract actual = client.readContract(
                            mapping.getDatabaseName(), mapping.getTargetTableName());
                    if (!Objects.equals(TargetContractCanonicalizer.canonicalHash(actual),
                            TargetContractCanonicalizer.canonicalHash(contract))) {
                        errorCode.set(LakeErrorCode.LAKE_RESOURCE_CONFLICT);
                        throw new LakeExternalOperationException(
                                LakeErrorCode.LAKE_RESOURCE_CONFLICT,
                                "Doris table differs from the MANAGED contract");
                    }
                    return Boolean.TRUE;
                } catch (LakeExternalOperationException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    errorCode.set(LakeErrorCode.LAKE_DORIS_UNAVAILABLE);
                    throw new LakeExternalOperationException(
                            LakeErrorCode.LAKE_DORIS_UNAVAILABLE, "Doris table operation is unavailable");
                }
            });
            if (!coordinator.finalizeSuccess(handle, "Doris table exists and matches contract")) {
                throw stale("The table create result is stale");
            }
            return detail(mapping.getId());
        } catch (LakeExternalOperationException exception) {
            throw classifiedExternal(exception.getErrorCode(), "Doris table create is unavailable");
        } catch (LakeOperationException exception) {
            throw classifiedExternal(errorCode.get(), "Doris table create is unavailable");
        }
    }

    private LakeOperationHandle retryHandle(LakeOdsTableMapping mapping) {
        if (mapping.getOperationToken() == null) {
            return begin(mapping, LakeOperationType.RETRY, false);
        }
        LakeResourceOperation operation = latestOpenOperation(mapping);
        if (operation == null) {
            throw stale("The active MANAGED table operation cannot be retried");
        }
        LakeOperationHandle staleHandle = new LakeOperationHandle(
                operation.getId(), LakeResourceTypes.ODS_TABLE_MAPPING, mapping.getId(),
                mapping.getGeneration(), mapping.getOperationToken(), mapping.getLockVersion());
        LakeOperationIntent retryIntent = intent(mapping, LakeOperationType.RETRY);
        retryIntent.setGeneration(mapping.getGeneration());
        retryIntent.setLockVersion(mapping.getLockVersion());
        retryIntent.setOperationToken(mapping.getOperationToken());
        try {
            return coordinator.takeOverStale(staleHandle, retryIntent);
        } catch (LakeOperationException exception) {
            throw stale("The MANAGED table operation is not stale or was already replaced");
        }
    }

    private LakeOperationHandle begin(
            LakeOdsTableMapping mapping, LakeOperationType operationType, boolean rebuild) {
        LakeOperationIntent intent = intent(mapping, operationType);
        intent.setRebuild(rebuild);
        try {
            return coordinator.begin(intent);
        } catch (LakeOperationException exception) {
            throw conflict("The MANAGED table is currently being changed");
        }
    }

    private LakeOperationIntent intent(LakeOdsTableMapping mapping, LakeOperationType operationType) {
        return new LakeOperationIntent(
                LakeResourceTypes.ODS_TABLE_MAPPING,
                mapping.getId(),
                operationType,
                mapping.getTargetContractHash(),
                requireCurrentUserId());
    }

    private LakeOdsTableMapping prepareMapping(
            Long expectedMappingId,
            LakeSourceObjectRef sourceRef,
            LakeOdsDatabaseBinding binding,
            String targetTableName,
            TargetContract contract,
            List<LakeManagedTableFieldMapping> mappings,
            Integer userId) {
        LakeOdsTableMapping bySource = tableMappingDao
                .queryByBindingIdAndSourceObjectIncludingDeleted(binding.getId(), sourceRef.getId());
        LakeOdsTableMapping byTarget = tableMappingDao
                .queryByBindingIdAndTargetTableIncludingDeleted(binding.getId(), targetTableName);
        LakeOdsTableMapping mapping = bySource != null ? bySource : byTarget;
        if (expectedMappingId != null
                && (mapping == null || !expectedMappingId.equals(mapping.getId()))) {
            throw invalid("previewToken mapping no longer exists");
        }
        if (byTarget != null && bySource != null && !Objects.equals(byTarget.getId(), bySource.getId())) {
            throw conflict("Doris target table is already reserved by another MANAGED mapping");
        }
        if (mapping != null && !Boolean.TRUE.equals(mapping.getDeleted())) {
            throw conflict("OpenMetadata table already has an active MANAGED mapping");
        }
        boolean rebuild = mapping != null;
        if (rebuild && !targetTableName.equalsIgnoreCase(mapping.getTargetTableName())) {
            throw conflict("A deleted MANAGED mapping cannot be renamed during rebuild");
        }
        if (!rebuild) {
            mapping = new LakeOdsTableMapping();
            mapping.initInsert();
            mapping.setGeneration(1);
            mapping.setLockVersion(1);
            mapping.setDeleted(false);
            mapping.setCreateUserId(userId);
        }
        fillMapping(mapping, sourceRef, binding, targetTableName, contract, mappings, userId);
        if (rebuild) {
            mapping.setDeleted(false);
            mapping.setResourceStatus(LakeResourceStatus.PENDING_CREATE);
            mapping.setOperationToken(null);
            if (!tableMappingDao.updateById(mapping)) {
                throw conflict("Deleted MANAGED mapping could not be reopened");
            }
        } else {
            try {
                if (tableMappingDao.insert(mapping) <= 0) {
                    throw conflict("MANAGED table mapping could not be persisted");
                }
            } catch (DuplicateKeyException exception) {
                throw conflict("MANAGED table mapping already exists");
            }
        }
        return mapping;
    }

    private void fillMapping(
            LakeOdsTableMapping mapping,
            LakeSourceObjectRef sourceRef,
            LakeOdsDatabaseBinding binding,
            String targetTableName,
            TargetContract contract,
            List<LakeManagedTableFieldMapping> mappings,
            Integer userId) {
        mapping.setSourceObjectRefId(sourceRef.getId());
        mapping.setOdsDatabaseBindingId(binding.getId());
        mapping.setLakeDataSourceId(binding.getLakeDataSourceId());
        mapping.setDatabaseName(binding.getDatabaseName());
        mapping.setTargetTableName(targetTableName);
        mapping.setManagementLevel(LakeManagementLevel.MANAGED);
        mapping.setTableModel(contract.getTableModel());
        mapping.setSourceSchemaHash(sourceRef.getSourceSchemaHash());
        mapping.setTargetContractHash(TargetContractCanonicalizer.canonicalHash(contract));
        mapping.setSourceSnapshotJson(sourceRef.getSourceSnapshotJson());
        mapping.setTargetContractJson(writeJson(contract, "Target contract could not be serialized"));
        mapping.setFieldMappingsJson(writeJson(mappings, "Field mappings could not be serialized"));
        mapping.setSourceConsistencyStatus(LakeConsistencyStatus.CONSISTENT);
        mapping.setTargetConsistencyStatus(LakeConsistencyStatus.UNKNOWN);
        mapping.setTaskConsistencyStatus(LakeConsistencyStatus.UNBOUND);
        mapping.setActualTableExists(false);
        mapping.setResourceStatus(LakeResourceStatus.PENDING_CREATE);
        mapping.setErrorCode(null);
        mapping.setErrorMessage(null);
        mapping.setUpdateUserId(userId);
        mapping.initUpdate();
    }

    private LakeSourceObjectRef ensureSourceRef(
            SourceObjectSnapshot source, Long sourceDataSourceId, Integer userId) {
        LakeSourceObjectRef reference = sourceObjectRefDao
                .queryByOmEntityIdIncludingDeleted(source.omEntityId());
        if (reference != null && !Objects.equals(reference.getSourceDataSourceId(), sourceDataSourceId)) {
            throw conflict("OpenMetadata table is already owned by another source");
        }
        if (reference == null) {
            reference = new LakeSourceObjectRef();
            reference.initInsert();
            reference.setGeneration(1);
            reference.setLockVersion(1);
            reference.setCreateUserId(userId);
            reference.setResourceStatus(LakeResourceStatus.READY);
            reference.setDeleted(false);
            reference.setOperationToken(null);
            reference.setSourceDataSourceId(sourceDataSourceId);
            reference.setOmEntityId(source.omEntityId());
            reference.setOmFqn(source.omFqn());
            reference.setObjectType(org.apache.seatunnel.web.common.enums.LakeSourceObjectType.TABLE);
            reference.setSourceSchemaHash(source.sourceSchemaHash());
            reference.setSourceSnapshotJson(source.snapshotJson());
            try {
                if (sourceObjectRefDao.insert(reference) <= 0) {
                    throw conflict("Source object reference could not be persisted");
                }
            } catch (DuplicateKeyException exception) {
                throw conflict("Source object reference already exists");
            }
            return reference;
        }
        reference.setDeleted(false);
        reference.setResourceStatus(LakeResourceStatus.READY);
        reference.setSourceDataSourceId(sourceDataSourceId);
        reference.setOmEntityId(source.omEntityId());
        reference.setOmFqn(source.omFqn());
        reference.setObjectType(org.apache.seatunnel.web.common.enums.LakeSourceObjectType.TABLE);
        reference.setSourceSchemaHash(source.sourceSchemaHash());
        reference.setSourceSnapshotJson(source.snapshotJson());
        reference.setUpdateUserId(userId);
        reference.initUpdate();
        if (!sourceObjectRefDao.updateById(reference)) {
            throw conflict("Source object reference could not be updated");
        }
        return reference;
    }

    private LakeOdsDatabaseBinding requireReadyBinding(Long bindingId, Long sourceDataSourceId) {
        if (bindingId == null || bindingId <= 0) {
            throw conflict("ODS database binding does not exist");
        }
        LakeOdsDatabaseBinding binding = databaseBindingDao.queryActiveById(bindingId);
        if (binding == null || Boolean.TRUE.equals(binding.getDeleted())) {
            throw conflict("ODS database binding does not exist");
        }
        if (sourceDataSourceId != null
                && !Objects.equals(binding.getSourceDataSourceId(), sourceDataSourceId)) {
            throw conflict("ODS database binding does not belong to this source");
        }
        if (binding.getResourceStatus() != LakeResourceStatus.READY) {
            throw conflict("ODS database binding is not ready");
        }
        return binding;
    }

    private LakeOdsTableMapping requireMappingIncludingDeleted(Long id) {
        if (id == null || id <= 0) {
            throw conflict("MANAGED table mapping does not exist");
        }
        LakeOdsTableMapping mapping = tableMappingDao.queryByIdIncludingDeleted(id);
        if (mapping == null) {
            throw conflict("MANAGED table mapping does not exist");
        }
        return mapping;
    }

    private TargetContract readStoredContract(LakeOdsTableMapping mapping) {
        if (mapping.getTargetContractJson() == null || mapping.getTargetContractJson().isBlank()) {
            throw conflict("MANAGED target contract is missing");
        }
        TargetContract contract = readContract(mapping.getTargetContractJson());
        if (!Objects.equals(mapping.getTargetContractHash(),
                TargetContractCanonicalizer.canonicalHash(contract))) {
            throw conflict("MANAGED target contract is invalid");
        }
        return contract;
    }

    private TargetContract readContract(String json) {
        try {
            return org.apache.seatunnel.web.api.lake.contract.TargetContractValidator
                    .validateAndNormalize(MAPPER.readValue(json, TargetContract.class));
        } catch (Exception exception) {
            throw invalid("Target contract is invalid");
        }
    }

    private boolean actualMatches(
            DorisLakeClient client, LakeOdsTableMapping mapping, TargetContract expected) {
        try {
            TargetContract actual = client.readContract(mapping.getDatabaseName(), mapping.getTargetTableName());
            return Objects.equals(TargetContractCanonicalizer.canonicalHash(actual),
                    TargetContractCanonicalizer.canonicalHash(expected));
        } catch (RuntimeException exception) {
            throw new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "Doris table metadata is unavailable");
        }
    }

    private boolean tableExists(DorisLakeClient client, String databaseName, String tableName) {
        try {
            return client.tableExists(databaseName, tableName);
        } catch (RuntimeException exception) {
            throw new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "Doris table lookup is unavailable");
        }
    }

    private LakeResourceOperation latestOpenOperation(LakeOdsTableMapping mapping) {
        return coordinator.queryByResource(LakeResourceTypes.ODS_TABLE_MAPPING, mapping.getId()).stream()
                .filter(operation -> Objects.equals(operation.getOperationToken(), mapping.getOperationToken())
                        && (operation.getStatus() == LakeOperationStatus.PENDING
                        || operation.getStatus() == LakeOperationStatus.RUNNING))
                .max(Comparator.comparing(LakeResourceOperation::getStartedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private LakeManagedTableVO toVO(LakeOdsTableMapping mapping) {
        LakeManagedTableVO result = new LakeManagedTableVO();
        result.setId(mapping.getId());
        result.setSourceObjectRefId(mapping.getSourceObjectRefId());
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
        result.setTargetContractHash(mapping.getTargetContractHash());
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
        LakeSourceObjectRef sourceRef = sourceObjectRefDao
                .queryByIdIncludingDeleted(mapping.getSourceObjectRefId());
        if (sourceRef != null) {
            result.setSourceDataSourceId(sourceRef.getSourceDataSourceId());
            result.setOmEntityId(sourceRef.getOmEntityId());
            result.setOmFqn(sourceRef.getOmFqn());
        }
        result.setTargetContract(readStoredContract(mapping));
        result.setFieldMappings(readMappings(mapping.getFieldMappingsJson()));
        return result;
    }

    private List<LakeManagedTableFieldMapping> readMappings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(MAPPER.readValue(json,
                    new TypeReference<List<LakeManagedTableFieldMapping>>() {
                    }));
        } catch (Exception exception) {
            throw conflict("MANAGED field mappings are invalid");
        }
    }

    private static LakeManagedTableRelationImpactVO toImpact(LakeJobRelation relation) {
        LakeManagedTableRelationImpactVO result = new LakeManagedTableRelationImpactVO();
        result.setRelationId(relation.getId());
        result.setJobId(relation.getJobId());
        result.setJobVersion(relation.getJobVersion());
        result.setRelationScope(relation.getRelationScope());
        result.setJobRuntimeType(relation.getJobRuntimeType());
        result.setRelationStatus(relation.getRelationStatus());
        return result;
    }

    private static String impactHash(LakeManagedTableDeleteImpactVO impact) {
        StringBuilder value = new StringBuilder()
                .append(impact.getMappingId()).append('\u0000')
                .append(impact.getTargetTableName()).append('\u0000')
                .append(impact.isActualTableExists()).append('\u0000')
                .append(impact.isLifecycleBound()).append('\u0000')
                .append(impact.isAllowed()).append('\u0000');
        impact.getRelations().stream()
                .sorted(Comparator.comparing(LakeManagedTableRelationImpactVO::getRelationId,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(relation -> value.append(relation.getRelationId()).append(':')
                        .append(relation.getJobId()).append(':').append(relation.getJobVersion()).append(':')
                        .append(relation.getRelationScope()).append(':').append(relation.getJobRuntimeType())
                        .append(':').append(relation.getRelationStatus()).append('\u0000'));
        impact.getBlockers().forEach(blocker -> value.append(blocker).append('\u0000'));
        return org.apache.seatunnel.web.api.lake.source.SourceSchemaCanonicalizer.sha256(value.toString());
    }

    private static boolean sameSource(LakeOdsTableMapping mapping, LakeSourceObjectRef sourceRef) {
        return sourceRef != null && Objects.equals(mapping.getSourceObjectRefId(), sourceRef.getId());
    }

    private static LakeManagedTablePreviewVO invalidPreview(
            LakeManagedTablePreviewDTO request, String message) {
        LakeManagedTablePreviewVO result = new LakeManagedTablePreviewVO();
        result.setValid(false);
        if (request != null) {
            result.setSourceDataSourceId(request.getSourceDataSourceId());
            result.setOmEntityId(request.getOmEntityId());
            result.setOdsDatabaseBindingId(request.getOdsDatabaseBindingId());
            result.setTargetTableName(request.getTargetTableName());
        }
        result.getErrors().add(message == null || message.isBlank()
                ? "MANAGED table preview is invalid" : message);
        return result;
    }

    private static void validateRequest(LakeManagedTablePreviewDTO request) {
        if (request == null || request.getSourceDataSourceId() == null
                || request.getSourceDataSourceId() <= 0
                || request.getOdsDatabaseBindingId() == null
                || request.getOdsDatabaseBindingId() <= 0
                || request.getOmEntityId() == null || request.getOmEntityId().isBlank()
                || request.getTargetTableName() == null || request.getTargetTableName().isBlank()) {
            throw invalid("sourceDataSourceId, omEntityId, odsDatabaseBindingId and targetTableName are required");
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
            return org.apache.seatunnel.web.api.lake.DorisIdentifier.normalize(value);
        } catch (IllegalArgumentException exception) {
            throw invalid("targetTableName is not a valid Doris identifier");
        }
    }

    private static String writeJson(Object value, String message) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new LakeServiceException(LakeErrorCode.LAKE_RESOURCE_CONFLICT, message);
        }
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

    private static LakeServiceException classifiedExternal(String code, String fallback) {
        return new LakeServiceException(code == null ? LakeErrorCode.LAKE_DORIS_UNAVAILABLE : code, fallback);
    }
}
