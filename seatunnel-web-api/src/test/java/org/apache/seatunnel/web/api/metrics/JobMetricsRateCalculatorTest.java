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
    void calculatesAverageRatesForPipelineAndTableMetrics() {
        JobMetrics pipeline = new JobMetrics();
        pipeline.setReadRowCount(10L);
        pipeline.setWriteRowCount(8L);
        pipeline.setReadBytes(12_000L);
        pipeline.setWriteBytes(8_000L);
        pipeline.setReadQps(BigDecimal.ZERO);
        pipeline.setWriteQps(BigDecimal.ZERO);

        JobTableMetrics table = new JobTableMetrics();
        table.setReadRowCount(10L);
        table.setWriteRowCount(8L);
        table.setReadBytes(12_000L);
        table.setWriteBytes(8_000L);
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
        assertEquals(0, new BigDecimal("6000.0000").compareTo(pipeline.getReadBps()));
        assertEquals(0, new BigDecimal("4000.0000").compareTo(pipeline.getWriteBps()));
        assertEquals(0, new BigDecimal("6000.0000").compareTo(table.getReadBps()));
        assertEquals(0, new BigDecimal("4000.0000").compareTo(table.getWriteBps()));
    }

    @Test
    void leavesMetricsUntouchedWhenRuntimeIsUnavailable() {
        JobMetrics pipeline = new JobMetrics();
        pipeline.setReadRowCount(10L);
        pipeline.setReadQps(new BigDecimal("3.25"));
        pipeline.setReadBps(new BigDecimal("4.5"));

        ParsedJobMetrics parsed = new ParsedJobMetrics();
        parsed.setPipelineMetrics(Map.of(0, pipeline));

        JobMetricsRateCalculator.applyAverageQps(parsed, 0L);

        assertEquals(0, new BigDecimal("3.25").compareTo(pipeline.getReadQps()));
        assertEquals(0, new BigDecimal("4.5").compareTo(pipeline.getReadBps()));
    }

    @Test
    void preservesEngineByteRateWhenCumulativeBytesAreMissing() {
        JobMetrics pipeline = new JobMetrics();
        pipeline.setReadBps(new BigDecimal("1234.5"));

        JobTableMetrics table = new JobTableMetrics();
        table.setWriteBps(new BigDecimal("987.6"));

        ParsedJobMetrics parsed = new ParsedJobMetrics();
        parsed.setPipelineMetrics(Map.of(0, pipeline));
        parsed.setTableMetrics(List.of(table));

        JobMetricsRateCalculator.applyAverageRates(parsed, 2_000L);

        assertEquals(0, new BigDecimal("1234.5").compareTo(pipeline.getReadBps()));
        assertEquals(0, new BigDecimal("987.6").compareTo(table.getWriteBps()));
    }
}
