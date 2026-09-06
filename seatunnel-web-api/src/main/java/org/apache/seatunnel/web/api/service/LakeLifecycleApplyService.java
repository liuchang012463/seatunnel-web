package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleValidateVO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleApplyDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleRetentionUpdateDTO;

/** Explicit lifecycle policy application and retention update boundary. */
public interface LakeLifecycleApplyService {

    LakeLifecycleValidateVO apply(LakeLifecycleApplyDTO request);

    LakeLifecycleValidateVO update(
            Long mappingId, LakeLifecycleRetentionUpdateDTO request);
}
