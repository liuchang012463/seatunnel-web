package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.api.lake.LakeServiceException;
import org.apache.seatunnel.web.api.lake.doris.DorisLakeClient;
import org.apache.seatunnel.web.api.lake.doris.LakeDorisClientProvider;
import org.apache.seatunnel.web.api.lake.inventory.LakePhysicalTableInventoryRelationVO;
import org.apache.seatunnel.web.api.lake.inventory.LakePhysicalTableInventoryTableVO;
import org.apache.seatunnel.web.api.lake.inventory.LakePhysicalTableInventoryVO;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.entity.LakeOdsDatabaseBinding;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LakePhysicalTableInventoryServiceImplTest {

    @Mock private LakeOdsDatabaseBindingDao bindingDao;
    @Mock private LakeOdsTableMappingDao tableMappingDao;
    @Mock private LakeJobRelationDao relationDao;
    @Mock private LakeDorisClientProvider dorisClientProvider;
    @Mock private DorisLakeClient dorisClient;

    private LakePhysicalTableInventoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LakePhysicalTableInventoryServiceImpl(
                bindingDao, tableMappingDao, relationDao, dorisClientProvider);
        lenient().when(bindingDao.queryActiveById(41L)).thenReturn(binding());
        lenient().when(dorisClientProvider.get(99L)).thenReturn(dorisClient);
        lenient().when(dorisClient.listTables("ODS"))
                .thenReturn(List.of("Orders", "users", "USERS", "alpha"));
        lenient().when(tableMappingDao.queryByOdsDatabaseBindingId(41L))
                .thenReturn(List.of());
        lenient().when(relationDao.queryByOdsDatabaseBindingId(41L))
                .thenReturn(List.of());
    }

    @Test
    void inventoryComputesCaseInsensitiveStableDifferenceAndMarksMissingRegisteredTables() {
        LakeOdsTableMapping orders = mapping(11L, "orders");
        LakeOdsTableMapping missing = mapping(12L, "missing");
        LakeOdsTableMapping users = mapping(13L, "USERS");
        LakeOdsTableMapping deleted = mapping(14L, "ignored");
        deleted.setDeleted(true);
        when(tableMappingDao.queryByOdsDatabaseBindingId(41L))
                .thenReturn(List.of(users, deleted, missing, orders));

        LakePhysicalTableInventoryVO result = service.inventory(41L);

        assertEquals(41L, result.getOdsDatabaseBindingId());
        assertEquals("ODS", result.getDatabaseName());
        assertEquals(List.of("alpha", "Orders", "USERS"), result.getActualTableNames());
        assertEquals(List.of("missing", "orders", "USERS"), result.getRegisteredTables().stream()
                .map(LakePhysicalTableInventoryTableVO::getTargetTableName).toList());
        assertEquals(List.of("alpha"), result.getDiscoveredTables().stream()
                .map(LakePhysicalTableInventoryTableVO::getTargetTableName).toList());
        assertFalse(result.getRegisteredTables().get(0).getActualExists());
        assertTrue(result.getRegisteredTables().get(1).getActualExists());
        assertEquals(LakeManagementLevel.UNMANAGED,
                result.getDiscoveredTables().get(0).getManagementLevel());
        assertFalse(result.getDiscoveredTables().get(0).getSourceBound());
        assertTrue(result.getDiscoveredTables().get(0).getActualExists());
        verify(dorisClient).listTables("ODS");
        verify(dorisClient, never()).tableExists(any(), any());
        verify(dorisClient, never()).readContract(any(), any());
        verify(dorisClient, never()).createTable(any(), any(), any());
        verify(dorisClient, never()).dropTable(any(), any());
        verify(tableMappingDao, never()).insert(any());
        verify(tableMappingDao, never()).updateById(any());
        verify(relationDao, never()).insert(any());
        verify(relationDao, never()).updateById(any());
    }

    @Test
    void inventoryPreservesAndSeparatesAllTableAndNamespaceRelationsIncludingStale() {
        LakeJobRelation tableFirst = relation(101L, 7L, LakeRelationScope.TABLE,
                LakeRelationStatus.ACTIVE, 81L);
        tableFirst.setSourceEndpointSnapshot("source-7");
        tableFirst.setSinkEndpointSnapshot("sink-7");
        tableFirst.setSchemaSaveModeSnapshot("ERROR_WHEN_SCHEMA_NOT_EXIST");
        LakeJobRelation namespace = relation(102L, 8L, LakeRelationScope.NAMESPACE,
                LakeRelationStatus.ACTIVE, null);
        LakeJobRelation tableStale = relation(103L, 6L, LakeRelationScope.TABLE,
                LakeRelationStatus.STALE, 82L);
        when(relationDao.queryByOdsDatabaseBindingId(41L))
                .thenReturn(List.of(namespace, tableStale, tableFirst));

        LakePhysicalTableInventoryVO result = service.inventory(41L);

        assertEquals(List.of(6L, 7L), result.getTableRelations().stream()
                .map(LakePhysicalTableInventoryRelationVO::getJobId).toList());
        assertEquals(List.of(8L), result.getNamespaceRelations().stream()
                .map(LakePhysicalTableInventoryRelationVO::getJobId).toList());
        LakePhysicalTableInventoryRelationVO first = result.getTableRelations().get(1);
        assertEquals(LakeRelationScope.TABLE, first.getRelationScope());
        assertEquals(LakeJobRuntimeType.BATCH, first.getJobRuntimeType());
        assertEquals(81L, first.getTableMappingId());
        assertEquals("source-7", first.getSourceEndpointSnapshot());
        assertEquals("sink-7", first.getSinkEndpointSnapshot());
        assertEquals("ERROR_WHEN_SCHEMA_NOT_EXIST", first.getSchemaSaveModeSnapshot());
        assertEquals(LakeRelationStatus.STALE,
                result.getTableRelations().get(0).getRelationStatus());
    }

    @Test
    void inventoryRejectsDorisUnavailableWithoutTurningFailureIntoEmptyInventory() {
        when(dorisClient.listTables("ODS")).thenThrow(new IllegalStateException("secret"));

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.inventory(41L));

        assertEquals(LakeErrorCode.LAKE_DORIS_UNAVAILABLE, exception.getLakeErrorCode());
        assertFalse(exception.getMessage().contains("secret"));
        verify(tableMappingDao, never()).queryByOdsDatabaseBindingId(anyLong());
        verify(relationDao, never()).queryByOdsDatabaseBindingId(anyLong());
        verify(tableMappingDao, never()).insert(any());
        verify(relationDao, never()).insert(any());
    }

    @Test
    void inventoryRejectsMissingOrNonReadyBindingBeforeDorisRead() {
        when(bindingDao.queryActiveById(41L)).thenReturn(null);

        LakeServiceException missing = assertThrows(
                LakeServiceException.class, () -> service.inventory(41L));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, missing.getLakeErrorCode());
        verifyNoDorisRead();

        LakeOdsDatabaseBinding creating = binding();
        creating.setResourceStatus(LakeResourceStatus.CREATING);
        when(bindingDao.queryActiveById(41L)).thenReturn(creating);

        LakeServiceException notReady = assertThrows(
                LakeServiceException.class, () -> service.inventory(41L));

        assertEquals(LakeErrorCode.LAKE_RESOURCE_CONFLICT, notReady.getLakeErrorCode());
        verifyNoDorisRead();
    }

    @Test
    void inventoryTreatsNullDorisTableListAsUnavailable() {
        when(dorisClient.listTables("ODS")).thenReturn(null);

        LakeServiceException exception = assertThrows(
                LakeServiceException.class, () -> service.inventory(41L));

        assertEquals(LakeErrorCode.LAKE_DORIS_UNAVAILABLE, exception.getLakeErrorCode());
        verify(tableMappingDao, never()).queryByOdsDatabaseBindingId(anyLong());
        verify(relationDao, never()).queryByOdsDatabaseBindingId(anyLong());
    }

    @Test
    void inventoryPreservesLegacyNamesThatAreNotBindableIdentifiers() {
        when(dorisClient.listTables("ODS"))
                .thenReturn(List.of("legacy.table", " LEGACY.TABLE "));

        LakePhysicalTableInventoryVO result = service.inventory(41L);

        assertEquals(List.of("LEGACY.TABLE"), result.getActualTableNames());
        assertEquals(List.of("LEGACY.TABLE"), result.getDiscoveredTables().stream()
                .map(LakePhysicalTableInventoryTableVO::getTargetTableName).toList());
        assertEquals(LakeManagementLevel.UNMANAGED,
                result.getDiscoveredTables().get(0).getManagementLevel());
        assertFalse(result.getDiscoveredTables().get(0).getSourceBound());
    }

    private void verifyNoDorisRead() {
        verify(dorisClientProvider, never()).get(anyLong());
        verify(dorisClient, never()).listTables(any());
    }

    private LakeOdsDatabaseBinding binding() {
        LakeOdsDatabaseBinding binding = new LakeOdsDatabaseBinding();
        binding.setId(41L);
        binding.setLakeDataSourceId(99L);
        binding.setDatabaseName("ODS");
        binding.setResourceStatus(LakeResourceStatus.READY);
        binding.setDeleted(false);
        return binding;
    }

    private LakeOdsTableMapping mapping(Long id, String targetTableName) {
        LakeOdsTableMapping mapping = new LakeOdsTableMapping();
        mapping.setId(id);
        mapping.setOdsDatabaseBindingId(41L);
        mapping.setTargetTableName(targetTableName);
        mapping.setManagementLevel(LakeManagementLevel.UNMANAGED);
        mapping.setResourceStatus(LakeResourceStatus.READY);
        mapping.setSourceObjectRefId(id + 100L);
        mapping.setDeleted(false);
        return mapping;
    }

    private LakeJobRelation relation(
            Long id, Long jobId, LakeRelationScope scope,
            LakeRelationStatus status, Long tableMappingId) {
        LakeJobRelation relation = new LakeJobRelation();
        relation.setId(id);
        relation.setOdsDatabaseBindingId(41L);
        relation.setJobId(jobId);
        relation.setJobRuntimeType(LakeJobRuntimeType.BATCH);
        relation.setJobVersion(3);
        relation.setRelationScope(scope);
        relation.setRelationStatus(status);
        relation.setTableMappingId(tableMappingId);
        return relation;
    }
}
