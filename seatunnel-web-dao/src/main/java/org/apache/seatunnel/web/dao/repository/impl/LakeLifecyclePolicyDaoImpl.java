package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.dao.mapper.LakeLifecyclePolicyMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.LakeLifecyclePolicyDao;
import org.springframework.stereotype.Repository;

@Repository
public class LakeLifecyclePolicyDaoImpl extends BaseDao<LakeLifecyclePolicy, LakeLifecyclePolicyMapper>
        implements LakeLifecyclePolicyDao {

    private final LakeLifecyclePolicyMapper mapper;

    public LakeLifecyclePolicyDaoImpl(@NonNull LakeLifecyclePolicyMapper mapper) {
        super(mapper);
        this.mapper = mapper;
    }

    @Override
    public LakeLifecyclePolicy queryByPolicyName(String policyName) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeLifecyclePolicy>()
                .eq(LakeLifecyclePolicy::getPolicyName, policyName));
    }
}
