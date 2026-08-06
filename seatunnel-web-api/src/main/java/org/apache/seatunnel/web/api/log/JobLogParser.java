package org.apache.seatunnel.web.api.log;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic v1 parser for Web task logs and Engine snapshot sections.
 * It deliberately keeps the original line so no diagnostic evidence is lost.
 */
@Component
public class JobLogParser {

    public static final String CATEGORY_OPERATION = "OPERATION";
    public static final String CATEGORY_DATA_SNAPSHOT = "DATA_SNAPSHOT";
    public static final String CATEGORY_EXECUTION_FLOW = "EXECUTION_FLOW";
    public static final String CATEGORY_ERROR = "ERROR";
    public static final String CATEGORY_TIMELINE = "TIMELINE";

    private static final String ENGINE_MARKER = "=== SEA TUNNEL ENGINE LOG SNAPSHOT";
    private static final String ENGINE_END_MARKER = "=== END SEA TUNNEL ENGINE LOG SNAPSHOT ===";
    private static final Pattern WEB_LINE = Pattern.compile(
            "^\\[(?<timestamp>[^]]+)]\\s+\\[(?<level>[A-Za-z]+)]\\s*(?<message>.*)$"
    );
    private static final Pattern GENERIC_LINE = Pattern.compile(
            "^(?<timestamp>\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{3})?)\\s+(?:\\[(?<level1>[A-Za-z]+)]|(?<level2>[A-Za-z]+))\\s*[-:]?\\s*(?<message>.*)$"
    );
    private static final Pattern TARGET_ASSIGNMENT = Pattern.compile(
            "(?i)(?:table|index|pipeline|source|sink|job|task|表|索引|任务)\\s*(?:name\\s*)?(?:=|:|：)\\s*([^,;\\s]+)"
    );
    private static final Pattern TARGET_REFERENCE = Pattern.compile(
            "(?i)\\b(?:for|from|to|on)\\s+(?:table|index|pipeline|source|sink|job|task)?\\s*([\\w./:-]+)"
    );
    private static final List<DateTimeFormatter> TIMESTAMP_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
    );

    public List<JobLogEntry> parse(String content) {
        if (StringUtils.isBlank(content)) {
            return List.of();
        }

        List<JobLogEntry> entries = new ArrayList<>();
        String source = "WEB";
        String previousLevel = "INFO";
        LocalDateTime firstTimestamp = null;
        long sequence = 0;
        long lineNumber = 0;
        String[] lines = content.split("\\R", -1);

        for (String rawLine : lines) {
            lineNumber++;
            if (rawLine.contains(ENGINE_MARKER)) {
                source = "ENGINE";
                continue;
            }
            if (rawLine.contains(ENGINE_END_MARKER)) {
                source = "WEB";
                continue;
            }
            if (rawLine.isEmpty() && lineNumber == lines.length) {
                continue;
            }

            ParsedLine parsed = parseLine(rawLine, previousLevel);
            if (parsed.level() != null) {
                previousLevel = parsed.level();
            }

            LocalDateTime timestamp = parseTimestamp(parsed.timestamp());
            if (firstTimestamp == null && timestamp != null) {
                firstTimestamp = timestamp;
            }
            Long elapsedMs = timestamp == null || firstTimestamp == null
                    ? null
                    : java.time.Duration.between(firstTimestamp, timestamp).toMillis();

            String message = StringUtils.defaultIfBlank(parsed.message(), rawLine);
            String level = StringUtils.defaultIfBlank(parsed.level(), previousLevel).toUpperCase(Locale.ROOT);
            Classification classification = classify(message, level);
            entries.add(new JobLogEntry(
                    ++sequence,
                    lineNumber,
                    parsed.timestamp(),
                    level,
                    source,
                    classification.category(),
                    classification.eventType(),
                    message,
                    rawLine,
                    elapsedMs
            ));
        }

        return entries;
    }

    /**
     * Converts one parsed physical line into a normalized observability row.
     * The mapping is deliberately deterministic so the same log always
     * produces the same table and replay labels.
     */
    public JobLogStructuredRecord toStructuredRecord(JobLogEntry entry) {
        String message = StringUtils.defaultString(entry.message()).trim();
        String lower = message.toLowerCase(Locale.ROOT);
        String operation = operation(entry.category(), lower);
        String target = target(message);
        String status = status(entry.level(), lower);
        return new JobLogStructuredRecord(
                entry.sequence(),
                entry.lineNumber(),
                entry.timestamp(),
                entry.elapsedMs(),
                entry.source(),
                entry.category(),
                entry.eventType(),
                operation,
                target,
                status,
                message
        );
    }

    private String operation(String category, String lower) {
        if (CATEGORY_DATA_SNAPSHOT.equals(category)) {
            if (containsAny(lower, "read", "scan", "fetch", "query", "pull", "读取", "扫描", "拉取")) {
                return "读取数据";
            }
            if (containsAny(lower, "write", "sink", "insert", "append", "写入", "落盘")) {
                return "写入数据";
            }
            return "记录数据快照";
        }
        if (CATEGORY_EXECUTION_FLOW.equals(category)) {
            if (containsAny(lower, "finished", "complete", "completed", "成功", "完成")) {
                return "完成执行";
            }
            if (containsAny(lower, "running", "scheduled", "pending", "启动", "运行", "调度")) {
                return "推进执行";
            }
            return "更新执行状态";
        }
        if (CATEGORY_ERROR.equals(category)) {
            return "记录异常";
        }
        if (containsAny(lower, "submit", "submitted", "提交")) {
            return "提交任务";
        }
        if (containsAny(lower, "config", "配置")) {
            return "加载配置";
        }
        if (containsAny(lower, "checkpoint", "检查点")) {
            return "处理检查点";
        }
        if (containsAny(lower, "savepoint", "保存点")) {
            return "处理保存点";
        }
        if (containsAny(lower, "pause", "stop", "cancel", "暂停", "终止", "取消")) {
            return "控制任务";
        }
        if (containsAny(lower, "connect", "connection", "连接")) {
            return "建立连接";
        }
        if (containsAny(lower, "create", "created", "init", "initialize", "初始化")) {
            return "初始化任务";
        }
        return CATEGORY_TIMELINE.equals(category) ? "记录时序" : "记录操作";
    }

    private String target(String message) {
        Matcher assignment = TARGET_ASSIGNMENT.matcher(message);
        if (assignment.find()) {
            return assignment.group(1);
        }
        Matcher reference = TARGET_REFERENCE.matcher(message);
        if (reference.find()) {
            return reference.group(1);
        }
        return "-";
    }

    private String status(String level, String lower) {
        if ("ERROR".equalsIgnoreCase(level) || containsAny(lower, "failed", "failure", "exception", "失败", "异常")) {
            return "失败";
        }
        if (containsAny(lower, "finished", "complete", "completed", "success", "成功", "完成")) {
            return "完成";
        }
        if ("WARN".equalsIgnoreCase(level) || containsAny(lower, "retry", "warning", "警告", "重试")) {
            return "警告";
        }
        if (containsAny(lower, "submit", "submitted", "created", "initialized", "running", "scheduled", "pending", "进行中", "运行", "调度")) {
            return "进行中";
        }
        return "记录";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private ParsedLine parseLine(String rawLine, String previousLevel) {
        Matcher webMatcher = WEB_LINE.matcher(rawLine);
        if (webMatcher.matches()) {
            return new ParsedLine(
                    webMatcher.group("timestamp"),
                    webMatcher.group("level"),
                    webMatcher.group("message")
            );
        }

        Matcher genericMatcher = GENERIC_LINE.matcher(rawLine);
        if (genericMatcher.matches()) {
            String level = genericMatcher.group("level1");
            if (level == null) {
                level = genericMatcher.group("level2");
            }
            return new ParsedLine(
                    genericMatcher.group("timestamp"),
                    level,
                    genericMatcher.group("message")
            );
        }

        return new ParsedLine(null, previousLevel, rawLine);
    }

    private LocalDateTime parseTimestamp(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        for (DateTimeFormatter formatter : TIMESTAMP_FORMATTERS) {
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
                // Try the next supported format.
            }
        }
        return null;
    }

    private Classification classify(String message, String level) {
        String lower = message.toLowerCase(Locale.ROOT);

        if ("ERROR".equals(level)
                || lower.contains("exception")
                || lower.contains(" stack trace")
                || lower.contains("failed")
                || lower.contains("failure")
                || lower.contains("error")) {
            return new Classification(CATEGORY_ERROR, "ERROR");
        }

        if (lower.contains("metric")
                || lower.contains("snapshot")
                || lower.contains("rows")
                || lower.contains("records")
                || lower.contains("bytes")
                || lower.contains("throughput")
                || lower.contains("pipeline")
                || lower.contains("table")) {
            return new Classification("DATA_SNAPSHOT", "DATA");
        }

        if (lower.contains("submit")
                || lower.contains("config")
                || lower.contains("monitor registered")
                || lower.contains("watcher registered")
                || lower.contains("pause")
                || lower.contains("stop")
                || lower.contains("checkpoint")
                || lower.contains("savepoint")
                || lower.contains("created")) {
            return new Classification("OPERATION", "OPERATION");
        }

        if (lower.contains("running")
                || lower.contains("scheduled")
                || lower.contains("pending")
                || lower.contains("finished")
                || lower.contains("canceled")
                || lower.contains("cancelled")
                || lower.contains("status")) {
            return new Classification("EXECUTION_FLOW", "STATUS");
        }

        return new Classification("TIMELINE", "LOG");
    }

    private record ParsedLine(String timestamp, String level, String message) {
    }

    private record Classification(String category, String eventType) {
    }
}
