package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.LakeDataSourceAlias;

public interface LakeDataSourceAliasDao extends IDao<LakeDataSourceAlias> {

    LakeDataSourceAlias queryByLegacyId(Long legacyDataSourceId);
}
