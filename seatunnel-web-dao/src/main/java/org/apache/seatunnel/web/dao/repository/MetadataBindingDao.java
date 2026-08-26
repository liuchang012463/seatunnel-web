package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;

import java.util.Collection;
import java.util.List;
import java.util.Date;

public interface MetadataBindingDao extends IDao<MetadataSourceBinding> {

    MetadataSourceBinding queryByDataSourceId(Long dataSourceId);

    List<MetadataSourceBinding> queryByDataSourceIds(Collection<Long> dataSourceIds);

    int deleteByDataSourceId(Long dataSourceId);

    List<MetadataSourceBinding> queryReconcileCandidates(Date now, Date staleClaimBefore, int limit);

    /** Atomically claims a candidate. A false result means another node owns it. */
    boolean tryClaim(Long id, Long expectedVersion, Date now, Date staleClaimBefore);

    /** Applies a completed reconciliation only while this node still owns the same lease/version. */
    boolean updateClaimed(MetadataSourceBinding binding, Long expectedVersion);

    boolean deleteClaimed(Long id, Long expectedVersion);

    /** Candidate selection is local-only; each candidate still uses a version-conditional write. */
    List<MetadataSourceBinding> queryStatusRefreshCandidates(Date olderThan, int limit);

    /** Reserves one scan/profile trigger without a DB transaction spanning the OM request. */
    boolean reserveRun(
            Long id, Long expectedVersion, boolean metadataScan, Long metadataTriggeredVersion, Date now);

    /** Persists a run state only if no competing request changed the binding in the meantime. */
    boolean updateIfVersion(MetadataSourceBinding binding, Long expectedVersion);
}
