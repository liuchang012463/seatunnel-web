package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.api.lake.operation.LakeResourceOperationVO;

import java.util.List;

/** Read-only access to the durable lake resource operation journal. */
public interface LakeResourceOperationService {

    List<LakeResourceOperationVO> list(String resourceType, Long resourceId);
}
