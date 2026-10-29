package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.common.enums.RunMode;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.spi.bean.dto.SeaTunnelJobInstanceDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.apache.seatunnel.web.spi.bean.vo.JobTableMetricsVO;
import org.apache.seatunnel.web.spi.bean.vo.StreamingInstanceMetricsDashboardVO;

import java.util.Date;
import java.util.List;

public interface StreamingJobInstanceService {

    JobInstanceVO create(Long jobDefineId, RunMode runMode, JobMode jobMode);

    /** Creates an instance only when no active instance exists, across all Web nodes. */
    JobInstanceVO createIfNoRunningInstance(Long jobDefineId, RunMode runMode, JobMode jobMode);

    PaginationResult<JobInstanceVO> paging(SeaTunnelJobInstanceDTO dto);

    JobInstanceVO selectById(Long id);

    String getLogContent(Long instanceId);

    boolean existsRunningInstance(Long definitionId);

    /** Refreshes an in-flight streaming submission or its cancellation heartbeat. */
    boolean touchStartingInstance(Long instanceId);

    /** Attaches the reserved Engine id only while the instance is still being submitted. */
    boolean updateEngineIdIfStarting(Long instanceId, String engineJobId, Date submitTime);

    boolean markCancelingIfStarting(Long instanceId, String engineJobId, String errorMessage);

    boolean updateFinalStatusIfStarting(Long instanceId,
                                        String expectedEngineJobId,
                                        Date expectedLastActivity,
                                        JobStatus status,
                                        String errorMessage);

    boolean updateFinalStatusIfRunningLike(Long instanceId, JobStatus status, String errorMessage);

    /** Replaces the reserved id while submission is active or cancellation is pending. */
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

    void removeAllByDefinitionId(Long definitionId);

    void updateById(StreamingJobInstance po);

    List<JobTableMetricsVO> listTableMetrics(Long instanceId);

    List<JobInstanceVO> listRunningStreamingInstances();

    StreamingInstanceMetricsDashboardVO getMetricsDashboard(Long instanceId, String range);

    StreamingJobInstance lastInstance(Long definitionId);

    void updateStatus(Long instanceId, JobStatus status, String errorMessage);
}
