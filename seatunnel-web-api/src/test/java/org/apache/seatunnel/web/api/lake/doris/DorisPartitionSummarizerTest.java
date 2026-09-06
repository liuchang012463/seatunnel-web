package org.apache.seatunnel.web.api.lake.doris;

import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DorisPartitionSummarizerTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void dayPartitionsAreClassifiedAndNamesAreStable() {
        DorisPartitionSummary summary = DorisPartitionSummarizer.summarize(
                LakePartitionGranularity.DAY,
                List.of(
                        partition("p_current", "2026-09-15", "2026-09-16"),
                        partition("z_later_old", "2026-09-11", "2026-09-12"),
                        partition("p_old", "2026-09-10", "2026-09-11"),
                        partition("p_future", "2026-09-16", "2026-09-17"),
                        partition("p_bad", "not-a-date", "2026-09-18"),
                        new DorisPartitionMetadata("p_reversed", null, null, null,
                                "2026-09-20", "2026-09-19")), CLOCK);

        assertEquals(6, summary.total());
        assertEquals(2, summary.historical());
        assertEquals(1, summary.current());
        assertEquals(1, summary.future());
        assertEquals(2, summary.unknown());
        assertEquals(List.of("p_bad", "p_current", "p_future", "p_old", "p_reversed", "z_later_old"),
                summary.partitionNames());
        assertEquals(List.of("p_old", "z_later_old"), summary.historicalPartitionNames());
        assertEquals(List.of("p_current"), summary.currentPartitionNames());
        assertEquals(List.of("p_future"), summary.futurePartitionNames());
        assertEquals(List.of("p_bad", "p_reversed"), summary.unknownPartitionNames());
        assertEquals(NOW, summary.observedAt());
    }

    @Test
    void monthAndYearBoundariesUseTheirNaturalCalendarForms() {
        DorisPartitionSummary month = DorisPartitionSummarizer.summarize(
                LakePartitionGranularity.MONTH,
                List.of(
                        partition("m_old", "2026-07", "2026-08"),
                        partition("m_current", "2026-09", "2026-10"),
                        partition("m_future", "2026-10", "2026-11")), CLOCK);
        DorisPartitionSummary year = DorisPartitionSummarizer.summarize(
                LakePartitionGranularity.YEAR,
                List.of(
                        partition("y_old", "2024", "2025"),
                        partition("y_current", "2026", "2027"),
                        partition("y_future", "2027", "2028")), CLOCK);

        assertEquals(List.of(1, 1, 1), List.of(month.historical(), month.current(), month.future()));
        assertEquals(List.of(1, 1, 1), List.of(year.historical(), year.current(), year.future()));
    }

    @Test
    void rangeTextIsUsedWithoutGuessingMalformedOrUnboundedBounds() {
        DorisPartitionSummary summary = DorisPartitionSummarizer.summarize(
                LakePartitionGranularity.DAY,
                List.of(
                        new DorisPartitionMetadata("p_old", "NORMAL", "`event_time`",
                                "[\"2026-09-01 00:00:00\", \"2026-09-02 00:00:00\")", null, null),
                        new DorisPartitionMetadata("p_unknown", "NORMAL", "`event_time`",
                                "[MINVALUE, MAXVALUE)", null, null),
                        new DorisPartitionMetadata("p_missing", "NORMAL", "`event_time`", null,
                                null, null)), CLOCK);

        assertEquals(1, summary.historical());
        assertEquals(0, summary.current());
        assertEquals(0, summary.future());
        assertEquals(2, summary.unknown());
    }

    @Test
    void parsesTheDoris412TypedSingleKeyRangeFixture() {
        String oldRange = "[types: [DATETIMEV2]; keys: [2026-06-01 00:00:00]; "
                + "..types: [DATETIMEV2]; keys: [2026-07-01 00:00:00]; )";
        String currentRange = "[types: [DATETIMEV2]; keys: [2026-09-01 00:00:00]; "
                + "..types: [DATETIMEV2]; keys: [2026-10-01 00:00:00]; )";

        DorisPartitionSummary summary = DorisPartitionSummarizer.summarize(
                LakePartitionGranularity.DAY,
                List.of(
                        new DorisPartitionMetadata("p_old", "NORMAL", "`event_time`", oldRange, null, null),
                        new DorisPartitionMetadata("p_current", "NORMAL", "`event_time`", currentRange, null, null)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(1, summary.historical());
        assertEquals(1, summary.current());
        assertEquals(List.of("p_old"), summary.historicalPartitionNames());
        assertEquals(List.of("p_current"), summary.currentPartitionNames());
    }

    @Test
    void rejectsTypedRangesWithMultipleOrNestedKeys() {
        String multiKey = "[types: [DATETIMEV2, DATE]; keys: [2026-09-01, 1]; "
                + "..types: [DATETIMEV2, DATE]; keys: [2026-10-01, 2]; )";
        DorisPartitionSummary summary = DorisPartitionSummarizer.summarize(
                LakePartitionGranularity.DAY,
                List.of(new DorisPartitionMetadata("p_multi", null, null, multiKey, null, null)), CLOCK);

        assertEquals(1, summary.unknown());
    }

    @Test
    void defaultClockUsesTheSystemZoneForDorisLocalBoundaries() {
        Clock shanghai = Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"));
        DorisPartitionSummary summary = new DorisPartitionSummarizer(shanghai).summarize(
                LakePartitionGranularity.DAY,
                List.of(partition("p_current", "2026-09-15 19:00:00", "2026-09-16 19:00:00")));

        assertEquals(1, summary.current());
    }

    @Test
    void oneMissingBoundaryRemainsUnknownEvenWhenTheOtherBoundaryIsInThePastOrFuture() {
        DorisPartitionSummary summary = DorisPartitionSummarizer.summarize(
                LakePartitionGranularity.DAY,
                List.of(
                        new DorisPartitionMetadata("p_only_upper", null, null, null, null, "2026-09-01"),
                        new DorisPartitionMetadata("p_only_lower", null, null, null, "2026-10-01", null)), CLOCK);

        assertEquals(2, summary.total());
        assertEquals(0, summary.historical());
        assertEquals(0, summary.future());
        assertEquals(2, summary.unknown());
        assertEquals(List.of("p_only_lower", "p_only_upper"), summary.unknownPartitionNames());
    }

    @Test
    void invalidContractGranularityDoesNotPretendToKnowPartitionState() {
        DorisPartitionSummarizer summarizer = new DorisPartitionSummarizer(CLOCK);
        DorisPartitionSummary summary = summarizer.summarize("WEEK",
                List.of(partition("p", "2026-09-01", "2026-09-02")));

        assertEquals(1, summary.total());
        assertEquals(1, summary.unknown());
        assertEquals(List.of("p"), summary.partitionNames());
    }

    private static DorisPartitionMetadata partition(String name, String lower, String upper) {
        return new DorisPartitionMetadata(name, "NORMAL", "`event_time`", null, lower, upper);
    }
}
