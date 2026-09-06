package org.apache.seatunnel.web.api.controller;

import jakarta.validation.Valid;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationRequestDTO;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationVO;
import org.apache.seatunnel.web.api.service.LakeRecommendationService;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only recommendation endpoint for the lake dual-mode decision tree. */
@RestController
@RequestMapping("/api/v1/lake")
public class LakeRecommendationController {

    private final LakeRecommendationService service;

    @Autowired
    public LakeRecommendationController(LakeRecommendationService service) {
        this.service = service;
    }

    @PostMapping("/recommend")
    public Result<LakeRecommendationVO> recommend(
            @Valid @RequestBody LakeRecommendationRequestDTO request) {
        return Result.buildSuc(service.recommend(request));
    }
}
