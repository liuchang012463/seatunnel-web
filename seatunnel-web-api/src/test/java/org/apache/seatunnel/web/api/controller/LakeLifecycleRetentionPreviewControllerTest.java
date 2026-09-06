package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleRetentionPreviewVO;
import org.apache.seatunnel.web.api.service.LakeLifecycleRetentionPreviewService;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleRetentionPreviewDTO;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeLifecycleRetentionPreviewControllerTest {

    @Test
    void previewIsPostOnlyAndDelegatesPathAndBody() throws Exception {
        LakeLifecycleRetentionPreviewService service = mock(LakeLifecycleRetentionPreviewService.class);
        LakeLifecycleRetentionPreviewVO value = new LakeLifecycleRetentionPreviewVO();
        LakeLifecycleRetentionPreviewDTO request = new LakeLifecycleRetentionPreviewDTO();
        request.setPolicyId(901L);
        when(service.preview(501L, request)).thenReturn(value);

        LakeLifecycleRetentionPreviewController controller =
                new LakeLifecycleRetentionPreviewController(service);
        Result<LakeLifecycleRetentionPreviewVO> result = controller.preview(501L, request);

        assertNotNull(result);
        verify(service).preview(501L, request);
        Method method = LakeLifecycleRetentionPreviewController.class
                .getDeclaredMethod("preview", Long.class, LakeLifecycleRetentionPreviewDTO.class);
        assertEquals("/tables/{mappingId}/retention/preview",
                method.getAnnotation(PostMapping.class).value()[0]);
        assertEquals("/api/v1/lake/lifecycle",
                LakeLifecycleRetentionPreviewController.class
                        .getAnnotation(RequestMapping.class).value()[0]);
    }
}
