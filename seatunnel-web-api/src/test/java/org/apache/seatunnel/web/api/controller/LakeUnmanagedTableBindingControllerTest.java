package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.lake.table.LakeManagedTableVO;
import org.apache.seatunnel.web.api.service.LakeUnmanagedTableBindingService;
import org.apache.seatunnel.web.spi.bean.dto.LakeUnmanagedTableBindDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeUnmanagedTableBindingControllerTest {

    @Test
    void exposesOnlyExplicitBindAndUnbindCommands() {
        LakeUnmanagedTableBindingService service = mock(LakeUnmanagedTableBindingService.class);
        LakeUnmanagedTableBindDTO request = new LakeUnmanagedTableBindDTO();
        LakeManagedTableVO bound = new LakeManagedTableVO();
        LakeManagedTableVO unbound = new LakeManagedTableVO();
        when(service.bind(request)).thenReturn(bound);
        when(service.unbind(22L)).thenReturn(unbound);
        LakeUnmanagedTableBindingController controller =
                new LakeUnmanagedTableBindingController(service);

        assertEquals(bound, controller.bind(request).getData());
        assertEquals(unbound, controller.unbind(22L).getData());
        verify(service).bind(request);
        verify(service).unbind(22L);
    }
}
