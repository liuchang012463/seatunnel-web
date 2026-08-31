package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;

public interface LakeLifecyclePolicyDao extends IDao<LakeLifecyclePolicy> {

    LakeLifecyclePolicy queryByPolicyName(String policyName);
}
