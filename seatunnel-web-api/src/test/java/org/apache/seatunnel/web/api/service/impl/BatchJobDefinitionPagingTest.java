package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.service.application.JobScheduleApplicationService;
import org.apache.seatunnel.web.common.enums.TaskExecutionMode;
import org.apache.seatunnel.web.common.enums.ScheduleStatusEnum;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.spi.bean.dto.BatchJobDefinitionQueryDTO;
import org.apache.seatunnel.web.spi.bean.vo.BatchJobDefinitionVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BatchJobDefinitionPagingTest {
    @Test
    void normalizesJoinedScheduleWithoutPerRowQueries() {
        var dao = mock(JobDefinitionDao.class);
        var schedules = mock(JobScheduleApplicationService.class);
        var service = new BatchJobDefinitionServiceImpl();
        ReflectionTestUtils.setField(service, "jobDefinitionDao", dao);
        ReflectionTestUtils.setField(service, "scheduleApplicationService", schedules);
        var query = new BatchJobDefinitionQueryDTO();
        query.setPageNo(1);
        query.setPageSize(10);
        var manual = new BatchJobDefinitionVO();
        manual.setExecutionMode(TaskExecutionMode.MANUAL);
        manual.setCronExpression("0 0 * * * ?");
        manual.setNextScheduleTime(new Date());
        var auto = new BatchJobDefinitionVO();
        auto.setCronExpression("0 0 * * * ?");
        when(dao.selectPageWithLatestInstance(query, 0, 10)).thenReturn(List.of(manual, auto));
        when(dao.count(query)).thenReturn(2L);

        service.paging(query);

        assertEquals(TaskExecutionMode.MANUAL, manual.getExecutionMode());
        assertNull(manual.getCronExpression());
        assertNull(manual.getNextScheduleTime());
        assertEquals(ScheduleStatusEnum.PAUSE, manual.getScheduleStatus());
        assertEquals(TaskExecutionMode.AUTO, auto.getExecutionMode());
        assertEquals("0 0 * * * ?", auto.getCronExpression());
        verifyNoInteractions(schedules);
    }
}
