package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.api.lake.lifecycle.LakeLifecycleRetentionPreviewVO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecycleRetentionPreviewDTO;

/** Explicit read-through retention impact preview boundary. */
public interface LakeLifecycleRetentionPreviewService {

    LakeLifecycleRetentionPreviewVO preview(
            Long mappingId, LakeLifecycleRetentionPreviewDTO request);
}
