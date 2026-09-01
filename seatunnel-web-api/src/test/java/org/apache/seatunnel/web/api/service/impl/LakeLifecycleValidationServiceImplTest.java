package org.apache.seatunnel.web.api.service.impl;

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
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionMetadata;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionSummarizer;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleValidateVO;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.common.enums.LakeTableModel;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.repository.LakeLifecyclePolicyDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleValidateDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakeLifecycleValidationServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Mock private LakeOdsTableMappingDao tableMappingDao;
    @Mock private LakeLifecyclePolicyDao policyDao;
    @Mock private LakeTableLifecycleBindingDao lifecycleBindingDao;
    @Mock private LakeDorisClientProvider dorisClientProvider;
    @Mock private DorisLakeClient dorisClient;

    private LakeLifecycleValidationServiceImpl service;
    private TargetContract contract;
    private LakeOdsTableMapping mapping;
    private LakeLifecyclePolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        service = new LakeLifecycleValidationServiceImpl(
                tableMappingDao, policyDao, lifecycleBindingDao, dorisClientProvider,
                new DorisPartitionSummarizer(Clock.fixed(NOW, ZoneOffset.UTC)));
        contract = contract(LakeTableModel.DUPLICATE,
                TargetPartition.autoRange("event_time", "DAY"));
        mapping = mapping(contract);
        policy = policy(901L, LakeLifecyclePolicyStatus.ACTIVE,
                LakePartitionGranularity.DAY, 7);
        lenient().when(tableMappingDao.queryActiveById(501L)).thenReturn(mapping);
        lenient().when(policyDao.queryById(901L)).thenReturn(policy);
        lenient().when(lifecycleBindingDao.queryByTableMappingId(501L)).thenReturn(null);
        lenient().when(dorisClientProvider.get(31L)).thenReturn(dorisClient);
        lenient().when(dorisClient.tableExists("ods", "orders")).thenReturn(true);
        lenient().when(dorisClient.readContract("ods", "orders")).thenReturn(contract);
        lenient().when(dorisClient.readTableProperties("ods", "orders"))
                .thenReturn(Map.of("partition.retention_count", "5"));
        lenient().when(dorisClient.listPartitions("ods", "orders")).thenReturn(List.of(
                new DorisPartitionMetadata("p_old", "NORMAL", null,
                        "['2026-01-01 00:00:00', '2026-02-01 00:00:00')", null, null),
                new DorisPartitionMetadata("p_current", "NORMAL", null,
                        "['2026-08-01 00:00:00', '2026-10-01 00:00:00')", null, null)));
    }

    @Test
    void validRequestReadsDorisAndReturnsSummaryWithoutWriting() {
        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertTrue(result.isValid());
        assertEquals(LakeLifecycleValidationServiceImpl.VALID, result.getCode());
        assertEquals("event_time", result.getPartitionColumn());
        assertEquals(LakePartitionGranularity.DAY, result.getGranularity());
        assertEquals(7, result.getDesiredRetentionCount());
        assertEquals(5, result.getActualRetentionCount());
        assertTrue(result.getStructuralMatch());
        assertEquals(2, result.getPartitionSummary().total());
        assertEquals(1, result.getPartitionSummary().historical());
        assertEquals(List.of("p_old"), result.getPartitionSummary().historicalNames());
        assertEquals(NOW, result.getObservedAt());
        verify(dorisClient).tableExists("ods", "orders");
        verify(dorisClient).readContract("ods", "orders");
        verify(dorisClient).readTableProperties("ods", "orders");
        verify(dorisClient).listPartitions("ods", "orders");
        verify(tableMappingDao, never()).insert(any(LakeOdsTableMapping.class));
        verify(tableMappingDao, never()).updateById(any(LakeOdsTableMapping.class));
        verify(tableMappingDao, never()).deleteById(anyLong());
        verify(policyDao, never()).insert(any(LakeLifecyclePolicy.class));
        verify(lifecycleBindingDao, never()).insert(any(LakeTableLifecycleBinding.class));
    }

    @Test
    void disabledAutoRangeReturnsDedicatedStableReasonBeforeDorisRead() throws Exception {
        mapping = mapping(contract(LakeTableModel.DUPLICATE, TargetPartition.disabled()));
        when(tableMappingDao.queryActiveById(501L)).thenReturn(mapping);

        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertFalse(result.isValid());
        assertEquals(LakeErrorCode.LAKE_LIFECYCLE_REQUIRES_PREPARTITIONED_TABLE,
                result.getCode());
        verify(dorisClientProvider, never()).get(anyLong());
    }

    @Test
    void nullableSourceColumnIsRejectedWithoutGuessing() {
        mapping.setSourceSnapshotJson(sourceSnapshot(true));

        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertFalse(result.isValid());
        assertEquals(LakeLifecycleValidationServiceImpl.SOURCE_COLUMN_NULLABLE,
                result.getCode());
        assertTrue(result.getReasons().contains(
                LakeLifecycleValidationServiceImpl.SOURCE_COLUMN_NULLABLE));
        verify(dorisClientProvider, never()).get(anyLong());
    }

    @Test
    void missingSourceNullabilityIsUnknownAndDoesNotBecomeNotNull() {
        mapping.setSourceSnapshotJson("{\"schema\":{\"columns\":[{\"name\":\"event_time\"}]}}");

        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertFalse(result.isValid());
        assertEquals(LakeLifecycleValidationServiceImpl.SOURCE_NULLABILITY_UNKNOWN,
                result.getCode());
        verify(dorisClientProvider, never()).get(anyLong());
    }

    @Test
    void malformedSourceSnapshotIsUnknown() {
        mapping.setSourceSnapshotJson("not-json");

        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertFalse(result.isValid());
        assertEquals(LakeLifecycleValidationServiceImpl.SOURCE_NULLABILITY_UNKNOWN,
                result.getCode());
        verify(dorisClientProvider, never()).get(anyLong());
    }

    @Test
    void uniquePartitionOutsideKeyIsRejectedAsInvalidContract() throws Exception {
        TargetContract invalidContract = contract(LakeTableModel.UNIQUE,
                TargetPartition.autoRange("event_time", "DAY"));
        mapping = mapping(contract);
        mapping.setTargetContractJson(MAPPER.writeValueAsString(invalidContract));
        mapping.setTargetContractHash("invalid-contract-hash");
        when(tableMappingDao.queryActiveById(501L)).thenReturn(mapping);

        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertFalse(result.isValid());
        assertEquals(LakeLifecycleValidationServiceImpl.TARGET_CONTRACT_INVALID,
                result.getCode());
        verify(dorisClientProvider, never()).get(anyLong());
    }

    @Test
    void disabledPolicyAndGranularityMismatchAreStableReasons() {
        policy.setStatus(LakeLifecyclePolicyStatus.DISABLED);
        policy.setGranularity(LakePartitionGranularity.MONTH);

        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertFalse(result.isValid());
        assertEquals(LakeLifecycleValidationServiceImpl.POLICY_DISABLED, result.getCode());
        assertTrue(result.getReasons().contains(LakeLifecycleValidationServiceImpl.GRANULARITY_MISMATCH));
        verify(dorisClientProvider, never()).get(anyLong());
    }

    @Test
    void mappingMustBeManagedReadyAndWithoutOperation() {
        mapping.setManagementLevel(LakeManagementLevel.UNMANAGED);
        mapping.setResourceStatus(LakeResourceStatus.CREATING);
        mapping.setOperationToken("opaque-operation-token");

        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertFalse(result.isValid());
        assertEquals(LakeLifecycleValidationServiceImpl.MAPPING_NOT_MANAGED, result.getCode());
        assertTrue(result.getReasons().contains(LakeLifecycleValidationServiceImpl.MAPPING_NOT_READY));
        assertTrue(result.getReasons().contains(
                LakeLifecycleValidationServiceImpl.MAPPING_OPERATION_IN_PROGRESS));
        verify(dorisClientProvider, never()).get(anyLong());
    }

    @Test
    void missingActualTableIsReturnedAsMissing() {
        when(dorisClient.tableExists("ods", "orders")).thenReturn(false);

        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertFalse(result.isValid());
        assertEquals(LakeLifecycleValidationServiceImpl.TARGET_TABLE_MISSING, result.getCode());
        assertFalse(result.getStructuralMatch());
        verify(dorisClient, never()).readContract(anyString(), anyString());
        verify(dorisClient, never()).listPartitions(anyString(), anyString());
    }

    @Test
    void actualContractDriftIsReturnedWithoutReadingPropertiesOrPartitions() {
        TargetContract actual = contract(LakeTableModel.DUPLICATE,
                TargetPartition.autoRange("event_time", "MONTH"));
        when(dorisClient.readContract("ods", "orders")).thenReturn(actual);

        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertFalse(result.isValid());
        assertEquals(LakeLifecycleValidationServiceImpl.STRUCTURAL_DRIFT, result.getCode());
        assertEquals(7, result.getDesiredRetentionCount());
        assertFalse(result.getStructuralMatch());
        verify(dorisClient, never()).readTableProperties(anyString(), anyString());
        verify(dorisClient, never()).listPartitions(anyString(), anyString());
    }

    @Test
    void DorisExceptionIsReturnedWithStableUnavailableCode() {
        when(dorisClientProvider.get(31L)).thenThrow(new IllegalStateException("password=secret"));

        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertFalse(result.isValid());
        assertEquals(LakeErrorCode.LAKE_DORIS_UNAVAILABLE, result.getCode());
        assertEquals(7, result.getDesiredRetentionCount());
        assertFalse(result.getReasons().stream().anyMatch(reason -> reason.contains("secret")));
    }

    @Test
    void missingRetentionPropertyMeansPermanentButDoesNotHidePartitionObservation() {
        when(dorisClient.readTableProperties("ods", "orders")).thenReturn(Map.of());

        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertTrue(result.isValid());
        assertNull(result.getActualRetentionCount());
        assertEquals(2, result.getPartitionSummary().total());
    }

    @Test
    void invalidOrNonpositiveRetentionPropertyIsUnknown() {
        when(dorisClient.readTableProperties("ods", "orders"))
                .thenReturn(Map.of("partition.retention_count", "0"));

        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertFalse(result.isValid());
        assertEquals(LakeLifecycleValidationServiceImpl.ACTUAL_RETENTION_UNKNOWN,
                result.getCode());
        assertEquals(7, result.getDesiredRetentionCount());
        verify(dorisClient, never()).listPartitions(anyString(), anyString());
    }

    @Test
    void existingBindingIsReturnedAndPolicyDifferenceIsNotDerivedFromActualProperty() {
        LakeTableLifecycleBinding binding = new LakeTableLifecycleBinding();
        binding.setId(1001L);
        binding.setTableMappingId(501L);
        binding.setPolicyId(902L);
        binding.setPolicyVersion(2);
        binding.setPartitionColumn("event_time");
        binding.setGranularity(LakePartitionGranularity.DAY);
        binding.setRetentionCount(30);
        binding.setActualRetentionCount(5);
        binding.setStatus(LakeLifecycleBindingStatus.ACTIVE);
        when(lifecycleBindingDao.queryByTableMappingId(501L)).thenReturn(binding);

        LakeLifecycleValidateVO result = service.validate(request(501L, 901L));

        assertTrue(result.isValid());
        assertTrue(result.getExistingBindingPolicyDiff());
        assertEquals(902L, result.getExistingBinding().getPolicyId());
        assertEquals(30, result.getExistingBinding().getRetentionCount());
        assertEquals(5, result.getActualRetentionCount());
        assertEquals(7, result.getDesiredRetentionCount());
    }

    @Test
    void deletedOrMissingMappingIsAStableConflict() throws Exception {
        LakeOdsTableMapping deleted = mapping(contract);
        deleted.setDeleted(true);
        when(tableMappingDao.queryActiveById(501L)).thenReturn(null);
        when(tableMappingDao.queryByIdIncludingDeleted(501L)).thenReturn(deleted);

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.validate(request(501L, 901L)));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, exception.getLakeErrorCode());
        verify(dorisClientProvider, never()).get(anyLong());
    }

    @Test
    void detailUsesOnlyCachedRowsAndNeverCallsDoris() {
        LakeTableLifecycleBinding binding = new LakeTableLifecycleBinding();
        binding.setId(1001L);
        binding.setTableMappingId(501L);
        binding.setPolicyId(901L);
        binding.setPartitionColumn("event_time");
        binding.setGranularity(LakePartitionGranularity.DAY);
        binding.setRetentionCount(7);
        binding.setActualRetentionCount(7);
        binding.setPolicyVersion(1);
        binding.setStatus(LakeLifecycleBindingStatus.ACTIVE);
        binding.setLastObservedAt(java.util.Date.from(NOW));
        binding.setPolicySnapshotJson(
                "{\"policyId\":901,\"version\":1,\"granularity\":\"DAY\","
                        + "\"retentionCount\":7}");
        binding.setActualPartitionSummaryJson(
                "{\"total\":0,\"historical\":0,\"current\":0,\"future\":0,"
                        + "\"unknown\":0,\"partitionNames\":[],"
                        + "\"observedAt\":\"2026-09-01T00:00:00Z\","
                        + "\"historicalPartitionNames\":[],\"currentPartitionNames\":[],"
                        + "\"futurePartitionNames\":[],\"unknownPartitionNames\":[]}");
        mapping.setTargetConsistencyStatus(LakeConsistencyStatus.CONSISTENT);
        when(lifecycleBindingDao.queryByTableMappingId(501L)).thenReturn(binding);
        policy.setStatus(LakeLifecyclePolicyStatus.DISABLED);
        policy.setVersion(2);
        policy.setRetentionCount(30);

        LakeLifecycleValidateVO result = service.detail(501L);

        assertTrue(result.isValid());
        assertEquals(LakeLifecycleValidationServiceImpl.VALID, result.getCode());
        assertEquals(7, result.getActualRetentionCount());
        assertEquals(0, result.getPartitionSummary().total());
        verify(dorisClientProvider, never()).get(anyLong());
        verify(dorisClient, never()).tableExists(anyString(), anyString());
        verify(dorisClient, never()).readContract(anyString(), anyString());
    }

    @Test
    void detailDoesNotReportValidForStaleRetentionCache() {
        LakeTableLifecycleBinding binding = new LakeTableLifecycleBinding();
        binding.setId(1001L);
        binding.setTableMappingId(501L);
        binding.setPolicyId(901L);
        binding.setPolicyVersion(1);
        binding.setPartitionColumn("event_time");
        binding.setGranularity(LakePartitionGranularity.DAY);
        binding.setRetentionCount(7);
        binding.setActualRetentionCount(5);
        binding.setStatus(LakeLifecycleBindingStatus.ACTIVE);
        binding.setLastObservedAt(java.util.Date.from(NOW));
        binding.setActualPartitionSummaryJson(
                "{\"total\":0,\"historical\":0,\"current\":0,\"future\":0,"
                        + "\"unknown\":0,\"partitionNames\":[],"
                        + "\"observedAt\":\"2026-09-01T00:00:00Z\","
                        + "\"historicalPartitionNames\":[],\"currentPartitionNames\":[],"
                        + "\"futurePartitionNames\":[],\"unknownPartitionNames\":[]}");
        mapping.setTargetConsistencyStatus(LakeConsistencyStatus.CONSISTENT);
        when(lifecycleBindingDao.queryByTableMappingId(501L)).thenReturn(binding);

        LakeLifecycleValidateVO result = service.detail(501L);

        assertFalse(result.isValid());
        assertEquals(LakeLifecycleValidationServiceImpl.CACHE_UNAVAILABLE, result.getCode());
        verify(dorisClientProvider, never()).get(anyLong());
    }

    private static LakeLifecycleValidateDTO request(Long mappingId, Long policyId) {
        LakeLifecycleValidateDTO request = new LakeLifecycleValidateDTO();
        request.setMappingId(mappingId);
        request.setPolicyId(policyId);
        return request;
    }

    private static LakeOdsTableMapping mapping(TargetContract contract) throws Exception {
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setId(501L);
        mapping.setSourceObjectRefId(701L);
        mapping.setOdsDatabaseBindingId(801L);
        mapping.setLakeDataSourceId(31L);
        mapping.setDatabaseName("ods");
        mapping.setTargetTableName("orders");
        mapping.setManagementLevel(LakeManagementLevel.MANAGED);
        mapping.setResourceStatus(LakeResourceStatus.READY);
        mapping.setDeleted(false);
        mapping.setGeneration(1);
        mapping.setLockVersion(1);
        mapping.setTargetContractJson(MAPPER.writeValueAsString(contract));
        mapping.setTargetContractHash(TargetContractCanonicalizer.canonicalHash(contract));
        mapping.setSourceSnapshotJson(sourceSnapshot(false));
        mapping.setSourceSchemaHash("source-hash");
        return mapping;
    }

    private static LakeLifecyclePolicy policy(
            Long id, LakeLifecyclePolicyStatus status,
            LakePartitionGranularity granularity, int retentionCount) {
        LakeLifecyclePolicy policy = new LakeLifecyclePolicy();
        policy.setId(id);
        policy.setPolicyName("daily");
        policy.setVersion(1);
        policy.setStatus(status);
        policy.setGranularity(granularity);
        policy.setRetentionCount(retentionCount);
        return policy;
    }

    private static TargetContract contract(
            LakeTableModel tableModel, org.apache.seatunnel.web.api.lake.contract.TargetPartition partition) {
        return new TargetContract(tableModel, List.of(
                new TargetColumn("id", 1, "id", TargetType.varchar(255), false, true, 1),
                new TargetColumn("event_time", 2, "event_time",
                        new TargetType(DorisTypeBase.DATETIME), false, false, 2),
                new TargetColumn("payload", 3, "payload",
                        new TargetType(DorisTypeBase.STRING), true, false, 3)),
                List.of("id"), partition,
                tableModel == LakeTableModel.UNIQUE
                        ? TargetDistribution.hash(List.of("id")) : TargetDistribution.random());
    }

    private static String sourceSnapshot(boolean nullable) {
        return "{\"objectType\":\"TABLE\",\"omEntityId\":\"om-table\","
                + "\"schema\":{\"columns\":[{\"name\":\"event_time\",\"nullable\":"
                + nullable + "}]}}";
    }
}
