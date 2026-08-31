package org.apache.seatunnel.web.api.lake.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.contract.TargetContractCanonicalizer;
import org.apache.seatunnel.web.api.lake.contract.TargetContractValidator;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.job.LakeJobDescriptor;
import org.apache.seatunnel.web.api.lake.job.LakeJobDetector;
import org.apache.seatunnel.web.api.lake.source.LakeSourceObjectResolver;
import org.apache.seatunnel.web.api.lake.source.SourceObjectSnapshot;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.core.hocon.JobDefinitionCommandResolver;
import org.apache.seatunnel.web.core.hocon.StreamingJobDefinitionCommandResolver;
import org.apache.seatunnel.web.dao.entity.JobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeSourceObjectRef;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionEntity;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionDao;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Performs a read-only three-dimensional consistency evaluation for one ODS
 * table mapping.
 *
 * <p>The evaluator intentionally returns UNKNOWN when an observation cannot
 * be made safely.  In particular, it never treats a missing target contract,
 * an unavailable OM/Doris endpoint, or an unresolvable persisted job as
 * CONSISTENT.  It also does not update the cached consistency columns; a
 * future reconcile command may persist an evaluation explicitly.</p>
 */
@Component
public class LakeTableDriftEvaluator {

    public static final String SOURCE_CONSISTENT = "LAKE_SOURCE_CONSISTENT";
    public static final String SOURCE_SCHEMA_DRIFT = "LAKE_SOURCE_SCHEMA_DRIFT";
    public static final String TARGET_CONSISTENT = "LAKE_TARGET_CONSISTENT";
    public static final String TARGET_TABLE_MISSING = "LAKE_TARGET_TABLE_MISSING";
    public static final String TARGET_CONTRACT_DRIFT = "LAKE_TARGET_CONTRACT_DRIFT";
    public static final String TARGET_CONTRACT_UNKNOWN = "LAKE_TARGET_CONTRACT_UNKNOWN";
    public static final String TASK_CONSISTENT = "LAKE_TASK_CONSISTENT";
    public static final String TASK_UNBOUND = "LAKE_TASK_UNBOUND";
    public static final String TASK_RELATION_MISSING = "LAKE_TASK_RELATION_MISSING";
    public static final String TASK_RELATION_DRIFT = "LAKE_TASK_RELATION_DRIFT";
    public static final String TASK_RELATION_UNKNOWN = "LAKE_TASK_RELATION_UNKNOWN";
    public static final String TASK_RELATIONS_UNKNOWN = "LAKE_TASK_RELATIONS_UNKNOWN";
    public static final String MAPPING_MISSING = "LAKE_TABLE_MAPPING_MISSING";
    public static final String MAPPING_UNKNOWN = "LAKE_TABLE_MAPPING_UNKNOWN";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LakeSourceObjectRefDao sourceObjectRefDao;
    private final LakeJobRelationDao jobRelationDao;
    private final LakeSourceObjectResolver sourceResolver;
    private final LakeDorisClientProvider dorisClientProvider;
    private final LakeJobDetector jobDetector;
    private final JobDefinitionCommandResolver batchCommandResolver;
    private final StreamingJobDefinitionCommandResolver streamingCommandResolver;
    private final JobDefinitionDao batchJobDefinitionDao;
    private final StreamingJobDefinitionDao streamingJobDefinitionDao;

    @Autowired
    public LakeTableDriftEvaluator(
            LakeSourceObjectRefDao sourceObjectRefDao,
            LakeJobRelationDao jobRelationDao,
            LakeSourceObjectResolver sourceResolver,
            LakeDorisClientProvider dorisClientProvider,
            LakeJobDetector jobDetector,
            JobDefinitionCommandResolver batchCommandResolver,
            StreamingJobDefinitionCommandResolver streamingCommandResolver,
            JobDefinitionDao batchJobDefinitionDao,
            StreamingJobDefinitionDao streamingJobDefinitionDao) {
        this.sourceObjectRefDao = sourceObjectRefDao;
        this.jobRelationDao = jobRelationDao;
        this.sourceResolver = sourceResolver;
        this.dorisClientProvider = dorisClientProvider;
        this.jobDetector = jobDetector;
        this.batchCommandResolver = batchCommandResolver;
        this.streamingCommandResolver = streamingCommandResolver;
        this.batchJobDefinitionDao = batchJobDefinitionDao;
        this.streamingJobDefinitionDao = streamingJobDefinitionDao;
    }

