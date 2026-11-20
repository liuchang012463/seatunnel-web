package org.apache.seatunnel.web.api.log;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.enums.JobMode;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.dao.entity.JobInstance;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelRestClient;
import org.apache.seatunnel.web.spi.enums.Status;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.apache.seatunnel.web.common.utils.NonNullFunctions;
import org.apache.seatunnel.web.api.utils.HoconSensitiveMaskUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

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
    private static final int MAX_REPLAY_STEPS = 10;
    private static final long ENGINE_LOG_RETRY_INTERVAL_MS = 30_000L;
    private static final int MAX_MASKABLE_LOG_LINE_CHARS = 64 * 1024;
    private static final int MAX_SEARCH_LOG_LINE_CHARS = 16 * 1024;
    private static final String MASTER_FALLBACK_NODE = "__master_shared_logs__";
    private static final ConcurrentHashMap<Path, ReentrantLock> ENGINE_COLLECTION_LOCKS = new ConcurrentHashMap<>();
    private static final Set<Path> SCHEDULED_ENGINE_COLLECTIONS = ConcurrentHashMap.newKeySet();
    private static final ThreadPoolExecutor ENGINE_LOG_EXECUTOR = createEngineLogExecutor();

    @Resource
    private JobInstanceDao jobInstanceDao;

    @Resource
    private StreamingJobInstanceDao streamingJobInstanceDao;

    @Resource
    private SeaTunnelRestClient seaTunnelRestClient;

    @Resource
    private JobLogParser jobLogParser;

    /**
     * Engine clients whose nodes share one log directory. For these clients every node writes the
     * same {@code job-{engineJobId}.log}, so one download through the configured REST address
     * replaces the node listing and the per-node downloads.
     */
    @Value("${seatunnel.job.shared-log-client-ids:}")
    private String sharedLogClientIds;

    private final JobLogFileReader jobLogFileReader = new JobLogFileReader();

    private static ThreadPoolExecutor createEngineLogExecutor() {
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "engine-log-collection-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
                threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Returns the bounded tail currently available for an instance. Engine output is
     * persisted separately so analysis and diagnosis never materialize an unbounded log.
     */
    public String getFullContent(Long instanceId, JobMode requestedMode) {
        JobLogContext context = resolve(instanceId, requestedMode);
        collectEngineLog(context);
        String localContent = readLocalContent(context);

        if (StringUtils.isBlank(localContent)) {
            throw new ServiceException(Status.BATCH_JOB_INSTANCE_LOG_NOT_EXIST);
        }

        return localContent;
    }

    /** Writes a complete persisted log directly to the response stream without buffering it in memory. */
    public void writeFullContent(Long instanceId, JobMode requestedMode, OutputStream output) throws IOException {
        JobLogContext context = resolve(instanceId, requestedMode);
        collectEngineLog(context);
        Path path = resolveLogPath(context);
        if (path == null || !Files.isRegularFile(path) || Files.size(path) == 0L) {
            throw new ServiceException(Status.BATCH_JOB_INSTANCE_LOG_NOT_EXIST);
        }
        Files.copy(path, output);
    }

    /**
     * Collects Engine log updates during monitoring. The persisted cursor limits
     * background fetches to one attempt per instance every 30 seconds.
     */
    public void persistEngineLog(Long instanceId, JobMode requestedMode) {
        JobLogContext context = resolve(instanceId, requestedMode);
        scheduleEngineLogCollection(context);
    }

    private void scheduleEngineLogCollection(JobLogContext context) {
        if (context == null || !context.hasEngineLogReference()) {
            return;
        }
        Path path = resolveLogPath(context);
        if (path == null || !SCHEDULED_ENGINE_COLLECTIONS.add(path)) {
            return;
        }
        try {
            ENGINE_LOG_EXECUTOR.execute(() -> {
                try {
                    collectEngineLog(context);
                } finally {
                    SCHEDULED_ENGINE_COLLECTIONS.remove(path);
                }
            });
        } catch (RejectedExecutionException e) {
            SCHEDULED_ENGINE_COLLECTIONS.remove(path);
            log.warn("Engine log refresh queue is full, instanceId={}, jobMode={}",
                    context.instanceId(), context.jobMode());
        }
    }

    /** Performs one final collection after the job status has been persisted. */
    public void persistFinalEngineLog(Long instanceId, JobMode requestedMode) {
        JobLogContext context = resolve(instanceId, requestedMode);
        collectEngineLog(context, true);
    }

    /** Reads one bounded page from the persisted log without contacting Engine. */
    public JobLogTailResult tail(Long instanceId,
                                 JobMode requestedMode,
                                 Long afterCursor,
                                 Long beforeCursor) {
        JobLogContext context = resolve(instanceId, requestedMode);
        // Return the locally retained page immediately; remote node reads run off-request.
        scheduleEngineLogCollection(context);
        Path path = resolveLogPath(context);
        try {
            JobLogFileReader.Page page = jobLogFileReader.read(path, afterCursor, beforeCursor);
            return new JobLogTailResult(
                    instanceId,
                    requestedMode.name(),
                    availability(context, path),
                    page.content(),
                    page.startCursor(),
                    page.endCursor(),
                    page.hasOlder(),
                    page.hasNewer()
            );
        } catch (Exception e) {
            log.warn("Read bounded job log page failed, instanceId={}, jobMode={}", instanceId, requestedMode, e);
            return new JobLogTailResult(
                    instanceId, requestedMode.name(), availability(context, path), "", 0L, 0L, false, false);
        }
    }

    public JobLogSearchResult search(Long instanceId,
                                     JobMode requestedMode,
                                     String keyword,
                                     String level,
                                     String source,
                                     String category,
                                     Integer page,
                                     Integer pageSize) {
        JobLogContext context = resolve(instanceId, requestedMode);
        scheduleEngineLogCollection(context);
        String normalizedKeyword = StringUtils.defaultString(keyword).trim().toLowerCase(Locale.ROOT);
        String normalizedLevel = StringUtils.defaultString(level).trim().toUpperCase(Locale.ROOT);
        String normalizedSource = StringUtils.defaultString(source).trim().toUpperCase(Locale.ROOT);
        String normalizedCategory = StringUtils.defaultString(category).trim().toUpperCase(Locale.ROOT);
        int safePage = page == null || page < 1 ? 1 : page;
        int safePageSize = pageSize == null || pageSize < 1 ? 100 : Math.min(pageSize, 500);
        long firstMatch = ((long) safePage - 1L) * safePageSize;
        List<JobLogEntry> pageEntries = new ArrayList<>(safePageSize);
        long totalMatches = 0L;

        Path path = resolveLogPath(context);
        if (path != null && Files.isRegularFile(path)) {
            boolean inEngineSection = false;
            long lineNumber = 0L;
            long sequence = 0L;
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String rawLine;
                while ((rawLine = readBoundedLine(reader, MAX_SEARCH_LOG_LINE_CHARS)) != null) {
                    lineNumber++;
                    if (rawLine.contains(ENGINE_SNAPSHOT_MARKER)) {
                        inEngineSection = true;
                        continue;
                    }
                    if (rawLine.contains(ENGINE_SNAPSHOT_END_MARKER)) {
                        inEngineSection = false;
                        continue;
                    }

                    List<JobLogEntry> parsed = jobLogParser.parse(rawLine);
                    for (JobLogEntry item : parsed) {
                        sequence++;
                        String entrySource = inEngineSection ? "ENGINE" : item.source();
                        JobLogEntry entry = new JobLogEntry(
                                sequence,
                                lineNumber,
                                item.timestamp(),
                                item.level(),
                                entrySource,
                                item.category(),
                                item.eventType(),
                                item.message(),
                                rawLine,
                                item.elapsedMs()
                        );
                        if (!matches(entry, normalizedKeyword, normalizedLevel, normalizedSource, normalizedCategory)) {
                            continue;
                        }
                        if (totalMatches >= firstMatch && pageEntries.size() < safePageSize) {
                            pageEntries.add(entry);
                        }
                        totalMatches++;
                    }
                }
            } catch (IOException e) {
                log.warn("Stream job log search failed, instanceId={}, path={}", instanceId, path, e);
            }
        }
        long nextMatch = firstMatch + pageEntries.size();

        return new JobLogSearchResult(
                keyword,
                level,
                source,
                category,
                totalMatches,
                safePage,
                safePageSize,
                nextMatch < totalMatches,
                pageEntries
        );
    }

    /** Reads one line without allowing a single malformed or giant line to consume unbounded memory. */
    private String readBoundedLine(BufferedReader reader, int maxChars) throws IOException {
        StringBuilder line = new StringBuilder(Math.min(1024, maxChars));
        boolean oversized = false;
        boolean readAny = false;
        int current;
        while ((current = reader.read()) != -1) {
            readAny = true;
            if (current == '\n') {
                return oversized ? "[omitted oversized log line]" : line.toString();
            }
            if (!oversized) {
                if (line.length() >= maxChars) {
                    line.setLength(0);
                    oversized = true;
                } else {
                    line.append((char) current);
                }
            }
        }
        if (!readAny) {
            return null;
        }
        return oversized ? "[omitted oversized log line]" : line.toString();
    }

    public JobLogAnalysisResult analyze(Long instanceId, JobMode requestedMode) {
        List<JobLogEntry> entries = parseEntries(instanceId, requestedMode);
        return new JobLogAnalysisResult(
                instanceId,
                requestedMode.name(),
                entries.size(),
                countByLevel(entries, "ERROR"),
                countByLevel(entries, "WARN"),
                structuredEntriesByCategory(entries, JobLogParser.CATEGORY_OPERATION),
                structuredEntriesByCategory(entries, JobLogParser.CATEGORY_DATA_SNAPSHOT),
                structuredEntriesByCategory(entries, JobLogParser.CATEGORY_EXECUTION_FLOW),
                entriesByCategory(entries, JobLogParser.CATEGORY_ERROR),
                structuredEntriesByCategory(entries, JobLogParser.CATEGORY_TIMELINE)
        );
    }

    public JobLogReplayResult replay(Long instanceId, JobMode requestedMode) {
        List<JobLogEntry> entries = parseEntries(instanceId, requestedMode);
        List<JobLogReplaySection> sections = replaySections(entries);
        Long durationMs = entries.stream()
                .map(NonNullFunctions.from(JobLogEntry::elapsedMs))
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        int totalSteps = sections.stream().mapToInt(section -> section.steps().size()).sum();
        return new JobLogReplayResult(
                instanceId,
                requestedMode.name(),
                sections.size(),
                totalSteps,
                durationMs,
                sections
        );
    }

    private List<JobLogEntry> parseEntries(Long instanceId, JobMode requestedMode) {
        JobLogContext context = resolve(instanceId, requestedMode);
        scheduleEngineLogCollection(context);
        String localContent = readLocalContent(context);
        if (StringUtils.isBlank(localContent)) {
            throw new ServiceException(Status.BATCH_JOB_INSTANCE_LOG_NOT_EXIST);
        }
        return jobLogParser.parse(localContent);
    }

    private int countByLevel(List<JobLogEntry> entries, String level) {
        return (int) entries.stream().filter(entry -> level.equals(entry.level())).count();
    }

    private List<JobLogEntry> entriesByCategory(List<JobLogEntry> entries, String category) {
        return entries.stream().filter(entry -> category.equals(entry.category())).toList();
    }

    private List<JobLogStructuredRecord> structuredEntriesByCategory(List<JobLogEntry> entries, String category) {
        return entries.stream()
                .filter(entry -> category.equals(entry.category()))
                .map(jobLogParser::toStructuredRecord)
                .toList();
    }

    private List<JobLogReplaySection> replaySections(List<JobLogEntry> entries) {
        List<List<JobLogEntry>> groups = new ArrayList<>();
        List<JobLogEntry> current = new ArrayList<>();
        String currentCategory = null;

        for (JobLogEntry entry : entries) {
            if (currentCategory != null && !currentCategory.equals(entry.category())) {
                groups.add(current);
                current = new ArrayList<>();
            }
            currentCategory = entry.category();
            current.add(entry);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }

        while (groups.size() > MAX_REPLAY_STEPS) {
            int mergeIndex = 0;
            int smallestPair = Integer.MAX_VALUE;
            for (int index = 0; index < groups.size() - 1; index++) {
                int pairSize = groups.get(index).size() + groups.get(index + 1).size();
                if (pairSize < smallestPair) {
                    smallestPair = pairSize;
                    mergeIndex = index;
                }
            }

            List<JobLogEntry> merged = new ArrayList<>(groups.get(mergeIndex));
            merged.addAll(groups.get(mergeIndex + 1));
            groups.set(mergeIndex, merged);
            groups.remove(mergeIndex + 1);
        }

        List<JobLogReplaySection> sections = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            sections.add(toReplaySection(index + 1, groups.get(index)));
        }
        return sections;
    }

    private JobLogReplaySection toReplaySection(int sectionNumber, List<JobLogEntry> entries) {
        String category = entries.get(0).category();
        List<JobLogReplayStep> steps = List.of(toReplayStep(entries));
        Long startElapsed = entries.get(0).elapsedMs();
        Long endElapsed = entries.get(entries.size() - 1).elapsedMs();
        Long durationMs = startElapsed == null || endElapsed == null
                ? null
                : Math.max(0L, endElapsed - startElapsed);
        return new JobLogReplaySection(
                "section-" + sectionNumber,
                sectionTitle(category),
                category,
                entries.get(0).timestamp(),
                entries.get(entries.size() - 1).timestamp(),
                durationMs,
                steps
        );
    }

    private JobLogReplayStep toReplayStep(List<JobLogEntry> entries) {
        List<JobLogStructuredRecord> records = entries.stream()
                .map(jobLogParser::toStructuredRecord)
                .toList();
        JobLogStructuredRecord record = records.get(0);
        String operation = records.size() == 1 ? record.operation() : sectionTitle(record.category());
        String target = distinctValues(records.stream().map(NonNullFunctions.from(JobLogStructuredRecord::target)).toList(), "-");
        String source = distinctValues(records.stream().map(NonNullFunctions.from(JobLogStructuredRecord::source)).toList(), "-");
        String detail = records.size() == 1
                ? record.detail()
                : "本阶段包含 " + entries.size() + " 条日志，右侧保留该阶段全部原始日志。";
        return new JobLogReplayStep(
                record.sequence(),
                record.lineNumber(),
                record.timestamp(),
                record.elapsedMs(),
                source,
                record.category(),
                records.size() == 1 ? record.eventType() : "LOG_GROUP",
                operation,
                target,
                aggregateStatus(records),
                detail,
                sectionTitle(record.category()),
                entries.stream()
                        .filter(entry -> entry != null)
                        .map(NonNullFunctions.from(JobLogEntry::raw))
                        .toList()
        );
    }

    private String distinctValues(List<String> values, String fallback) {
        String result = values.stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.joining("、"));
        return StringUtils.defaultIfBlank(result, fallback);
    }

    private String aggregateStatus(List<JobLogStructuredRecord> records) {
        if (records.stream().anyMatch(record -> "失败".equals(record.status()))) {
            return "失败";
        }
        if (records.stream().anyMatch(record -> "警告".equals(record.status()))) {
            return "警告";
        }
        return records.get(records.size() - 1).status();
    }

    private String sectionTitle(String category) {
        return switch (category) {
            case JobLogParser.CATEGORY_OPERATION -> "任务操作";
            case JobLogParser.CATEGORY_DATA_SNAPSHOT -> "数据读取";
            case JobLogParser.CATEGORY_EXECUTION_FLOW -> "执行流程";
            case JobLogParser.CATEGORY_ERROR -> "异常处理";
            default -> "时序记录";
        };
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
        Path path = resolveLogPath(context);
        if (path == null) {
            return "";
        }

        try {
            return jobLogFileReader.read(path, null, null).content();
        } catch (Exception e) {
            log.warn("Read bounded local job log tail failed, instanceId={}, path={}", context.instanceId(), path, e);
            return "";
        }
    }

    private boolean matches(JobLogEntry entry, String keyword, String level, String source, String category) {
        return (keyword.isEmpty()
                        || StringUtils.defaultString(entry.message()).toLowerCase(Locale.ROOT).contains(keyword)
                        || StringUtils.defaultString(entry.raw()).toLowerCase(Locale.ROOT).contains(keyword))
                && (level.isEmpty() || level.equals(entry.level()))
                && (source.isEmpty() || source.equals(entry.source()))
                && (category.isEmpty() || category.equals(entry.category()));
    }

    private JobLogAvailability availability(JobLogContext context, Path path) {
        try {
            if (path != null && Files.isRegularFile(path) && Files.size(path) > 0L) {
                return JobLogAvailability.RETAINED;
            }
        } catch (IOException e) {
            log.debug("Could not inspect local job log state, instanceId={}", context.instanceId(), e);
        }
        return context.hasEngineLogReference() ? JobLogAvailability.PENDING : JobLogAvailability.UNAVAILABLE;
    }

    private Path resolveLogPath(JobLogContext context) {
        if (context == null || StringUtils.isBlank(context.logPath())) {
            return null;
        }
        try {
            return JobLogPathResolver.resolveStoredPath(context.logPath());
        } catch (Exception e) {
            log.warn("Resolve local job log path failed, instanceId={}", context.instanceId(), e);
            return null;
        }
    }

    private void collectEngineLog(JobLogContext context) {
        collectEngineLog(context, false);
    }

    private void collectEngineLog(JobLogContext context, boolean forceTerminal) {
        if (context == null || !context.hasEngineLogReference()) {
            return;
        }
        Path path = resolveLogPath(context);
        if (path == null) {
            return;
        }

        ReentrantLock lock = ENGINE_COLLECTION_LOCKS.computeIfAbsent(path, ignored -> new ReentrantLock());
        lock.lock();
        List<StagedLog> stagedLogs = new ArrayList<>();
        try {
            Path statePath = snapshotStatePath(path);
            Files.createDirectories(statePath.getParent());
            Path lockPath = statePath.resolveSibling(statePath.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(
                    lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                collectEngineLogLocked(context, path, forceTerminal, stagedLogs);
            }
        } catch (Exception e) {
            log.warn("Persist SeaTunnel Engine log failed, instanceId={}, jobMode={}",
                    context.instanceId(), context.jobMode(), e);
        } finally {
            for (StagedLog staged : stagedLogs) {
                deleteStagedLog(staged.path());
            }
            lock.unlock();
        }
    }

    private void collectEngineLogLocked(
            JobLogContext context, Path path, boolean forceTerminal, List<StagedLog> stagedLogs) throws IOException {
        long now = System.currentTimeMillis();
        EngineSnapshotState state = readSnapshotState(path);
        if (context.isTerminal() && state.terminalCollected()) {
            return;
        }
        if (!forceTerminal
                && state.lastAttemptAt() > 0L
                && now - state.lastAttemptAt() < ENGINE_LOG_RETRY_INTERVAL_MS) {
            return;
        }
        if (isSharedLogClient(context.clientId())) {
            collectSharedEngineLogLocked(context, path, state, now, stagedLogs);
            return;
        }

        Map<String, EngineNodeSnapshotState> nodeSnapshots = new LinkedHashMap<>(state.nodeSnapshots());
        List<SeaTunnelRestClient.NodeLogFile> files = List.of();
        boolean listingFailed = false;
        try {
            files = seaTunnelRestClient.jobLogFiles(context.clientId(), context.engineJobId());
        } catch (Exception e) {
            listingFailed = true;
            log.warn("List Engine job log nodes failed, instanceId={}, engineJobId={}",
                    context.instanceId(), context.engineJobId(), e);
        }
        if (files == null) {
            files = List.of();
        }

        boolean allNodesFetched = !listingFailed && !files.isEmpty();
        for (SeaTunnelRestClient.NodeLogFile file : files) {
            try {
                StagedLog staged = stageNodeLog(context, path, file);
                if (staged == null) {
                    allNodesFetched = false;
                } else {
                    stagedLogs.add(staged);
                }
            } catch (Exception e) {
                allNodesFetched = false;
                log.warn("Fetch Engine job log node failed, instanceId={}, node={}",
                        context.instanceId(), file.node(), e);
            }
        }

        boolean hasNodeHistory = nodeSnapshots.keySet().stream()
                .anyMatch(node -> !MASTER_FALLBACK_NODE.equals(node));
        boolean masterFallback = state.masterFallback();
        if (!hasNodeHistory && (!allNodesFetched || stagedLogs.isEmpty())) {
            try {
                StagedLog fallback = stageMasterLog(context, path);
                if (fallback != null) {
                    stagedLogs.add(fallback);
                    masterFallback = true;
                    log.info("Persisted shared Engine job log through configured master endpoint, instanceId={}, engineJobId={}",
                            context.instanceId(), context.engineJobId());
                }
            } catch (Exception e) {
                log.warn("Fetch configured master Engine job log fallback failed, instanceId={}, engineJobId={}",
                        context.instanceId(), context.engineJobId(), e);
            }
        }

        boolean anyLogContent = false;
        for (StagedLog staged : stagedLogs) {
            try {
                EngineNodeSnapshotState previous = nodeSnapshots.get(staged.node());
                EngineNodeSnapshotState next = appendEngineSnapshot(
                        context, path, staged.path(), staged.node(), previous);
                nodeSnapshots.put(staged.node(), next);
                if (!MASTER_FALLBACK_NODE.equals(staged.node())) {
                    anyLogContent = true;
                }
            } catch (Exception e) {
                allNodesFetched = false;
                log.warn("Append Engine job log node failed, instanceId={}, node={}",
                        context.instanceId(), staged.node(), e);
            }
        }

        boolean terminalCollected = context.isTerminal()
                && allNodesFetched
                && anyLogContent
                && files.stream().allMatch(file -> {
                    EngineNodeSnapshotState nodeState = nodeSnapshots.get(file.node());
                    return nodeState != null && nodeState.terminalCollected();
                });
        writeSnapshotState(path, new EngineSnapshotState(
                now,
                terminalCollected ? now : state.lastTerminalAttemptAt(),
                terminalCollected,
                masterFallback,
                nodeSnapshots));
    }

    /**
     * Collects the log of a client whose Engine nodes share one log directory. The configured
     * REST endpoint already serves the merged file, so one download replaces the node listing
     * and the per-node downloads; the file keeps the existing master-fallback cursor identity so
     * a previous archive is continued instead of duplicated.
     */
    private void collectSharedEngineLogLocked(
            JobLogContext context,
            Path path,
            EngineSnapshotState state,
            long now,
            List<StagedLog> stagedLogs) throws IOException {
        Map<String, EngineNodeSnapshotState> nodeSnapshots = new LinkedHashMap<>(state.nodeSnapshots());
        StagedLog staged = null;
        try {
            staged = stageMasterLog(context, path);
        } catch (Exception e) {
            log.warn("Fetch shared Engine job log failed, keep the archived content and cursor, "
                            + "clientId={}, instanceId={}, engineJobId={}, reason={}",
                    context.clientId(), context.instanceId(), context.engineJobId(), e.getMessage(), e);
        }

        if (staged == null) {
            log.warn("Shared Engine job log is not available yet, keep the archived content and cursor, "
                            + "clientId={}, instanceId={}, engineJobId={}",
                    context.clientId(), context.instanceId(), context.engineJobId());
            // Remember the attempt so the next one honours the retry interval, but keep the
            // archived snapshots and the cursor untouched.
            writeSnapshotState(path, new EngineSnapshotState(
                    now,
                    state.lastTerminalAttemptAt(),
                    state.terminalCollected(),
                    state.masterFallback(),
                    nodeSnapshots));
            return;
        }

        stagedLogs.add(staged);
        EngineNodeSnapshotState previous = nodeSnapshots.get(MASTER_FALLBACK_NODE);
        if (previous == null) {
            previous = findMatchingNodeCursor(nodeSnapshots, staged.path());
        }
        EngineNodeSnapshotState next = appendEngineSnapshot(
                context, path, staged.path(), MASTER_FALLBACK_NODE, previous);
        nodeSnapshots.put(MASTER_FALLBACK_NODE, next);
        writeSnapshotState(path, new EngineSnapshotState(
                now,
                next.terminalCollected() ? now : state.lastTerminalAttemptAt(),
                next.terminalCollected(),
                true,
                nodeSnapshots));
        log.debug("Collected shared Engine job log, clientId={}, instanceId={}, engineJobId={}, "
                        + "bytes={}, terminalCollected={}",
                context.clientId(), context.instanceId(), context.engineJobId(),
                next.snapshotBytes(), next.terminalCollected());
    }

    private boolean isSharedLogClient(Long clientId) {
        if (clientId == null || clientId <= 0 || StringUtils.isBlank(sharedLogClientIds)) {
            return false;
        }
        String target = String.valueOf(clientId);
        for (String configuredId : StringUtils.split(sharedLogClientIds, ',')) {
            if (target.equals(StringUtils.trim(configuredId))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Picks the furthest legacy per-node cursor whose archived prefix still matches the shared
     * file. Only a verified prefix may reuse an old offset; a mismatch makes the next append
     * start from the beginning of the shared file.
     */
    private EngineNodeSnapshotState findMatchingNodeCursor(
            Map<String, EngineNodeSnapshotState> nodeSnapshots, Path sharedLog) throws IOException {
        long currentBytes = Files.size(sharedLog);
        EngineNodeSnapshotState matched = null;
        for (Map.Entry<String, EngineNodeSnapshotState> entry : nodeSnapshots.entrySet()) {
            if (MASTER_FALLBACK_NODE.equals(entry.getKey())) {
                continue;
            }
            EngineNodeSnapshotState candidate = entry.getValue();
            if (candidate.snapshotBytes() <= 0L
                    || candidate.snapshotBytes() > currentBytes
                    || StringUtils.isBlank(candidate.snapshotHash())
                    || (matched != null && matched.snapshotBytes() >= candidate.snapshotBytes())) {
                continue;
            }
            if (sha256Prefix(sharedLog, candidate.snapshotBytes()).equals(candidate.snapshotHash())) {
                matched = candidate;
            }
        }
        return matched;
    }

    private StagedLog stageNodeLog(JobLogContext context, Path logPath, SeaTunnelRestClient.NodeLogFile file)
            throws IOException {
        Path raw = createStagingFile(logPath, ".raw");
        Path masked = createStagingFile(logPath, ".masked");
        boolean keepMasked = false;
        try {
            try (OutputStream output = Files.newOutputStream(
                    raw, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                seaTunnelRestClient.writeJobLogFile(context.clientId(), context.engineJobId(), file, output);
            }
            if (Files.size(raw) == 0L) {
                return null;
            }
            maskSensitiveFile(raw, masked);
            if (Files.size(masked) == 0L) {
                return null;
            }
            keepMasked = true;
            return new StagedLog(file.node(), masked);
        } finally {
            deleteStagedLog(raw);
            if (!keepMasked) {
                deleteStagedLog(masked);
            }
        }
    }

    private StagedLog stageMasterLog(JobLogContext context, Path logPath) throws IOException {
        Path raw = createStagingFile(logPath, ".raw");
        Path masked = createStagingFile(logPath, ".masked");
        boolean keepMasked = false;
        try {
            try (OutputStream output = Files.newOutputStream(
                    raw, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                seaTunnelRestClient.writeMasterJobLogFile(context.clientId(), context.engineJobId(), output);
            }
            if (Files.size(raw) == 0L) {
                return null;
            }
            maskSensitiveFile(raw, masked);
            if (Files.size(masked) == 0L) {
                return null;
            }
            keepMasked = true;
            return new StagedLog(MASTER_FALLBACK_NODE, masked);
        } finally {
            deleteStagedLog(raw);
            if (!keepMasked) {
                deleteStagedLog(masked);
            }
        }
    }

    private Path createStagingFile(Path logPath, String suffix) throws IOException {
        Path parent = logPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return Files.createTempFile(parent, ".seatunnel-engine-log-", suffix);
    }

    /** Masks one bounded line at a time; an oversized line is dropped to avoid leaking a credential. */
    private void maskSensitiveFile(Path source, Path destination) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        Files.newInputStream(source), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        Files.newOutputStream(destination, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                        StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder(1024);
            boolean oversized = false;
            int current;
            while ((current = reader.read()) != -1) {
                if (current == '\n') {
                    writeMaskedLine(writer, line, oversized);
                    writer.write('\n');
                    line.setLength(0);
                    oversized = false;
                } else if (!oversized) {
                    if (line.length() >= MAX_MASKABLE_LOG_LINE_CHARS) {
                        line.setLength(0);
                        oversized = true;
                    } else {
                        line.append((char) current);
                    }
                }
            }
            if (oversized || line.length() > 0) {
                writeMaskedLine(writer, line, oversized);
            }
        }
    }

    private void writeMaskedLine(BufferedWriter writer, StringBuilder line, boolean oversized) throws IOException {
        if (oversized) {
            writer.write("[omitted oversized Engine log line]");
        } else {
            writer.write(HoconSensitiveMaskUtil.maskSensitiveText(line.toString()));
        }
    }

    private EngineNodeSnapshotState appendEngineSnapshot(
            JobLogContext context,
            Path logPath,
            Path engineLogPath,
            String node,
            EngineNodeSnapshotState previous) throws IOException {
        long currentBytes = Files.size(engineLogPath);
        if (currentBytes == 0L) {
            return previous;
        }
        String hash = sha256(engineLogPath);
        if (previous != null && previous.snapshotBytes() == currentBytes && hash.equals(previous.snapshotHash())) {
            return new EngineNodeSnapshotState(
                    currentBytes, hash, previous.terminalCollected() || context.isTerminal());
        }
        repairIncompleteEngineSnapshotTail(logPath);
        SnapshotPresence archivedSnapshot = findCompletedSnapshot(logPath, hash, node);
        if (archivedSnapshot.sameNode() || archivedSnapshot.sharedContent()) {
            return new EngineNodeSnapshotState(
                    currentBytes,
                    hash,
                    context.isTerminal() || (previous != null && previous.terminalCollected()));
        }

        long deltaStart = 0L;
        boolean appendDelta = previous != null
                && previous.snapshotBytes() > 0L
                && previous.snapshotBytes() <= currentBytes
                && StringUtils.isNotBlank(previous.snapshotHash())
                && sha256Prefix(engineLogPath, previous.snapshotBytes()).equals(previous.snapshotHash());
        if (appendDelta) {
            deltaStart = previous.snapshotBytes();
        }
        if (deltaStart < currentBytes) {
            String safeNode = node.replaceAll("[^A-Za-z0-9._:-]", "_");
            String header = System.lineSeparator()
                    + ENGINE_SNAPSHOT_MARKER
                    + " sha256=" + hash
                    + " node=" + safeNode
                    + (appendDelta ? " delta=true fromBytes=" + deltaStart : "")
                    + " engineJobId=" + context.engineJobId()
                    + " ==="
                    + System.lineSeparator();
            try (OutputStream output = Files.newOutputStream(
                    logPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                output.write(header.getBytes(StandardCharsets.UTF_8));
                copyRange(engineLogPath, deltaStart, output);
                output.write((System.lineSeparator()
                        + ENGINE_SNAPSHOT_END_MARKER
                        + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            }
        }

        boolean stableTerminalSnapshot = context.isTerminal()
                && previous != null
                && hash.equals(previous.snapshotHash());
        return new EngineNodeSnapshotState(
                currentBytes,
                hash,
                stableTerminalSnapshot || (previous != null && previous.terminalCollected())
        );
    }

    /**
     * Removes a frame suffix left open by a process exit before its END marker.
     * This runs under the collection FileLock and only when the Engine cursor has
     * advanced, so ordinary tail reads do not rescan an unchanged log.
     */
    private void repairIncompleteEngineSnapshotTail(Path logPath) throws IOException {
        if (!Files.isRegularFile(logPath)) {
            return;
        }

        long corruptTailStart = -1L;
        long openFrameStart = -1L;
        long lineStart = 0L;
        byte[] line = new byte[4 * 1024];
        int lineLength = 0;
        boolean oversizedLine = false;
        try (RandomAccessFile file = new RandomAccessFile(logPath.toFile(), "rw")) {
            int current;
            while ((current = file.read()) != -1) {
                if (current == '\n') {
                    if (!oversizedLine) {
                        int contentLength = lineLength;
                        if (contentLength > 0 && line[contentLength - 1] == '\r') {
                            contentLength--;
                        }
                        String text = new String(line, 0, contentLength, StandardCharsets.UTF_8);
                        if (isEngineSnapshotHeaderLine(text)) {
                            if (openFrameStart >= 0 && corruptTailStart < 0) {
                                corruptTailStart = openFrameStart;
                            }
                            openFrameStart = lineStart;
                        } else if (ENGINE_SNAPSHOT_END_MARKER.equals(text)) {
                            openFrameStart = -1L;
                        }
                    }
                    lineStart = file.getFilePointer();
                    lineLength = 0;
                    oversizedLine = false;
                } else if (!oversizedLine) {
                    if (lineLength < line.length) {
                        line[lineLength++] = (byte) current;
                    } else {
                        oversizedLine = true;
                    }
                }
            }

            if (!oversizedLine && lineLength > 0) {
                int contentLength = lineLength;
                if (contentLength > 0 && line[contentLength - 1] == '\r') {
                    contentLength--;
                }
                String text = new String(line, 0, contentLength, StandardCharsets.UTF_8);
                if (isEngineSnapshotHeaderLine(text)) {
                    if (openFrameStart >= 0 && corruptTailStart < 0) {
                        corruptTailStart = openFrameStart;
                    }
                    openFrameStart = lineStart;
                } else if (ENGINE_SNAPSHOT_END_MARKER.equals(text)) {
                    openFrameStart = -1L;
                }
            }

            if (corruptTailStart < 0) {
                corruptTailStart = openFrameStart;
            }
            if (corruptTailStart >= 0 && corruptTailStart < file.length()) {
                long removedBytes = file.length() - corruptTailStart;
                file.setLength(corruptTailStart);
                log.warn("Removed incomplete Engine log snapshot tail, path={}, offset={}, bytes={}",
                        logPath, corruptTailStart, removedBytes);
            }
        }
    }

    private boolean isEngineSnapshotHeaderLine(String line) {
        return line.startsWith(ENGINE_SNAPSHOT_MARKER + " sha256=") && line.endsWith(" ===");
    }

    private void copyRange(Path source, long start, OutputStream output) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            input.skipNBytes(start);
            input.transferTo(output);
        }
    }

    private String sha256(Path source) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream input = Files.newInputStream(source)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256Prefix(Path source, long byteCount) throws IOException {
        MessageDigest digest = newSha256();
        long remaining = byteCount;
        try (InputStream input = Files.newInputStream(source)) {
            byte[] buffer = new byte[8192];
            while (remaining > 0L) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    return "";
                }
                digest.update(buffer, 0, read);
                remaining -= read;
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private SnapshotPresence findCompletedSnapshot(Path path, String hash, String node) throws IOException {
        if (!Files.isRegularFile(path)) {
            return new SnapshotPresence(false, false);
        }
        String hashHeaderPrefix = ENGINE_SNAPSHOT_MARKER + " sha256=" + hash + " ";
        String nodeHeaderPrefix = hashHeaderPrefix
                + "node=" + node.replaceAll("[^A-Za-z0-9._:-]", "_") + " ";
        boolean currentSnapshotHasHash = false;
        boolean currentSnapshotHasNode = false;
        boolean sharedContent = false;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = readBoundedLine(reader, 4 * 1024)) != null) {
                if (line.startsWith(ENGINE_SNAPSHOT_MARKER)) {
                    currentSnapshotHasHash = line.startsWith(hashHeaderPrefix);
                    currentSnapshotHasNode = line.startsWith(nodeHeaderPrefix);
                } else if (line.equals(ENGINE_SNAPSHOT_END_MARKER)) {
                    if (currentSnapshotHasHash) {
                        sharedContent = true;
                    }
                    if (currentSnapshotHasNode) {
                        return new SnapshotPresence(true, true);
                    }
                    currentSnapshotHasHash = false;
                    currentSnapshotHasNode = false;
                }
            }
        }
        return new SnapshotPresence(false, sharedContent);
    }

    private void deleteStagedLog(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Delete temporary Engine log file failed, path={}", path, e);
        }
    }

    private EngineSnapshotState readSnapshotState(Path logPath) {
        Path statePath = snapshotStatePath(logPath);
        if (!Files.isRegularFile(statePath)) {
            return EngineSnapshotState.empty();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(statePath)) {
            properties.load(input);
            if (!"2".equals(properties.getProperty("formatVersion"))) {
                return EngineSnapshotState.empty();
            }
            Map<String, EngineNodeSnapshotState> nodeSnapshots = new LinkedHashMap<>();
            for (String key : properties.stringPropertyNames()) {
                if (!key.startsWith("node.") || !key.endsWith(".name")) {
                    continue;
                }
                String hash = key.substring("node.".length(), key.length() - ".name".length());
                String node = properties.getProperty(key);
                String prefix = "node." + hash + ".";
                if (StringUtils.isBlank(node)) {
                    continue;
                }
                nodeSnapshots.put(node, new EngineNodeSnapshotState(
                        Long.parseLong(properties.getProperty(prefix + "bytes", "0")),
                        properties.getProperty(prefix + "hash", ""),
                        Boolean.parseBoolean(properties.getProperty(prefix + "terminalCollected", "false"))
                ));
            }
            return new EngineSnapshotState(
                    Long.parseLong(properties.getProperty("lastAttemptAt", "0")),
                    Long.parseLong(properties.getProperty("lastTerminalAttemptAt", "0")),
                    Boolean.parseBoolean(properties.getProperty("terminalCollected", "false")),
                    Boolean.parseBoolean(properties.getProperty("masterFallback", "false")),
                    nodeSnapshots
            );
        } catch (Exception e) {
            log.warn("Read Engine log collection state failed, path={}", statePath, e);
            return EngineSnapshotState.empty();
        }
    }

    private void writeSnapshotState(Path logPath, EngineSnapshotState state) throws IOException {
        Path statePath = snapshotStatePath(logPath);
        Path parent = statePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties properties = new Properties();
        properties.setProperty("formatVersion", "2");
        properties.setProperty("terminalCollected", Boolean.toString(state.terminalCollected()));
        properties.setProperty("lastAttemptAt", Long.toString(state.lastAttemptAt()));
        properties.setProperty("lastTerminalAttemptAt", Long.toString(state.lastTerminalAttemptAt()));
        properties.setProperty("masterFallback", Boolean.toString(state.masterFallback()));
        for (Map.Entry<String, EngineNodeSnapshotState> entry : state.nodeSnapshots().entrySet()) {
            String prefix = "node." + sha256(entry.getKey()) + ".";
            properties.setProperty(prefix + "name", entry.getKey());
            properties.setProperty(prefix + "bytes", Long.toString(entry.getValue().snapshotBytes()));
            properties.setProperty(prefix + "hash", entry.getValue().snapshotHash());
            properties.setProperty(prefix + "terminalCollected", Boolean.toString(entry.getValue().terminalCollected()));
        }
        Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            properties.store(output, "SeaTunnel Web Engine log collection cursor");
        }
        try {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path snapshotStatePath(Path logPath) {
        return logPath.resolveSibling(logPath.getFileName() + ".engine-state");
    }

    private record EngineSnapshotState(
            long lastAttemptAt,
            long lastTerminalAttemptAt,
            boolean terminalCollected,
            boolean masterFallback,
            Map<String, EngineNodeSnapshotState> nodeSnapshots) {
        private static EngineSnapshotState empty() {
            return new EngineSnapshotState(0L, 0L, false, false, Map.of());
        }
    }

    private record EngineNodeSnapshotState(long snapshotBytes, String snapshotHash, boolean terminalCollected) {
    }

    private record StagedLog(String node, Path path) {
    }

    private record SnapshotPresence(boolean sameNode, boolean sharedContent) {
    }

    private String sha256(String value) {
        return HexFormat.of().formatHex(newSha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
