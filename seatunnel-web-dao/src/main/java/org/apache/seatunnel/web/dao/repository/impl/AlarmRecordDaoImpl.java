package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.AlarmRecordEntity;
import org.apache.seatunnel.web.dao.mapper.AlarmRecordMapper;
import org.apache.seatunnel.web.dao.repository.AlarmRecordDao;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.MyBatisColumn;
import org.springframework.stereotype.Repository;

@Repository
public class AlarmRecordDaoImpl extends BaseDao<AlarmRecordEntity, AlarmRecordMapper>
        implements AlarmRecordDao {

    public AlarmRecordDaoImpl(@NonNull AlarmRecordMapper alarmRecordMapper) {
        super(alarmRecordMapper);
    }

    @Override
    public IPage<AlarmRecordEntity> page(int pageNo, int pageSize, Long jobInstanceId,
                                         String channelType, String severity, Integer success) {
        Page<AlarmRecordEntity> page = new Page<>(pageNo < 1 ? 1 : pageNo, pageSize < 1 ? 10 : pageSize);
        LambdaQueryWrapper<AlarmRecordEntity> wrapper = new LambdaQueryWrapper<>();
        if (jobInstanceId != null) {
            wrapper.eq(MyBatisColumn.getter(AlarmRecordEntity::getJobInstanceId), jobInstanceId);
        }
        if (channelType != null && !channelType.isBlank()) {
            wrapper.eq(MyBatisColumn.getter(AlarmRecordEntity::getChannelType), channelType);
        }
        if (severity != null && !severity.isBlank()) {
            wrapper.eq(MyBatisColumn.getter(AlarmRecordEntity::getSeverity), severity);
        }
        if (success != null) {
            wrapper.eq(MyBatisColumn.getter(AlarmRecordEntity::getSuccess), success);
        }
        wrapper.orderByDesc(MyBatisColumn.getter(AlarmRecordEntity::getCreateTime));
        return mybatisMapper.selectPage(page, wrapper);
    }
}
