package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleValidateVO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleValidateDTO;

/** Read-through lifecycle eligibility validation and cached table detail. */
public interface LakeLifecycleValidationService {

    /** Performs local eligibility checks and explicit Doris metadata reads. */
    LakeLifecycleValidateVO validate(LakeLifecycleValidateDTO request);

    /** Returns persisted lifecycle/mapping observations without remote reads. */
    LakeLifecycleValidateVO detail(Long mappingId);
}
