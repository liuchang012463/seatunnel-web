package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.api.log.JobLogService;
import org.apache.seatunnel.web.api.metrics.JobMetricsRateCalculator;
import org.apache.seatunnel.web.api.service.BatchJobInstanceService;
import org.apache.seatunnel.web.api.service.support.JobInstanceFactory;
import org.apache.seatunnel.web.api.utils.HoconSensitiveMaskUtil;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.common.enums.RunMode;
import org.apache.seatunnel.web.common.utils.CodeGenerateUtils;
import org.apache.seatunnel.web.common.utils.ConvertUtil;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.core.hocon.JobDefinitionCommandResolver;
import org.apache.seatunnel.web.core.hocon.JobDefinitionHoconBuilder;
import org.apache.seatunnel.web.core.job.TaskTypeResolver;
import org.apache.seatunnel.web.dao.entity.JobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.JobMetricsDao;
import org.apache.seatunnel.web.dao.repository.JobTableMetricsDao;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelEngineRestClient;
import org.apache.seatunnel.web.spi.bean.dto.SeaTunnelJobInstanceDTO;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.apache.seatunnel.web.spi.bean.vo.JobTableMetricsVO;
import org.apache.seatunnel.web.spi.bean.vo.EngineJobInfoVO;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Slf4j
public class BatchJobInstanceServiceImpl implements BatchJobInstanceService {

    @Resource
    private JobInstanceDao jobInstanceDao;

    @Resource
    private JobDefinitionDao jobDefinitionDao;

    @Resource
    private JobMetricsDao jobMetricsDao;

    @Resource
    private JobInstanceFactory jobInstanceFactory;

    @Resource
    private JobDefinitionHoconBuilder jobDefinitionHoconBuilder;

    @Resource
    private JobDefinitionCommandResolver jobDefinitionCommandResolver;

    @Value("${seatunnel.job.log-dir:logs}")
    private String baseLogDir;

    @Resource
    private JobLogService jobLogService;

    @Resource
    private JobTableMetricsDao jobTableMetricsDao;

    @Resource
    private SeaTunnelEngineRestClient engineRestClient;

    @PostConstruct
    public void init() {
        log.info("BatchJobInstanceServiceImpl initialized: {}", System.identityHashCode(this));
    }

