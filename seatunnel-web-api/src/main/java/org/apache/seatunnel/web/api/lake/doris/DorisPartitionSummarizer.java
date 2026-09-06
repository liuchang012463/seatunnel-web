package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.api.lake.contract.TargetContract;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Pure, read-only classifier for bounded Doris partition observations. */
public final class DorisPartitionSummarizer {

    private static final DateTimeFormatter SPACE_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter()
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private static final DateTimeFormatter COMPACT_DAY = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private static final DateTimeFormatter COMPACT_MONTH = DateTimeFormatter.ofPattern("uuuuMM")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    private static final DateTimeFormatter YEAR_FORMAT = DateTimeFormatter.ofPattern("uuuu")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);

    private final Clock clock;

    public DorisPartitionSummarizer() {
        this(Clock.systemDefaultZone());
    }

    public DorisPartitionSummarizer(Clock clock) {
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public DorisPartitionSummary summarize(LakePartitionGranularity granularity,
                                            List<DorisPartitionMetadata> partitions) {
        return summarize(granularity, partitions, clock);
    }

    /** Convenience overload for callers that carry a contract's string value. */
    public DorisPartitionSummary summarize(String granularity,
                                            List<DorisPartitionMetadata> partitions) {
        LakePartitionGranularity parsed = parseGranularity(granularity);
        return parsed == null
                ? unknownSummary(partitions, clock.instant())
                : summarize(parsed, partitions, clock);
    }

    public DorisPartitionSummary summarize(TargetContract contract,
                                            List<DorisPartitionMetadata> partitions) {
        return summarize(contractGranularity(contract), partitions, clock);
    }

    /**
     * Classifies half-open ranges against the supplied observation clock.  A
     * row is historical when its upper bound is at or before now, current when
     * it spans now, and future when its lower bound is after now.  Missing,
     * malformed, unbounded, or reversed ranges remain unknown.
     */
    public static DorisPartitionSummary summarize(LakePartitionGranularity granularity,
                                                   List<DorisPartitionMetadata> partitions,
                                                   Clock clock) {
        Objects.requireNonNull(granularity, "granularity");
        Clock effectiveClock = clock == null ? Clock.systemDefaultZone() : clock;
        Instant observedAt = effectiveClock.instant();
        ZoneId zone = effectiveClock.getZone();
        List<DorisPartitionMetadata> values = partitions == null ? List.of() : partitions;
        int historical = 0;
        int current = 0;
        int future = 0;
        int unknown = 0;
        Set<String> names = new TreeSet<>(Comparator.naturalOrder());
        List<NamedPartition> historicalNames = new ArrayList<>();
        Set<String> currentNames = new TreeSet<>();
        Set<String> futureNames = new TreeSet<>();
        Set<String> unknownNames = new TreeSet<>();
        for (DorisPartitionMetadata partition : values) {
            if (partition == null) {
                unknown++;
                continue;
            }
            if (partition.partitionName() != null) {
                names.add(partition.partitionName());
            }
            ParsedBounds bounds = parsedBounds(granularity, partition, zone);
            Classification classification = classify(bounds, observedAt);
            String partitionName = partition.partitionName();
            switch (classification) {
                case HISTORICAL -> {
                    historical++;
                    if (partitionName != null) {
                        historicalNames.add(new NamedPartition(partitionName, bounds.upper()));
                    }
                }
                case CURRENT -> {
                    current++;
                    if (partitionName != null) {
                        currentNames.add(partitionName);
                    }
                }
                case FUTURE -> {
                    future++;
                    if (partitionName != null) {
                        futureNames.add(partitionName);
                    }
                }
                case UNKNOWN -> {
                    unknown++;
                    if (partitionName != null) {
                        unknownNames.add(partitionName);
                    }
                }
            }
        }
        historicalNames.sort(Comparator.comparing(NamedPartition::upper)
                .thenComparing(NamedPartition::name));
        return new DorisPartitionSummary(values.size(), historical, current, future, unknown,
                new ArrayList<>(names), observedAt,
                historicalNames.stream().map(NamedPartition::name).toList(),
                new ArrayList<>(currentNames), new ArrayList<>(futureNames), new ArrayList<>(unknownNames));
    }

    public static DorisPartitionSummary summarize(String granularity,
                                                   List<DorisPartitionMetadata> partitions,
                                                   Clock clock) {
        LakePartitionGranularity parsed = parseGranularity(granularity);
        Instant observedAt = (clock == null ? Clock.systemDefaultZone() : clock).instant();
        return parsed == null ? unknownSummary(partitions, observedAt)
                : summarize(parsed, partitions, clock);
    }

    public static DorisPartitionSummary summarize(TargetContract contract,
                                                   List<DorisPartitionMetadata> partitions,
                                                   Clock clock) {
        return summarize(contractGranularity(contract), partitions, clock);
    }

    /** Alias that makes the pure/static form discoverable to metadata callers. */
    public static DorisPartitionSummary summarizePartitions(LakePartitionGranularity granularity,
                                                             List<DorisPartitionMetadata> partitions,
                                                             Clock clock) {
        return summarize(granularity, partitions, clock);
    }

    private static ParsedBounds parsedBounds(LakePartitionGranularity granularity,
                                             DorisPartitionMetadata partition,
                                             ZoneId zone) {
        RangeTexts range = rangeTexts(partition.range());
        String lowerText = partition.lowerBound() != null
                ? partition.lowerBound() : range == null ? null : range.lower();
        String upperText = partition.upperBound() != null
                ? partition.upperBound() : range == null ? null : range.upper();
        Instant lower = parseBoundary(lowerText, granularity, zone);
        Instant upper = parseBoundary(upperText, granularity, zone);
        return new ParsedBounds(lower, upper);
    }

    private static Classification classify(ParsedBounds bounds, Instant observedAt) {
        Instant lower = bounds.lower();
        Instant upper = bounds.upper();
        // Both bounds are required.  A missing/unbounded side is not enough
        // evidence to say that a retention window is historical or future.
        if (lower == null || upper == null || !lower.isBefore(upper)) {
            return Classification.UNKNOWN;
        }
        if (!upper.isAfter(observedAt)) {
            return Classification.HISTORICAL;
        }
        if (lower.isAfter(observedAt)) {
            return Classification.FUTURE;
        }
        return Classification.CURRENT;
    }

    private static RangeTexts rangeTexts(String range) {
        if (range == null || range.isBlank()) {
            return null;
        }
        String value = range.trim();
        int comma = commaOutsideQuotes(value);
        if (comma < 0) {
            return dorisRangeTexts(value);
        }
        String lower = stripRangeMarker(value.substring(0, comma), true);
        String upper = stripRangeMarker(value.substring(comma + 1), false);
        if (lower == null && upper == null) {
            return null;
        }
        return new RangeTexts(lower, upper);
    }

    /**
     * Doris 4.1.2 renders SHOW PARTITIONS ranges as two typed key clauses,
     * for example: {@code [types: [DATETIMEV2]; keys: [2026-06-01 00:00:00];
     * ..types: [DATETIMEV2]; keys: [2026-07-01 00:00:00]; )}.  Only the
     * single-key form is accepted; nested or multi-key output is deliberately
     * left unknown rather than guessed.
     */
    private static RangeTexts dorisRangeTexts(String range) {
        List<String> keys = new ArrayList<>();
        int cursor = 0;
        while (cursor < range.length()) {
            int marker = indexOfWordIgnoreCase(range, "keys", cursor);
            if (marker < 0) {
                break;
            }
            int colon = skipWhitespace(range, marker + "keys".length());
            if (colon >= range.length() || range.charAt(colon) != ':') {
                return null;
            }
            int open = skipWhitespace(range, colon + 1);
            if (open >= range.length() || range.charAt(open) != '[') {
                return null;
            }
            int close = closeBracket(range, open);
            if (close < 0) {
                return null;
            }
            String body = range.substring(open + 1, close).trim();
            if (body.isEmpty() || body.indexOf(',') >= 0
                    || body.indexOf('[') >= 0 || body.indexOf(']') >= 0) {
                return null;
            }
            keys.add(stripQuotes(body));
            cursor = close + 1;
        }
        return keys.size() == 2 && keys.stream().allMatch(value -> value != null && !value.isBlank())
                ? new RangeTexts(keys.get(0), keys.get(1)) : null;
    }

    private static int indexOfWordIgnoreCase(String value, String word, int fromIndex) {
        int cursor = fromIndex;
        while (cursor >= 0 && cursor < value.length()) {
            int index = value.toLowerCase(java.util.Locale.ROOT).indexOf(word, cursor);
            if (index < 0) {
                return -1;
            }
            boolean left = index == 0 || !Character.isLetterOrDigit(value.charAt(index - 1));
            int end = index + word.length();
            boolean right = end >= value.length() || !Character.isLetterOrDigit(value.charAt(end));
            if (left && right) {
                return index;
            }
            cursor = end;
        }
        return -1;
    }

    private static int skipWhitespace(String value, int index) {
        int cursor = index;
        while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static int closeBracket(String value, int open) {
        int depth = 0;
        char quote = 0;
        for (int index = open; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                if (character == quote && (index == 0 || value.charAt(index - 1) != '\\')) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == '[') {
                depth++;
            } else if (character == ']') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static int commaOutsideQuotes(String value) {
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == quote) {
                    quote = 0;
                }
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (character == ',') {
                return index;
            }
        }
        return -1;
    }

    private static String stripRangeMarker(String source, boolean lower) {
        String value = source == null ? null : source.trim();
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (lower && (value.startsWith("[") || value.startsWith("("))) {
            value = value.substring(1).trim();
        }
        if (!lower && (value.endsWith("]") || value.endsWith(")"))) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return stripQuotes(value);
    }

    private static String stripQuotes(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String result = value.trim();
        if (result.length() >= 2) {
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                result = result.substring(1, result.length() - 1).trim();
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static Instant parseBoundary(String value, LakePartitionGranularity granularity,
                                         ZoneId zone) {
        String candidate = stripQuotes(value);
        if (candidate == null || isUnbounded(candidate)) {
            return null;
        }
        try {
            return Instant.parse(candidate);
        } catch (DateTimeParseException ignored) {
            // Try Doris' timezone-less date/datetime forms below.
        }
        try {
            return OffsetDateTime.parse(candidate, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with local forms interpreted in the observation zone.
        }
        String localCandidate = candidate.replace(' ', 'T');
        try {
            return LocalDateTime.parse(localCandidate, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with date-only forms.
        }
        try {
            return LocalDateTime.parse(candidate, SPACE_DATE_TIME).atZone(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with date-only forms.
        }
        try {
            return LocalDate.parse(candidate, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with compact and month/year forms.
        }
        try {
            return LocalDate.parse(candidate.replace('/', '-'), COMPACT_DAY)
                    .atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with compact and month/year forms.
        }
        try {
            return YearMonth.parse(candidate, DateTimeFormatter.ofPattern("uuuu-MM"))
                    .atDay(1).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with compact and year forms.
        }
        try {
            return YearMonth.parse(candidate, COMPACT_MONTH)
                    .atDay(1).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with year form.
        }
        try {
            return Year.parse(candidate, YEAR_FORMAT).atDay(1).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean isUnbounded(String candidate) {
        String normalized = candidate.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("MINVALUE") || normalized.equals("MAXVALUE")
                || normalized.equals("MIN") || normalized.equals("MAX")
                || normalized.equals("NULL") || normalized.equals("-INF")
                || normalized.equals("+INF") || normalized.equals("-INFINITY")
                || normalized.equals("+INFINITY") || normalized.equals("INFINITY");
    }

    private static LakePartitionGranularity parseGranularity(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LakePartitionGranularity.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String contractGranularity(TargetContract contract) {
        if (contract == null || contract.getPartition() == null
                || !Boolean.TRUE.equals(contract.getPartition().getEnabled())) {
            return null;
        }
        return contract.getPartition().getGranularity();
    }

    private static DorisPartitionSummary unknownSummary(List<DorisPartitionMetadata> partitions,
                                                        Instant observedAt) {
        List<DorisPartitionMetadata> values = partitions == null ? List.of() : partitions;
        Set<String> names = new TreeSet<>();
        for (DorisPartitionMetadata partition : values) {
            if (partition != null && partition.partitionName() != null) {
                names.add(partition.partitionName());
            }
        }
        List<String> all = new ArrayList<>(names);
        return new DorisPartitionSummary(values.size(), 0, 0, 0, values.size(),
                all, observedAt, List.of(), List.of(), List.of(), all);
    }

    private enum Classification {
        HISTORICAL,
        CURRENT,
        FUTURE,
        UNKNOWN
    }

    private record RangeTexts(String lower, String upper) {
    }

    private record ParsedBounds(Instant lower, Instant upper) {
    }

    private record NamedPartition(String name, Instant upper) {
    }
}
