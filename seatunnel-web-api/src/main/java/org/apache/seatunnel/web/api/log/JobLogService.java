package org.apache.seatunnel.web.api.log;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.api.metrics.JobFileLogger;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HexFormat;

/**
 * Owns the complete task-log document.  The document is kept on the Web node
 * at the instance log path, while Engine output is fetched through the
 * configured SeaTunnel client and appended as a de-duplicated snapshot.
 */
@Service
@Slf4j
public class JobLogService {

    private static final String ENGINE_SNAPSHOT_MARKER = "=== SEA TUNNEL ENGINE LOG SNAPSHOT";
    private static final String ENGINE_SNAPSHOT_END_MARKER = "=== END SEA TUNNEL ENGINE LOG SNAPSHOT ===";

    @Resource
    private JobInstanceDao jobInstanceDao;

    @Resource
    private StreamingJobInstanceDao streamingJobInstanceDao;

    @Resource
    private SeaTunnelRestClient seaTunnelRestClient;

    @Resource
    private JobLogParser jobLogParser;

    /**
     * Returns the complete log currently available for an instance.  A running
     * task receives a live Engine section; terminal tasks persist that section
     * before the content is returned.
     */
    public String getFullContent(Long instanceId, JobMode requestedMode) {
        JobLogContext context = resolve(instanceId, requestedMode);
        String localContent = readLocalContent(context);
        String engineContent = fetchEngineContent(context);

        if (StringUtils.isNotBlank(engineContent)) {
            if (context.isTerminal()) {
                appendEngineSnapshot(context, engineContent);
                localContent = readLocalContent(context);
            } else {
                localContent = mergeLiveEngineContent(localContent, engineContent);
            }
        }

        if (StringUtils.isBlank(localContent)) {
            throw new ServiceException(Status.BATCH_JOB_INSTANCE_LOG_NOT_EXIST);
        }

        return localContent;
    }

    /**
     * Fetches and persists the terminal Engine log.  Failures are recorded in
     * the local document but do not overwrite the already known job status.
     */
    public void persistEngineLog(Long instanceId, JobMode requestedMode) {
        JobLogContext context = resolve(instanceId, requestedMode);
        if (!context.hasEngineLogReference()) {
            return;
        }

        String engineContent = fetchEngineContent(context);
        if (StringUtils.isBlank(engineContent)) {
            return;
        }

        appendEngineSnapshot(context, engineContent);
    }

    public JobLogSearchResult search(Long instanceId,
                                     JobMode requestedMode,
                                     String keyword,
                                     String level,
                                     String source,
                                     String category,
                                     Integer page,
                                     Integer pageSize) {
        List<JobLogEntry> entries = parseEntries(instanceId, requestedMode);
        String normalizedKeyword = StringUtils.defaultString(keyword).trim().toLowerCase(Locale.ROOT);
        String normalizedLevel = StringUtils.defaultString(level).trim().toUpperCase(Locale.ROOT);
        String normalizedSource = StringUtils.defaultString(source).trim().toUpperCase(Locale.ROOT);
        String normalizedCategory = StringUtils.defaultString(category).trim().toUpperCase(Locale.ROOT);

        List<JobLogEntry> matches = entries.stream()
                .filter(entry -> normalizedKeyword.isEmpty()
                        || entry.message().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                        || entry.raw().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                .filter(entry -> normalizedLevel.isEmpty() || normalizedLevel.equals(entry.level()))
                .filter(entry -> normalizedSource.isEmpty() || normalizedSource.equals(entry.source()))
                .filter(entry -> normalizedCategory.isEmpty() || normalizedCategory.equals(entry.category()))
                .toList();

        int safePage = page == null || page < 1 ? 1 : page;
        int safePageSize = pageSize == null || pageSize < 1 ? 100 : Math.min(pageSize, 500);
        int fromIndex = Math.min((safePage - 1) * safePageSize, matches.size());
        int toIndex = Math.min(fromIndex + safePageSize, matches.size());
        List<JobLogEntry> pageEntries = new ArrayList<>(matches.subList(fromIndex, toIndex));

        return new JobLogSearchResult(
                keyword,
                level,
                source,
                category,
                matches.size(),
                safePage,
                safePageSize,
                toIndex < matches.size(),
                pageEntries
        );
    }

    public JobLogAnalysisResult analyze(Long instanceId, JobMode requestedMode) {
        List<JobLogEntry> entries = parseEntries(instanceId, requestedMode);
        return new JobLogAnalysisResult(
                instanceId,
                requestedMode.name(),
                entries.size(),
                countByLevel(entries, "ERROR"),
                countByLevel(entries, "WARN"),
                entriesByCategory(entries, JobLogParser.CATEGORY_OPERATION),
                entriesByCategory(entries, JobLogParser.CATEGORY_DATA_SNAPSHOT),
                entriesByCategory(entries, JobLogParser.CATEGORY_EXECUTION_FLOW),
                entriesByCategory(entries, JobLogParser.CATEGORY_ERROR),
                entriesByCategory(entries, JobLogParser.CATEGORY_TIMELINE)
        );
    }

    private List<JobLogEntry> parseEntries(Long instanceId, JobMode requestedMode) {
        return jobLogParser.parse(getFullContent(instanceId, requestedMode));
    }

    private int countByLevel(List<JobLogEntry> entries, String level) {
        return (int) entries.stream().filter(entry -> level.equals(entry.level())).count();
    }

    private List<JobLogEntry> entriesByCategory(List<JobLogEntry> entries, String category) {
        return entries.stream().filter(entry -> category.equals(entry.category())).toList();
    }

    public JobLogContext resolve(Long instanceId, JobMode requestedMode) {
        if (instanceId == null || instanceId <= 0) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "instanceId");
        }

