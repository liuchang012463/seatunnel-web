package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.lake.table.LakeManagedTableDeleteImpactVO;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTablePreviewVO;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableVO;
import org.apache.seatunnel.web.api.service.LakeManagedTableService;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableDeleteDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTablePreviewDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeManagedTableControllerTest {

    @Test
    void previewAndCreateDelegateStructuredRequests() {
        LakeManagedTableService service = mock(LakeManagedTableService.class);
        LakeManagedTablePreviewDTO previewRequest = new LakeManagedTablePreviewDTO();
        LakeManagedTablePreviewVO preview = new LakeManagedTablePreviewVO();
        LakeManagedTableCreateDTO createRequest = new LakeManagedTableCreateDTO();
        LakeManagedTableVO created = new LakeManagedTableVO();
        when(service.preview(previewRequest)).thenReturn(preview);
        when(service.create(createRequest)).thenReturn(created);
        LakeManagedTableController controller = new LakeManagedTableController(service);

        assertEquals(preview, controller.preview(previewRequest).getData());
        assertEquals(created, controller.create(createRequest).getData());
        verify(service).preview(previewRequest);
        verify(service).create(createRequest);
    }

    @Test
    void getEndpointsOnlyReadAndDeleteRequiresImpactConfirmation() {
        LakeManagedTableService service = mock(LakeManagedTableService.class);
        LakeManagedTableVO detail = new LakeManagedTableVO();
        LakeManagedTableVO reconciled = new LakeManagedTableVO();
        LakeManagedTableVO retry = new LakeManagedTableVO();
        LakeManagedTableDeleteImpactVO impact = new LakeManagedTableDeleteImpactVO();
        LakeManagedTableDeleteDTO request = new LakeManagedTableDeleteDTO();
        when(service.detail(41L)).thenReturn(detail);
        when(service.reconcile(41L)).thenReturn(reconciled);
        when(service.retry(41L)).thenReturn(retry);
        when(service.deleteImpact(41L)).thenReturn(impact);
        LakeManagedTableController controller = new LakeManagedTableController(service);

        assertEquals(detail, controller.detail(41L).getData());
        assertEquals(reconciled, controller.reconcile(41L).getData());
        assertEquals(retry, controller.retry(41L).getData());
        assertEquals(impact, controller.deleteImpact(41L).getData());
        Result<Void> deleted = controller.delete(41L, request);

        verify(service).detail(41L);
        verify(service).reconcile(41L);
        verify(service).retry(41L);
        verify(service).deleteImpact(41L);
        verify(service).delete(41L, request);
        assertEquals(0, deleted.getCode());
    }
}
