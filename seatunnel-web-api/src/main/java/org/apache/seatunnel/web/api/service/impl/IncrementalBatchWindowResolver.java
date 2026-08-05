package org.apache.seatunnel.web.api.service.impl;

import java.time.LocalDateTime;

/**
 * Calculates one runtime incremental batch window.
 *
 * <p>The first window bootstraps from the configured start value directly to
 * the source database time. Once a control row exists, regular windows are
 * bounded by the resolved schedule interval and the configured safety delay.
 * The result is immutable so a retry can reuse the persisted record without
 * recalculating the window.</p>
 */
final class IncrementalBatchWindowResolver {

    private IncrementalBatchWindowResolver() {
    }

    static Window resolve(boolean bootstrap,
                          LocalDateTime start,
                          LocalDateTime sourceNow,
                          int safetyDelaySeconds,
                          int overlapSeconds,
                          int maxWindowSeconds) {
        LocalDateTime end;
        int effectiveOverlap;
        if (bootstrap) {
            end = sourceNow;
            effectiveOverlap = 0;
        } else {
            LocalDateTime safeEnd = sourceNow.minusSeconds(safetyDelaySeconds);
            end = safeEnd.isAfter(start.plusSeconds(maxWindowSeconds))
                    ? start.plusSeconds(maxWindowSeconds) : safeEnd;
            effectiveOverlap = Math.max(0,
                    Math.min(overlapSeconds, Math.max(0, maxWindowSeconds - 1)));
        }

        if (!end.isAfter(start)) {
            return null;
        }
        return new Window(start, end, start.minusSeconds(effectiveOverlap));
    }

    record Window(LocalDateTime start, LocalDateTime end, LocalDateTime queryStart) {
    }
}
