package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.common.enums.TimeRange;
import org.apache.seatunnel.web.dao.repository.JobMetricsDao;
import org.apache.seatunnel.web.dao.repository.JobTableMetricsDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobMetricsDao;
import org.apache.seatunnel.web.engine.client.rest.SeaTunnelEngineRestClient;
import org.apache.seatunnel.web.spi.bean.vo.OverviewChartsVO;
import org.apache.seatunnel.web.spi.bean.vo.OverviewSummaryVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobMetricsServiceImplTest {

    @Mock private SeaTunnelEngineRestClient engineRestClient;
    @Mock private JobMetricsDao jobMetricsDao;
    @Mock private JobTableMetricsDao jobTableMetricsDao;
    @Mock private StreamingJobMetricsDao streamingJobMetricsDao;

    @InjectMocks private JobMetricsServiceImpl service;

    @Test
    void summaryUsesJobStatusCountsInsteadOfMarkingEveryTaskSuccessful() {
        when(jobMetricsDao.selectOverviewSummary(anyString(), anyString(), eq("BATCH")))
                .thenReturn(summaryRow());

        OverviewSummaryVO result = service.summary(TimeRange.D7, "BATCH");

        assertEquals(3, result.getTotalTasks());
        assertEquals(1, result.getSuccessTasks());
        assertEquals(1, result.getFailedTasks());
        assertEquals(1, result.getRunningTasks());
        assertEquals(0, result.getStoppedTasks());
        verify(streamingJobMetricsDao, never()).selectOverviewSummary(anyLong(), anyLong());
    }

    @Test
    void summaryMapsStoppedTaskCount() {
        Map<String, Object> row = summaryRow();
        row.put("stoppedTasks", 2L);
        when(jobMetricsDao.selectOverviewSummary(anyString(), anyString(), eq("BATCH")))
                .thenReturn(row);

        OverviewSummaryVO result = service.summary(TimeRange.D7, "BATCH");

        assertEquals(2, result.getStoppedTasks());
    }

    @Test
    void summaryPreservesPrecisionWhenScalingTotals() {
        Map<String, Object> row = summaryRow();
        row.put("totalRecords", 12_000L);
        row.put("totalBytes", 1_441_792L);
        when(jobMetricsDao.selectOverviewSummary(anyString(), anyString(), eq("BATCH")))
                .thenReturn(row);

        OverviewSummaryVO result = service.summary(TimeRange.D7, "BATCH");

        assertEquals(1.2d, result.getTotalRecords());
        assertEquals("万", result.getTotalRecordsUnit());
        assertEquals(1.38d, result.getTotalBytes());
        assertEquals("MB", result.getTotalBytesUnit());
    }

    @Test
    void summaryReadsRealtimeMetricsFromStreamingSnapshots() {
        Map<String, Object> row = summaryRow();
        row.put("avgRecordDelay", 125L);
        when(streamingJobMetricsDao.selectOverviewSummary(anyLong(), anyLong())).thenReturn(row);

        OverviewSummaryVO result = service.summary(TimeRange.H24, "STREAM");

        assertEquals(350L, result.getTotalRecords());
        assertEquals(3, result.getTotalTasks());
        assertEquals(1, result.getRunningTasks());
        assertEquals(0, result.getStoppedTasks());
        assertEquals(125, result.getAvgRecordDelay());
        verify(jobMetricsDao, never()).selectOverviewSummary(anyString(), anyString(), anyString());
    }

    @Test
    void realtimeChartsReturnTheStreamingWriteSeries() {
        Map<String, Object> trend = new HashMap<>();
        trend.put("date", "2026-10-01");
        trend.put("recordsValue", 120L);
        trend.put("bytesValue", 4096L);
        trend.put("recordsSpeed", new BigDecimal("0.75"));
        trend.put("bytesSpeed", new BigDecimal("512.125"));
        when(streamingJobMetricsDao.selectOverviewTrend(anyLong(), anyLong(), eq("DAY")))
                .thenReturn(List.of(trend));

        OverviewChartsVO result = service.charts(TimeRange.D7, "STREAM");

        assertEquals("2026-10-01", result.getRecordsTrend().get(0).getDate());
        assertEquals(120d, result.getRecordsTrend().get(0).getValue());
        assertEquals(4d, result.getBytesTrend().get(0).getValue());
        assertEquals(0.75d, result.getRecordsSpeedTrend().get(0).getValue(), 0.001d);
        assertEquals(512.13d, result.getBytesSpeedTrend().get(0).getValue(), 0.001d);
        verify(jobMetricsDao, never()).selectRecordsTrend(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void chartsUseTheRangeTotalToChooseTrendUnits() {
        when(jobMetricsDao.selectRecordsTrend(anyString(), anyString(), eq("BATCH"), eq("DAY")))
                .thenReturn(List.of(trendRow("2026-09-30", 6_000L), trendRow("2026-10-01", 6_000L)));
        when(jobMetricsDao.selectBytesTrend(anyString(), anyString(), eq("BATCH"), eq("DAY")))
                .thenReturn(List.of(trendRow("2026-09-30", 720_896L), trendRow("2026-10-01", 720_896L)));
        when(jobMetricsDao.selectRecordsSpeedTrend(anyString(), anyString(), eq("BATCH"), eq("DAY")))
                .thenReturn(List.of(trendRow("2026-09-30", 10L)));
        when(jobMetricsDao.selectBytesSpeedTrend(anyString(), anyString(), eq("BATCH"), eq("DAY")))
                .thenReturn(List.of(trendRow("2026-09-30", 1_024L)));

        OverviewChartsVO result = service.charts(TimeRange.D7, "BATCH");

        assertEquals(0.6d, result.getRecordsTrend().get(0).getValue());
        assertEquals(0.6d, result.getRecordsTrend().get(1).getValue());
        assertEquals(0.69d, result.getBytesTrend().get(0).getValue());
        assertEquals(0.69d, result.getBytesTrend().get(1).getValue());
    }

    private static Map<String, Object> trendRow(String date, long value) {
        Map<String, Object> row = new HashMap<>();
        row.put("date", date);
        row.put("value", value);
        return row;
    }

    private static Map<String, Object> summaryRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("totalRecords", 350L);
        row.put("totalBytes", 8192L);
        row.put("totalTasks", 3L);
        row.put("successTasks", 1L);
        row.put("failedTasks", 1L);
        row.put("runningTasks", 1L);
        row.put("stoppedTasks", 0L);
        return row;
    }
}
