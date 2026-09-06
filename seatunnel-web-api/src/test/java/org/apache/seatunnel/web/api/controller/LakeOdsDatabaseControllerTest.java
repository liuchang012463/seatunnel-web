package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.service.LakeOdsDatabaseService;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.LakeOdsDatabaseVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeOdsDatabaseControllerTest {

    @Test
    void databaseDetailGetOnlyReadsCachedBinding() {
        LakeOdsDatabaseService service = mock(LakeOdsDatabaseService.class);
        LakeOdsDatabaseVO expected = new LakeOdsDatabaseVO();
        expected.setId(41L);
        when(service.detail(41L)).thenReturn(expected);
        LakeOdsDatabaseController controller = new LakeOdsDatabaseController(service);

        Result<LakeOdsDatabaseVO> result = controller.detail(41L);

        assertEquals(expected, result.getData());
        verify(service).detail(41L);
        verify(service, never()).reconcile(41L);
    }
}
