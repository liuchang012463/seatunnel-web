package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.LakeOdsTableMapping;

import java.util.List;

public interface LakeOdsTableMappingDao extends IDao<LakeOdsTableMapping> {

    List<LakeOdsTableMapping> queryByOdsDatabaseBindingId(Long odsDatabaseBindingId);

    LakeOdsTableMapping queryByBindingIdAndTargetTable(Long odsDatabaseBindingId, String targetTableName);

    LakeOdsTableMapping queryByBindingIdAndSourceObject(Long odsDatabaseBindingId, Long sourceObjectRefId);

    boolean updateIfTokenAndVersion(LakeOdsTableMapping entity, String operationToken, Integer lockVersion);
}
