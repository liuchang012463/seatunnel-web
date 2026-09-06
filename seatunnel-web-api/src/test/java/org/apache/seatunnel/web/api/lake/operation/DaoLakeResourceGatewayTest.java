package org.apache.seatunnel.web.api.lake.operation;

import org.apache.seatunnel.web.api.lake.LakeErrorCode;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;
import org.apache.seatunnel.web.dao.repository.LakeExternalCatalogBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsTableMappingDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DaoLakeResourceGatewayTest {

    @Test
    void claimUsesNullTokenCompareAndSwapAndAdvancesEntityVersion() {
        LakeSourceObjectRefDao sourceDao = mock(LakeSourceObjectRefDao.class);
        LakeOdsDatabaseBindingDao databaseDao = mock(LakeOdsDatabaseBindingDao.class);
        LakeOdsTableMappingDao tableDao = mock(LakeOdsTableMappingDao.class);
        LakeExternalCatalogBindingDao catalogDao = mock(LakeExternalCatalogBindingDao.class);
        LakeOdsTableMapping row = new LakeOdsTableMapping();
        row.setId(20L);
        row.setGeneration(1);
        row.setLockVersion(1);
        row.setDeleted(false);
        row.setResourceStatus(LakeResourceStatus.PENDING_CREATE);
        when(tableDao.queryByIdIncludingDeleted(20L)).thenReturn(row);
        doAnswer(invocation -> {
            LakeOdsTableMapping entity = invocation.getArgument(0);
            Integer expectedVersion = invocation.getArgument(2);
            entity.setLockVersion(expectedVersion + 1);
            return true;
        }).when(tableDao).updateIfTokenAndVersion(any(), isNull(), anyInt());

        DaoLakeResourceGateway gateway = new DaoLakeResourceGateway(
                sourceDao, databaseDao, tableDao, catalogDao);
        LakeResourceState expected = gateway.get(LakeResourceTypes.ODS_TABLE_MAPPING, 20L);
        assertTrue(gateway.claim(expected, "new-token", 1, LakeResourceStatus.CREATING));
        assertEquals("new-token", row.getOperationToken());
        assertEquals(2, row.getLockVersion());
        verify(tableDao).updateIfTokenAndVersion(row, null, 1);
    }

    @Test
    void rebuildReopensDeletedRowAndDropFinalizationMarksItDeleted() {
        LakeSourceObjectRefDao sourceDao = mock(LakeSourceObjectRefDao.class);
        LakeOdsDatabaseBindingDao databaseDao = mock(LakeOdsDatabaseBindingDao.class);
        LakeOdsTableMappingDao tableDao = mock(LakeOdsTableMappingDao.class);
        LakeExternalCatalogBindingDao catalogDao = mock(LakeExternalCatalogBindingDao.class);
        LakeOdsTableMapping row = new LakeOdsTableMapping();
        row.setId(21L);
        row.setGeneration(3);
        row.setLockVersion(4);
        row.setDeleted(false);
        row.setOperationToken("drop-token");
        row.setResourceStatus(LakeResourceStatus.DELETING);
        row.setActualTableExists(true);
        row.setTargetConsistencyStatus(LakeConsistencyStatus.CONSISTENT);
        when(tableDao.queryByIdIncludingDeleted(21L)).thenReturn(row);
        doAnswer(invocation -> {
            LakeOdsTableMapping entity = invocation.getArgument(0);
            Integer expectedVersion = invocation.getArgument(2);
            entity.setLockVersion(expectedVersion + 1);
            return true;
        }).when(tableDao).updateIfTokenAndVersion(any(), any(), anyInt());
        doAnswer(invocation -> {
            LakeOdsTableMapping entity = invocation.getArgument(0);
            Integer expectedVersion = invocation.getArgument(2);
            entity.setLockVersion(expectedVersion + 1);
            return true;
        }).when(tableDao).updateIfTokenAndVersionIncludingDeleted(any(), any(), anyInt());

        DaoLakeResourceGateway gateway = new DaoLakeResourceGateway(
                sourceDao, databaseDao, tableDao, catalogDao);
        LakeOperationHandle handle = new LakeOperationHandle(
                1L, LakeResourceTypes.ODS_TABLE_MAPPING, 21L, 3, "drop-token", 4);
        assertTrue(gateway.finalizeSuccess(handle, null));
        assertEquals(LakeResourceStatus.DELETED, row.getResourceStatus());
        assertTrue(row.getDeleted());
        assertEquals(null, row.getOperationToken());
        assertFalse(row.getActualTableExists());

        row.setGeneration(3);
        row.setLockVersion(6);
        row.setDeleted(true);
        row.setOperationToken(null);
        row.setResourceStatus(LakeResourceStatus.DELETED);
        LakeResourceState deleted = gateway.get(LakeResourceTypes.ODS_TABLE_MAPPING, 21L);
        assertTrue(deleted.deleted());
        assertTrue(gateway.claim(deleted, "rebuild-token", 4, LakeResourceStatus.CREATING));
        assertFalse(row.getDeleted());
        assertEquals(7, row.getLockVersion());
        verify(tableDao).updateIfTokenAndVersionIncludingDeleted(row, null, 6);
    }

    @Test
    void createFinalizationPublishesObservedTableStateWithTheCasUpdate() {
        LakeSourceObjectRefDao sourceDao = mock(LakeSourceObjectRefDao.class);
        LakeOdsDatabaseBindingDao databaseDao = mock(LakeOdsDatabaseBindingDao.class);
        LakeOdsTableMappingDao tableDao = mock(LakeOdsTableMappingDao.class);
        LakeExternalCatalogBindingDao catalogDao = mock(LakeExternalCatalogBindingDao.class);
        LakeOdsTableMapping row = new LakeOdsTableMapping();
        row.setId(23L);
        row.setGeneration(1);
        row.setLockVersion(2);
        row.setDeleted(false);
        row.setOperationToken("create-token");
        row.setResourceStatus(LakeResourceStatus.CREATING);
        row.setActualTableExists(false);
        row.setTargetConsistencyStatus(LakeConsistencyStatus.UNKNOWN);
        when(tableDao.queryByIdIncludingDeleted(23L)).thenReturn(row);
        doAnswer(invocation -> {
            LakeOdsTableMapping entity = invocation.getArgument(0);
            Integer expectedVersion = invocation.getArgument(2);
            entity.setLockVersion(expectedVersion + 1);
            return true;
        }).when(tableDao).updateIfTokenAndVersion(any(), any(), anyInt());

        DaoLakeResourceGateway gateway = new DaoLakeResourceGateway(
                sourceDao, databaseDao, tableDao, catalogDao);
        LakeOperationHandle handle = new LakeOperationHandle(
                3L, LakeResourceTypes.ODS_TABLE_MAPPING, 23L, 1, "create-token", 2);

        assertTrue(gateway.finalizeSuccess(handle, "created"));
        assertEquals(LakeResourceStatus.READY, row.getResourceStatus());
        assertFalse(row.getDeleted());
        assertTrue(row.getActualTableExists());
        assertEquals(LakeConsistencyStatus.CONSISTENT, row.getTargetConsistencyStatus());
        verify(tableDao).updateIfTokenAndVersion(row, "create-token", 2);
    }

    @Test
    void externalFailureMapsMissingAndUnavailableToStableResourceStates() {
        LakeSourceObjectRefDao sourceDao = mock(LakeSourceObjectRefDao.class);
        LakeOdsDatabaseBindingDao databaseDao = mock(LakeOdsDatabaseBindingDao.class);
        LakeOdsTableMappingDao tableDao = mock(LakeOdsTableMappingDao.class);
        LakeExternalCatalogBindingDao catalogDao = mock(LakeExternalCatalogBindingDao.class);
        LakeOdsTableMapping row = new LakeOdsTableMapping();
        row.setId(22L);
        row.setGeneration(1);
        row.setLockVersion(1);
        row.setDeleted(false);
        row.setOperationToken("missing-token");
        row.setResourceStatus(LakeResourceStatus.CREATING);
        when(tableDao.queryByIdIncludingDeleted(22L)).thenReturn(row);
        doAnswer(invocation -> {
            LakeOdsTableMapping entity = invocation.getArgument(0);
            Integer expectedVersion = invocation.getArgument(2);
            entity.setLockVersion(expectedVersion + 1);
            return true;
        }).when(tableDao).updateIfTokenAndVersion(any(), any(), anyInt());

        DaoLakeResourceGateway gateway = new DaoLakeResourceGateway(
                sourceDao, databaseDao, tableDao, catalogDao);

        LakeOperationHandle missingHandle = new LakeOperationHandle(
                1L, LakeResourceTypes.ODS_TABLE_MAPPING, 22L, 1, "missing-token", 1);
        assertTrue(gateway.finalizeFailure(
                missingHandle, LakeErrorCode.LAKE_DATABASE_MISSING, "missing"));
        assertEquals(LakeResourceStatus.MISSING, row.getResourceStatus());

        row.setOperationToken("unknown-token");
        row.setResourceStatus(LakeResourceStatus.CREATING);
        row.setLockVersion(2);
        LakeOperationHandle unknownHandle = new LakeOperationHandle(
                2L, LakeResourceTypes.ODS_TABLE_MAPPING, 22L, 1, "unknown-token", 2);
        assertTrue(gateway.finalizeFailure(
                unknownHandle, LakeErrorCode.LAKE_DORIS_UNAVAILABLE, "unavailable"));
        assertEquals(LakeResourceStatus.UNKNOWN, row.getResourceStatus());
    }
}
