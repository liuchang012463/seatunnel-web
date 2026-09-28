package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.NonNull;
import org.apache.seatunnel.web.common.enums.ScheduleStatusEnum;
import org.apache.seatunnel.web.dao.entity.JobSchedule;
import org.apache.seatunnel.web.dao.mapper.JobScheduleMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.JobScheduleDao;
import org.apache.seatunnel.web.dao.repository.MyBatisColumn;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public class JobScheduleDaoImpl
        extends BaseDao<JobSchedule, JobScheduleMapper>
        implements JobScheduleDao {

    @Resource
    private JobScheduleMapper jobScheduleMapper;

    public JobScheduleDaoImpl(@NonNull JobScheduleMapper jobScheduleMapper) {
        super(jobScheduleMapper);
    }

    @Override
    public JobSchedule queryByJobDefinitionId(Long jobDefinitionId) {
        LambdaQueryWrapper<JobSchedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MyBatisColumn.getter(JobSchedule::getJobDefinitionId), jobDefinitionId)
                .last("LIMIT 1");
        return jobScheduleMapper.selectOne(wrapper);
    }

    @Override
    public boolean existsByJobDefinitionId(Long jobDefinitionId) {
        LambdaQueryWrapper<JobSchedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MyBatisColumn.getter(JobSchedule::getJobDefinitionId), jobDefinitionId);
        return jobScheduleMapper.selectCount(wrapper) > 0;
    }

    @Override
    public boolean deleteByJobDefinitionId(Long jobDefinitionId) {
        LambdaQueryWrapper<JobSchedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MyBatisColumn.getter(JobSchedule::getJobDefinitionId), jobDefinitionId);
        return jobScheduleMapper.delete(wrapper) > 0;
    }

    @Override
    public List<JobSchedule> queryByScheduleStatus(ScheduleStatusEnum scheduleStatus) {
        LambdaQueryWrapper<JobSchedule> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MyBatisColumn.getter(JobSchedule::getScheduleStatus), scheduleStatus)
                .orderByDesc(MyBatisColumn.getter(JobSchedule::getUpdateTime));
        return jobScheduleMapper.selectList(wrapper);
    }

    @Override
    public boolean updateScheduleStatus(Long scheduleId, ScheduleStatusEnum scheduleStatus) {
        LambdaUpdateWrapper<JobSchedule> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(MyBatisColumn.getter(JobSchedule::getId), scheduleId)
                .set(MyBatisColumn.getter(JobSchedule::getScheduleStatus), scheduleStatus);
        return jobScheduleMapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean updateLastScheduleTime(Long scheduleId, Date lastScheduleTime) {
        LambdaUpdateWrapper<JobSchedule> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(MyBatisColumn.getter(JobSchedule::getId), scheduleId)
                .set(MyBatisColumn.getter(JobSchedule::getLastScheduleTime), lastScheduleTime);
        return jobScheduleMapper.update(null, wrapper) > 0;
    }

    @Override
    public boolean updateNextScheduleTime(Long scheduleId, Date nextScheduleTime) {
        LambdaUpdateWrapper<JobSchedule> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(MyBatisColumn.getter(JobSchedule::getId), scheduleId)
                .set(MyBatisColumn.getter(JobSchedule::getNextScheduleTime), nextScheduleTime);
        return jobScheduleMapper.update(null, wrapper) > 0;
    }
}
