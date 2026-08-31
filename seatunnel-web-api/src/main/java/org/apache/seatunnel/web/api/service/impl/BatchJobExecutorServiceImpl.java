package org.apache.seatunnel.web.api.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.seatunnel.web.api.lake.job.LakeJobGuard;
import org.apache.seatunnel.web.api.metrics.BatchJobSubmitter;
import org.apache.seatunnel.web.api.service.BatchJobDefinitionService;
import org.apache.seatunnel.web.api.service.BatchJobExecutorService;
import org.apache.seatunnel.web.api.service.BatchJobInstanceService;
import org.apache.seatunnel.web.api.service.JobScheduleService;
import org.apache.seatunnel.web.api.service.IncrementalBatchExecution;
import org.apache.seatunnel.web.api.service.IncrementalBatchService;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.ReleaseState;
import org.apache.seatunnel.web.common.enums.RunMode;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.entity.JobSchedule;
import org.apache.seatunnel.web.spi.bean.vo.BatchJobDefinitionVO;
import org.apache.seatunnel.web.spi.bean.vo.BatchJobOperateResultVO;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BatchJobExecutorServiceImpl implements BatchJobExecutorService {
    private final BatchJobInstanceService instanceService;
    private final BatchJobDefinitionService definitionService;
    private final BatchJobSubmitter jobSubmitter;
    private final IncrementalBatchService incrementalBatchService;
    private final JobScheduleService jobScheduleService;
    private final LakeJobGuard lakeJobGuard;

    @Autowired
    public BatchJobExecutorServiceImpl(BatchJobInstanceService instanceService,
                                       BatchJobDefinitionService definitionService,
                                       BatchJobSubmitter jobSubmitter,
                                       IncrementalBatchService incrementalBatchService,
                                       JobScheduleService jobScheduleService,
                                       LakeJobGuard lakeJobGuard) {
        this.instanceService = instanceService;
        this.definitionService = definitionService;
        this.jobSubmitter = jobSubmitter;
        this.incrementalBatchService = incrementalBatchService;
        this.jobScheduleService = jobScheduleService;
        this.lakeJobGuard = lakeJobGuard;
    }

    public BatchJobExecutorServiceImpl(BatchJobInstanceService instanceService,
                                       BatchJobDefinitionService definitionService,
                                       BatchJobSubmitter jobSubmitter,
                                       IncrementalBatchService incrementalBatchService,
                                       JobScheduleService jobScheduleService) {
        this(instanceService, definitionService, jobSubmitter, incrementalBatchService,
                jobScheduleService, null);
    }

    @Override
    public Long jobExecute(Long jobDefineId, RunMode runMode) {
        validateJobDefinitionId(jobDefineId);
        validateRunMode(runMode);
        validateJobDefinitionOnline(jobDefineId);
        if (lakeJobGuard != null) {
            lakeJobGuard.validateBeforeExecute(jobDefineId, LakeJobRuntimeType.BATCH);
        }

        IncrementalBatchExecution incrementalExecution = incrementalBatchService.prepare(jobDefineId);
        if (incrementalExecution != null && incrementalExecution.isSkipped()) {
            log.info("Incremental job execution skipped: jobDefineId={}", jobDefineId);
            return null;
        }

        JobInstanceVO instance;
        try {
            instance = instanceService.create(jobDefineId, runMode,
                    incrementalExecution == null ? null : incrementalExecution.getRuntimeParams());
            if (incrementalExecution != null) {
                incrementalBatchService.bindInstance(incrementalExecution.getBatchId(), instance.getId());
            }
        } catch (Exception e) {
            if (incrementalExecution != null) {
                incrementalBatchService.markBatchFailure(incrementalExecution.getBatchId(), e);
            }
            throw e;
        }

        log.info("Job execute requested: jobDefineId={}, runMode={}, instanceId={}",
                jobDefineId, runMode, instance.getId());

        try {
            jobSubmitter.submit(instance);
        } catch (Exception e) {
            if (incrementalExecution != null) {
                incrementalBatchService.markSubmitFailure(instance.getId(), e);
            }
            throw e;
        }

        if (runMode == RunMode.MANUAL) {
            updateManualLastScheduleTime(jobDefineId);
        }

        return instance.getId();
    }

    /**
     * Manual execution does not pass through QuartzJob, so record its trigger
     * time explicitly for the task list and detail views.
     */
    private void updateManualLastScheduleTime(Long jobDefinitionId) {
        try {
            JobSchedule schedule = jobScheduleService.getByTaskDefinitionId(jobDefinitionId);
            if (schedule == null || schedule.getId() == null) {
                log.debug("No schedule row found while recording manual run, jobDefinitionId={}", jobDefinitionId);
                return;
            }

            if (!jobScheduleService.updateLastScheduleTime(schedule.getId())) {
                log.warn("Record manual last run time failed, jobDefinitionId={}, scheduleId={}",
                        jobDefinitionId, schedule.getId());
            }
        } catch (Exception e) {
            log.warn("Record manual last run time failed, jobDefinitionId={}", jobDefinitionId, e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long jobPause(Long jobInstanceId) {
        validateInstanceId(jobInstanceId);

        JobInstanceVO instance = instanceService.selectById(jobInstanceId);
        if (instance == null || instance.getId() == null) {
            throw new ServiceException(Status.BATCH_JOB_INSTANCE_NOT_EXIST);
        }

        if (isFinishedStatus(instance.getJobStatus())) {
            log.info("Job instance already finished, skip pause: instanceId={}, status={}",
                    jobInstanceId, instance.getJobStatus());
            return jobInstanceId;
        }

        log.info("Job pause requested: instanceId={}, status={}",
                jobInstanceId, instance.getJobStatus());

        try {
            jobSubmitter.pause(instance);

            JobInstance update = new JobInstance();
            update.setId(jobInstanceId);
            update.setJobStatus(JobStatus.CANCELED);
            update.setEndTime(new Date());
            update.setErrorMessage("Job was manually paused by user.");

            instanceService.updateById(update);

            log.info("Job pause success: instanceId={}", jobInstanceId);
            return jobInstanceId;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Job pause failed: instanceId={}", jobInstanceId, e);

            JobInstance update = new JobInstance();
            update.setId(jobInstanceId);
            update.setErrorMessage("Job pause failed: " + e.getMessage());
            instanceService.updateById(update);

            throw new ServiceException(Status.JOB_DEFINITION_EXECUTE_ERROR);
        }
    }

    /**
     * Batch execute jobs by job definition ids.
     *
     * Rules:
     * 1. All selected jobs must exist.
     * 2. All selected jobs must be ONLINE.
     * 3. All selected jobs must not have running instances.
     * 4. If any job does not satisfy the rules, reject the whole batch operation.
     */
    @Override
    public BatchJobOperateResultVO batchExecute(List<Long> jobDefinitionIds, RunMode runMode) {
        validateJobDefinitionIds(jobDefinitionIds);
        validateRunMode(runMode);

        List<Long> distinctIds = normalizeJobDefinitionIds(jobDefinitionIds);

        BatchJobOperateResultVO result = new BatchJobOperateResultVO();
        result.setTotalCount(distinctIds.size());

        List<BatchJobDefinitionVO> definitions = loadAndValidateDefinitions(distinctIds);

        validateAllJobsOnline(definitions);
        validateNoRunningJobs(distinctIds);

        for (Long jobDefinitionId : distinctIds) {
            try {
                Long jobInstanceId = jobExecute(jobDefinitionId, runMode);
                result.addSuccess(jobDefinitionId, jobInstanceId, "Job started successfully.");

                log.info("Batch execute success: jobDefinitionId={}, jobInstanceId={}",
                        jobDefinitionId, jobInstanceId);
            } catch (Exception e) {
                log.error("Batch execute failed: jobDefinitionId={}", jobDefinitionId, e);
                result.addFailed(jobDefinitionId, null, getErrorMessage(e));
            }
        }

        return result;
    }

    /**
     * Batch pause jobs by job definition ids.
     *
     * Rules:
     * 1. All selected jobs must exist.
     * 2. All selected jobs must have running instances.
     * 3. If any job does not satisfy the rules, reject the whole batch operation.
     */
    @Override
    public BatchJobOperateResultVO batchPause(List<Long> jobDefinitionIds) {
        validateJobDefinitionIds(jobDefinitionIds);

        List<Long> distinctIds = normalizeJobDefinitionIds(jobDefinitionIds);

        BatchJobOperateResultVO result = new BatchJobOperateResultVO();
        result.setTotalCount(distinctIds.size());

        loadAndValidateDefinitions(distinctIds);

        List<JobInstance> runningInstances =
                instanceService.listRunningInstanceByDefinitionIds(distinctIds);

        Map<Long, List<JobInstance>> instanceMap = Optional.ofNullable(runningInstances)
                .orElse(Collections.emptyList())
                .stream()
                .collect(Collectors.groupingBy(JobInstance::getJobDefinitionId));

        validateAllJobsRunning(distinctIds, instanceMap);

        for (Long jobDefinitionId : distinctIds) {
            List<JobInstance> instances = instanceMap.get(jobDefinitionId);

            for (JobInstance instance : instances) {
                try {
                    Long jobInstanceId = instance.getId();
                    jobPause(jobInstanceId);

                    result.addSuccess(jobDefinitionId, jobInstanceId, "Job paused successfully.");

                    log.info("Batch pause success: jobDefinitionId={}, jobInstanceId={}",
                            jobDefinitionId, jobInstanceId);
                } catch (Exception e) {
                    log.error("Batch pause failed: jobDefinitionId={}, instanceId={}",
                            jobDefinitionId, instance.getId(), e);
                    result.addFailed(jobDefinitionId, instance.getId(), getErrorMessage(e));
                }
            }
        }

        return result;
    }

    private List<Long> normalizeJobDefinitionIds(List<Long> jobDefinitionIds) {
        List<Long> ids = jobDefinitionIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .collect(Collectors.toList());

        if (CollectionUtils.isEmpty(ids)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobDefinitionIds");
        }

        return ids;
    }

    /**
     * Load definitions and validate all selected ids exist.
     */
    private List<BatchJobDefinitionVO> loadAndValidateDefinitions(List<Long> jobDefinitionIds) {
        List<BatchJobDefinitionVO> definitions = definitionService.listByIds(jobDefinitionIds);

        if (CollectionUtils.isEmpty(definitions)) {
            throw new ServiceException(
                    Status.JOB_DEFINITION_EXECUTE_ERROR,
                    "Job definitions not found: " + jobDefinitionIds
            );
        }

        Set<Long> existsIds = definitions.stream()
                .map(BatchJobDefinitionVO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<Long> notExistsIds = jobDefinitionIds.stream()
                .filter(id -> !existsIds.contains(id))
                .collect(Collectors.toList());

        if (CollectionUtils.isNotEmpty(notExistsIds)) {
            throw new ServiceException(
                    Status.JOB_DEFINITION_EXECUTE_ERROR,
                    "Job definitions not found: " + notExistsIds
            );
        }

        return definitions;
    }

    /**
     * Validate all selected jobs are ONLINE.
     */
    private void validateAllJobsOnline(List<BatchJobDefinitionVO> definitions) {
        List<Long> offlineIds = definitions.stream()
                .filter(item -> !isOnline(item.getReleaseState()))
                .map(BatchJobDefinitionVO::getId)
                .collect(Collectors.toList());

        if (CollectionUtils.isNotEmpty(offlineIds)) {
            throw new ServiceException(
                    Status.JOB_DEFINITION_EXECUTE_ERROR,
                    "存在未上线任务，请先上线后再启动。任务ID：" + offlineIds
            );
        }
    }

    /**
     * Manual execution and Quartz execution both require the definition to be online.
     * The batch endpoint validates this for the whole request; this guard also protects
     * the single-definition execute endpoint.
     */
    private void validateJobDefinitionOnline(Long jobDefinitionId) {
        BatchJobDefinitionVO definition = definitionService.selectById(jobDefinitionId);
        if (definition == null) {
            throw new ServiceException(
                    Status.JOB_DEFINITION_EXECUTE_ERROR,
                    "Job definition not found: " + jobDefinitionId
            );
        }
        if (!isOnline(definition.getReleaseState())) {
            throw new ServiceException(
                    Status.JOB_DEFINITION_EXECUTE_ERROR,
                    "任务尚未上线，请先上线后再启动。任务ID：" + jobDefinitionId
            );
        }
    }

    /**
     * Validate selected jobs do not contain running jobs.
     */
    private void validateNoRunningJobs(List<Long> jobDefinitionIds) {
        List<Long> runningIds = jobDefinitionIds.stream()
                .filter(instanceService::existsRunningInstance)
                .collect(Collectors.toList());

        if (CollectionUtils.isNotEmpty(runningIds)) {
            throw new ServiceException(
                    Status.JOB_DEFINITION_EXECUTE_ERROR,
                    "存在运行中的任务，请只选择未运行任务进行批量启动。任务ID：" + runningIds
            );
        }
    }

    /**
     * Validate all selected jobs have running instances.
     */
    private void validateAllJobsRunning(List<Long> jobDefinitionIds,
                                        Map<Long, List<JobInstance>> instanceMap) {
        List<Long> notRunningIds = jobDefinitionIds.stream()
                .filter(id -> !instanceMap.containsKey(id)
                        || CollectionUtils.isEmpty(instanceMap.get(id)))
                .collect(Collectors.toList());

        if (CollectionUtils.isNotEmpty(notRunningIds)) {
            throw new ServiceException(
                    Status.JOB_DEFINITION_EXECUTE_ERROR,
                    "存在未运行的任务，请只选择运行中的任务进行批量停止。任务ID：" + notRunningIds
            );
        }
    }

    /**
     * Compatible with ReleaseState enum or string-like value.
     */
    private boolean isOnline(Object releaseState) {
        if (releaseState == null) {
            return false;
        }

        if (releaseState instanceof ReleaseState) {
            return ReleaseState.ONLINE.equals(releaseState);
        }

        return ReleaseState.ONLINE.name().equalsIgnoreCase(String.valueOf(releaseState));
    }

    private void validateJobDefinitionId(Long jobDefinitionId) {
        if (jobDefinitionId == null || jobDefinitionId <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobDefinitionId");
        }
    }

    private void validateJobDefinitionIds(List<Long> jobDefinitionIds) {
        if (CollectionUtils.isEmpty(jobDefinitionIds)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobDefinitionIds");
        }
    }

    private void validateRunMode(RunMode runMode) {
        if (runMode == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "runMode");
        }
    }

    private void validateInstanceId(Long jobInstanceId) {
        if (jobInstanceId == null || jobInstanceId <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobInstanceId");
        }
    }

    private boolean isFinishedStatus(String status) {
        if (status == null) {
            return false;
        }

        return "FINISHED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "CANCELED".equalsIgnoreCase(status)
                || "CANCELLED".equalsIgnoreCase(status)
                || "STOPPED".equalsIgnoreCase(status);
    }

    private String getErrorMessage(Exception e) {
        if (e == null) {
            return "Unknown error";
        }

        if (e.getMessage() == null) {
            return e.getClass().getSimpleName();
        }

        return e.getMessage();
    }
}
