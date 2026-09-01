package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.service.LakeLifecyclePolicyService;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyDisableDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyPageDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyUpdateDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.LakeLifecyclePolicyVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeLifecyclePolicyControllerTest {

    @Test
    void delegatesAllPolicyCommandsToService() {
        LakeLifecyclePolicyService service = mock(LakeLifecyclePolicyService.class);
        LakeLifecyclePolicyController controller = new LakeLifecyclePolicyController(service);
        LakeLifecyclePolicyPageDTO pageRequest = new LakeLifecyclePolicyPageDTO();
        LakeLifecyclePolicyCreateDTO createRequest = new LakeLifecyclePolicyCreateDTO();
        LakeLifecyclePolicyUpdateDTO updateRequest = new LakeLifecyclePolicyUpdateDTO();
        LakeLifecyclePolicyDisableDTO disableRequest = new LakeLifecyclePolicyDisableDTO();
        LakeLifecyclePolicyVO expected = new LakeLifecyclePolicyVO();
        PaginationResult<LakeLifecyclePolicyVO> page = PaginationResult.buildSuc(java.util.List.of(expected), 1, 1, 10);
        when(service.page(pageRequest)).thenReturn(page);
        when(service.create(createRequest)).thenReturn(expected);
        when(service.update(11L, updateRequest)).thenReturn(expected);
        when(service.disable(11L, disableRequest)).thenReturn(expected);

        assertSame(page, controller.page(pageRequest));
        assertSame(expected, controller.create(createRequest).getData());
        assertSame(expected, controller.update(11L, updateRequest).getData());
        assertSame(expected, controller.disable(11L, disableRequest).getData());
        verify(service).page(pageRequest);
        verify(service).create(createRequest);
        verify(service).update(11L, updateRequest);
        verify(service).disable(11L, disableRequest);
    }
}
