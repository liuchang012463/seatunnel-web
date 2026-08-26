package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;

import java.util.List;
import java.util.Date;

public interface MetadataBindingDao extends IDao<MetadataSourceBinding> {

    MetadataSourceBinding queryByDataSourceId(Long dataSourceId);

    int deleteByDataSourceId(Long dataSourceId);

    List<MetadataSourceBinding> queryReconcileCandidates(Date now, Date staleClaimBefore, int limit);

    /** Atomically claims a candidate. A false result means another node owns it. */
    boolean tryClaim(Long id, Long expectedVersion, Date now, Date staleClaimBefore);

    /** Applies a completed reconciliation only while this node still owns the same lease/version. */
    boolean updateClaimed(MetadataSourceBinding binding, Long expectedVersion);

    boolean deleteClaimed(Long id, Long expectedVersion);
}
