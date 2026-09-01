package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationRequestDTO;
import org.apache.seatunnel.web.api.lake.recommendation.LakeRecommendationVO;

/** Read-only mode recommendation for the lake dual-mode control plane. */
public interface LakeRecommendationService {

    LakeRecommendationVO recommend(LakeRecommendationRequestDTO request);
}
