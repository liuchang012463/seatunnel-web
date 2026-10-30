package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.common.enums.RunMode;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.spi.bean.dto.SeaTunnelJobInstanceDTO;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.apache.seatunnel.web.spi.bean.vo.JobTableMetricsVO;
import org.apache.seatunnel.web.spi.bean.vo.EngineJobInfoVO;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface BatchJobInstanceService {

    JobInstanceVO create(Long jobDefineId, RunMode runMode);

    JobInstanceVO create(Long jobDefineId, RunMode runMode, Map<String, String> runtimeParams);

    PaginationResult<JobInstanceVO> paging(SeaTunnelJobInstanceDTO dto);

    String buildJobConfig(JobDefinitionSaveCommand command);

    JobInstanceVO selectById(Long id);

    /** Fetches the whitelisted job-info fields directly from the instance's Engine. */
    EngineJobInfoVO getEngineJobInfo(Long instanceId);

    String getLogContent(Long instanceId);

    boolean existsRunningInstance(Long definitionId);

    /**
     * Creates an instance only when no submission-blocking instance exists, serializing the
     * check-and-insert across Web instances on the job definition row.
     */
    JobInstanceVO createIfNoRunningInstance(Long jobDefineId,
                                            RunMode runMode,
                                            Map<String, String> runtimeParams);

    void removeAllByDefinitionId(Long definitionId);

    void updateById(JobInstance po);

    /**
     * Records a successful submission only while the instance is still STARTING.
     *
     * @return true when the instance was still awaiting an Engine id
     */
    boolean updateEngineIdIfStarting(Long instanceId, String engineJobId, Date startTime);

    boolean markCancelingIfStarting(Long instanceId, String engineJobId, String errorMessage);

    /**
     * Applies an Engine-reported final status only while the instance is still running-like.
     *
     * @return true when the final status was applied
     */
    boolean updateFinalStatusIfRunningLike(Long instanceId,
                                           JobStatus targetStatus,
                                           String errorMessage);

    boolean updateFinalStatusIfStarting(Long instanceId,
                                        String expectedEngineJobId,
                                        Date expectedLastActivity,
                                        JobStatus targetStatus,
                                        String errorMessage);

    /** Refreshes the heartbeat of an in-flight submission or its cancellation. */
    boolean touchStartingInstance(Long instanceId);

    /**
     * Query table level metrics for one job instance.
     */
    List<JobTableMetricsVO> listTableMetrics(Long instanceId);

    List<JobInstance> listRunningInstanceByDefinitionIds(List<Long> definitionIds);
}