    @Override
    public JobInstanceVO create(Long jobDefineId, RunMode runMode) {
        return create(jobDefineId, runMode, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JobInstanceVO createIfNoRunningInstance(Long jobDefineId,
                                                   RunMode runMode,
                                                   Map<String, String> runtimeParams) {
        validateDefinitionId(jobDefineId);
        validateRunMode(runMode);

        // Lock the definition row so two Web instances cannot both pass the check and insert.
        jobDefinitionDao.lockById(jobDefineId);
        if (jobInstanceDao.existsRunningInstance(jobDefineId)) {
            throw new ServiceException(
                    Status.JOB_DEFINITION_EXECUTE_ERROR,
                    "任务已有运行中实例。任务ID：" + jobDefineId
            );
        }
        return create(jobDefineId, runMode, runtimeParams);
    }

    @Override
    public JobInstanceVO create(Long jobDefineId, RunMode runMode, Map<String, String> runtimeParams) {
        validateDefinitionId(jobDefineId);
        validateRunMode(runMode);

        try {
            log.info("Creating batch job instance, jobDefineId={}, runMode={}", jobDefineId, runMode);

            JobDefinitionSaveCommand command = loadDefinitionCommand(jobDefineId);
            JobInstance instance = buildJobInstance(command, runMode, runtimeParams);

            jobInstanceDao.insert(instance);

            log.info("Batch job instance created successfully, instanceId={}", instance.getId());
            return ConvertUtil.sourceToTarget(instance, JobInstanceVO.class);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Create batch job instance failed, jobDefineId={}, runMode={}", jobDefineId, runMode, e);
            throw new ServiceException(Status.CREATE_BATCH_JOB_INSTANCE_ERROR);
        }
    }

    @Override
    public PaginationResult<JobInstanceVO> paging(SeaTunnelJobInstanceDTO dto) {
        validatePagingRequest(dto);

        try {
            IPage<JobInstanceVO> pageResult = jobInstanceDao.pageWithDefinition(dto);

            if (pageResult.getRecords() != null) {
                pageResult.getRecords().forEach(record -> {
                    record.setTaskType(TaskTypeResolver.resolve(
                            record.getTaskType(),
                            record.getDefinitionMode(),
                            record.getJobType(),
                            record.getSourceType()));
                    applyTerminalAverageQps(record);
                    maskSensitiveFields(record);
                });
            }

            return PaginationResult.buildSuc(pageResult.getRecords(), pageResult);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Query batch job instance paging failed, dto={}", dto, e);
            throw new ServiceException(Status.QUERY_BATCH_JOB_INSTANCE_ERROR);
        }
    }

    @Override
    public String buildJobConfig(JobDefinitionSaveCommand command) {
        validateDefinitionCommand(command);

        try {
            return jobDefinitionHoconBuilder.build(command);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Build batch job config failed, command={}", command, e);
            throw new ServiceException(Status.BUILD_JOB_INSTANCE_CONFIG_ERROR);
        }
    }

    @Override
    public JobInstanceVO selectById(Long id) {
        validateInstanceId(id);

        try {
            JobInstanceVO vo = jobInstanceDao.selectDetailById(id);
            if (vo == null) {
                throw new ServiceException(Status.BATCH_JOB_INSTANCE_NOT_EXIST);
            }

            applyTerminalAverageQps(vo);
            vo.setTableMetrics(listTableMetrics(id));

            vo.setTaskType(TaskTypeResolver.resolve(
                    vo.getTaskType(),
                    vo.getDefinitionMode(),
                    vo.getJobType(),
                    vo.getSourceType()));

            maskSensitiveFields(vo);
            return vo;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Query batch job instance by id failed, id={}", id, e);
            throw new ServiceException(Status.QUERY_BATCH_JOB_INSTANCE_ERROR);
        }
    }

    @Override
    public EngineJobInfoVO getEngineJobInfo(Long instanceId) {
        validateInstanceId(instanceId);

        JobInstanceVO instance = jobInstanceDao.selectDetailById(instanceId);
        if (instance == null) {
            throw new ServiceException(Status.BATCH_JOB_INSTANCE_NOT_EXIST);
        }

        EngineJobInfoVO result = new EngineJobInfoVO();
        result.setInstanceId(instanceId);
        result.setEngineJobId(instance.getEngineJobId());
        result.setLocalStatus(instance.getJobStatus());
        result.setLocalErrorMessage(HoconSensitiveMaskUtil.maskSensitiveText(instance.getErrorMessage()));
        result.setStatus(instance.getJobStatus());
        result.setEngineAvailable(false);

        if (instance.getClientId() == null
                || instance.getClientId() <= 0
                || instance.getEngineJobId() == null
                || instance.getEngineJobId().isBlank()) {
            return result;
        }

        try {
            Map<String, Object> engineInfo = engineRestClient.jobInfo(
                    instance.getClientId(), instance.getEngineJobId());
            if (engineInfo == null || engineInfo.isEmpty()) {
                return result;
            }

            result.setStatus(stringValue(engineInfo.get("status"), result.getStatus()));
            result.setCreateTime(engineInfo.get("createTime"));
            result.setFinishTime(engineInfo.get("finishTime"));
            result.setErrorMsg(HoconSensitiveMaskUtil.maskSensitiveText(
                    stringValue(engineInfo.get("errorMsg"), null)));
            // The Engine payload is copied field by field, so mask it before it leaves the Web.
            result.setJobDag(HoconSensitiveMaskUtil.maskSensitiveValues(engineInfo.get("jobDag")));
            result.setDiagnostics(
                    HoconSensitiveMaskUtil.maskSensitiveValues(engineInfo.get("diagnostics")));
            result.setMetrics(HoconSensitiveMaskUtil.maskSensitiveValues(engineInfo.get("metrics")));
            result.setEngineAvailable(true);
            return result;
        } catch (Exception e) {
            /*
             * The local instance row already carries the status the caller needs, so an Engine
             * outage degrades this diagnostics view instead of failing the whole request.
             */
            log.warn("Fetch Engine job-info failed, instanceId={}, engineJobId={}",
                    instanceId, instance.getEngineJobId(), e);
            return result;
        }
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : Objects.toString(value, fallback);
    }

    @Override
    public String getLogContent(Long instanceId) {
        validateInstanceId(instanceId);
        return jobLogService.getFullContent(instanceId, JobMode.BATCH);
    }

    @Override
    public boolean existsRunningInstance(Long definitionId) {
        if (definitionId == null) {
            return false;
        }

        try {
            return jobInstanceDao.existsRunningInstance(definitionId);
        } catch (Exception e) {
            log.error("Check running batch job instance failed, definitionId={}", definitionId, e);
            throw new ServiceException(Status.QUERY_BATCH_JOB_INSTANCE_ERROR);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeAllByDefinitionId(Long definitionId) {
        if (definitionId == null) {
            return;
        }

        try {
            jobTableMetricsDao.deleteByDefinitionId(definitionId);
            jobMetricsDao.deleteByDefinitionId(definitionId);
            jobInstanceDao.deleteByDefinitionId(definitionId);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Remove all batch job instances by definition id failed, definitionId={}", definitionId, e);
            throw new ServiceException(Status.DELETE_BATCH_JOB_INSTANCE_ERROR);
        }
    }

    @Override
    public boolean updateEngineIdIfStarting(Long instanceId, String engineJobId, Date startTime) {
        if (instanceId == null || instanceId <= 0) {
            return false;
        }
        try {
            return jobInstanceDao.updateEngineIdIfStarting(instanceId, engineJobId, startTime);
        } catch (Exception e) {
            log.error("Update engine id conditionally failed, id={}", instanceId, e);
            throw new ServiceException(Status.UPDATE_BATCH_JOB_INSTANCE_ERROR);
        }
    }

    @Override
    public boolean updateStatusIfCurrent(Long instanceId,
                                         JobStatus expectedStatus,
                                         JobStatus targetStatus,
                                         String errorMessage) {
        if (instanceId == null || instanceId <= 0 || expectedStatus == null || targetStatus == null) {
            return false;
        }
        try {
            return jobInstanceDao.updateStatusIfCurrent(
                    instanceId, expectedStatus, targetStatus, errorMessage);
        } catch (Exception e) {
            log.error("Update status conditionally failed, id={}, expected={}, target={}",
                    instanceId, expectedStatus, targetStatus, e);
            throw new ServiceException(Status.UPDATE_BATCH_JOB_INSTANCE_ERROR);
        }
    }

    @Override
    public boolean updateFinalStatusIfRunningLike(Long instanceId,
                                                  JobStatus targetStatus,
                                                  String errorMessage) {
        if (instanceId == null || instanceId <= 0 || targetStatus == null) {
            return false;
        }
        try {
            return jobInstanceDao.updateFinalStatusIfRunningLike(
                    instanceId, targetStatus, errorMessage);
        } catch (Exception e) {
            log.error("Update final status conditionally failed, id={}, target={}",
                    instanceId, targetStatus, e);
            throw new ServiceException(Status.UPDATE_BATCH_JOB_INSTANCE_ERROR);
        }
    }

    @Override
    public boolean touchStartingInstance(Long instanceId) {
        if (instanceId == null || instanceId <= 0) {
            return false;
        }
        try {
            return jobInstanceDao.touchStartingInstance(instanceId);
        } catch (Exception e) {
            log.warn("Refresh submission heartbeat failed, id={}", instanceId, e);
            return false;
        }
    }

    @Override
    public void updateById(JobInstance po) {
        if (po == null || po.getId() == null || po.getId() <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobInstance");
        }

        try {
            jobInstanceDao.updateById(po);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Update batch job instance failed, id={}", po.getId(), e);
            throw  new ServiceException(Status.UPDATE_BATCH_JOB_INSTANCE_ERROR);
        }
    }

    @Override
    public List<JobTableMetricsVO> listTableMetrics(Long instanceId) {
        validateInstanceId(instanceId);

        JobInstanceVO instance = jobInstanceDao.selectDetailById(instanceId);
        if (instance == null) {
            throw new ServiceException(Status.BATCH_JOB_INSTANCE_NOT_EXIST);
        }

        try {
            List<JobTableMetricsVO> tableMetrics = jobTableMetricsDao.selectByInstanceId(instanceId);
            if (tableMetrics == null || tableMetrics.isEmpty() || tableMetrics.size() != 1) {
                return tableMetrics == null ? List.of() : tableMetrics;
            }

            if (!isTerminalInstance(instance)) {
                return tableMetrics;
            }

            JobTableMetricsVO table = tableMetrics.get(0);
            if (isZero(table.getReadRowCount()) && hasRows(instance.getReadRowCount())) {
                table.setReadRowCount(instance.getReadRowCount());
            }
            if (isZero(table.getWriteRowCount()) && hasRows(instance.getWriteRowCount())) {
                table.setWriteRowCount(instance.getWriteRowCount());
            }

            long runtimeMillis = runtimeMillis(instance);
            if (runtimeMillis > 0) {
                table.setReadQps(JobMetricsRateCalculator.calculateAverageQps(
                        table.getReadRowCount(), runtimeMillis));
                table.setWriteQps(JobMetricsRateCalculator.calculateAverageQps(
                        table.getWriteRowCount(), runtimeMillis));
            }

            return tableMetrics;
        } catch (Exception e) {
            log.error("Query table metrics failed, instanceId={}", instanceId, e);
            throw new ServiceException(Status.QUERY_BATCH_JOB_INSTANCE_ERROR);
        }
    }

    private void applyTerminalAverageQps(JobInstanceVO instance) {
        if (!isTerminalInstance(instance)) {
            return;
        }

        long runtimeMillis = runtimeMillis(instance);
        if (runtimeMillis <= 0) {
            return;
        }

        instance.setReadQps(JobMetricsRateCalculator.calculateAverageQps(
                instance.getReadRowCount(), runtimeMillis));
        instance.setWriteQps(JobMetricsRateCalculator.calculateAverageQps(
                instance.getWriteRowCount(), runtimeMillis));
    }

    private boolean isTerminalInstance(JobInstanceVO instance) {
        if (instance == null || instance.getJobStatus() == null) {
            return false;
        }

        try {
            return JobStatus.fromString(instance.getJobStatus()).isEndState();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private long runtimeMillis(JobInstanceVO instance) {
        if (instance == null || instance.getStartTime() == null || instance.getEndTime() == null) {
            return 0L;
        }
        return instance.getEndTime().getTime() - instance.getStartTime().getTime();
    }

    private boolean isZero(Long value) {
        return value == null || value == 0L;
    }

    private boolean hasRows(Long value) {
        return value != null && value > 0L;
    }

    @Override
    public List<JobInstance> listRunningInstanceByDefinitionIds(List<Long> definitionIds) {
        if (definitionIds == null || definitionIds.isEmpty()) {
            return List.of();
        }

        try {
            return jobInstanceDao.selectRunningInstanceByDefinitionIds(definitionIds);
        } catch (Exception e) {
            log.error("Query running batch job instances failed, definitionIds={}", definitionIds, e);
            throw new ServiceException(Status.QUERY_BATCH_JOB_INSTANCE_ERROR);
        }
    }

    /**
     * Load persisted definition command for instance creation.
     */
    private JobDefinitionSaveCommand loadDefinitionCommand(Long jobDefineId) {
        return jobDefinitionCommandResolver.resolve(jobDefineId);
    }

    /**
     * Build job instance entity.
     */
    private JobInstance buildJobInstance(JobDefinitionSaveCommand command,
                                         RunMode runMode,
                                         Map<String, String> runtimeParams) {
        Long id = generateInstanceId();
        if (runtimeParams != null
                && command instanceof org.apache.seatunnel.web.spi.bean.dto.command.BatchJobSaveCommand
                && ((org.apache.seatunnel.web.spi.bean.dto.command.BatchJobSaveCommand) command).getSchedule() != null) {
            ((org.apache.seatunnel.web.spi.bean.dto.command.BatchJobSaveCommand) command)
                    .getSchedule().setRuntimeParams(runtimeParams);
        }
        String runtimeConfig = buildJobConfig(command);

        JobInstance instance = jobInstanceFactory.create(
                command,
                id,
                runtimeConfig,
                runMode,
                buildLogPath(id),
                JobMode.BATCH
        );
        // Persist the caller-supplied Engine id before any network request. Recovery can then
        // reconcile a lost submit response without asking the user or submitting a duplicate job.
        instance.setEngineJobId(String.valueOf(id));
        captureSubmittedTableSnapshot(instance, command.getId());
        return instance;
    }

    /**
     * Stores the source/target identifiers that this instance is actually submitted with. The
     * definition may be edited while the asynchronous submission and lineage write are in flight,
     * so the lineage service must not read those values back from the definition row.
     */
    private void captureSubmittedTableSnapshot(JobInstance instance, Long jobDefinitionId) {
        if (instance == null || jobDefinitionId == null) {
            return;
        }
        JobDefinitionEntity definition = jobDefinitionDao.queryById(jobDefinitionId);
        if (definition == null) {
            return;
        }
        instance.setSourceDatasourceId(definition.getSourceDatasourceId());
        instance.setSinkDatasourceId(definition.getSinkDatasourceId());
        instance.setSourceTable(definition.getSourceTable());
        instance.setSinkTable(definition.getSinkTable());
    }

    /**
     * Generate instance id.
     */
    private Long generateInstanceId() {
        try {
            return CodeGenerateUtils.getInstance().genCode();
        } catch (CodeGenerateUtils.CodeGenerateException e) {
            log.error("Generate batch job instance id failed", e);
            throw new ServiceException(Status.GENERATE_JOB_INSTANCE_ID_ERROR);
        }
    }

    /**
     * Build log file path.
     */
    private String buildLogPath(Long id) {
        return System.getProperty("user.dir")
                + File.separator
                + Paths.get(baseLogDir, "job-" + id + ".log");
    }

    /**
     * Query instance by id or throw exception.
     */
    private JobInstance getJobInstanceOrThrow(Long id) {
        JobInstance entity = jobInstanceDao.queryById(id);
        if (entity == null) {
            throw new ServiceException(Status.BATCH_JOB_INSTANCE_NOT_EXIST);
        }
        return entity;
    }

    /**
     * Validate definition id.
     */
    private void validateDefinitionId(Long jobDefineId) {
        if (jobDefineId == null || jobDefineId <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobDefineId");
        }
    }

    /**
     * Validate instance id.
     */
    private void validateInstanceId(Long instanceId) {
        if (instanceId == null || instanceId <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "instanceId");
        }
    }

    /**
     * Validate run mode.
     */
    private void validateRunMode(RunMode runMode) {
        if (runMode == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "runMode");
        }
    }

    /**
     * Validate paging request.
     */
    private void validatePagingRequest(SeaTunnelJobInstanceDTO dto) {
        if (dto == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "dto");
        }
        if (dto.getPageNo() == null || dto.getPageNo() <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "pageNo");
        }
        if (dto.getPageSize() == null || dto.getPageSize() <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "pageSize");
        }
    }

    /**
     * Validate definition command.
     */
    private void validateDefinitionCommand(JobDefinitionSaveCommand command) {
        if (command == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobDefinition");
        }
    }

    /**
     * Mask sensitive fields before return.
     */
    private void maskSensitiveFields(JobInstanceVO vo) {
        if (vo == null) {
            return;
        }

        if (vo.getRuntimeConfig() != null) {
            vo.setRuntimeConfig(HoconSensitiveMaskUtil.maskSensitiveInfo(vo.getRuntimeConfig()));
        }
    }
}
