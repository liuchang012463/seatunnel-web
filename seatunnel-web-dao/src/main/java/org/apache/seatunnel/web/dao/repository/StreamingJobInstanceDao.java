package org.apache.seatunnel.web.dao.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.spi.bean.dto.SeaTunnelJobInstanceDTO;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;

import java.util.Date;
import java.util.List;

public interface StreamingJobInstanceDao extends IDao<StreamingJobInstance> {

    IPage<JobInstanceVO> pageWithDefinition(SeaTunnelJobInstanceDTO dto);

    JobInstanceVO selectDetailById(Long id);

    boolean existsRunningInstance(Long definitionId);

    void deleteByDefinitionId(Long definitionId);

    int failRunningInstancesByClientId(Long clientId, String errorMessage);

    List<StreamingJobInstance> listRunningLikeInstances();

    void updateStatus(Long instanceId, JobStatus status, String errorMessage);

    /**
     * Writes a terminal status only while the instance is still running. A job the operator
     * stopped in the meantime keeps its status instead of being overwritten by a recovery.
     *
     * @return true when the status was written
     */
    boolean updateFinalStatusIfRunningLike(Long instanceId, JobStatus status, String errorMessage);

    /** Applies a recovered final status only if the stale STARTING snapshot is unchanged. */
    boolean updateFinalStatusIfStarting(Long instanceId,
                                        String expectedEngineJobId,
                                        Date expectedLastActivity,
                                        JobStatus status,
                                        String errorMessage);

    boolean replaceEngineIdIfStarting(Long instanceId,
                                      String expectedEngineJobId,
                                      String replacementEngineJobId,
                                      String errorMessage);

    boolean updateStatusIfSnapshot(Long instanceId,
                                   JobStatus expectedStatus,
                                   String expectedEngineJobId,
                                   Date expectedLastActivity,
                                   JobStatus status,
                                   String errorMessage);

    boolean updateEngineIdIfStarting(Long instanceId, String engineJobId, Date submitTime);

    /** Records cancellation intent without ending the instance while submission may still be in flight. */
    boolean markCancelingIfStarting(Long instanceId, String engineJobId, String errorMessage);

    boolean touchStartingInstance(Long instanceId);

    void updateStatusAndEngineId(Long instanceId, JobStatus status, String engineJobId);

    void updateSubmitResult(Long instanceId, String engineJobId, JobStatus submitStatus, Date submitTime);

    List<JobInstanceVO> listRunning();

    StreamingJobInstance lastInstance(Long definitionId);
}
