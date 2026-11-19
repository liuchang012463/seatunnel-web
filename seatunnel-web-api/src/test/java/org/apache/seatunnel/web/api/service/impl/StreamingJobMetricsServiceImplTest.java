package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.common.enums.JobStatus;
import org.apache.seatunnel.web.api.metrics.fetch.EngineJobInfo;
import org.apache.seatunnel.web.api.metrics.streaming.parser.DefaultStreamingJobInfoMetricsParser;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.dao.entity.StreamingJobMetricsCurrent;
import org.apache.seatunnel.web.dao.entity.StreamingJobTableMetricsCurrent;
import org.apache.seatunnel.web.dao.repository.StreamingJobInstanceDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobMetricsCurrentDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobMetricsDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobTableMetricsCurrentDao;
import org.apache.seatunnel.web.spi.bean.vo.StreamingMetricsSnapshotVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamingJobMetricsServiceImplTest {

    @Mock
    private StreamingJobMetricsCurrentDao streamingJobMetricsCurrentDao;

    @Mock
    private StreamingJobTableMetricsCurrentDao streamingJobTableMetricsCurrentDao;

    @Mock
    private StreamingJobMetricsDao streamingJobMetricsDao;

    @Mock
    private StreamingJobInstanceDao streamingJobInstanceDao;

    @InjectMocks
    private StreamingJobMetricsServiceImpl service;

    @Test
    void terminalSnapshotUsesAverageByteRateAndMarksMetricsAvailable() {
        StreamingJobMetricsCurrent current = new StreamingJobMetricsCurrent();
        current.setJobInstanceId(1L);
        current.setReadBytes(12_000_000L);
        current.setWriteBytes(8_000_000L);
        current.setReadBps(new BigDecimal("9000000"));
        current.setWriteBps(new BigDecimal("7000000"));
        current.setLastCollectTimeMs(3_000L);

        StreamingJobTableMetricsCurrent table = new StreamingJobTableMetricsCurrent();
        table.setReadBytes(4_000_000L);
        table.setWriteBytes(2_000_000L);
        table.setReadBps(new BigDecimal("3000000"));
        table.setWriteBps(new BigDecimal("1000000"));

        StreamingJobInstance instance = StreamingJobInstance.builder()
                .id(1L)
                .jobStatus(JobStatus.FINISHED)
                .startTime(new Date(1_000L))
                .endTime(new Date(3_000L))
                .build();

        when(streamingJobMetricsCurrentDao.selectByInstanceId(1L)).thenReturn(current);
        when(streamingJobTableMetricsCurrentDao.selectByInstanceId(1L)).thenReturn(List.of(table));
        when(streamingJobInstanceDao.queryById(1L)).thenReturn(instance);

        StreamingMetricsSnapshotVO result = service.latest(1L);

        assertTrue(result.getMetricsAvailable());
        assertEquals(0, new BigDecimal("6000000.0000").compareTo(result.getReadBps()));
        assertEquals(0, new BigDecimal("4000000.0000").compareTo(result.getWriteBps()));
        assertEquals(0, new BigDecimal("2000000.0000").compareTo(result.getTableMetrics().get(0).getReadBps()));
        assertEquals(0, new BigDecimal("1000000.0000").compareTo(result.getTableMetrics().get(0).getWriteBps()));
    }

    @Test
    void parserKeepsEngineRateWhenCumulativeBytesAreOmitted() {
        EngineJobInfo engineJobInfo = new EngineJobInfo();
        engineJobInfo.setRawMetrics(java.util.Map.of("readBps", 1_234.5d, "writeBps", 987.6d));

        var parsed = new DefaultStreamingJobInfoMetricsParser().parse(engineJobInfo);
        var pipeline = parsed.getPipelineMetrics().get(0);

        assertNull(pipeline.getReadBytes());
        assertNull(pipeline.getWriteBytes());
        assertEquals(0, new BigDecimal("1234.5").compareTo(pipeline.getReadBps()));
        assertEquals(0, new BigDecimal("987.6").compareTo(pipeline.getWriteBps()));
    }

    @Test
    void terminalSnapshotDoesNotReplaceEngineRateWhenStoredByteCounterIsZero() {
        StreamingJobMetricsCurrent current = new StreamingJobMetricsCurrent();
        current.setJobInstanceId(3L);
        current.setReadBytes(0L);
        current.setWriteBytes(0L);
        current.setReadBps(new BigDecimal("1234.5"));
        current.setWriteBps(new BigDecimal("987.6"));
        current.setLastCollectTimeMs(3_000L);

        StreamingJobInstance instance = StreamingJobInstance.builder()
                .id(3L)
                .jobStatus(JobStatus.FINISHED)
                .startTime(new Date(1_000L))
                .endTime(new Date(3_000L))
                .build();
        when(streamingJobMetricsCurrentDao.selectByInstanceId(3L)).thenReturn(current);
        when(streamingJobTableMetricsCurrentDao.selectByInstanceId(3L)).thenReturn(List.of());
        when(streamingJobInstanceDao.queryById(3L)).thenReturn(instance);

        StreamingMetricsSnapshotVO result = service.latest(3L);

        assertEquals(0, new BigDecimal("1234.5").compareTo(result.getReadBps()));
        assertEquals(0, new BigDecimal("987.6").compareTo(result.getWriteBps()));
    }

    @Test
    void absentSnapshotIsMarkedUnavailable() {
        when(streamingJobMetricsCurrentDao.selectByInstanceId(2L)).thenReturn(null);
        when(streamingJobTableMetricsCurrentDao.selectByInstanceId(2L)).thenReturn(List.of());

        StreamingMetricsSnapshotVO result = service.latest(2L);

        assertFalse(result.getMetricsAvailable());
    }
}
