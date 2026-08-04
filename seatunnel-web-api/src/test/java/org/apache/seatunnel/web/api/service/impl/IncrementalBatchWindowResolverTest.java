package org.apache.seatunnel.web.api.service.impl;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IncrementalBatchWindowResolverTest {

    @Test
    void bootstrapUsesSourceNowAndDoesNotApplySafetyOrOverlap() {
        LocalDateTime start = LocalDateTime.of(1970, 1, 1, 0, 0);
        LocalDateTime sourceNow = LocalDateTime.of(2026, 8, 4, 18, 0, 0);

        IncrementalBatchWindowResolver.Window window = IncrementalBatchWindowResolver.resolve(
                true, start, sourceNow, 120, 60, 300);

        assertEquals(start, window.start());
        assertEquals(sourceNow, window.end());
        assertEquals(start, window.queryStart());
    }

    @Test
    void nextWindowStartsAtCommittedBootstrapEndAndUsesScheduleBounds() {
        LocalDateTime initial = LocalDateTime.of(1970, 1, 1, 0, 0);
        LocalDateTime firstEnd = LocalDateTime.of(2026, 8, 4, 18, 0, 0);
        IncrementalBatchWindowResolver.Window first = IncrementalBatchWindowResolver.resolve(
                true, initial, firstEnd, 0, 0, 300);

        IncrementalBatchWindowResolver.Window next = IncrementalBatchWindowResolver.resolve(
                false, first.end(), firstEnd.plusMinutes(10), 0, 0, 300);

        assertEquals(first.end(), next.start());
        assertEquals(first.end().plusMinutes(5), next.end());
        assertEquals(first.end(), next.queryStart());
    }

    @Test
    void regularWindowPreservesExplicitLegacySafetyAndOverlap() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 4, 18, 0);
        IncrementalBatchWindowResolver.Window window = IncrementalBatchWindowResolver.resolve(
                false, start, start.plusMinutes(10), 120, 60, 300);

        assertEquals(start.plusMinutes(5), window.end());
        assertEquals(start.minusMinutes(1), window.queryStart());
    }

    @Test
    void returnsNoWindowWhenSourceHasNoNewTime() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 4, 18, 0);

        assertNull(IncrementalBatchWindowResolver.resolve(
                false, start, start, 0, 0, 300));
    }
}
