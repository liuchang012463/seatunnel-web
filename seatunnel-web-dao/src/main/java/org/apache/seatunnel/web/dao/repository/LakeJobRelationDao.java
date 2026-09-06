package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.dao.entity.LakeJobRelation;

import java.util.List;

public interface LakeJobRelationDao extends IDao<LakeJobRelation> {

    List<LakeJobRelation> queryByOdsDatabaseBindingId(Long odsDatabaseBindingId);

    List<LakeJobRelation> queryActiveByJobId(Long jobId);

    LakeJobRelation queryByBindingJobAndScope(
            Long odsDatabaseBindingId, Long jobId, LakeRelationScope relationScope);

    boolean markStaleByJobId(Long jobId);
}
