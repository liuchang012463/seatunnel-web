package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;

import java.util.List;

public interface MetadataBindingDao extends IDao<MetadataSourceBinding> {

    MetadataSourceBinding queryByDataSourceId(Long dataSourceId);

    int deleteByDataSourceId(Long dataSourceId);

    List<MetadataSourceBinding> queryReconcileCandidates(int limit);
}
