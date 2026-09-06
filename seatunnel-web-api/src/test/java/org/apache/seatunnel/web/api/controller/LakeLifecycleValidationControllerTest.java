package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleValidateVO;
import org.apache.seatunnel.web.api.service.LakeLifecycleValidationService;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleValidateDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeLifecycleValidationControllerTest {

    @Test
    void delegatesPostValidationAndCachedGet() {
        LakeLifecycleValidationService service = mock(LakeLifecycleValidationService.class);
        LakeLifecycleValidationController controller =
                new LakeLifecycleValidationController(service);
        LakeLifecycleValidateDTO request = new LakeLifecycleValidateDTO();
        LakeLifecycleValidateVO expected = new LakeLifecycleValidateVO();
        when(service.validate(request)).thenReturn(expected);
        when(service.detail(501L)).thenReturn(expected);

        assertSame(expected, controller.validate(request).getData());
        assertSame(expected, controller.detail(501L).getData());
        verify(service).validate(request);
        verify(service).detail(501L);
    }

    @Test
    void exposesPostValidateAndGetCachedDetailRoutes() throws Exception {
        Method validate = Arrays.stream(LakeLifecycleValidationController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("validate"))
                .findFirst().orElseThrow();
        Method detail = Arrays.stream(LakeLifecycleValidationController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("detail"))
                .findFirst().orElseThrow();
        assertTrue(validate.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class));
        assertEquals("/validate", validate.getAnnotation(
                org.springframework.web.bind.annotation.PostMapping.class).value()[0]);
        assertTrue(detail.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class));
        assertEquals("/tables/{mappingId}", detail.getAnnotation(
                org.springframework.web.bind.annotation.GetMapping.class).value()[0]);
    }
}