        if (requestedMode == JobMode.BATCH) {
            JobInstance instance = jobInstanceDao.queryById(instanceId);
            if (instance == null) {
                throw new ServiceException(Status.BATCH_JOB_INSTANCE_NOT_EXIST);
            }
            return new JobLogContext(
                    instance.getId(),
                    instance.getJobDefinitionId(),
                    instance.getClientId(),
                    instance.getEngineJobId(),
                    JobMode.BATCH,
                    instance.getRuntimeConfig(),
                    instance.getLogPath(),
                    instance.getJobStatus() == null ? null : instance.getJobStatus().name()
            );
        }

        if (requestedMode == JobMode.STREAMING) {
            StreamingJobInstance instance = streamingJobInstanceDao.queryById(instanceId);
            if (instance == null) {
                throw new ServiceException(Status.BATCH_JOB_INSTANCE_NOT_EXIST);
            }
            return new JobLogContext(
                    instance.getId(),
                    instance.getJobDefinitionId(),
                    instance.getClientId(),
                    instance.getEngineJobId(),
                    JobMode.STREAMING,
                    instance.getRuntimeConfig(),
                    instance.getLogPath(),
                    instance.getJobStatus() == null ? null : instance.getJobStatus().name()
            );
        }

        JobInstance batchInstance = jobInstanceDao.queryById(instanceId);
        if (batchInstance != null) {
            return resolve(instanceId, JobMode.BATCH);
        }

        StreamingJobInstance streamingInstance = streamingJobInstanceDao.queryById(instanceId);
        if (streamingInstance != null) {
            return resolve(instanceId, JobMode.STREAMING);
        }

        throw new ServiceException(Status.BATCH_JOB_INSTANCE_NOT_EXIST);
    }

    private String readLocalContent(JobLogContext context) {
        if (StringUtils.isBlank(context.logPath())) {
            return "";
        }

        try {
            Path path = Paths.get(context.logPath());
            if (!Files.exists(path)) {
                return "";
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Read local job log failed, instanceId={}, path={}", context.instanceId(), context.logPath(), e);
            return "";
        }
    }

    private String fetchEngineContent(JobLogContext context) {
        if (!context.hasEngineLogReference()) {
            return "";
        }

        try {
            return seaTunnelRestClient.jobLogs(context.clientId(), context.engineJobId(), "json");
        } catch (Exception e) {
            log.warn(
                    "Fetch SeaTunnel Engine log failed, instanceId={}, engineJobId={}",
                    context.instanceId(),
                    context.engineJobId(),
                    e
            );
            return "";
        }
    }

    private void appendEngineSnapshot(JobLogContext context, String engineContent) {
        if (StringUtils.isBlank(context.logPath()) || StringUtils.isBlank(engineContent)) {
            return;
        }

        String hash = sha256(engineContent);
        String existing = readLocalContent(context);
        if (existing.contains("sha256=" + hash)) {
            return;
        }

        String section = System.lineSeparator()
                + ENGINE_SNAPSHOT_MARKER
                + " sha256=" + hash
                + " engineJobId=" + context.engineJobId()
                + " ==="
                + System.lineSeparator()
                + engineContent
                + (engineContent.endsWith(System.lineSeparator()) ? "" : System.lineSeparator())
                + ENGINE_SNAPSHOT_END_MARKER
                + System.lineSeparator();

        JobFileLogger.appendExternal(context.logPath(), section);
    }

    private String mergeLiveEngineContent(String localContent, String engineContent) {
        String liveSection = ENGINE_SNAPSHOT_MARKER
                + " live=true ==="
                + System.lineSeparator()
                + engineContent
                + (engineContent.endsWith(System.lineSeparator()) ? "" : System.lineSeparator())
                + ENGINE_SNAPSHOT_END_MARKER;

        if (StringUtils.isBlank(localContent)) {
            return liveSection;
        }
        return localContent + System.lineSeparator() + liveSection;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
