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

    void removeAllByDefinitionId(Long definitionId);

    void updateById(JobInstance po);

    /**
     * Records a successful submission only while the instance is still STARTING.
     *
     * @return true when the instance was still awaiting an Engine id
     */
    boolean updateEngineIdIfStarting(Long instanceId, String engineJobId, Date startTime);

    /**
     * Applies a status transition only when the instance is still in the expected status.
     *
     * @return true when the transition was applied
     */
    boolean updateStatusIfCurrent(Long instanceId,
                                  JobStatus expectedStatus,
                                  JobStatus targetStatus,
                                  String errorMessage);

    /** Refreshes the heartbeat of an in-flight submission. */
    boolean touchStartingInstance(Long instanceId);

    /**
     * Query table level metrics for one job instance.
     */
    List<JobTableMetricsVO> listTableMetrics(Long instanceId);

    List<JobInstance> listRunningInstanceByDefinitionIds(List<Long> definitionIds);
}
