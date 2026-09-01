package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.lake.inventory.LakePhysicalTableInventoryVO;
import org.apache.seatunnel.web.api.service.LakePhysicalTableInventoryService;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakePhysicalTableInventoryControllerTest {

    @Test
    void inventoryIsAGetOnlyServiceDelegation() {
        LakePhysicalTableInventoryService service = mock(LakePhysicalTableInventoryService.class);
        LakePhysicalTableInventoryVO expected = new LakePhysicalTableInventoryVO();
        expected.setOdsDatabaseBindingId(41L);
        when(service.inventory(41L)).thenReturn(expected);
        LakePhysicalTableInventoryController controller =
                new LakePhysicalTableInventoryController(service);

        Result<LakePhysicalTableInventoryVO> result = controller.inventory(41L);

        assertSame(expected, result.getData());
        verify(service).inventory(41L);
    }
}
