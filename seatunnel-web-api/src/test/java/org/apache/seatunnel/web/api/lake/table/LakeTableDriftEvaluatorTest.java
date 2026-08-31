package org.apache.seatunnel.web.api.lake.table;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.contract.DorisTypeBase;
import org.apache.seatunnel.web.api.lake.contract.TargetColumn;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.contract.TargetContractCanonicalizer;
import org.apache.seatunnel.web.api.lake.contract.TargetDistribution;
import org.apache.seatunnel.web.api.lake.contract.TargetPartition;
import org.apache.seatunnel.web.api.lake.contract.TargetType;
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
import org.apache.seatunnel.web.common.enums.LakeTableModel;
import org.apache.seatunnel.web.core.hocon.JobDefinitionCommandResolver;
import org.apache.seatunnel.web.core.hocon.StreamingJobDefinitionCommandResolver;
import org.apache.seatunnel.web.dao.entity.JobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeSourceObjectRef;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionDao;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakeTableDriftEvaluatorTest {

    private static final long MAPPING_ID = 70L;
    private static final long BINDING_ID = 21L;
    private static final long SOURCE_REF_ID = 101L;
    private static final long SOURCE_DATA_SOURCE_ID = 11L;
    private static final long LAKE_DATA_SOURCE_ID = 99L;

    @Mock private LakeSourceObjectRefDao sourceObjectRefDao;
    @Mock private LakeJobRelationDao jobRelationDao;
    @Mock private LakeSourceObjectResolver sourceResolver;
    @Mock private LakeDorisClientProvider dorisClientProvider;
    @Mock private DorisLakeClient dorisClient;
    @Mock private LakeJobDetector jobDetector;
    @Mock private JobDefinitionCommandResolver batchCommandResolver;
    @Mock private StreamingJobDefinitionCommandResolver streamingCommandResolver;
    @Mock private JobDefinitionDao batchJobDefinitionDao;
    @Mock private StreamingJobDefinitionDao streamingJobDefinitionDao;

    private LakeTableDriftEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new LakeTableDriftEvaluator(
                sourceObjectRefDao, jobRelationDao, sourceResolver, dorisClientProvider,
                jobDetector, batchCommandResolver, streamingCommandResolver,
                batchJobDefinitionDao, streamingJobDefinitionDao);

        lenient().when(sourceObjectRefDao.queryByIdIncludingDeleted(SOURCE_REF_ID))
                .thenReturn(sourceReference());
        lenient().when(sourceResolver.resolve(SOURCE_DATA_SOURCE_ID, "om-table"))
                .thenReturn(sourceSnapshot("source-hash"));
        lenient().when(dorisClientProvider.get(LAKE_DATA_SOURCE_ID)).thenReturn(dorisClient);
        lenient().when(dorisClient.tableExists("ods", "orders")).thenReturn(true);
        lenient().when(jobRelationDao.queryByOdsDatabaseBindingId(BINDING_ID))
                .thenReturn(List.of());
    }

    @Test
    void unboundTaskIsDistinctFromUnknownAndResultIsImmutable() {
        LakeTableDriftEvaluator.Evaluation result = evaluator.evaluate(mapping(LakeManagementLevel.MANAGED));

        assertEquals(LakeConsistencyStatus.CONSISTENT, result.source().status());
        assertEquals(LakeConsistencyStatus.UNKNOWN, result.target().status());
        assertEquals(LakeConsistencyStatus.UNBOUND, result.task().status());
        assertEquals(LakeConsistencyStatus.UNKNOWN, result.aggregateStatus());
        assertTrue(result.taskRelations().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> result.taskRelations().add(null));
        assertEquals(null, mapping(LakeManagementLevel.MANAGED).getTaskConsistencyStatus());
    }

    @Test
    void sourceMissingAndSourceUnavailableHaveDifferentStableReasons() {
        when(sourceObjectRefDao.queryByIdIncludingDeleted(SOURCE_REF_ID)).thenReturn(null);
        LakeTableDriftEvaluator.Evaluation missing = evaluator.evaluate(mapping(LakeManagementLevel.MANAGED));
        assertEquals(LakeConsistencyStatus.MISSING, missing.source().status());
        assertEquals(LakeErrorCode.LAKE_SOURCE_OBJECT_MISSING, missing.source().reasonCode());
        verify(sourceResolver, never()).resolve(any(), anyString());

        when(sourceObjectRefDao.queryByIdIncludingDeleted(SOURCE_REF_ID)).thenReturn(sourceReference());
        doThrow(new LakeServiceException(LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN, "secret-token"))
                .when(sourceResolver).resolve(SOURCE_DATA_SOURCE_ID, "om-table");
        LakeTableDriftEvaluator.Evaluation unknown = evaluator.evaluate(mapping(LakeManagementLevel.MANAGED));
        assertEquals(LakeConsistencyStatus.UNKNOWN, unknown.source().status());
        assertEquals(LakeErrorCode.LAKE_SOURCE_OBJECT_UNKNOWN, unknown.source().reasonCode());
        assertFalse(unknown.source().reason().contains("secret-token"));
    }

    @Test
    void sourceSchemaDriftIsDetectedAgainstSourceReferenceBaseline() {
        when(sourceResolver.resolve(SOURCE_DATA_SOURCE_ID, "om-table"))
                .thenReturn(sourceSnapshot("changed-hash"));

        LakeTableDriftEvaluator.Evaluation result = evaluator.evaluate(mapping(LakeManagementLevel.AUTO_CREATED));

        assertEquals(LakeConsistencyStatus.DRIFT, result.source().status());
        assertEquals(LakeTableDriftEvaluator.SOURCE_SCHEMA_DRIFT, result.source().reasonCode());
    }

    @Test
    void targetMissingAndTargetUnavailableAreDifferentStableReasons() {
        when(dorisClient.tableExists("ods", "orders")).thenReturn(false);
        LakeTableDriftEvaluator.Evaluation missing = evaluator.evaluate(mapping(LakeManagementLevel.MANAGED));
        assertEquals(LakeConsistencyStatus.MISSING, missing.target().status());
        assertEquals(LakeTableDriftEvaluator.TARGET_TABLE_MISSING, missing.target().reasonCode());
        verify(dorisClient, never()).readContract(anyString(), anyString());

        when(dorisClientProvider.get(LAKE_DATA_SOURCE_ID))
                .thenThrow(new LakeServiceException(LakeErrorCode.LAKE_DORIS_UNAVAILABLE, "jdbc-password"));
        LakeTableDriftEvaluator.Evaluation unknown = evaluator.evaluate(mapping(LakeManagementLevel.UNMANAGED));
        assertEquals(LakeConsistencyStatus.UNKNOWN, unknown.target().status());
        assertEquals(LakeErrorCode.LAKE_DORIS_UNAVAILABLE, unknown.target().reasonCode());
        assertFalse(unknown.target().reason().contains("jdbc-password"));
    }

    @Test
    void existingTargetWithoutContractRemainsUnknown() {
        LakeTableDriftEvaluator.Evaluation result = evaluator.evaluate(mapping(LakeManagementLevel.UNMANAGED));

        assertEquals(LakeConsistencyStatus.UNKNOWN, result.target().status());
        assertEquals(LakeTableDriftEvaluator.TARGET_CONTRACT_UNKNOWN, result.target().reasonCode());
        verify(dorisClient).tableExists("ods", "orders");
        verify(dorisClient, never()).readContract(anyString(), anyString());
    }

    @Test
    void targetContractUsesStructuralReadAndDetectsDriftWithoutRawDdl() throws Exception {
        TargetContract expected = contract();
        LakeOdsTableMapping mapping = mapping(LakeManagementLevel.MANAGED);
        mapping.setActualTableExists(false);
        mapping.setTargetContractJson(new ObjectMapper().writeValueAsString(expected));
        mapping.setTargetContractHash(TargetContractCanonicalizer.canonicalHash(expected));

        when(dorisClient.readContract("ods", "orders")).thenReturn(expected);
        LakeTableDriftEvaluator.Evaluation consistent = evaluator.evaluate(mapping);
        assertEquals(LakeConsistencyStatus.CONSISTENT, consistent.target().status());

        TargetContract changed = new TargetContract(LakeTableModel.DUPLICATE, List.of(
                new TargetColumn("id", 1, "id", TargetType.varchar(255), false, true, 1),
                new TargetColumn("payload", 2, "payload", new TargetType(DorisTypeBase.INT),
                        true, false, 2)), List.of("id"), TargetPartition.disabled(),
                TargetDistribution.random());
        when(dorisClient.readContract("ods", "orders")).thenReturn(changed);
        LakeTableDriftEvaluator.Evaluation drift = evaluator.evaluate(mapping);
        assertEquals(LakeConsistencyStatus.DRIFT, drift.target().status());
        assertEquals(LakeTableDriftEvaluator.TARGET_CONTRACT_DRIFT, drift.target().reasonCode());
        verify(dorisClient, never()).showCreateTable(anyString(), anyString());
    }

    @Test
    void activeNamespaceRelationDoesNotBecomeTableConsistency() {
        LakeJobRelation namespace = relation(601L, 801L, LakeRelationScope.NAMESPACE,
                LakeJobRuntimeType.BATCH, 4);
        when(jobRelationDao.queryByOdsDatabaseBindingId(BINDING_ID)).thenReturn(List.of(namespace));

        LakeTableDriftEvaluator.Evaluation result = evaluator.evaluate(mapping(LakeManagementLevel.MANAGED));

        assertEquals(LakeConsistencyStatus.UNBOUND, result.task().status());
        verifyNoTaskReads();
    }

    @Test
    void multipleActiveRelationsHaveIndependentResultsAndPriority() {
        JobDefinitionSaveCommand firstCommand = anyCommand();
        JobDefinitionSaveCommand secondCommand = anyCommand();
        LakeJobRelation first = relation(601L, 801L, LakeRelationScope.TABLE,
                LakeJobRuntimeType.BATCH, 4);
        LakeJobRelation second = relation(602L, 802L, LakeRelationScope.TABLE,
                LakeJobRuntimeType.BATCH, 4);
        LakeJobDescriptor firstDescriptor = descriptor("source-one", "sink-one", "ERROR");
        LakeJobDescriptor secondDescriptor = descriptor("source-two-current", "sink-two", "ERROR");
        first.setSourceEndpointSnapshot(firstDescriptor.sourceEndpointSnapshot());
        first.setSinkEndpointSnapshot(firstDescriptor.sinkEndpointSnapshot());
        first.setSchemaSaveModeSnapshot(firstDescriptor.schemaSaveModeSnapshot());
        second.setSourceEndpointSnapshot("stored-source-two");
        second.setSinkEndpointSnapshot(secondDescriptor.sinkEndpointSnapshot());
        second.setSchemaSaveModeSnapshot(secondDescriptor.schemaSaveModeSnapshot());
        when(jobRelationDao.queryByOdsDatabaseBindingId(BINDING_ID)).thenReturn(List.of(first, second));
        when(batchJobDefinitionDao.queryById(801L)).thenReturn(definition(4));
        when(batchJobDefinitionDao.queryById(802L)).thenReturn(definition(4));
        when(batchCommandResolver.resolve(801L)).thenReturn(firstCommand);
        when(batchCommandResolver.resolve(802L)).thenReturn(secondCommand);
        when(jobDetector.detect(firstCommand, LakeJobRuntimeType.BATCH)).thenReturn(firstDescriptor);
        when(jobDetector.detect(secondCommand, LakeJobRuntimeType.BATCH)).thenReturn(secondDescriptor);

        LakeTableDriftEvaluator.Evaluation result = evaluator.evaluate(mapping(LakeManagementLevel.AUTO_CREATED));

        assertEquals(LakeConsistencyStatus.CONSISTENT, result.taskRelations().get(0).status());
        assertEquals(LakeConsistencyStatus.DRIFT, result.taskRelations().get(1).status());
        assertEquals(LakeConsistencyStatus.DRIFT, result.task().status());
        assertEquals(LakeConsistencyStatus.DRIFT, result.aggregateStatus());
    }

    @Test
    void missingPersistedTaskIsMissingAndResolverFailureIsUnknown() {
        LakeJobRelation missing = relation(601L, 801L, LakeRelationScope.TABLE,
                LakeJobRuntimeType.BATCH, 4);
        when(jobRelationDao.queryByOdsDatabaseBindingId(BINDING_ID)).thenReturn(List.of(missing));
        when(batchJobDefinitionDao.queryById(801L)).thenReturn(null);

        LakeTableDriftEvaluator.Evaluation missingResult = evaluator.evaluate(mapping(LakeManagementLevel.MANAGED));
        assertEquals(LakeConsistencyStatus.MISSING, missingResult.task().status());
        assertEquals(LakeTableDriftEvaluator.TASK_RELATION_MISSING,
                missingResult.taskRelations().get(0).reasonCode());

        when(batchJobDefinitionDao.queryById(801L)).thenReturn(definition(4));
        when(batchCommandResolver.resolve(801L)).thenThrow(new IllegalStateException("private-content"));
        LakeTableDriftEvaluator.Evaluation unknownResult = evaluator.evaluate(mapping(LakeManagementLevel.MANAGED));
        assertEquals(LakeConsistencyStatus.UNKNOWN, unknownResult.task().status());
        assertEquals(LakeTableDriftEvaluator.TASK_RELATION_UNKNOWN,
                unknownResult.taskRelations().get(0).reasonCode());
    }

    @Test
    void evaluatorOnlyReadsAndNeverMutatesLakeStateOrDoris() {
        LakeTableDriftEvaluator.Evaluation result = evaluator.evaluate(mapping(LakeManagementLevel.MANAGED));

        assertEquals(LakeConsistencyStatus.UNKNOWN, result.aggregateStatus());
        verify(sourceObjectRefDao, never()).insert(any());
        verify(sourceObjectRefDao, never()).updateById(any());
        verify(jobRelationDao, never()).insert(any());
        verify(jobRelationDao, never()).updateById(any());
        verify(dorisClient, never()).createDatabase(anyString());
        verify(dorisClient, never()).dropDatabase(anyString());
        verify(dorisClient, never()).createTable(anyString(), anyString(), any(TargetContract.class));
        verify(dorisClient, never()).dropTable(anyString(), anyString());
        verify(dorisClient).tableExists("ods", "orders");
    }

    @Test
    void aggregatePriorityKeepsUnboundOutOfUnknown() {
        assertEquals(LakeConsistencyStatus.MISSING,
                LakeTableDriftEvaluator.aggregateStatus(LakeConsistencyStatus.DRIFT,
                        LakeConsistencyStatus.MISSING, LakeConsistencyStatus.UNKNOWN));
        assertEquals(LakeConsistencyStatus.DRIFT,
                LakeTableDriftEvaluator.aggregateStatus(LakeConsistencyStatus.DRIFT,
                        LakeConsistencyStatus.UNKNOWN));
        assertEquals(LakeConsistencyStatus.UNKNOWN,
                LakeTableDriftEvaluator.aggregateStatus(LakeConsistencyStatus.UNKNOWN,
                        LakeConsistencyStatus.UNBOUND));
        assertEquals(LakeConsistencyStatus.UNBOUND,
                LakeTableDriftEvaluator.aggregateStatus(LakeConsistencyStatus.UNBOUND,
                        LakeConsistencyStatus.CONSISTENT));
    }

    private LakeOdsTableMapping mapping(LakeManagementLevel level) {
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setId(MAPPING_ID);
        mapping.setSourceObjectRefId(SOURCE_REF_ID);
        mapping.setOdsDatabaseBindingId(BINDING_ID);
        mapping.setLakeDataSourceId(LAKE_DATA_SOURCE_ID);
        mapping.setDatabaseName("ods");
        mapping.setTargetTableName("orders");
        mapping.setManagementLevel(level);
        mapping.setSourceSchemaHash("source-hash");
        mapping.setResourceStatus(LakeResourceStatus.READY);
        mapping.setActualTableExists(true);
        mapping.setDeleted(false);
        return mapping;
    }

    private LakeSourceObjectRef sourceReference() {
        LakeSourceObjectRef reference = new LakeSourceObjectRef();
        reference.setId(SOURCE_REF_ID);
        reference.setSourceDataSourceId(SOURCE_DATA_SOURCE_ID);
        reference.setOmEntityId("om-table");
        reference.setSourceSchemaHash("reference-only-hash");
        reference.setResourceStatus(LakeResourceStatus.READY);
        reference.setDeleted(false);
        return reference;
    }

    private SourceObjectSnapshot sourceSnapshot(String hash) {
        return new SourceObjectSnapshot("om-table", "mysql.orders", List.of(), List.of(), hash, "{}");
    }

    private LakeJobRelation relation(Long id, Long jobId, LakeRelationScope scope,
                                      LakeJobRuntimeType runtimeType, int version) {
        LakeJobRelation relation = new LakeJobRelation();
        relation.setId(id);
        relation.setOdsDatabaseBindingId(BINDING_ID);
        relation.setTableMappingId(MAPPING_ID);
        relation.setRelationScope(scope);
        relation.setJobRuntimeType(runtimeType);
        relation.setJobId(jobId);
        relation.setJobVersion(version);
        relation.setRelationStatus(LakeRelationStatus.ACTIVE);
        return relation;
    }

    private LakeJobDescriptor descriptor(String source, String sink, String schemaMode) {
        return new LakeJobDescriptor(BINDING_ID, LAKE_DATA_SOURCE_ID, SOURCE_DATA_SOURCE_ID,
                LAKE_DATA_SOURCE_ID, LakeRelationScope.TABLE, MAPPING_ID,
                LakeJobRuntimeType.BATCH, source, sink, schemaMode);
    }

    private JobDefinitionSaveCommand anyCommand() {
        return org.mockito.Mockito.mock(JobDefinitionSaveCommand.class);
    }

    private JobDefinitionEntity definition(int version) {
        JobDefinitionEntity definition = new JobDefinitionEntity();
        definition.setId(801L);
        definition.setJobVersion(version);
        return definition;
    }

    private TargetContract contract() {
        return new TargetContract(LakeTableModel.DUPLICATE, List.of(
                new TargetColumn("id", 1, "id", TargetType.varchar(255), false, true, 1),
                new TargetColumn("payload", 2, "payload", new TargetType(DorisTypeBase.STRING),
                        true, false, 2)), List.of("id"), TargetPartition.disabled(),
                TargetDistribution.random());
    }

    private void verifyNoTaskReads() {
        verify(batchJobDefinitionDao, never()).queryById(any());
        verify(streamingJobDefinitionDao, never()).queryById(any());
        verify(batchCommandResolver, never()).resolve(any());
        verify(streamingCommandResolver, never()).resolve(any());
        verify(jobDetector, never()).detect(any(), any());
    }
}
