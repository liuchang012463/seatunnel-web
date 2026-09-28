package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.commons.lang3.StringUtils;
import lombok.NonNull;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.dao.mapper.LakeLifecyclePolicyMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.LakeLifecyclePolicyDao;
import org.apache.seatunnel.web.dao.repository.MyBatisColumn;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyPageDTO;
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
                .eq(MyBatisColumn.getter(LakeLifecyclePolicy::getPolicyName), policyName));
    }

    @Override
    public LakeLifecyclePolicy queryByPolicyNameExcludeId(String policyName, Long id) {
        return mapper.selectOne(new LambdaQueryWrapper<LakeLifecyclePolicy>()
                .eq(MyBatisColumn.getter(LakeLifecyclePolicy::getPolicyName), policyName)
                .ne(id != null, MyBatisColumn.getter(LakeLifecyclePolicy::getId), id));
    }

    @Override
    public IPage<LakeLifecyclePolicy> queryPage(LakeLifecyclePolicyPageDTO request) {
        int pageNo = request == null || request.getPageNo() == null ? 1 : request.getPageNo();
        int pageSize = request == null || request.getPageSize() == null ? 10 : request.getPageSize();
        return mapper.selectPage(new Page<>(pageNo, pageSize), buildQueryWrapper(request));
    }

    static LambdaQueryWrapper<LakeLifecyclePolicy> buildQueryWrapper(
            LakeLifecyclePolicyPageDTO request) {
        String policyName = request == null ? null : request.getPolicyName();
        LakeLifecyclePolicyStatus status = request == null ? null : request.getStatus();
        LakePartitionGranularity granularity = request == null ? null : request.getGranularity();
        return new LambdaQueryWrapper<LakeLifecyclePolicy>()
                .like(StringUtils.isNotBlank(policyName), MyBatisColumn.getter(LakeLifecyclePolicy::getPolicyName),
                        StringUtils.trimToEmpty(policyName))
                .eq(status != null, MyBatisColumn.getter(LakeLifecyclePolicy::getStatus), status)
                .eq(granularity != null, MyBatisColumn.getter(LakeLifecyclePolicy::getGranularity), granularity)
                .orderByDesc(MyBatisColumn.getter(LakeLifecyclePolicy::getUpdateTime))
                .orderByDesc(MyBatisColumn.getter(LakeLifecyclePolicy::getId));
    }

    @Override
    public boolean updateIfVersion(LakeLifecyclePolicy entity, Integer expectedVersion) {
        if (entity == null || entity.getId() == null || expectedVersion == null) {
            return false;
        }
        return mapper.update(null, new LambdaUpdateWrapper<LakeLifecyclePolicy>()
                .eq(MyBatisColumn.getter(LakeLifecyclePolicy::getId), entity.getId())
                .eq(MyBatisColumn.getter(LakeLifecyclePolicy::getVersion), expectedVersion)
                .set(MyBatisColumn.getter(LakeLifecyclePolicy::getPolicyName), entity.getPolicyName())
                .set(MyBatisColumn.getter(LakeLifecyclePolicy::getVersion), entity.getVersion())
                .set(MyBatisColumn.getter(LakeLifecyclePolicy::getStatus), entity.getStatus())
                .set(MyBatisColumn.getter(LakeLifecyclePolicy::getGranularity), entity.getGranularity())
                .set(MyBatisColumn.getter(LakeLifecyclePolicy::getRetentionCount), entity.getRetentionCount())
                .set(MyBatisColumn.getter(LakeLifecyclePolicy::getDescription), entity.getDescription())
                .set(MyBatisColumn.getter(LakeLifecyclePolicy::getCreateUserId), entity.getCreateUserId())
                .set(MyBatisColumn.getter(LakeLifecyclePolicy::getUpdateUserId), entity.getUpdateUserId())
                .set(MyBatisColumn.getter(LakeLifecyclePolicy::getCreateTime), entity.getCreateTime())
                .set(MyBatisColumn.getter(LakeLifecyclePolicy::getUpdateTime), entity.getUpdateTime())) > 0;
    }
}
