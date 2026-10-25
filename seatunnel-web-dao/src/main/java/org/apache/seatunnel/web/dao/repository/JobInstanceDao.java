package org.apache.seatunnel.web.dao.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.spi.bean.dto.SeaTunnelJobInstanceDTO;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;

import java.util.Date;
import java.util.List;

public interface JobInstanceDao extends IDao<JobInstance> {

    IPage<JobInstanceVO> pageWithDefinition(SeaTunnelJobInstanceDTO dto);

    JobInstanceVO selectDetailById(Long id);

    boolean existsRunningInstance(Long definitionId);

    void deleteByDefinitionId(Long definitionId);

    List<JobInstance> listRunningLikeInstances();

    int failRunningInstancesByClientId(Long clientId, String errorMessage);

    void updateStatus(Long instanceId, JobStatus status, String errorMessage);

    void updateStatusAndEngineId(Long instanceId, JobStatus status, String engineJobId);

    /**
     * Records a successful submission only while the instance is still STARTING. A late response
     * must not resurrect an instance that the recovery scheduler already resolved.
     *
     * @return true when the row was still STARTING and has been updated
     */
    boolean updateEngineIdIfStarting(Long instanceId, String engineJobId, Date startTime);

    /**
     * Applies an Engine-reported final status only while the instance is still running-like.
     * A late success must not overwrite a terminal decision already made by the recovery
     * scheduler (for example, an ambiguous submission resolved as FAILED).
     *
     * @return true when the final status was applied
     */
    boolean updateFinalStatusIfRunningLike(Long instanceId,
                                           JobStatus targetStatus,
                                           String errorMessage);

    /**
     * Refreshes the heartbeat of an in-flight submission so recovery does not mistake a long
     * driver upload for a lost submission.
     *
     * @return true when the instance is still STARTING
     */
    boolean touchStartingInstance(Long instanceId);

    void updateSubmitResult(Long instanceId, String engineJobId, JobStatus submitStatus, Date submitTime);

    List<JobInstanceVO> listRunningByJobType(JobMode jobMode);

    List<JobInstance> selectRunningInstanceByDefinitionIds(List<Long> definitionIds);
}
