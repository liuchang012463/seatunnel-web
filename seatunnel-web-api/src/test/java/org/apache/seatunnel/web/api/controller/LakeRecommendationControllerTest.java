package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationRequestDTO;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationVO;
import org.apache.seatunnel.web.api.service.LakeRecommendationService;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LakeRecommendationControllerTest {

    @Test
    void exposesPostRecommendAndDelegatesStructuredRequest() throws Exception {
        LakeRecommendationService service = mock(LakeRecommendationService.class);
        LakeRecommendationController controller = new LakeRecommendationController(service);
        LakeRecommendationRequestDTO request = new LakeRecommendationRequestDTO();
        LakeRecommendationVO recommendation = mock(LakeRecommendationVO.class);
        when(service.recommend(request)).thenReturn(recommendation);

        Result<LakeRecommendationVO> result = controller.recommend(request);

        assertSame(recommendation, result.getData());
        assertEquals("/api/v1/lake", LakeRecommendationController.class
                .getAnnotation(RequestMapping.class).value()[0]);
        assertEquals("/recommend", LakeRecommendationController.class
                .getMethod("recommend", LakeRecommendationRequestDTO.class)
                .getAnnotation(PostMapping.class).value()[0]);
        verify(service).recommend(request);
    }
}
