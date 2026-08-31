package org.apache.seatunnel.web.api.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.service.StreamingJobDefinitionService;
import org.apache.seatunnel.web.api.service.StreamingJobInstanceService;
import org.apache.seatunnel.web.api.service.StreamingJobMetricsService;
import org.apache.seatunnel.web.api.service.cdc.CdcServerIdAllocationService;
import org.apache.seatunnel.web.api.lake.job.LakeJobRelationBridgeService;
import org.apache.seatunnel.web.api.lake.job.LakeJobGuard;
import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.common.enums.ReleaseState;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.modal.JobDefinitionAnalysisResult;
import org.apache.seatunnel.web.common.utils.CodeGenerateUtils;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.core.job.assembler.StreamingJobDefinitionAssembler;
import org.apache.seatunnel.web.core.job.handler.JobDefinitionModeHandler;
import org.apache.seatunnel.web.core.job.registry.JobDefinitionModeHandlerRegistry;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionContentEntity;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionEntity;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionContentDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionDao;
import org.apache.seatunnel.web.spi.bean.dto.StreamingJobDefinitionQueryDTO;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionBatchCreateCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.StreamingJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingScriptJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.JobDefinitionEditDetailVO;
import org.apache.seatunnel.web.spi.bean.vo.JobDefinitionBatchCreateResultVO;
import org.apache.seatunnel.web.spi.bean.vo.JobDefinitionSaveResultVO;
import org.apache.seatunnel.web.spi.bean.vo.JobDefinitionStateVO;
import org.apache.seatunnel.web.spi.bean.vo.StreamingJobDefinitionVO;
import org.apache.seatunnel.web.spi.bean.vo.StreamingMetricsSnapshotVO;
import org.apache.seatunnel.web.spi.bean.vo.StreamingMetricsTrendItemVO;
import org.apache.seatunnel.web.spi.enums.JobRuntimeType;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class StreamingJobDefinitionServiceImpl extends BaseServiceImpl implements StreamingJobDefinitionService {

    @Resource
    private JobDefinitionModeHandlerRegistry handlerRegistry;

    @Resource
    private StreamingJobDefinitionDao streamingJobDefinitionDao;

    @Resource
    private StreamingJobDefinitionContentDao streamingJobDefinitionContentDao;

    @Resource
    private StreamingJobDefinitionAssembler streamingJobDefinitionAssembler;

    @Resource
    private StreamingJobDefinitionQueryService definitionQueryService;

    @Resource
    private CdcServerIdAllocationService cdcServerIdAllocationService;

    @Resource
    private StreamingJobInstanceService streamingJobInstanceService;

    @Resource
    private StreamingJobMetricsService streamingJobMetricsService;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private CurrentUserProvider currentUserProvider;

    @Resource
    private LakeJobRelationBridgeService lakeJobRelationBridgeService;

    @Resource
    private LakeJobGuard lakeJobGuard;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JobDefinitionSaveResultVO saveOrUpdate(StreamingScriptJobSaveCommand command) {
        return doSaveOrUpdate(command);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JobDefinitionSaveResultVO saveOrUpdate(StreamingGuideSingleJobSaveCommand command) {
        return doSaveOrUpdate(command);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JobDefinitionSaveResultVO saveOrUpdate(StreamingGuideMultiJobSaveCommand command) {
        return doSaveOrUpdate(command);
    }

    protected JobDefinitionSaveResultVO doSaveOrUpdate(StreamingJobSaveCommand command) {
        validatePersistCommand(command);
        if (lakeJobGuard != null) {
            lakeJobGuard.validateBeforeSave(command);
        }
        validateStreaming(command);

        try {
            SaveContext context = prepareSaveContext(command);

            StreamingJobDefinitionEntity entity = saveDefinition(command, context);

            cdcServerIdAllocationService.prepare(command, entity.getId());

            saveDefinitionContent(command, context, entity);

            if (lakeJobRelationBridgeService != null) {
                lakeJobRelationBridgeService.syncRelationAfterJobSave(
                        command, entity.getId(), context.getNextVersion(), LakeJobRuntimeType.STREAMING);
            }

            return buildSaveResult(entity, context.getNextVersion());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Save or update streaming job definition failed, command={}", command, e);
            throw new ServiceException(e.getMessage());
        }
    }

    @Override
    public String buildHoconConfig(StreamingScriptJobSaveCommand command) {
        return doBuildHoconConfig(command);
    }

    @Override
    public String buildHoconConfig(StreamingGuideSingleJobSaveCommand command) {
        return doBuildHoconConfig(command);
    }

    @Override
    public String buildHoconConfig(StreamingGuideMultiJobSaveCommand command) {
        return doBuildHoconConfig(command);
    }

    protected String doBuildHoconConfig(StreamingJobSaveCommand command) {
        validatePersistCommand(command);
        if (lakeJobGuard != null) {
            lakeJobGuard.validateBeforeSave(command);
        }
        validateStreaming(command);

        try {
            JobDefinitionModeHandler handler = getAndValidateHandler(command);
            String hocon = handler.buildHoconConfig(command);

            if (StringUtils.isBlank(hocon)) {
                throw new ServiceException(
                        Status.BUILD_BATCH_JOB_HOCON_CONFIG_ERROR,
                        "hocon config is empty"
                );
            }

            return hocon;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Build streaming hocon config failed, command={}", command, e);
            throw new ServiceException(e.getMessage());
        }
    }

    @Override
    public StreamingJobDefinitionVO selectById(Long id) {
        validateId(id);
        return definitionQueryService.selectById(id);
    }

    @Override
    public PaginationResult<StreamingJobDefinitionVO> paging(StreamingJobDefinitionQueryDTO dto) {
        validatePagingRequest(dto);

        try {
            int offset = (dto.getPageNo() - 1) * dto.getPageSize();

            List<StreamingJobDefinitionVO> records =
                    streamingJobDefinitionDao.selectPage(dto, offset, dto.getPageSize());

            enrichMetrics(records);

            Long total = streamingJobDefinitionDao.count(dto);

            return PaginationResult.buildSuc(records, total, dto.getPageNo(), dto.getPageSize());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Query streaming job definition paging failed, dto={}", dto, e);
            throw new ServiceException(Status.QUERY_BATCH_JOB_DEFINITION_ERROR);
        }
    }

    private void enrichMetrics(List<StreamingJobDefinitionVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        for (StreamingJobDefinitionVO record : records) {
            Long instanceId = record.getInstanceId();
            if (instanceId == null || instanceId <= 0) {
                record.setLatestMetrics(null);
                record.setRecentMetrics(Collections.emptyList());
                continue;
            }

            try {
                StreamingMetricsSnapshotVO latestMetrics =
                        streamingJobMetricsService.latest(instanceId);

                List<StreamingMetricsTrendItemVO> recentMetrics =
                        streamingJobMetricsService.recentTrend(instanceId, 20);

                record.setLatestMetrics(latestMetrics);
                record.setRecentMetrics(recentMetrics == null ? Collections.emptyList() : recentMetrics);
            } catch (Exception e) {
                log.warn("Enrich streaming job metrics failed, definitionId={}, instanceId={}",
                        record.getId(), instanceId, e);

                record.setLatestMetrics(null);
                record.setRecentMetrics(Collections.emptyList());
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean delete(Long jobDefinitionId) {
        validateId(jobDefinitionId);

        StreamingJobDefinitionEntity definition = definitionQueryService.getDefinitionOrThrow(jobDefinitionId);
        validateDelete(definition.getId());

        try {
            cdcServerIdAllocationService.release(jobDefinitionId);
            streamingJobInstanceService.removeAllByDefinitionId(jobDefinitionId);
            if (lakeJobRelationBridgeService != null) {
                lakeJobRelationBridgeService.markRelationsAfterJobDelete(jobDefinitionId);
            }
            streamingJobDefinitionContentDao.deleteByJobDefinitionId(jobDefinitionId);

            boolean deleted = streamingJobDefinitionDao.deleteById(jobDefinitionId);
            if (!deleted) {
                throw new ServiceException(Status.DELETE_BATCH_JOB_DEFINITION_ERROR);
            }

            return true;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Delete streaming job definition failed, id={}", jobDefinitionId, e);
            throw new ServiceException(Status.DELETE_BATCH_JOB_DEFINITION_ERROR);
        }
    }

    @Override
    public JobDefinitionEditDetailVO selectEditDetail(Long id) {
        validateId(id);

        try {
            StreamingJobDefinitionEntity definition = definitionQueryService.getDefinitionOrThrow(id);
            validateEditable(definition);

            StreamingJobDefinitionContentEntity latestContent = getLatestContentOrThrow(id);

            JobDefinitionSaveCommand command = definitionQueryService.buildEditCommand(definition, latestContent);

            return buildEditDetail(command, definition, latestContent);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Query streaming job definition edit detail failed, id={}", id, e);
            throw new ServiceException(Status.QUERY_BATCH_JOB_DEFINITION_ERROR);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public JobDefinitionBatchCreateResultVO batchCreate(JobDefinitionBatchCreateCommand command) {
        List<Long> templateIds = normalizeBatchCreateTemplateIds(command);
        int copiesPerTemplate = command.getCopiesPerTemplate();

        JobDefinitionBatchCreateResultVO result = new JobDefinitionBatchCreateResultVO();
        result.setTemplateCount(templateIds.size());
        result.setCopiesPerTemplate(copiesPerTemplate);

        for (Long templateId : templateIds) {
            StreamingJobDefinitionEntity definition = definitionQueryService.getDefinitionOrThrow(templateId);
            StreamingJobDefinitionContentEntity latestContent = getLatestContentOrThrow(templateId);
            JobDefinitionSaveCommand template = definitionQueryService.buildEditCommand(
                    definition,
                    latestContent
            );

            for (int copyIndex = 1; copyIndex <= copiesPerTemplate; copyIndex++) {
                Long newId = CodeGenerateUtils.getInstance().genCode();
                StreamingJobSaveCommand copy = copyStreamingCommand(
                        template,
                        newId,
                        buildCopyName(definition.getJobName(), command.getJobNamePrefix(), copyIndex)
                );
                result.addCreatedJob(doSaveOrUpdate(copy));
            }
        }

        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateReleaseState(Long id, ReleaseState releaseState) {
        validateId(id);
        validateReleaseState(releaseState);

        try {
            StreamingJobDefinitionEntity entity = definitionQueryService.getDefinitionOrThrow(id);

            ReleaseState currentState = entity.getReleaseState();
            if (releaseState == currentState) {
                log.info("Streaming job definition release state already synced, id={}, state={}",
                        id, releaseState);
                return true;
            }

            if (releaseState.isOnline()) {
                validateBeforeOnline(id);
            }

            if (releaseState.isOffline()) {
                validateBeforeOffline(id);
            }

            boolean updated = streamingJobDefinitionDao.updateReleaseState(
                    id,
                    releaseState,
                    currentUserProvider.getCurrentUserId(),
                    new Date()
            );
            if (!updated) {
                throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR);
            }

            log.info("Streaming job definition release state updated, id={}, from={}, to={}",
                    id, currentState, releaseState);
            return true;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("Update streaming job definition release state failed, id={}, state={}",
                    id, releaseState, e);
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR);
        }
    }

    private SaveContext prepareSaveContext(StreamingJobSaveCommand command) {
        JobDefinitionModeHandler handler = getAndValidateHandler(command);
        JobDefinitionAnalysisResult analysis = handler.analyze(command);
        String definitionContent = handler.serializeDefinition(command);

        if (StringUtils.isBlank(definitionContent)) {
            throw new ServiceException(
                    Status.SAVE_OR_UPDATE_BATCH_JOB_DEFINITION_ERROR,
                    "definition content is empty"
            );
        }

        StreamingJobDefinitionEntity existing = command.getId() == null
                ? null
                : streamingJobDefinitionDao.queryById(command.getId());

        validateWritable(existing);

        int nextVersion = resolveNextVersion(existing);

        SaveContext context = new SaveContext();
        context.setHandler(handler);
        context.setAnalysis(analysis);
        context.setDefinitionContent(definitionContent);
        context.setExisting(existing);
        context.setNextVersion(nextVersion);
        context.setNow(new Date());
        return context;
    }

    private StreamingJobDefinitionEntity saveDefinition(StreamingJobSaveCommand command, SaveContext context) {
        StreamingJobDefinitionEntity entity;

        if (ObjectUtils.isEmpty(context.getExisting())) {
            entity = streamingJobDefinitionAssembler.create(command, context.getAnalysis());
            Integer currentUserId = currentUserProvider.getCurrentUserId();
            entity.setCreateUserId(currentUserId);
            entity.setUpdateUserId(currentUserId);
        } else {
            entity = context.getExisting();
            streamingJobDefinitionAssembler.update(
                    entity,
                    command,
                    context.getAnalysis(),
                    context.getNow(),
                    context.getNextVersion()
            );
            entity.setUpdateUserId(currentUserProvider.getCurrentUserId());
        }

        normalizePersistState(entity, context.getNextVersion());

        streamingJobDefinitionDao.saveOrUpdate(entity);
        return entity;
    }

    private void saveDefinitionContent(
            StreamingJobSaveCommand command,
            SaveContext context,
            StreamingJobDefinitionEntity entity) {
        if (entity == null || entity.getId() == null) {
            throw new ServiceException(
                    Status.SAVE_OR_UPDATE_BATCH_JOB_DEFINITION_ERROR,
                    "streaming definition id is empty"
            );
        }

        StreamingJobDefinitionContentEntity contentEntity =
                StreamingJobDefinitionContentEntity.builder()
                        .jobDefinitionId(entity.getId())
                        .version(context.getNextVersion())
                        .mode(command.getMode())
                        .contentSchemaVersion(1)
                        .definitionContent(context.getDefinitionContent())
                        .envConfig(JSONUtils.toJsonString(command.getEnv()))
                        .build();

        contentEntity.initInsert();
        streamingJobDefinitionContentDao.save(contentEntity);
    }

    private JobDefinitionSaveResultVO buildSaveResult(
            StreamingJobDefinitionEntity entity,
            Integer contentVersion) {
        JobDefinitionStateVO state = JobDefinitionStateVO.synced(
                resolveReleaseState(entity.getReleaseState()),
                entity.getJobVersion(),
                contentVersion
        );

        return JobDefinitionSaveResultVO.builder()
                .id(entity.getId())
                .state(state)
                .build();
    }

    private JobDefinitionEditDetailVO buildEditDetail(
            JobDefinitionSaveCommand command,
            StreamingJobDefinitionEntity definition,
            StreamingJobDefinitionContentEntity latestContent) {
        JobDefinitionEditDetailVO detail = objectMapper.convertValue(
                command,
                JobDefinitionEditDetailVO.class
        );

        detail.setState(JobDefinitionStateVO.synced(
                resolveReleaseState(definition.getReleaseState()),
                definition.getJobVersion(),
                latestContent.getVersion()
        ));
        detail.setCreateUserId(definition.getCreateUserId());
        detail.setUpdateUserId(definition.getUpdateUserId());
        detail.setCreateTime(definition.getCreateTime());
        detail.setUpdateTime(definition.getUpdateTime());

        return detail;
    }

    private void normalizePersistState(StreamingJobDefinitionEntity entity, Integer nextVersion) {
        if (entity == null) {
            return;
        }

        entity.setJobVersion(nextVersion);

        if (entity.getReleaseState() == null) {
            entity.setReleaseState(ReleaseState.OFFLINE);
        }
    }

    private ReleaseState resolveReleaseState(ReleaseState releaseState) {
        return releaseState == null ? ReleaseState.OFFLINE : releaseState;
    }

    private List<Long> normalizeBatchCreateTemplateIds(JobDefinitionBatchCreateCommand command) {
        if (command == null
                || command.getTemplateJobDefinitionIds() == null
                || command.getTemplateJobDefinitionIds().isEmpty()) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "templateJobDefinitionIds");
        }

        if (command.getCopiesPerTemplate() == null
                || command.getCopiesPerTemplate() < 1
                || command.getCopiesPerTemplate() > 20) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "copiesPerTemplate");
        }

        Set<Long> distinctIds = new LinkedHashSet<>();
        for (Long id : command.getTemplateJobDefinitionIds()) {
            if (id != null && id > 0) {
                distinctIds.add(id);
            }
        }

        if (distinctIds.isEmpty()) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "templateJobDefinitionIds");
        }

        return new ArrayList<>(distinctIds);
    }

    private StreamingJobSaveCommand copyStreamingCommand(
            JobDefinitionSaveCommand template,
            Long id,
            String jobName) {
        if (template == null || template.getMode() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "templateJobDefinition");
        }

        StreamingJobSaveCommand copy;
        JobDefinitionMode mode = template.getMode();
        switch (mode) {
            case SCRIPT:
                StreamingScriptJobSaveCommand script = objectMapper.convertValue(
                        template,
                        StreamingScriptJobSaveCommand.class
                );
                script.setId(id);
                copy = script;
                break;
            case GUIDE_SINGLE:
                StreamingGuideSingleJobSaveCommand single = objectMapper.convertValue(
                        template,
                        StreamingGuideSingleJobSaveCommand.class
                );
                single.setId(id);
                copy = single;
                break;
            case GUIDE_MULTI:
                StreamingGuideMultiJobSaveCommand multi = objectMapper.convertValue(
                        template,
                        StreamingGuideMultiJobSaveCommand.class
                );
                multi.setId(id);
                copy = multi;
                break;
            default:
                throw new ServiceException(
                        Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                        "unsupported template mode: " + mode
                );
        }

        copy.getBasic().setJobName(jobName);
        return copy;
    }

    private String buildCopyName(String sourceName, String requestedPrefix, int copyIndex) {
        String baseName = StringUtils.isBlank(requestedPrefix)
                ? sourceName
                : requestedPrefix.trim();
        if (StringUtils.isBlank(baseName)) {
            baseName = "实时任务";
        }
        return baseName + " - 副本 " + copyIndex;
    }

    private int resolveNextVersion(StreamingJobDefinitionEntity existing) {
        if (existing == null || existing.getJobVersion() == null) {
            return 1;
        }

        return existing.getJobVersion() + 1;
    }

    private JobDefinitionModeHandler getAndValidateHandler(JobDefinitionSaveCommand command) {
        validatePersistCommand(command);

        JobDefinitionModeHandler handler = handlerRegistry.getHandler(command.getMode());
        handler.validate(command);

        return handler;
    }

    private void validateBeforeOnline(Long id) {
        StreamingJobDefinitionEntity definition = definitionQueryService.getDefinitionOrThrow(id);
        StreamingJobDefinitionContentEntity latestContent = getLatestContentOrThrow(id);

        JobDefinitionSaveCommand command = definitionQueryService.buildEditCommand(definition, latestContent);

        if (!(command instanceof StreamingJobSaveCommand)) {
            throw new ServiceException(
                    Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                    "streaming job command"
            );
        }

        String hocon = doBuildHoconConfig((StreamingJobSaveCommand) command);
        if (StringUtils.isBlank(hocon)) {
            throw new ServiceException(
                    Status.BUILD_BATCH_JOB_HOCON_CONFIG_ERROR,
                    "hocon config is empty"
            );
        }
    }

    private void validateBeforeOffline(Long id) {
        if (streamingJobInstanceService.existsRunningInstance(id)) {
            throw new ServiceException(
                    Status.JOB_DEFINITION_EXECUTE_ERROR,
                    "streaming job has running instance, please stop it before offline"
            );
        }
    }

    private void validateDelete(Long id) {
        if (streamingJobInstanceService.existsRunningInstance(id)) {
            throw new ServiceException(
                    Status.DELETE_BATCH_JOB_DEFINITION_ERROR,
                    "streaming job has running instance"
            );
        }
    }

    private void validateWritable(StreamingJobDefinitionEntity existing) {
        if (existing == null) {
            return;
        }

        if (existing.getReleaseState() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "releaseState");
        }

        if (!existing.getReleaseState().isOffline()) {
            throw new ServiceException(
                    Status.SAVE_OR_UPDATE_BATCH_JOB_DEFINITION_ERROR,
                    "only offline streaming job definition can be updated"
            );
        }
    }

    private void validateEditable(StreamingJobDefinitionEntity definition) {
        if (definition == null) {
            throw new ServiceException(Status.BATCH_JOB_DEFINITION_NOT_EXIST);
        }

        if (definition.getReleaseState() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "releaseState");
        }

        if (!definition.getReleaseState().isOffline()) {
            throw new ServiceException(
                    Status.QUERY_BATCH_JOB_DEFINITION_ERROR,
                    "only offline streaming job definition can be edited"
            );
        }
    }

    private StreamingJobDefinitionContentEntity getLatestContentOrThrow(Long id) {
        StreamingJobDefinitionContentEntity latestContent =
                streamingJobDefinitionContentDao.queryLatestByJobDefinitionId(id);

        if (latestContent == null) {
            throw new ServiceException(
                    Status.BATCH_JOB_DEFINITION_NOT_EXIST,
                    "streaming definition content not found"
            );
        }

        return latestContent;
    }

    private void validateStreaming(StreamingJobSaveCommand command) {
        if (command.getRuntimeType() != JobRuntimeType.STREAMING) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "runtimeType");
        }
    }

    private void validatePersistCommand(JobDefinitionSaveCommand command) {
        if (command == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "command");
        }
        if (command.getBasic() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "basic");
        }
        if (command.getMode() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "mode");
        }
        if (command.getId() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "id");
        }
        if (StringUtils.isBlank(command.getBasic().getJobName())) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "jobName");
        }
        if (command.getEnv() == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "env");
        }
    }

    private void validateReleaseState(ReleaseState releaseState) {
        if (releaseState == null) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "releaseState");
        }
    }

    private void validatePagingRequest(StreamingJobDefinitionQueryDTO dto) {
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

    private void validateId(Long id) {
        if (id == null || id <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "id");
        }
    }

    @Data
    private static class SaveContext {
        private JobDefinitionModeHandler handler;
        private JobDefinitionAnalysisResult analysis;
        private String definitionContent;
        private StreamingJobDefinitionEntity existing;
        private Integer nextVersion;
        private Date now;
    }
}
