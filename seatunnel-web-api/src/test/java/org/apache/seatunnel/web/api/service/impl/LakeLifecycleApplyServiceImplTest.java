package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.contract.DorisTypeBase;
import org.apache.seatunnel.web.api.lake.contract.TargetColumn;
import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.api.lake.contract.TargetDistribution;
import org.apache.seatunnel.web.api.lake.contract.TargetPartition;
import org.apache.seatunnel.web.api.lake.contract.TargetType;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionMetadata;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionSummarizer;
import org.apache.seatunnel.web.api.lake.doris.DorisPartitionSummary;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleBindingSnapshotVO;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleConfirmationTokenService;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleMappingSnapshotVO;
import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleValidateVO;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.LakeLifecycleValidationService;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeTableLifecycleBinding;
import org.apache.seatunnel.web.dao.repository.LakeTableLifecycleBindingDao;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleApplyDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleRetentionUpdateDTO;
import org.apache.seatunnel.web.spi.bean.vo.LakeLifecyclePolicyVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakeLifecycleApplyServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Mock private LakeLifecycleValidationService validationService;
    @Mock private LakeLifecycleApplyPersistenceService persistenceService;
    @Mock private LakeTableLifecycleBindingDao lifecycleBindingDao;
    @Mock private LakeLifecycleConfirmationTokenService tokenService;
    @Mock private LakeDorisClientProvider dorisClientProvider;
    @Mock private CurrentUserProvider currentUserProvider;
    @Mock private DorisLakeClient dorisClient;
    @Mock private DorisPartitionSummarizer partitionSummarizer;

    private LakeLifecycleApplyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LakeLifecycleApplyServiceImpl(
                validationService, persistenceService, lifecycleBindingDao, tokenService,
                dorisClientProvider, currentUserProvider, partitionSummarizer);
        when(currentUserProvider.getCurrentUserId()).thenReturn(7);
    }

    @Test
    void freshExactApplyIsIdempotentWithoutAlterAndReturnsLatestLockVersion() {
        LakeLifecycleValidateVO validated = validated(7, impactSummary());
        LakeTableLifecycleBinding active = binding(7, 4);
        LakeTableLifecycleBinding current = binding(7, 9);
        when(validationService.validate(any())).thenReturn(validated);
        when(persistenceService.start(any())).thenAnswer(invocation -> {
            LakeLifecycleApplyPersistenceService.StartRequest request = invocation.getArgument(0);
            assertEquals(7, request.freshActualRetentionCount());
            assertTrue(request.freshSummaryComplete());
            return new LakeLifecycleApplyPersistenceService.StartResult(null, active, true);
        });
        when(lifecycleBindingDao.queryByTableMappingId(501L)).thenReturn(current);

        LakeLifecycleValidateVO result = service.apply(applyRequest(901L));

        assertTrue(result.isValid());
        assertEquals(9, result.getExistingBinding().getLockVersion());
        assertEquals(LakeLifecycleBindingStatus.ACTIVE, result.getExistingBinding().getStatus());
        verify(dorisClientProvider, never()).get(any());
        verify(dorisClient, never()).alterTableProperties(anyString(), anyString(), any());
        verify(persistenceService, never()).finalizeSuccess(any(), any(), any(), anyInt());
    }

    @Test
    void applyAltersRetentionVerifiesContractPropertyAndPartitionsBeforeFinalizing() {
        DorisPartitionSummary summary = completeSummary();
        LakeLifecycleValidateVO validated = validated(7, summary);
        LakeLifecycleApplyPersistenceService.StartResult started = started(7);
        LakeTableLifecycleBinding current = binding(7, 8);
        List<DorisPartitionMetadata> partitions = List.of(
                new DorisPartitionMetadata("p_current", "NORMAL", "event_time", null,
                        "2026-09-01 00:00:00", "2026-10-01 00:00:00"));
        when(validationService.validate(any())).thenReturn(validated);
        when(persistenceService.start(any())).thenReturn(started);
        when(dorisClientProvider.get(31L)).thenReturn(dorisClient);
        when(dorisClient.tableExists("ods", "orders")).thenReturn(true);
        when(dorisClient.readContract("ods", "orders")).thenReturn(validated.getMappingSnapshot()
                .getTargetContract());
        when(dorisClient.readTableProperties("ods", "orders"))
                .thenReturn(Map.of("partition.retention_count", "7"));
        when(dorisClient.listPartitions("ods", "orders")).thenReturn(partitions);
        when(partitionSummarizer.summarize(eq(LakePartitionGranularity.DAY), eq(partitions)))
                .thenReturn(summary);
        when(persistenceService.finalizeSuccess(
                eq(started.handle()), eq(7), eq(summary), eq(7))).thenReturn(true);
        when(lifecycleBindingDao.queryByTableMappingId(501L)).thenReturn(current);

        LakeLifecycleValidateVO result = service.apply(applyRequest(901L));

        assertTrue(result.isValid());
        assertEquals(8, result.getExistingBinding().getLockVersion());
        ArgumentCaptor<Map<String, String>> properties = ArgumentCaptor.forClass(Map.class);
        verify(dorisClient).alterTableProperties(eq("ods"), eq("orders"), properties.capture());
        assertEquals(Map.of("partition.retention_count", "7"), properties.getValue());
        verify(dorisClient).readContract("ods", "orders");
        verify(dorisClient).readTableProperties("ods", "orders");
        verify(dorisClient).listPartitions("ods", "orders");
        verify(persistenceService).finalizeSuccess(started.handle(), 7, summary, 7);
    }

    @Test
    void propertyVerificationMismatchFinalizesFailureWithoutPublishingSuccess() {
        LakeLifecycleValidateVO validated = validated(7, completeSummary());
        LakeLifecycleApplyPersistenceService.StartResult started = started(7);
        when(validationService.validate(any())).thenReturn(validated);
        when(persistenceService.start(any())).thenReturn(started);
        when(dorisClientProvider.get(31L)).thenReturn(dorisClient);
        when(dorisClient.tableExists("ods", "orders")).thenReturn(true);
        when(dorisClient.readContract("ods", "orders"))
                .thenReturn(validated.getMappingSnapshot().getTargetContract());
        when(dorisClient.readTableProperties("ods", "orders"))
                .thenReturn(Map.of("partition.retention_count", "6"));
        when(persistenceService.finalizeFailure(
                eq(started.handle()), eq(LakeLifecycleApplyServiceImpl.RETENTION_VERIFY_MISMATCH),
                eq(7))).thenReturn(true);

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.apply(applyRequest(901L)));

        assertEquals(LakeLifecycleApplyServiceImpl.RETENTION_VERIFY_MISMATCH,
                exception.getLakeErrorCode());
        verify(dorisClient).alterTableProperties(eq("ods"), eq("orders"), any());
        verify(persistenceService).finalizeFailure(
                started.handle(), LakeLifecycleApplyServiceImpl.RETENTION_VERIFY_MISMATCH, 7);
        verify(persistenceService, never()).finalizeSuccess(any(), any(), any(), anyInt());
        verify(dorisClient, never()).listPartitions(anyString(), anyString());
    }

    @Test
    void decreaseRevalidatesImpactAndConsumesTokenExactlyOnce() {
        DorisPartitionSummary summary = impactSummary();
        LakeLifecycleValidateVO validated = validated(2, summary);
        LakeTableLifecycleBinding existing = binding(5, 2);
        LakeLifecycleBindingSnapshotVO snapshot = bindingSnapshot(existing);
        List<String> impacted = LakeLifecycleRetentionPreviewServiceImpl
                .impactedHistoricalPartitionNames(summary, 2);
        String impactHash = LakeLifecycleRetentionPreviewServiceImpl.observedImpactHash(
                validated.getMappingSnapshot(), snapshot, validated, 2, impacted);
        LakeLifecycleConfirmationTokenService.Payload payload =
                new LakeLifecycleConfirmationTokenService.Payload(
                        "LAKE_LIFECYCLE_RETENTION_DECREASE", 7, 501L, 3, 4,
                        701L, 1, 2, 5, 901L, 2, 2, impactHash,
                        NOW.plusSeconds(300).getEpochSecond(), "nonce");
        LakeLifecycleApplyPersistenceService.StartResult started = started(2);
        LakeTableLifecycleBinding current = binding(2, 6);
        when(validationService.validate(any())).thenReturn(validated);
        when(lifecycleBindingDao.queryByTableMappingId(501L)).thenReturn(existing, current);
        when(tokenService.verify("decrease-token", 7)).thenReturn(payload);
        when(tokenService.consume("decrease-token", payload)).thenReturn(true);
        when(persistenceService.start(any())).thenReturn(started);
        stubExternal(validated, summary, "2");
        when(persistenceService.finalizeSuccess(
                eq(started.handle()), eq(2), eq(summary), eq(7))).thenReturn(true);

        LakeLifecycleRetentionUpdateDTO request = new LakeLifecycleRetentionUpdateDTO();
        request.setPolicyId(901L);
        request.setConfirmationToken("decrease-token");
        LakeLifecycleValidateVO result = service.update(501L, request);

        assertTrue(result.isValid());
        assertEquals(6, result.getExistingBinding().getLockVersion());
        verify(tokenService).verify("decrease-token", 7);
        verify(tokenService).consume("decrease-token", payload);
        ArgumentCaptor<Map<String, String>> properties = ArgumentCaptor.forClass(Map.class);
        verify(dorisClient).alterTableProperties(eq("ods"), eq("orders"), properties.capture());
        assertEquals(Map.of("partition.retention_count", "2"), properties.getValue());
    }

    @Test
    void staleTx2DoesNotFinalizeFailureOrReturnAStaleSuccess() {
        DorisPartitionSummary summary = completeSummary();
        LakeLifecycleValidateVO validated = validated(7, summary);
        LakeLifecycleApplyPersistenceService.StartResult started = started(7);
        when(validationService.validate(any())).thenReturn(validated);
        when(persistenceService.start(any())).thenReturn(started);
        stubExternal(validated, summary, "7");
        when(persistenceService.finalizeSuccess(
                eq(started.handle()), eq(7), eq(summary), eq(7))).thenReturn(false);

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.apply(applyRequest(901L)));

        assertEquals(LakeErrorCode.LAKE_OPERATION_STALE, exception.getLakeErrorCode());
        verify(persistenceService).finalizeSuccess(started.handle(), 7, summary, 7);
        verify(persistenceService, never()).finalizeFailure(any(), anyString(), anyInt());
        verify(lifecycleBindingDao, never()).queryByTableMappingId(501L);
    }

    private void stubExternal(
            LakeLifecycleValidateVO validated, DorisPartitionSummary summary, String retention) {
        when(dorisClientProvider.get(31L)).thenReturn(dorisClient);
        when(dorisClient.tableExists("ods", "orders")).thenReturn(true);
        when(dorisClient.readContract("ods", "orders"))
                .thenReturn(validated.getMappingSnapshot().getTargetContract());
        when(dorisClient.readTableProperties("ods", "orders"))
                .thenReturn(Map.of("partition.retention_count", retention));
        List<DorisPartitionMetadata> partitions = List.of(
                new DorisPartitionMetadata("p_current", "NORMAL", "event_time", null,
                        "2026-09-01 00:00:00", "2026-10-01 00:00:00"));
        when(dorisClient.listPartitions("ods", "orders")).thenReturn(partitions);
        when(partitionSummarizer.summarize(eq(LakePartitionGranularity.DAY), eq(partitions)))
                .thenReturn(summary);
    }

    private static LakeLifecycleApplyPersistenceService.StartResult started(Integer retention) {
        LakeTableLifecycleBinding pending = binding(retention, 2);
        pending.setStatus(LakeLifecycleBindingStatus.PENDING);
        pending.setOperationToken("operation-token");
        LakeLifecycleApplyPersistenceService.OperationHandle handle =
                new LakeLifecycleApplyPersistenceService.OperationHandle(
                        3001L, 501L, 3, 701L, 1, 2, "operation-token", 7);
        return new LakeLifecycleApplyPersistenceService.StartResult(handle, pending, false);
    }

    private static LakeLifecycleValidateVO validated(
            int desiredRetention, DorisPartitionSummary summary) {
        LakeLifecycleValidateVO result = new LakeLifecycleValidateVO();
        result.setValid(true);
        result.setCode(LakeLifecycleValidationServiceImpl.VALID);
        result.setMappingId(501L);
        result.setPolicyId(901L);
        result.setMappingSnapshot(mappingSnapshot());
        result.setPolicySnapshot(policySnapshot(desiredRetention));
        result.setPartitionColumn("event_time");
        result.setGranularity(LakePartitionGranularity.DAY);
        result.setDesiredRetentionCount(desiredRetention);
        result.setActualRetentionCount(desiredRetention);
        result.setPartitionSummary(summary);
        result.setObservedAt(summary.observedAt());
        return result;
    }

    private static LakeLifecycleMappingSnapshotVO mappingSnapshot() {
        LakeLifecycleMappingSnapshotVO mapping = new LakeLifecycleMappingSnapshotVO();
        mapping.setId(501L);
        mapping.setLakeDataSourceId(31L);
        mapping.setDatabaseName("ods");
        mapping.setTargetTableName("orders");
        mapping.setGeneration(3);
        mapping.setLockVersion(4);
        mapping.setManagementLevel(LakeManagementLevel.MANAGED);
        mapping.setResourceStatus(LakeResourceStatus.READY);
        mapping.setTargetConsistencyStatus(LakeConsistencyStatus.CONSISTENT);
        mapping.setTargetContract(contract());
        return mapping;
    }

    private static LakeLifecyclePolicyVO policySnapshot(int retention) {
        LakeLifecyclePolicyVO policy = new LakeLifecyclePolicyVO();
        policy.setId(901L);
        policy.setVersion(2);
        policy.setStatus(LakeLifecyclePolicyStatus.ACTIVE);
        policy.setGranularity(LakePartitionGranularity.DAY);
        policy.setRetentionCount(retention);
        return policy;
    }

    private static TargetContract contract() {
        return new TargetContract(
                org.apache.seatunnel.web.common.enums.LakeTableModel.DUPLICATE,
                List.of(
                        new TargetColumn("id", 1, "id", TargetType.varchar(255),
                                false, true, 1),
                        new TargetColumn("event_time", 2, "event_time",
                                new TargetType(DorisTypeBase.DATETIME), false, false, 2)),
                List.of("id"), TargetPartition.autoRange("event_time", "DAY"),
                TargetDistribution.random());
    }

    private static LakeTableLifecycleBinding binding(int retention, int lockVersion) {
        LakeTableLifecycleBinding binding = new LakeTableLifecycleBinding();
        binding.setId(701L);
        binding.setTableMappingId(501L);
        binding.setPolicyId(901L);
        binding.setPolicyVersion(2);
        binding.setPartitionColumn("event_time");
        binding.setGranularity(LakePartitionGranularity.DAY);
        binding.setRetentionCount(retention);
        binding.setActualRetentionCount(retention);
        binding.setActualPartitionSummaryJson("{}");
        binding.setLastObservedAt(java.util.Date.from(NOW));
        binding.setPolicySnapshotJson(
                "{\"policyId\":901,\"version\":2,\"granularity\":\"DAY\","
                        + "\"retentionCount\":" + retention + "}");
        binding.setStatus(LakeLifecycleBindingStatus.ACTIVE);
        binding.setGeneration(1);
        binding.setLockVersion(lockVersion);
        return binding;
    }

    private static LakeLifecycleBindingSnapshotVO bindingSnapshot(
            LakeTableLifecycleBinding binding) {
        LakeLifecycleBindingSnapshotVO snapshot = new LakeLifecycleBindingSnapshotVO();
        snapshot.setId(binding.getId());
        snapshot.setTableMappingId(binding.getTableMappingId());
        snapshot.setPolicyId(binding.getPolicyId());
        snapshot.setPolicyVersion(binding.getPolicyVersion());
        snapshot.setRetentionCount(binding.getRetentionCount());
        snapshot.setGeneration(binding.getGeneration());
        snapshot.setLockVersion(binding.getLockVersion());
        snapshot.setStatus(binding.getStatus());
        return snapshot;
    }

    private static DorisPartitionSummary completeSummary() {
        return new DorisPartitionSummary(
                1, 0, 1, 0, 0, List.of("p_current"), NOW,
                List.of(), List.of("p_current"), List.of(), List.of());
    }

    private static DorisPartitionSummary impactSummary() {
        return new DorisPartitionSummary(
                4, 3, 1, 0, 0,
                List.of("p_old", "p_mid", "p_new", "p_current"), NOW,
                List.of("p_old", "p_mid", "p_new"), List.of("p_current"),
                List.of(), List.of());
    }

    private static LakeLifecycleApplyDTO applyRequest(Long policyId) {
        LakeLifecycleApplyDTO request = new LakeLifecycleApplyDTO();
        request.setMappingId(501L);
        request.setPolicyId(policyId);
        return request;
    }
}
