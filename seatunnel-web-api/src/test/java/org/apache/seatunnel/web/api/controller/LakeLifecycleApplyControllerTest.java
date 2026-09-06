package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleValidateVO;
import org.apache.seatunnel.web.api.service.LakeLifecycleApplyService;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleApplyDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleRetentionUpdateDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeLifecycleApplyControllerTest {

    @Test
    void applyIsPostOnlyAndDelegatesBody() {
        LakeLifecycleApplyService service = mock(LakeLifecycleApplyService.class);
        LakeLifecycleValidateVO value = new LakeLifecycleValidateVO();
        LakeLifecycleApplyDTO request = new LakeLifecycleApplyDTO();
        request.setMappingId(501L);
        request.setPolicyId(901L);
        when(service.apply(request)).thenReturn(value);

        LakeLifecycleApplyController controller = new LakeLifecycleApplyController(service);

        Result<LakeLifecycleValidateVO> result = controller.apply(request);

        assertNotNull(result);
        verify(service).apply(request);
        Method method = declared("apply", LakeLifecycleApplyDTO.class);
        assertEquals("/apply", method.getAnnotation(PostMapping.class).value()[0]);
        assertEquals("/api/v1/lake/lifecycle",
                LakeLifecycleApplyController.class.getAnnotation(RequestMapping.class).value()[0]);
    }

    @Test
    void retentionUpdateIsPutOnlyAndDelegatesPathAndBody() {
        LakeLifecycleApplyService service = mock(LakeLifecycleApplyService.class);
        LakeLifecycleValidateVO value = new LakeLifecycleValidateVO();
        LakeLifecycleRetentionUpdateDTO request = new LakeLifecycleRetentionUpdateDTO();
        request.setPolicyId(901L);
        when(service.update(501L, request)).thenReturn(value);

        LakeLifecycleApplyController controller = new LakeLifecycleApplyController(service);

        Result<LakeLifecycleValidateVO> result = controller.update(501L, request);

        assertNotNull(result);
        verify(service).update(501L, request);
        Method method = declared("update", Long.class, LakeLifecycleRetentionUpdateDTO.class);
        assertEquals("/tables/{mappingId}/retention", method.getAnnotation(PutMapping.class).value()[0]);
    }

    private static Method declared(String name, Class<?>... parameterTypes) {
        try {
            return LakeLifecycleApplyController.class.getDeclaredMethod(name, parameterTypes);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
