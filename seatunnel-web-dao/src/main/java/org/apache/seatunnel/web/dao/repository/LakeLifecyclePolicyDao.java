package org.apache.seatunnel.web.dao.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyPageDTO;

public interface LakeLifecyclePolicyDao extends IDao<LakeLifecyclePolicy> {

    LakeLifecyclePolicy queryByPolicyName(String policyName);

    LakeLifecyclePolicy queryByPolicyNameExcludeId(String policyName, Long id);

    IPage<LakeLifecyclePolicy> queryPage(LakeLifecyclePolicyPageDTO request);

    boolean updateIfVersion(LakeLifecyclePolicy entity, Integer expectedVersion);
}