    /**
     * Test-friendly constructor for source/target evaluations that do not
     * need persisted task resolution.  Task relations are UNKNOWN when an
     * active relation is present because the persisted resolvers are absent.
     */
    public LakeTableDriftEvaluator(
            LakeSourceObjectRefDao sourceObjectRefDao,
            LakeJobRelationDao jobRelationDao,
            LakeSourceObjectResolver sourceResolver,
            LakeDorisClientProvider dorisClientProvider,
            LakeJobDetector jobDetector) {
        this(sourceObjectRefDao, jobRelationDao, sourceResolver, dorisClientProvider,
                jobDetector, null, null, null, null);
    }

    /**
     * Evaluates the supplied active mapping without changing the mapping,
     * source reference, relation rows, or any external resource.
     */
    public Evaluation evaluate(LakeOdsTableMapping mapping) {
        if (mapping == null) {
            return invalidEvaluation(null, null, MAPPING_MISSING,
                    "Lake table mapping is missing");
        }
        if (Boolean.TRUE.equals(mapping.getDeleted())) {
            return invalidEvaluation(mapping.getId(), mapping.getManagementLevel(), MAPPING_MISSING,
                    "Lake table mapping is deleted");
        }

        DimensionResult source = evaluateSource(mapping);
        DimensionResult target = evaluateTarget(mapping);
        TaskEvaluation task = evaluateTask(mapping);
        LakeConsistencyStatus aggregate = aggregateStatus(
                source.status(), target.status(), task.result().status());
        return new Evaluation(mapping.getId(), mapping.getManagementLevel(), source, target,
                task.result(), aggregate, task.relations());
    }

    /**
     * Aggregates statuses according to the v1.4 order.  UNBOUND is a real
     * task state and therefore remains distinct from UNKNOWN when no higher
     * priority state is present.
     */
    public static LakeConsistencyStatus aggregateStatus(
            LakeConsistencyStatus... statuses) {
        if (statuses == null || statuses.length == 0) {
            return LakeConsistencyStatus.UNKNOWN;
        }
        List<LakeConsistencyStatus> values = new ArrayList<>(statuses.length);
        for (LakeConsistencyStatus status : statuses) {
            values.add(status);
        }
        return aggregateStatus(values);
    }

