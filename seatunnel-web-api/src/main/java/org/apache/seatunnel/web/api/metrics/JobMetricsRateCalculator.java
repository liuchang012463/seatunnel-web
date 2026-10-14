package org.apache.seatunnel.web.api.metrics;

import org.apache.seatunnel.web.api.modal.ParsedJobMetrics;
import org.apache.seatunnel.web.dao.entity.JobMetrics;
import org.apache.seatunnel.web.dao.entity.JobTableMetrics;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates average row throughput for a completed job from its final cumulative counts.
 */
public final class JobMetricsRateCalculator {

    private static final BigDecimal MILLIS_PER_SECOND = BigDecimal.valueOf(1_000L);
    private static final int QPS_SCALE = 4;

    private JobMetricsRateCalculator() {
    }

    public static void applyAverageQps(ParsedJobMetrics metrics, long runtimeMillis) {
        if (metrics == null || runtimeMillis <= 0) {
            return;
        }

        if (metrics.getPipelineMetrics() != null) {
            for (JobMetrics item : metrics.getPipelineMetrics().values()) {
                if (item != null) {
                    item.setReadQps(calculateAverageQps(item.getReadRowCount(), runtimeMillis));
                    item.setWriteQps(calculateAverageQps(item.getWriteRowCount(), runtimeMillis));
                }
            }
        }

        if (metrics.getTableMetrics() != null) {
            for (JobTableMetrics item : metrics.getTableMetrics()) {
                if (item != null) {
                    item.setReadQps(calculateAverageQps(item.getReadRowCount(), runtimeMillis));
                    item.setWriteQps(calculateAverageQps(item.getWriteRowCount(), runtimeMillis));
                }
            }
        }
    }

    public static BigDecimal calculateAverageQps(Long rowCount, long runtimeMillis) {
        if (runtimeMillis <= 0) {
            return BigDecimal.ZERO.setScale(QPS_SCALE, RoundingMode.HALF_UP);
        }

        long count = rowCount == null ? 0L : Math.max(rowCount, 0L);
        return BigDecimal.valueOf(count)
                .multiply(MILLIS_PER_SECOND)
                .divide(BigDecimal.valueOf(runtimeMillis), QPS_SCALE, RoundingMode.HALF_UP);
    }
}
