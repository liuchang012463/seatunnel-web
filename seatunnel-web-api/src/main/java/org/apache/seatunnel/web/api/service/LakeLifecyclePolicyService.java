package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyDisableDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyPageDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyUpdateDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.LakeLifecyclePolicyVO;

/** CRUD boundary for reusable lifecycle policy definitions. */
public interface LakeLifecyclePolicyService {

    PaginationResult<LakeLifecyclePolicyVO> page(LakeLifecyclePolicyPageDTO request);

    LakeLifecyclePolicyVO create(LakeLifecyclePolicyCreateDTO request);

    LakeLifecyclePolicyVO update(Long id, LakeLifecyclePolicyUpdateDTO request);

    LakeLifecyclePolicyVO disable(Long id, LakeLifecyclePolicyDisableDTO request);
}
