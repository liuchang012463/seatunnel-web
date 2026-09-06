package org.apache.seatunnel.web.api.lake.job;

import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeJobRelationBridgeServiceTest {

    private LakeJobDetector detector;
    private LakeJobRelationDao relationDao;
    private LakeJobRelationBridgeService bridge;

    @BeforeEach
    void setUp() {
        detector = Mockito.mock(LakeJobDetector.class);
        relationDao = Mockito.mock(LakeJobRelationDao.class);
        bridge = new LakeJobRelationBridgeService(detector, relationDao);
    }

    @Test
    void saveCreatesActiveTableRelationWithAllSnapshots() {
        LakeJobDescriptor descriptor = descriptor(LakeRelationScope.TABLE, 70L,
                LakeJobRuntimeType.BATCH);
        when(detector.detect(any(JobDefinitionSaveCommand.class), eq(LakeJobRuntimeType.BATCH)))
                .thenReturn(descriptor);
        when(relationDao.queryActiveByJobId(101L)).thenReturn(List.of());
        when(relationDao.queryByBindingJobAndScope(7L, 101L, LakeRelationScope.TABLE))
                .thenReturn(null);

        bridge.syncRelationAfterJobSave(command(), 101L, 3, LakeJobRuntimeType.BATCH);

        ArgumentCaptor<LakeJobRelation> captor = ArgumentCaptor.forClass(LakeJobRelation.class);
        verify(relationDao).insert(captor.capture());
        LakeJobRelation relation = captor.getValue();
        assertEquals(7L, relation.getOdsDatabaseBindingId());
        assertEquals(70L, relation.getTableMappingId());
        assertEquals(LakeRelationScope.TABLE, relation.getRelationScope());
        assertEquals(LakeJobRuntimeType.BATCH, relation.getJobRuntimeType());
        assertEquals(101L, relation.getJobId());
        assertEquals(3, relation.getJobVersion());
        assertEquals(LakeRelationStatus.ACTIVE, relation.getRelationStatus());
        assertEquals("source", relation.getSourceEndpointSnapshot());
        assertEquals("sink", relation.getSinkEndpointSnapshot());
        assertEquals("ERROR_WHEN_SCHEMA_NOT_EXIST", relation.getSchemaSaveModeSnapshot());
    }

    @Test
    void changingScopeStalesOldRelationAndReusesExistingNamespaceRow() {
        LakeJobRelation old = relation(11L, 7L, 70L, LakeRelationScope.TABLE,
                LakeRelationStatus.ACTIVE);
        LakeJobRelation namespace = relation(12L, 7L, null, LakeRelationScope.NAMESPACE,
                LakeRelationStatus.STALE);
        LakeJobDescriptor descriptor = descriptor(LakeRelationScope.NAMESPACE, null,
                LakeJobRuntimeType.BATCH);
        when(detector.detect(any(JobDefinitionSaveCommand.class), eq(LakeJobRuntimeType.BATCH)))
                .thenReturn(descriptor);
        when(relationDao.queryActiveByJobId(101L)).thenReturn(List.of(old));
        when(relationDao.queryByBindingJobAndScope(7L, 101L, LakeRelationScope.NAMESPACE))
                .thenReturn(namespace);

        bridge.syncRelationAfterJobSave(command(), 101L, 4, LakeJobRuntimeType.BATCH);

        assertEquals(LakeRelationStatus.STALE, old.getRelationStatus());
        verify(relationDao).updateById(old);
        assertEquals(LakeRelationStatus.ACTIVE, namespace.getRelationStatus());
        assertEquals(4, namespace.getJobVersion());
        verify(relationDao).updateById(namespace);
    }

    @Test
    void ordinarySaveStalesActiveRelationAndDeleteUsesSoftState() {
        LakeJobRelation active = relation(11L, 7L, 70L, LakeRelationScope.TABLE,
                LakeRelationStatus.ACTIVE);
        when(detector.detect(any(JobDefinitionSaveCommand.class), eq(LakeJobRuntimeType.BATCH)))
                .thenReturn(null);
        when(relationDao.queryActiveByJobId(101L)).thenReturn(List.of(active));

        bridge.syncRelationAfterJobSave(command(), 101L, 5, LakeJobRuntimeType.BATCH);
        assertEquals(LakeRelationStatus.STALE, active.getRelationStatus());
        verify(relationDao).updateById(active);

        bridge.markRelationsAfterJobDelete(101L);
        verify(relationDao).markStaleByJobId(101L);
    }

    @Test
    void editRestoresTopLevelBindingForCopyWithoutChangingNestedAuthority() {
        LakeJobRelation active = relation(11L, 7L, 70L, LakeRelationScope.TABLE,
                LakeRelationStatus.ACTIVE);
        when(relationDao.queryActiveByJobId(101L)).thenReturn(List.of(active));
        BatchGuideSingleJobSaveCommand command = command();

        bridge.restoreBinding(command, 101L);

        assertEquals(7L, command.getOdsDatabaseBindingId());
    }

    private BatchGuideSingleJobSaveCommand command() {
        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setWorkflow(Map.of());
        return command;
    }

    private LakeJobDescriptor descriptor(
            LakeRelationScope scope, Long mappingId, LakeJobRuntimeType runtimeType) {
        return new LakeJobDescriptor(7L, 99L, 10L, 99L, scope, mappingId,
                runtimeType, "source", "sink", "ERROR_WHEN_SCHEMA_NOT_EXIST");
    }

    private LakeJobRelation relation(
            Long id, Long bindingId, Long mappingId, LakeRelationScope scope,
            LakeRelationStatus status) {
        LakeJobRelation relation = new LakeJobRelation();
        relation.setId(id);
        relation.setOdsDatabaseBindingId(bindingId);
        relation.setTableMappingId(mappingId);
        relation.setRelationScope(scope);
        relation.setRelationStatus(status);
        return relation;
    }
}
