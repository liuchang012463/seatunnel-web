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
import org.apache.seatunnel.web.api.lake.operation.LakeManagedTableOperationPublication;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceOperationCoordinator;
import org.apache.seatunnel.web.api.lake.operation.LakeResourceTypes;
import org.apache.seatunnel.web.api.lake.table.LakeTableDriftEvaluator;
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
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationType;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.common.enums.ScheduleStatusEnum;
import org.apache.seatunnel.web.common.enums.TaskExecutionMode;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeResourceOperation;
import org.apache.seatunnel.web.dao.entity.LakeSourceObjectRef;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.dao.entity.JobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.JobSchedule;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionEntity;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.JobScheduleDao;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeLifecyclePolicyDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
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
    private final JobDefinitionDao batchJobDefinitionDao;
    private final StreamingJobDefinitionDao streamingJobDefinitionDao;
    private final JobScheduleDao jobScheduleDao;
    private final JobInstanceDao jobInstanceDao;
    private final StreamingJobInstanceDao streamingJobInstanceDao;
    private final LakeTableLifecycleBindingDao lifecycleBindingDao;
    private final LakeLifecyclePolicyDao lifecyclePolicyDao;
    private final LakeSourceObjectResolver sourceResolver;
    private final LakeDorisClientProvider dorisClientProvider;
    private final LakeResourceOperationCoordinator coordinator;
    private final CurrentUserProvider currentUserProvider;
    private final LakePreviewTokenService previewTokenService;
    private final LakeManagedTableContractFactory contractFactory;
    private final DorisDdlBuilder ddlBuilder;
    private final LakeProperties lakeProperties;
    private final LakeTableDriftEvaluator driftEvaluator;
    private final LakeTableReconcilePersistenceService reconcilePersistenceService;
    private final LakeManagedTableLifecycleCreatePersistenceService lifecycleCreatePersistenceService;

    @Autowired
    public LakeManagedTableServiceImpl(
            DataSourceDao dataSourceDao,
            LakeOdsDatabaseBindingDao databaseBindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            LakeSourceObjectRefDao sourceObjectRefDao,
            LakeJobRelationDao jobRelationDao,
            JobDefinitionDao batchJobDefinitionDao,
            StreamingJobDefinitionDao streamingJobDefinitionDao,
            JobScheduleDao jobScheduleDao,
            JobInstanceDao jobInstanceDao,
            StreamingJobInstanceDao streamingJobInstanceDao,
            LakeTableLifecycleBindingDao lifecycleBindingDao,
            LakeLifecyclePolicyDao lifecyclePolicyDao,
            LakeSourceObjectResolver sourceResolver,
            LakeDorisClientProvider dorisClientProvider,
            LakeResourceOperationCoordinator coordinator,
            CurrentUserProvider currentUserProvider,
            LakePreviewTokenService previewTokenService,
            LakeProperties lakeProperties,
            LakeTableDriftEvaluator driftEvaluator,
            LakeTableReconcilePersistenceService reconcilePersistenceService,
            LakeManagedTableLifecycleCreatePersistenceService lifecycleCreatePersistenceService) {
        this(dataSourceDao, databaseBindingDao, tableMappingDao, sourceObjectRefDao,
                jobRelationDao, lifecycleBindingDao, sourceResolver, dorisClientProvider,
                coordinator, currentUserProvider, previewTokenService, lakeProperties,
                new LakeManagedTableContractFactory(), new DorisDdlBuilder(),
                batchJobDefinitionDao, streamingJobDefinitionDao, jobScheduleDao,
                jobInstanceDao, streamingJobInstanceDao, driftEvaluator,
                reconcilePersistenceService, lifecyclePolicyDao, lifecycleCreatePersistenceService);
    }

    /** Backwards-compatible constructor for callers that do not reconcile. */
    public LakeManagedTableServiceImpl(
            DataSourceDao dataSourceDao,
            LakeOdsDatabaseBindingDao databaseBindingDao,
            LakeOdsTableMappingDao tableMappingDao,
            LakeSourceObjectRefDao sourceObjectRefDao,
            LakeJobRelationDao jobRelationDao,
            JobDefinitionDao batchJobDefinitionDao,
            StreamingJobDefinitionDao streamingJobDefinitionDao,
            JobScheduleDao jobScheduleDao,
            JobInstanceDao jobInstanceDao,
            StreamingJobInstanceDao streamingJobInstanceDao,
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
                new LakeManagedTableContractFactory(), new DorisDdlBuilder(),
                batchJobDefinitionDao, streamingJobDefinitionDao, jobScheduleDao,
                jobInstanceDao, streamingJobInstanceDao, null, null);
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
        this(dataSourceDao, databaseBindingDao, tableMappingDao, sourceObjectRefDao,
                jobRelationDao, lifecycleBindingDao, sourceResolver, dorisClientProvider,
                coordinator, currentUserProvider, previewTokenService, lakeProperties,
                contractFactory, ddlBuilder, null, null, null, null, null, null, null);
    }

    /** Visible for unit tests that also exercise job lifecycle delete guards. */
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
            DorisDdlBuilder ddlBuilder,
            JobDefinitionDao batchJobDefinitionDao,
            StreamingJobDefinitionDao streamingJobDefinitionDao,
            JobScheduleDao jobScheduleDao,
            JobInstanceDao jobInstanceDao,
            StreamingJobInstanceDao streamingJobInstanceDao) {
        this(dataSourceDao, databaseBindingDao, tableMappingDao, sourceObjectRefDao,
                jobRelationDao, lifecycleBindingDao, sourceResolver, dorisClientProvider,
                coordinator, currentUserProvider, previewTokenService, lakeProperties,
                contractFactory, ddlBuilder, batchJobDefinitionDao, streamingJobDefinitionDao,
                jobScheduleDao, jobInstanceDao, streamingJobInstanceDao, null, null, null);
    }

    /** Constructor used by reconcile tests that provide the read-only evaluator. */
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
            DorisDdlBuilder ddlBuilder,
            JobDefinitionDao batchJobDefinitionDao,
            StreamingJobDefinitionDao streamingJobDefinitionDao,
            JobScheduleDao jobScheduleDao,
            JobInstanceDao jobInstanceDao,
            StreamingJobInstanceDao streamingJobInstanceDao,
            LakeTableDriftEvaluator driftEvaluator) {
        this(dataSourceDao, databaseBindingDao, tableMappingDao, sourceObjectRefDao,
                jobRelationDao, lifecycleBindingDao, sourceResolver, dorisClientProvider,
                coordinator, currentUserProvider, previewTokenService, lakeProperties,
                contractFactory, ddlBuilder, batchJobDefinitionDao, streamingJobDefinitionDao,
                jobScheduleDao, jobInstanceDao, streamingJobInstanceDao, driftEvaluator, null, null);
    }

    /** Constructor used by reconcile tests that provide the evaluator and short CAS writer. */
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
            DorisDdlBuilder ddlBuilder,
            JobDefinitionDao batchJobDefinitionDao,
            StreamingJobDefinitionDao streamingJobDefinitionDao,
            JobScheduleDao jobScheduleDao,
            JobInstanceDao jobInstanceDao,
            StreamingJobInstanceDao streamingJobInstanceDao,
            LakeTableDriftEvaluator driftEvaluator,
            LakeTableReconcilePersistenceService reconcilePersistenceService) {
        this(dataSourceDao, databaseBindingDao, tableMappingDao, sourceObjectRefDao,
                jobRelationDao, lifecycleBindingDao, sourceResolver, dorisClientProvider,
                coordinator, currentUserProvider, previewTokenService, lakeProperties,
                contractFactory, ddlBuilder, batchJobDefinitionDao, streamingJobDefinitionDao,
                jobScheduleDao, jobInstanceDao, streamingJobInstanceDao, driftEvaluator,
                reconcilePersistenceService, null);
    }

    /** Constructor allowing managed-table tests to provide the policy reader. */
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
            DorisDdlBuilder ddlBuilder,
            JobDefinitionDao batchJobDefinitionDao,
            StreamingJobDefinitionDao streamingJobDefinitionDao,
            JobScheduleDao jobScheduleDao,
            JobInstanceDao jobInstanceDao,
            StreamingJobInstanceDao streamingJobInstanceDao,
            LakeTableDriftEvaluator driftEvaluator,
            LakeTableReconcilePersistenceService reconcilePersistenceService,
            LakeLifecyclePolicyDao lifecyclePolicyDao) {
        this(dataSourceDao, databaseBindingDao, tableMappingDao, sourceObjectRefDao,
                jobRelationDao, lifecycleBindingDao, sourceResolver, dorisClientProvider,
                coordinator, currentUserProvider, previewTokenService, lakeProperties,
                contractFactory, ddlBuilder, batchJobDefinitionDao, streamingJobDefinitionDao,
                jobScheduleDao, jobInstanceDao, streamingJobInstanceDao, driftEvaluator,
                reconcilePersistenceService, lifecyclePolicyDao, null);
    }

    /** Full constructor with atomic lifecycle-create persistence. */
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
            DorisDdlBuilder ddlBuilder,
            JobDefinitionDao batchJobDefinitionDao,
            StreamingJobDefinitionDao streamingJobDefinitionDao,
            JobScheduleDao jobScheduleDao,
            JobInstanceDao jobInstanceDao,
            StreamingJobInstanceDao streamingJobInstanceDao,
            LakeTableDriftEvaluator driftEvaluator,
            LakeTableReconcilePersistenceService reconcilePersistenceService,
            LakeLifecyclePolicyDao lifecyclePolicyDao,
            LakeManagedTableLifecycleCreatePersistenceService lifecycleCreatePersistenceService) {
        this.dataSourceDao = Objects.requireNonNull(dataSourceDao, "dataSourceDao");
        this.databaseBindingDao = Objects.requireNonNull(databaseBindingDao, "databaseBindingDao");
        this.tableMappingDao = Objects.requireNonNull(tableMappingDao, "tableMappingDao");
        this.sourceObjectRefDao = Objects.requireNonNull(sourceObjectRefDao, "sourceObjectRefDao");
        this.jobRelationDao = Objects.requireNonNull(jobRelationDao, "jobRelationDao");
        this.batchJobDefinitionDao = batchJobDefinitionDao;
        this.streamingJobDefinitionDao = streamingJobDefinitionDao;
        this.jobScheduleDao = jobScheduleDao;
        this.jobInstanceDao = jobInstanceDao;
        this.streamingJobInstanceDao = streamingJobInstanceDao;
        this.lifecycleBindingDao = Objects.requireNonNull(lifecycleBindingDao, "lifecycleBindingDao");
        this.lifecyclePolicyDao = lifecyclePolicyDao;
        this.sourceResolver = Objects.requireNonNull(sourceResolver, "sourceResolver");
        this.dorisClientProvider = Objects.requireNonNull(dorisClientProvider, "dorisClientProvider");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.currentUserProvider = Objects.requireNonNull(currentUserProvider, "currentUserProvider");
        this.previewTokenService = Objects.requireNonNull(previewTokenService, "previewTokenService");
        this.lakeProperties = Objects.requireNonNull(lakeProperties, "lakeProperties");
        this.contractFactory = Objects.requireNonNull(contractFactory, "contractFactory");
        this.ddlBuilder = Objects.requireNonNull(ddlBuilder, "ddlBuilder");
        this.driftEvaluator = driftEvaluator == null
                ? new LakeTableDriftEvaluator(sourceObjectRefDao, jobRelationDao,
                sourceResolver, dorisClientProvider, null)
                : driftEvaluator;
        this.reconcilePersistenceService = reconcilePersistenceService == null
                ? new LakeTableReconcilePersistenceService(tableMappingDao)
                : reconcilePersistenceService;
        this.lifecycleCreatePersistenceService = lifecycleCreatePersistenceService;
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
        LakeLifecyclePolicy lifecyclePolicy;
        try {
            lifecyclePolicy = readPreviewLifecyclePolicy(request.getLifecyclePolicyId(), contract, source);
        } catch (LakeServiceException exception) {
            return invalidPreview(request, exception.getMessage());
        }
        String lifecycleSnapshot = lifecyclePolicy == null
                ? null : lifecyclePolicySnapshot(lifecyclePolicy);
        java.util.Map<String, String> lifecycleProperties = lifecycleProperties(lifecyclePolicy);
        String contractJson = writeJson(contract, "Target contract could not be serialized");
        List<LakeManagedTableFieldMapping> mappings = contractFactory.fieldMappings(contract);
        String mappingsJson = writeJson(mappings, "Field mappings could not be serialized");
        String contractHash = TargetContractCanonicalizer.canonicalHash(contract);
        String token;
        if (lifecyclePolicy == null) {
            token = previewTokenService.issue(
                    userId, request.getSourceDataSourceId(), source.omEntityId(), binding.getId(),
                    existingBySource == null ? null : existingBySource.getId(), targetTableName,
                    source.sourceSchemaHash(), contractHash, contractJson, mappingsJson);
        } else {
            token = previewTokenService.issue(
                    userId, request.getSourceDataSourceId(), source.omEntityId(), binding.getId(),
                    existingBySource == null ? null : existingBySource.getId(), targetTableName,
                    source.sourceSchemaHash(), contractHash, contractJson, mappingsJson,
                    lifecyclePolicy.getId(), lifecyclePolicy.getVersion(), lifecycleSnapshot,
                    contract.getPartition().getColumn(), contract.getPartition().getGranularity(),
                    lifecyclePolicy.getRetentionCount(),
                    "partition.retention_count",
                    lifecycleProperties.get("partition.retention_count"));
        }

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
        result.setDdl(ddlBuilder.build(binding.getDatabaseName(), targetTableName, contract,
                lifecycleProperties));
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
        LakeLifecyclePolicy lifecyclePolicy = readCreateLifecyclePolicy(payload, contract, source);
        java.util.Map<String, String> lifecycleProperties = lifecycleProperties(lifecyclePolicy);
        String contractHash = TargetContractCanonicalizer.canonicalHash(contract);
        if (!Objects.equals(payload.targetContractHash(), contractHash)) {
            throw invalid("previewToken contract is invalid");
        }
        if (payload.hasLifecyclePolicy()
                && !Objects.equals(payload.lifecycleIntentHash(),
                LakePreviewTokenService.lifecycleIntentHash(
                        payload.lifecyclePolicyId(), payload.lifecyclePolicyVersion(),
                        payload.lifecyclePolicySnapshotJson(), payload.lifecyclePartitionColumn(),
                        payload.lifecycleGranularity(), payload.lifecycleRetentionCount(),
                        payload.lifecyclePropertyKey(), payload.lifecyclePropertyValue()))) {
            throw invalid("previewToken lifecycle intent is invalid");
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
        LakeOdsTableMapping mapping;
        LakeOperationHandle handle;
        LakeManagedTableOperationPublication publication = null;
        if (payload.hasLifecyclePolicy() && lifecycleCreatePersistenceService != null) {
            mapping = prepareMappingCandidate(
                    payload.mappingId(), sourceRef, binding, targetTableName,
                    contract, mappings, userId);
            LakeManagedTableLifecycleCreatePersistenceService.StartResult start =
                    lifecycleCreatePersistenceService.start(
                            mapping,
                            new LakeManagedTableLifecycleCreatePersistenceService.LifecycleSpec(
                                    payload.lifecyclePolicyId(), payload.lifecyclePolicyVersion(),
                                    payload.lifecyclePartitionColumn(),
                                    parseLifecycleGranularity(payload.lifecycleGranularity()),
                                    payload.lifecycleRetentionCount(),
                                    payload.lifecyclePolicySnapshotJson()),
                            userId);
            mapping = start.mapping();
            handle = start.handle();
            publication = start.publication();
        } else {
            mapping = prepareMapping(
                    payload.mappingId(), sourceRef, binding, targetTableName,
                    contract, mappings, userId);
            handle = begin(mapping, LakeOperationType.CREATE_TABLE,
                    payload.mappingId() != null);
        }
        return executeCreate(mapping, handle, client, contract, false,
                lifecycleProperties, publication);
    }

    @Override
    public LakeManagedTableVO detail(Long id) {
        return toVO(requireMappingIncludingDeleted(id));
    }

    /**
     * Explicit read-through reconcile.  The evaluator performs all external
     * reads; this method only persists the resulting cached dimensions with a
     * token/version compare-and-set.  detail() deliberately remains cached.
     */
    @Override
    public LakeManagedTableVO reconcile(Long id) {
        LakeOdsTableMapping mapping = requireMappingIncludingDeleted(id);
        if (Boolean.TRUE.equals(mapping.getDeleted())) {
            throw conflict("Deleted lake table mapping cannot be reconciled");
        }
        if (mapping.getOperationToken() != null
                || !isStableReconcileStatus(mapping.getResourceStatus())) {
            throw stale("The lake table mapping is currently being changed");
        }
        Integer userId = requireCurrentUserId();
        LakeTableDriftEvaluator.Evaluation evaluation;
        try {
            evaluation = driftEvaluator.evaluate(mapping);
        } catch (RuntimeException exception) {
            throw conflict("Lake table reconcile could not be completed");
        }
        LakeOdsTableMapping persisted = reconcilePersistenceService.persist(mapping, evaluation, userId);
        return toVO(persisted);
    }

    private static boolean isStableReconcileStatus(LakeResourceStatus status) {
        return status == LakeResourceStatus.READY
                || status == LakeResourceStatus.ERROR
                || status == LakeResourceStatus.CREATE_FAILED
                || status == LakeResourceStatus.MISSING
                || status == LakeResourceStatus.UNKNOWN;
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
        return executeCreate(mapping, handle, client, contract, actualExists,
                java.util.Map.of(), null);
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
                if (isActiveRelationForMapping(relation, mapping)) {
                    addRelationImpact(result, relation);
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

    private boolean isActiveRelationForMapping(
            LakeJobRelation relation, LakeOdsTableMapping mapping) {
        if (relation == null || relation.getRelationStatus() != LakeRelationStatus.ACTIVE) {
            return false;
        }
        if (relation.getRelationScope() == LakeRelationScope.TABLE) {
            return Objects.equals(relation.getTableMappingId(), mapping.getId());
        }
        return relation.getRelationScope() == LakeRelationScope.NAMESPACE;
    }

    private void addRelationImpact(
            LakeManagedTableDeleteImpactVO result, LakeJobRelation relation) {
        result.getRelations().add(toImpact(relation));
        if (relation.getJobRuntimeType() == null) {
            result.getBlockers().add("An active job relation has an unknown runtime type");
            return;
        }
        if (relation.getJobId() == null) {
            result.getBlockers().add("An active job relation has no job definition");
            return;
        }

        switch (relation.getJobRuntimeType()) {
            case BATCH -> inspectBatchRelation(result, relation);
            case STREAMING -> inspectStreamingRelation(result, relation);
        }
    }

    private void inspectBatchRelation(
            LakeManagedTableDeleteImpactVO result, LakeJobRelation relation) {
        if (batchJobDefinitionDao == null || jobScheduleDao == null || jobInstanceDao == null) {
            result.getBlockers().add("An active batch job relation cannot be verified");
            return;
        }

        JobDefinitionEntity definition;
        try {
            definition = batchJobDefinitionDao.queryById(relation.getJobId());
        } catch (RuntimeException exception) {
            result.getBlockers().add("An active batch job relation cannot be verified");
            return;
        }
        if (definition == null) {
            result.getBlockers().add("An active batch job relation references a missing job definition");
            return;
        }
        if (definition.getReleaseState() == null) {
            result.getBlockers().add("An active batch job relation has an unknown release state");
        } else if (definition.getReleaseState().isOnline()) {
            result.getBlockers().add("An active batch job relation references an online job");
        }

        JobSchedule schedule;
        try {
            schedule = jobScheduleDao.queryByJobDefinitionId(relation.getJobId());
        } catch (RuntimeException exception) {
            result.getBlockers().add("An active batch job schedule cannot be verified");
            schedule = null;
        }
        if (schedule != null && scheduleEnabled(schedule)) {
            result.getBlockers().add("An active batch job relation has an enabled schedule");
        } else if (schedule != null && schedule.getScheduleStatus() == null) {
            result.getBlockers().add("An active batch job schedule has an unknown state");
        }

        try {
            if (jobInstanceDao.existsRunningInstance(relation.getJobId())) {
                result.getBlockers().add("An active batch job relation has a running instance");
            }
        } catch (RuntimeException exception) {
            result.getBlockers().add("An active batch job instance cannot be verified");
        }
    }

    private void inspectStreamingRelation(
            LakeManagedTableDeleteImpactVO result, LakeJobRelation relation) {
        if (streamingJobDefinitionDao == null || streamingJobInstanceDao == null) {
            result.getBlockers().add("An active streaming job relation cannot be verified");
            return;
        }

        StreamingJobDefinitionEntity definition;
        try {
            definition = streamingJobDefinitionDao.queryById(relation.getJobId());
        } catch (RuntimeException exception) {
            result.getBlockers().add("An active streaming job relation cannot be verified");
            return;
        }
        if (definition == null) {
            result.getBlockers().add(
                    "An active streaming job relation references a missing job definition");
            return;
        }
        if (definition.getReleaseState() == null) {
            result.getBlockers().add("An active streaming job relation has an unknown release state");
        } else if (definition.getReleaseState().isOnline()) {
            result.getBlockers().add("An active streaming job relation references an online job");
        }

        try {
            if (streamingJobInstanceDao.existsRunningInstance(relation.getJobId())) {
                result.getBlockers().add("An active streaming job relation has a running instance");
            }
        } catch (RuntimeException exception) {
            result.getBlockers().add("An active streaming job instance cannot be verified");
        }
    }

    private boolean scheduleEnabled(JobSchedule schedule) {
        ScheduleStatusEnum status = schedule.getScheduleStatus();
        if (status != null) {
            return status.shouldStartQuartz();
        }
        return schedule.getExecutionMode() != null
                && schedule.getExecutionMode() != TaskExecutionMode.MANUAL
                && schedule.getCronExpression() != null
                && !schedule.getCronExpression().isBlank();
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
            boolean actualExists,
            java.util.Map<String, String> tableProperties,
            LakeManagedTableOperationPublication lifecyclePublication) {
        AtomicReference<String> errorCode = new AtomicReference<>();
        try {
            coordinator.execute(handle, () -> {
                try {
                    if (!actualExists) {
                        // The client receives the validated structured
                        // contract.  No request field contains executable SQL.
                        if (tableProperties == null || tableProperties.isEmpty()) {
                            client.createTable(mapping.getDatabaseName(),
                                    mapping.getTargetTableName(), contract);
                        } else {
                            client.createTable(mapping.getDatabaseName(),
                                    mapping.getTargetTableName(), contract, tableProperties);
                        }
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
            boolean finalized = lifecyclePublication == null
                    ? coordinator.finalizeSuccess(handle, "Doris table exists and matches contract")
                    : coordinator.finalizeSuccess(
                            handle, "Doris table exists and matches contract", lifecyclePublication);
            if (!finalized) {
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
        return prepareMappingInternal(expectedMappingId, sourceRef, binding,
                targetTableName, contract, mappings, userId, true);
    }

    /** Builds the mapping candidate without writing it; atomic lifecycle TX1 persists it. */
    private LakeOdsTableMapping prepareMappingCandidate(
            Long expectedMappingId,
            LakeSourceObjectRef sourceRef,
            LakeOdsDatabaseBinding binding,
            String targetTableName,
            TargetContract contract,
            List<LakeManagedTableFieldMapping> mappings,
            Integer userId) {
        return prepareMappingInternal(expectedMappingId, sourceRef, binding,
                targetTableName, contract, mappings, userId, false);
    }

    private LakeOdsTableMapping prepareMappingInternal(
            Long expectedMappingId,
            LakeSourceObjectRef sourceRef,
            LakeOdsDatabaseBinding binding,
            String targetTableName,
            TargetContract contract,
            List<LakeManagedTableFieldMapping> mappings,
            Integer userId,
            boolean persist) {
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
        if (!persist) {
            return mapping;
        }
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
        // AUTO_CREATED/UNMANAGED projections intentionally have no Web
        // structural contract.  GET detail must still be a cached read for
        // those mappings; MANAGED mappings retain strict contract validation.
        if (mapping.getManagementLevel() == LakeManagementLevel.MANAGED
                || (mapping.getTargetContractJson() != null
                && !mapping.getTargetContractJson().isBlank())) {
            result.setTargetContract(readStoredContract(mapping));
        }
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
        impact.getBlockers().stream().sorted()
                .forEach(blocker -> value.append(blocker).append('\u0000'));
        return org.apache.seatunnel.web.api.lake.source.SourceSchemaCanonicalizer.sha256(value.toString());
    }

    private static boolean sameSource(LakeOdsTableMapping mapping, LakeSourceObjectRef sourceRef) {
        return sourceRef != null && Objects.equals(mapping.getSourceObjectRefId(), sourceRef.getId());
    }

    private LakeLifecyclePolicy readPreviewLifecyclePolicy(
            Long policyId, TargetContract contract, SourceObjectSnapshot source) {
        if (policyId == null) {
            return null;
        }
        if (policyId <= 0 || lifecyclePolicyDao == null) {
            throw conflict("Lifecycle policy does not exist");
        }
        LakeLifecyclePolicy policy;
        try {
            policy = lifecyclePolicyDao.queryById(policyId);
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle policy cannot be read");
        }
        if (policy == null || !Objects.equals(policy.getId(), policyId)) {
            throw conflict("Lifecycle policy does not exist");
        }
        validateLifecyclePolicy(policy, contract, source);
        return policy;
    }

    private LakeLifecyclePolicy readCreateLifecyclePolicy(
            LakePreviewTokenService.Payload payload,
            TargetContract contract,
            SourceObjectSnapshot source) {
        if (!payload.hasLifecyclePolicy()) {
            return null;
        }
        if (lifecyclePolicyDao == null) {
            throw conflict("Lifecycle policy changed after preview; request a new preview");
        }
        LakeLifecyclePolicy policy;
        try {
            policy = lifecyclePolicyDao.queryById(payload.lifecyclePolicyId());
        } catch (RuntimeException exception) {
            throw conflict("Lifecycle policy cannot be read");
        }
        try {
            validateLifecyclePolicy(policy, contract, source);
        } catch (LakeServiceException exception) {
            throw conflict("Lifecycle policy changed after preview; request a new preview");
        }
        String currentSnapshot = lifecyclePolicySnapshot(policy);
        if (!Objects.equals(payload.lifecyclePolicyVersion(), policy.getVersion())
                || !Objects.equals(payload.lifecyclePolicySnapshotJson(), currentSnapshot)
                || !Objects.equals(payload.lifecyclePartitionColumn(),
                contract.getPartition().getColumn())
                || !Objects.equals(payload.lifecycleGranularity(),
                contract.getPartition().getGranularity())
                || !Objects.equals(payload.lifecycleRetentionCount(), policy.getRetentionCount())
                || !"partition.retention_count".equalsIgnoreCase(payload.lifecyclePropertyKey())
                || !Objects.equals(payload.lifecyclePropertyValue(),
                String.valueOf(policy.getRetentionCount()))) {
            throw conflict("Lifecycle policy changed after preview; request a new preview");
        }
        return policy;
    }

    private static void validateLifecyclePolicy(
            LakeLifecyclePolicy policy,
            TargetContract contract,
            SourceObjectSnapshot source) {
        if (policy == null || policy.getId() == null || policy.getId() <= 0
                || policy.getVersion() == null || policy.getVersion() <= 0
                || policy.getStatus() != LakeLifecyclePolicyStatus.ACTIVE
                || policy.getGranularity() == null
                || policy.getRetentionCount() == null || policy.getRetentionCount() <= 0) {
            throw conflict("Lifecycle policy must be active and valid");
        }
        if (contract == null || contract.getPartition() == null
                || !Boolean.TRUE.equals(contract.getPartition().getEnabled())
                || contract.getPartition().getColumn() == null
                || contract.getPartition().getGranularity() == null
                || !policy.getGranularity().name().equalsIgnoreCase(
                contract.getPartition().getGranularity())) {
            throw conflict("Lifecycle policy requires a matching AUTO RANGE partition");
        }
        String partitionColumn = contract.getPartition().getColumn();
        org.apache.seatunnel.web.api.lake.contract.TargetColumn targetColumn = contract.getColumns()
                .stream()
                .filter(column -> column != null && column.getTargetName() != null
                        && column.getTargetName().equalsIgnoreCase(partitionColumn))
                .findFirst().orElse(null);
        if (targetColumn == null || targetColumn.getTargetType() == null
                || Boolean.TRUE.equals(targetColumn.getNullable())) {
            throw conflict("Lifecycle partition column must be DATE/DATETIME and NOT NULL");
        }
        org.apache.seatunnel.web.api.lake.contract.DorisTypeBase base = targetColumn
                .getTargetType().canonicalCopy().getBase().canonical();
        if (base != org.apache.seatunnel.web.api.lake.contract.DorisTypeBase.DATE
                && base != org.apache.seatunnel.web.api.lake.contract.DorisTypeBase.DATETIME) {
            throw conflict("Lifecycle partition column must be DATE/DATETIME and NOT NULL");
        }
        String sourceName = targetColumn.getSourceName();
        org.apache.seatunnel.web.api.lake.source.SourceColumnSnapshot sourceColumn = source == null
                ? null : source.columns().stream()
                .filter(column -> column != null && column.name() != null
                        && column.name().equalsIgnoreCase(sourceName))
                .findFirst().orElse(null);
        String sourceType = sourceColumn == null ? null : sourceColumn.dataType();
        if (sourceColumn == null || !Boolean.FALSE.equals(sourceColumn.nullable())
                || sourceType == null
                || (!sourceType.trim().equalsIgnoreCase("DATE")
                && !sourceType.trim().toUpperCase(java.util.Locale.ROOT).startsWith("DATETIME"))) {
            throw conflict("Lifecycle source partition column must be DATE/DATETIME and NOT NULL");
        }
    }

    private static java.util.Map<String, String> lifecycleProperties(
            LakeLifecyclePolicy policy) {
        return policy == null ? java.util.Map.of()
                : java.util.Map.of("partition.retention_count",
                String.valueOf(policy.getRetentionCount()));
    }

    private static LakePartitionGranularity parseLifecycleGranularity(String value) {
        if (value == null || value.isBlank()) {
            throw conflict("Lifecycle granularity is invalid");
        }
        try {
            return LakePartitionGranularity.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw conflict("Lifecycle granularity is invalid");
        }
    }

    private static String lifecyclePolicySnapshot(LakeLifecyclePolicy policy) {
        try {
            return MAPPER.writeValueAsString(new LifecyclePolicySnapshot(
                    policy.getId(), policy.getVersion(), policy.getGranularity().name(),
                    policy.getRetentionCount()));
        } catch (JsonProcessingException exception) {
            throw conflict("Lifecycle policy snapshot is invalid");
        }
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
        if (request.getLifecyclePolicyId() != null && request.getLifecyclePolicyId() <= 0) {
            throw invalid("lifecyclePolicyId must be positive");
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

    private record LifecyclePolicySnapshot(
            Long policyId, Integer version, String granularity, Integer retentionCount) {
    }
}