    /** Aggregates a collection using MISSING > DRIFT > UNKNOWN > CONSISTENT. */
    public static LakeConsistencyStatus aggregateStatus(
            Collection<LakeConsistencyStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return LakeConsistencyStatus.UNKNOWN;
        }
        boolean unbound = false;
        boolean unknown = false;
        boolean drift = false;
        boolean missing = false;
        for (LakeConsistencyStatus status : statuses) {
            if (status == null || status == LakeConsistencyStatus.UNKNOWN) {
                unknown = true;
            } else if (status == LakeConsistencyStatus.MISSING) {
                missing = true;
            } else if (status == LakeConsistencyStatus.DRIFT) {
                drift = true;
            } else if (status == LakeConsistencyStatus.UNBOUND) {
                unbound = true;
            }
        }
        if (missing) {
            return LakeConsistencyStatus.MISSING;
        }
        if (drift) {
            return LakeConsistencyStatus.DRIFT;
        }
        if (unknown) {
            return LakeConsistencyStatus.UNKNOWN;
        }
        return unbound ? LakeConsistencyStatus.UNBOUND : LakeConsistencyStatus.CONSISTENT;
    }

    private DimensionResult evaluateSource(LakeOdsTableMapping mapping) {
        if (sourceObjectRefDao == null || sourceResolver == null
                || mapping.getSourceObjectRefId() == null) {
            return unknown("Source baseline is unavailable");
        }

        LakeSourceObjectRef reference;
        try {
            reference = sourceObjectRefDao.queryByIdIncludingDeleted(mapping.getSourceObjectRefId());
        } catch (RuntimeException exception) {
            return unknown("Source baseline lookup is unavailable");
        }
        if (reference == null || Boolean.TRUE.equals(reference.getDeleted())
                || isMissing(reference.getResourceStatus())) {
            return missing(LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING,
                    "OpenMetadata source object is missing");
        }
        if (isUnavailable(reference.getResourceStatus())
                || reference.getSourceDataSourceId() == null
                || StringUtils.isBlank(reference.getOmEntityId())
                || StringUtils.isBlank(mapping.getSourceSchemaHash())) {
            return unknown("Source baseline is incomplete");
        }

        SourceObjectSnapshot current;
        try {
            current = sourceResolver.resolve(
                    reference.getSourceDataSourceId(), reference.getOmEntityId());
        } catch (LakeServiceException exception) {
            if (LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING.equals(exception.getLakeErrorCode())) {
                return missing(LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING,
                        "OpenMetadata source object is missing");
            }
            return unknown(LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN,
                    "OpenMetadata source object is unavailable");
        } catch (RuntimeException exception) {
            return unknown(LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN,
                    "OpenMetadata source object is unavailable");
        }
        if (current == null || StringUtils.isBlank(current.sourceSchemaHash())
                || (StringUtils.isNotBlank(current.omEntityId())
                && !reference.getOmEntityId().trim().equals(current.omEntityId().trim()))) {
            return unknown(LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN,
                    "OpenMetadata source snapshot is incomplete");
        }
        if (!mapping.getSourceSchemaHash().trim().equals(current.sourceSchemaHash().trim())) {
            return new DimensionResult(LakeConsistencyStatus.DRIFT, SOURCE_SCHEMA_DRIFT,
                    "OpenMetadata source schema differs from its creation baseline");
        }
        return new DimensionResult(LakeConsistencyStatus.CONSISTENT, SOURCE_CONSISTENT,
                "OpenMetadata source schema matches its creation baseline");
    }

    private DimensionResult evaluateTarget(LakeOdsTableMapping mapping) {
        if (dorisClientProvider == null
                || mapping.getLakeDataSourceId() == null
                || StringUtils.isBlank(mapping.getDatabaseName())
                || StringUtils.isBlank(mapping.getTargetTableName())) {
            return unknown("Doris target identity is unavailable");
        }

        DorisLakeClient client;
        try {
            client = dorisClientProvider.get(mapping.getLakeDataSourceId());
        } catch (RuntimeException exception) {
            return unknown(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "Doris target is unavailable");
        }
        if (client == null) {
            return unknown(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "Doris target is unavailable");
        }

        boolean actualExists;
        try {
            actualExists = client.tableExists(
                    mapping.getDatabaseName().trim(), mapping.getTargetTableName().trim());
        } catch (RuntimeException exception) {
            return unknown(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "Doris target lookup is unavailable");
        }
        if (!actualExists) {
            return missing(TARGET_TABLE_MISSING, "Doris target table is missing");
        }
        TargetContract expected = readStoredContract(mapping);
        if (expected == null) {
            return unknown(TARGET_CONTRACT_UNKNOWN,
                    "Target structural contract is unavailable");
        }

        TargetContract actual;
        try {
            actual = client.readContract(
                    mapping.getDatabaseName().trim(), mapping.getTargetTableName().trim());
        } catch (RuntimeException exception) {
            return unknown(LakeErrorCode.LAKE_DORIS_UNAVAILABLE,
                    "Doris target structure is unavailable");
        }
        if (actual == null) {
            return unknown(TARGET_CONTRACT_UNKNOWN,
                    "Doris target structure is unavailable");
        }
        try {
            String expectedHash = TargetContractCanonicalizer.canonicalHash(expected);
            String actualHash = TargetContractCanonicalizer.canonicalHash(actual);
            if (!expectedHash.equals(actualHash)) {
                return new DimensionResult(LakeConsistencyStatus.DRIFT, TARGET_CONTRACT_DRIFT,
                        "Doris target structure differs from the Web contract");
            }
        } catch (RuntimeException exception) {
            return unknown(TARGET_CONTRACT_UNKNOWN,
                    "Target structural contract cannot be compared");
        }
        return new DimensionResult(LakeConsistencyStatus.CONSISTENT, TARGET_CONSISTENT,
                "Doris target structure matches the Web contract");
    }

    private TargetContract readStoredContract(LakeOdsTableMapping mapping) {
        if (StringUtils.isBlank(mapping.getTargetContractJson())
                || StringUtils.isBlank(mapping.getTargetContractHash())) {
            return null;
        }
        try {
            TargetContract expected = TargetContractValidator.validateAndNormalize(
                    MAPPER.readValue(mapping.getTargetContractJson(), TargetContract.class));
            return mapping.getTargetContractHash().trim().equals(
                    TargetContractCanonicalizer.canonicalHash(expected)) ? expected : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private TaskEvaluation evaluateTask(LakeOdsTableMapping mapping) {
        if (jobRelationDao == null || mapping.getOdsDatabaseBindingId() == null
                || mapping.getId() == null) {
            return new TaskEvaluation(
                    unknown(TASK_RELATIONS_UNKNOWN, "Task relations are unavailable"), List.of());
        }

        List<LakeJobRelation> candidates;
        try {
            candidates = jobRelationDao.queryByOdsDatabaseBindingId(
                    mapping.getOdsDatabaseBindingId());
        } catch (RuntimeException exception) {
            return new TaskEvaluation(
                    unknown(TASK_RELATIONS_UNKNOWN, "Task relations are unavailable"), List.of());
        }
        if (candidates == null) {
            return new TaskEvaluation(
                    unknown(TASK_RELATIONS_UNKNOWN, "Task relations are unavailable"), List.of());
        }

        List<LakeJobRelation> activeTableRelations = candidates.stream()
                .filter(Objects::nonNull)
                .filter(relation -> relation.getRelationStatus() == LakeRelationStatus.ACTIVE)
                .filter(relation -> relation.getRelationScope() == LakeRelationScope.TABLE)
                .filter(relation -> Objects.equals(mapping.getId(), relation.getTableMappingId()))
                .toList();
        if (activeTableRelations.isEmpty()) {
            return new TaskEvaluation(
                    new DimensionResult(LakeConsistencyStatus.UNBOUND, TASK_UNBOUND,
                            "No ACTIVE TABLE relation is bound to this mapping"), List.of());
        }

        List<TaskRelationResult> results = new ArrayList<>(activeTableRelations.size());
        for (LakeJobRelation relation : activeTableRelations) {
            results.add(evaluateRelation(mapping, relation));
        }
        LakeConsistencyStatus status = aggregateStatus(
                results.stream().map(TaskRelationResult::status).toList());
        return new TaskEvaluation(taskResult(status), results);
    }

    private TaskRelationResult evaluateRelation(
            LakeOdsTableMapping mapping, LakeJobRelation relation) {
        Long jobId = relation.getJobId();
        if (jobId == null || jobId <= 0) {
            return relationResult(relation, LakeConsistencyStatus.MISSING,
                    TASK_RELATION_MISSING, "The persisted task for this relation is missing");
        }
        LakeJobRuntimeType runtimeType = relation.getJobRuntimeType();
        if (runtimeType == null) {
            return relationResult(relation, LakeConsistencyStatus.UNKNOWN,
                    TASK_RELATION_UNKNOWN, "The relation runtime type is unavailable");
        }

        JobDefinitionSaveCommand command;
        Integer currentVersion;
        try {
            if (runtimeType == LakeJobRuntimeType.BATCH) {
                if (batchCommandResolver == null || batchJobDefinitionDao == null) {
                    return relationResult(relation, LakeConsistencyStatus.UNKNOWN,
                            TASK_RELATION_UNKNOWN, "The batch task resolver is unavailable");
                }
                JobDefinitionEntity definition = batchJobDefinitionDao.queryById(jobId);
                if (definition == null) {
                    return relationResult(relation, LakeConsistencyStatus.MISSING,
                            TASK_RELATION_MISSING, "The persisted batch task is missing");
                }
                currentVersion = definition.getJobVersion();
                command = batchCommandResolver.resolve(jobId);
            } else if (runtimeType == LakeJobRuntimeType.STREAMING) {
                if (streamingCommandResolver == null || streamingJobDefinitionDao == null) {
                    return relationResult(relation, LakeConsistencyStatus.UNKNOWN,
                            TASK_RELATION_UNKNOWN, "The streaming task resolver is unavailable");
                }
                StreamingJobDefinitionEntity definition = streamingJobDefinitionDao.queryById(jobId);
                if (definition == null) {
                    return relationResult(relation, LakeConsistencyStatus.MISSING,
                            TASK_RELATION_MISSING, "The persisted streaming task is missing");
                }
                currentVersion = definition.getJobVersion();
                command = streamingCommandResolver.resolve(jobId);
            } else {
                return relationResult(relation, LakeConsistencyStatus.UNKNOWN,
                        TASK_RELATION_UNKNOWN, "The relation runtime type is unsupported");
            }
        } catch (RuntimeException exception) {
            return relationResult(relation, LakeConsistencyStatus.UNKNOWN,
                    TASK_RELATION_UNKNOWN, "The persisted task cannot be read safely");
        }
        if (command == null || currentVersion == null || relation.getJobVersion() == null) {
            return relationResult(relation, LakeConsistencyStatus.UNKNOWN,
                    TASK_RELATION_UNKNOWN, "The persisted task snapshot is incomplete");
        }
        if (!Objects.equals(currentVersion, relation.getJobVersion())) {
            return relationResult(relation, LakeConsistencyStatus.DRIFT,
                    TASK_RELATION_DRIFT, "The task version differs from the relation snapshot");
        }

        LakeJobDescriptor descriptor;
        try {
            descriptor = jobDetector == null ? null : jobDetector.detect(command, runtimeType);
        } catch (RuntimeException exception) {
            return relationResult(relation, LakeConsistencyStatus.UNKNOWN,
                    TASK_RELATION_UNKNOWN, "The structured task cannot be inspected safely");
        }
        if (descriptor == null) {
            return relationResult(relation, LakeConsistencyStatus.DRIFT,
                    TASK_RELATION_DRIFT, "The task no longer describes this structured table relation");
        }
        if (descriptor.relationScope() != LakeRelationScope.TABLE
                || !Objects.equals(mapping.getId(), descriptor.tableMappingId())
                || !Objects.equals(mapping.getOdsDatabaseBindingId(), descriptor.odsDatabaseBindingId())
                || descriptor.jobRuntimeType() != runtimeType) {
            return relationResult(relation, LakeConsistencyStatus.DRIFT,
                    TASK_RELATION_DRIFT, "The current task identity differs from the relation snapshot");
        }
        if (!snapshotMatches(relation.getSourceEndpointSnapshot(), descriptor.sourceEndpointSnapshot())
                || !snapshotMatches(relation.getSinkEndpointSnapshot(), descriptor.sinkEndpointSnapshot())
                || !snapshotMatches(relation.getSchemaSaveModeSnapshot(), descriptor.schemaSaveModeSnapshot())) {
            return relationResult(relation, LakeConsistencyStatus.DRIFT,
                    TASK_RELATION_DRIFT, "The current task endpoint or schema mode differs from the relation");
        }
        return relationResult(relation, LakeConsistencyStatus.CONSISTENT, TASK_CONSISTENT,
                "The active task relation matches the current structured job");
    }

    private boolean snapshotMatches(String stored, String current) {
        if (StringUtils.isBlank(stored) || StringUtils.isBlank(current)) {
            return StringUtils.isBlank(stored) && StringUtils.isBlank(current);
        }
        return stored.trim().equals(current.trim());
    }

    private TaskRelationResult relationResult(
            LakeJobRelation relation,
            LakeConsistencyStatus status,
            String reasonCode,
            String reason) {
        return new TaskRelationResult(relation.getId(), relation.getJobId(), relation.getJobVersion(),
                status, reasonCode, reason);
    }

    private DimensionResult taskResult(LakeConsistencyStatus status) {
        return switch (status) {
            case MISSING -> new DimensionResult(status, TASK_RELATION_MISSING,
                    "At least one ACTIVE TABLE relation is missing its task");
            case DRIFT -> new DimensionResult(status, TASK_RELATION_DRIFT,
                    "At least one ACTIVE TABLE relation differs from its task");
            case UNKNOWN -> new DimensionResult(status, TASK_RELATION_UNKNOWN,
                    "At least one ACTIVE TABLE relation cannot be inspected");
            case CONSISTENT -> new DimensionResult(status, TASK_CONSISTENT,
                    "All ACTIVE TABLE relations match their structured tasks");
            case UNBOUND -> new DimensionResult(status, TASK_UNBOUND,
                    "No ACTIVE TABLE relation is bound to this mapping");
        };
    }

    private Evaluation invalidEvaluation(
            Long mappingId,
            LakeManagementLevel managementLevel,
            String reasonCode,
            String reason) {
        DimensionResult result = missing(reasonCode, reason);
        return new Evaluation(mappingId, managementLevel, result, result, result,
                LakeConsistencyStatus.MISSING, List.of());
    }

    private static boolean isMissing(LakeResourceStatus status) {
        return status == LakeResourceStatus.MISSING
                || status == LakeResourceStatus.DELETING
                || status == LakeResourceStatus.DELETED;
    }

    private static boolean isUnavailable(LakeResourceStatus status) {
        return status != null && status != LakeResourceStatus.READY;
    }

    private static DimensionResult missing(String reasonCode, String reason) {
        return new DimensionResult(LakeConsistencyStatus.MISSING, reasonCode, reason);
    }

    private static DimensionResult unknown(String reason) {
        return unknown(MAPPING_UNKNOWN, reason);
    }

    private static DimensionResult unknown(String reasonCode, String reason) {
        return new DimensionResult(LakeConsistencyStatus.UNKNOWN, reasonCode, reason);
    }

    private record TaskEvaluation(DimensionResult result, List<TaskRelationResult> relations) {
        private TaskEvaluation {
            relations = relations == null ? List.of() : List.copyOf(relations);
        }
    }

    /** Immutable result for one Source, Target, or Task dimension. */
    public record DimensionResult(
            LakeConsistencyStatus status,
            String reasonCode,
            String reason) {
        public DimensionResult {
            status = status == null ? LakeConsistencyStatus.UNKNOWN : status;
            reasonCode = StringUtils.defaultIfBlank(reasonCode, MAPPING_UNKNOWN);
            reason = StringUtils.defaultIfBlank(reason, "Consistency could not be determined");
        }
    }

    /** Immutable independent result for one active TABLE relation. */
    public record TaskRelationResult(
            Long relationId,
            Long jobId,
            Integer relationJobVersion,
            LakeConsistencyStatus status,
            String reasonCode,
            String reason) {
        public TaskRelationResult {
            status = status == null ? LakeConsistencyStatus.UNKNOWN : status;
            reasonCode = StringUtils.defaultIfBlank(reasonCode, TASK_RELATION_UNKNOWN);
            reason = StringUtils.defaultIfBlank(reason, "Task relation consistency is unavailable");
        }

        public Integer jobVersion() {
            return relationJobVersion;
        }
    }

    /** Immutable complete result; taskRelations is never mutable or null. */
    public record Evaluation(
            Long mappingId,
            LakeManagementLevel managementLevel,
            DimensionResult source,
            DimensionResult target,
            DimensionResult task,
            LakeConsistencyStatus aggregateStatus,
            List<TaskRelationResult> taskRelations) {
        public Evaluation {
            source = source == null ? new DimensionResult(
                    LakeConsistencyStatus.UNKNOWN, MAPPING_UNKNOWN, "Source result is unavailable") : source;
            target = target == null ? new DimensionResult(
                    LakeConsistencyStatus.UNKNOWN, MAPPING_UNKNOWN, "Target result is unavailable") : target;
            task = task == null ? new DimensionResult(
                    LakeConsistencyStatus.UNKNOWN, MAPPING_UNKNOWN, "Task result is unavailable") : task;
            aggregateStatus = aggregateStatus == null
                    ? LakeTableDriftEvaluator.aggregateStatus(
                    source.status(), target.status(), task.status()) : aggregateStatus;
            taskRelations = taskRelations == null ? List.of() : List.copyOf(taskRelations);
        }

        public LakeConsistencyStatus status() {
            return aggregateStatus;
        }

        public LakeConsistencyStatus aggregate() {
            return aggregateStatus;
        }

        public List<TaskRelationResult> relations() {
            return taskRelations;
        }
    }
}
