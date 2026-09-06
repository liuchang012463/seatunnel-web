package org.apache.seatunnel.web.api.lake.job;

import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.core.job.bridge.LakeJobBindingResolver;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;
import org.apache.seatunnel.web.dao.repository.LakeJobRelationDao;
import org.apache.seatunnel.web.spi.bean.dto.command.BatchJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.StreamingJobSaveCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Keeps the durable lake-job relation in step with an existing job
 * definition.  It only writes local relation state; Doris is never called
 * from this bridge.
 */
@Component
public class LakeJobRelationBridgeService {

    private final LakeJobDetector detector;
    private final LakeJobRelationDao relationDao;

    @Autowired
    public LakeJobRelationBridgeService(
            LakeJobDetector detector, LakeJobRelationDao relationDao) {
        this.detector = detector;
        this.relationDao = relationDao;
    }

    /**
     * Synchronize the relation after the job definition and its content have
     * been saved.  The caller invokes this from its existing transaction.
     */
    public void syncRelationAfterJobSave(
            JobDefinitionSaveCommand command, Long jobId, int jobVersion) {
        syncRelationAfterJobSave(command, jobId, jobVersion, runtimeType(command));
    }

    /**
     * Runtime-explicit overload used by the batch and streaming save paths.
     * Keeping the three-argument boundary above also lets callers that only
     * have the common command interface use the design-level API.
     */
    public void syncRelationAfterJobSave(
            JobDefinitionSaveCommand command,
            Long jobId,
            int jobVersion,
            LakeJobRuntimeType runtimeType) {
        if (jobId == null || relationDao == null || detector == null) {
            return;
        }

        LakeJobDescriptor descriptor = detector.detect(command, runtimeType);
        List<LakeJobRelation> activeRelations = safeActiveRelations(jobId);
        if (descriptor == null) {
            markStale(activeRelations);
            return;
        }

        LakeJobRelation relation = relationDao.queryByBindingJobAndScope(
                descriptor.odsDatabaseBindingId(), jobId, descriptor.relationScope());
        boolean create = relation == null;
        for (LakeJobRelation active : activeRelations) {
            if (!sameRelation(active, descriptor, jobId)) {
                markStale(active);
            }
        }

        if (create) {
            relation = new LakeJobRelation();
        }

        relation.setOdsDatabaseBindingId(descriptor.odsDatabaseBindingId());
        relation.setTableMappingId(descriptor.tableMappingId());
        relation.setRelationScope(descriptor.relationScope());
        relation.setJobRuntimeType(descriptor.jobRuntimeType());
        relation.setJobId(jobId);
        relation.setJobVersion(jobVersion);
        relation.setRelationStatus(LakeRelationStatus.ACTIVE);
        relation.setSourceEndpointSnapshot(descriptor.sourceEndpointSnapshot());
        relation.setSinkEndpointSnapshot(descriptor.sinkEndpointSnapshot());
        relation.setSchemaSaveModeSnapshot(descriptor.schemaSaveModeSnapshot());
        if (create) {
            relation.initInsert();
            relationDao.insert(relation);
        } else {
            relation.initUpdate();
            relationDao.updateById(relation);
        }
    }

    /** Mark relation history stale when a job is deleted. */
    public void markRelationsAfterJobDelete(Long jobId) {
        if (jobId != null && relationDao != null) {
            relationDao.markStaleByJobId(jobId);
        }
    }

    /**
     * Restore a top-level binding omitted by old serialized single-table
     * content.  Multi/whole content carries the binding in target and is left
     * untouched, so command-vs-target mismatch validation remains authoritative.
     */
    public void restoreBinding(JobDefinitionSaveCommand command, Long jobId) {
        if (command == null || jobId == null || relationDao == null
                || !structuredMode(command.getMode())
                || command.getOdsDatabaseBindingId() != null
                || LakeJobBindingResolver.resolveTargetBindingId(command) != null) {
            return;
        }

        List<LakeJobRelation> activeRelations = safeActiveRelations(jobId);
        Long bindingId = null;
        for (LakeJobRelation relation : activeRelations) {
            if (relation == null || relation.getOdsDatabaseBindingId() == null) {
                continue;
            }
            if (bindingId != null && !bindingId.equals(relation.getOdsDatabaseBindingId())) {
                throw new IllegalStateException(
                        "job has active relations for different ODS database bindings");
            }
            bindingId = relation.getOdsDatabaseBindingId();
        }
        if (bindingId != null) {
            command.setOdsDatabaseBindingId(bindingId);
        }
    }

    private List<LakeJobRelation> safeActiveRelations(Long jobId) {
        List<LakeJobRelation> relations = relationDao.queryActiveByJobId(jobId);
        return relations == null ? Collections.emptyList() : relations;
    }

    private void markStale(List<LakeJobRelation> relations) {
        for (LakeJobRelation relation : relations) {
            markStale(relation);
        }
    }

    private void markStale(LakeJobRelation relation) {
        if (relation == null || relation.getRelationStatus() != LakeRelationStatus.ACTIVE) {
            return;
        }
        relation.setRelationStatus(LakeRelationStatus.STALE);
        relation.initUpdate();
        relationDao.updateById(relation);
    }

    private boolean sameRelation(
            LakeJobRelation relation, LakeJobDescriptor descriptor, Long jobId) {
        return relation != null
                && relation.getRelationStatus() == LakeRelationStatus.ACTIVE
                && Objects.equals(relation.getOdsDatabaseBindingId(), descriptor.odsDatabaseBindingId())
                && Objects.equals(relation.getTableMappingId(), descriptor.tableMappingId())
                && relation.getRelationScope() == descriptor.relationScope()
                && Objects.equals(relation.getJobId(), jobId);
    }

    private boolean structuredMode(JobDefinitionMode mode) {
        return mode == JobDefinitionMode.GUIDE_SINGLE
                || mode == JobDefinitionMode.GUIDE_SINGLE_INCREMENTAL
                || mode == JobDefinitionMode.GUIDE_MULTI;
    }

    private LakeJobRuntimeType runtimeType(JobDefinitionSaveCommand command) {
        if (command instanceof StreamingJobSaveCommand) {
            return LakeJobRuntimeType.STREAMING;
        }
        if (command instanceof BatchJobSaveCommand) {
            return LakeJobRuntimeType.BATCH;
        }
        return null;
    }
}
