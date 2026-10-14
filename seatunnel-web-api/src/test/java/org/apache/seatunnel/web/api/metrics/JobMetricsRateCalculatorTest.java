package org.apache.seatunnel.web.api.metrics;

import org.apache.seatunnel.web.api.modal.ParsedJobMetrics;
import org.apache.seatunnel.web.dao.entity.JobMetrics;
import org.apache.seatunnel.web.dao.entity.JobTableMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobMetricsRateCalculatorTest {

    @Test
    void calculatesAverageQpsForPipelineAndTableMetrics() {
        JobMetrics pipeline = new JobMetrics();
        pipeline.setReadRowCount(10L);
        pipeline.setWriteRowCount(8L);
        pipeline.setReadQps(BigDecimal.ZERO);
        pipeline.setWriteQps(BigDecimal.ZERO);

        JobTableMetrics table = new JobTableMetrics();
        table.setReadRowCount(10L);
        table.setWriteRowCount(8L);
        table.setReadQps(BigDecimal.ZERO);
        table.setWriteQps(BigDecimal.ZERO);

        ParsedJobMetrics parsed = new ParsedJobMetrics();
        parsed.setPipelineMetrics(Map.of(0, pipeline));
        parsed.setTableMetrics(List.of(table));

        JobMetricsRateCalculator.applyAverageQps(parsed, 2_000L);

        assertEquals(0, new BigDecimal("5.0000").compareTo(pipeline.getReadQps()));
        assertEquals(0, new BigDecimal("4.0000").compareTo(pipeline.getWriteQps()));
        assertEquals(0, new BigDecimal("5.0000").compareTo(table.getReadQps()));
        assertEquals(0, new BigDecimal("4.0000").compareTo(table.getWriteQps()));
    }

    @Test
    void leavesMetricsUntouchedWhenRuntimeIsUnavailable() {
        JobMetrics pipeline = new JobMetrics();
        pipeline.setReadRowCount(10L);
        pipeline.setReadQps(new BigDecimal("3.25"));

        ParsedJobMetrics parsed = new ParsedJobMetrics();
        parsed.setPipelineMetrics(Map.of(0, pipeline));

        JobMetricsRateCalculator.applyAverageQps(parsed, 0L);

        assertEquals(0, new BigDecimal("3.25").compareTo(pipeline.getReadQps()));
    }
}
