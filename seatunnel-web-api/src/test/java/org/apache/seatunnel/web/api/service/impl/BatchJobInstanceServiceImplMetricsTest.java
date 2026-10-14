package org.apache.seatunnel.web.api.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.seatunnel.web.dao.repository.JobInstanceDao;
import org.apache.seatunnel.web.dao.repository.JobTableMetricsDao;
import org.apache.seatunnel.web.spi.bean.dto.SeaTunnelJobInstanceDTO;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.apache.seatunnel.web.spi.bean.vo.JobTableMetricsVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchJobInstanceServiceImplMetricsTest {

    @Mock private JobInstanceDao jobInstanceDao;
    @Mock private JobTableMetricsDao jobTableMetricsDao;

    @InjectMocks private BatchJobInstanceServiceImpl service;

    @Test
    void detailDerivesAverageQpsAndSingleTableMetricsForLegacyCompletedRuns() {
        JobInstanceVO instance = new JobInstanceVO();
        instance.setId(42L);
        instance.setJobStatus("FINISHED");
        instance.setReadRowCount(10L);
        instance.setWriteRowCount(10L);
        instance.setStartTime(new Date(1_000L));
        instance.setEndTime(new Date(3_000L));

        JobTableMetricsVO table = new JobTableMetricsVO();
        table.setSourceTable("default.default.default");
        table.setSinkTable("test.public.target");
        table.setReadRowCount(0L);
        table.setWriteRowCount(0L);
        table.setReadQps(BigDecimal.ZERO);
        table.setWriteQps(BigDecimal.ZERO);

        when(jobInstanceDao.selectDetailById(42L)).thenReturn(instance);
        when(jobTableMetricsDao.selectByInstanceId(42L)).thenReturn(List.of(table));

        JobInstanceVO result = service.selectById(42L);

        assertEquals(0, new BigDecimal("5.0000").compareTo(result.getReadQps()));
        assertEquals(0, new BigDecimal("5.0000").compareTo(result.getWriteQps()));
        assertEquals(10L, result.getTableMetrics().get(0).getReadRowCount());
        assertEquals(10L, result.getTableMetrics().get(0).getWriteRowCount());
        assertEquals(0, new BigDecimal("5.0000")
                .compareTo(result.getTableMetrics().get(0).getReadQps()));
        assertEquals(0, new BigDecimal("5.0000")
                .compareTo(result.getTableMetrics().get(0).getWriteQps()));
    }

    @Test
    void historyPagingDerivesAverageQpsForCompletedRuns() {
        JobInstanceVO instance = new JobInstanceVO();
        instance.setJobStatus("FINISHED");
        instance.setReadRowCount(10L);
        instance.setWriteRowCount(10L);
        instance.setStartTime(new Date(1_000L));
        instance.setEndTime(new Date(3_000L));

        SeaTunnelJobInstanceDTO request = new SeaTunnelJobInstanceDTO();
        Page<JobInstanceVO> page = new Page<>(1, 10);
        page.setRecords(List.of(instance));
        when(jobInstanceDao.pageWithDefinition(request)).thenReturn(page);

        service.paging(request);

        assertEquals(0, new BigDecimal("5.0000").compareTo(instance.getReadQps()));
        assertEquals(0, new BigDecimal("5.0000").compareTo(instance.getWriteQps()));
    }
}
