package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.NonNull;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.seatunnel.web.dao.entity.AlarmRuleChannelEntity;
import org.apache.seatunnel.web.dao.mapper.AlarmRuleChannelMapper;
import org.apache.seatunnel.web.dao.repository.AlarmRuleChannelDao;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.MyBatisColumn;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Repository
public class AlarmRuleChannelDaoImpl extends BaseDao<AlarmRuleChannelEntity, AlarmRuleChannelMapper>
        implements AlarmRuleChannelDao {

    public AlarmRuleChannelDaoImpl(@NonNull AlarmRuleChannelMapper alarmRuleChannelMapper) {
        super(alarmRuleChannelMapper);
    }

    @Override
    public List<AlarmRuleChannelEntity> listByRuleId(Long ruleId) {
        if (ruleId == null) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<AlarmRuleChannelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MyBatisColumn.getter(AlarmRuleChannelEntity::getRuleId), ruleId);
        return mybatisMapper.selectList(wrapper);
    }

    @Override
    public List<AlarmRuleChannelEntity> listByRuleIds(Collection<Long> ruleIds) {
        if (CollectionUtils.isEmpty(ruleIds)) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<AlarmRuleChannelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(MyBatisColumn.getter(AlarmRuleChannelEntity::getRuleId), ruleIds);
        return mybatisMapper.selectList(wrapper);
    }

    @Override
    public void deleteByRuleId(Long ruleId) {
        if (ruleId == null) {
            return;
        }
        LambdaQueryWrapper<AlarmRuleChannelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MyBatisColumn.getter(AlarmRuleChannelEntity::getRuleId), ruleId);
        mybatisMapper.delete(wrapper);
    }

    @Override
    public void deleteByChannelId(Long channelId) {
        if (channelId == null) {
            return;
        }
        LambdaQueryWrapper<AlarmRuleChannelEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MyBatisColumn.getter(AlarmRuleChannelEntity::getChannelId), channelId);
        mybatisMapper.delete(wrapper);
    }
}
