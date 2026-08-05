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

    private static final String ENGINE_MARKER = "=== SEA TUNNEL ENGINE LOG SNAPSHOT";
    private static final String ENGINE_END_MARKER = "=== END SEA TUNNEL ENGINE LOG SNAPSHOT ===";
    private static final Pattern WEB_LINE = Pattern.compile(
            "^\\[(?<timestamp>[^]]+)]\\s+\\[(?<level>[A-Za-z]+)]\\s*(?<message>.*)$"
    );
    private static final Pattern GENERIC_LINE = Pattern.compile(
            "^(?<timestamp>\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{3})?)\\s+(?:\\[(?<level1>[A-Za-z]+)]|(?<level2>[A-Za-z]+))\\s*[-:]?\\s*(?<message>.*)$"
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
            return new Classification("EXECUTION_FLOW", "ERROR");
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
