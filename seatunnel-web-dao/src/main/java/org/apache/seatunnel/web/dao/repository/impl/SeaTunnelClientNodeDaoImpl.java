package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.NonNull;
import org.apache.seatunnel.web.dao.entity.SeaTunnelClientNode;
import org.apache.seatunnel.web.dao.mapper.SeaTunnelClientNodeMapper;
import org.apache.seatunnel.web.dao.repository.BaseDao;
import org.apache.seatunnel.web.dao.repository.MyBatisColumn;
import org.apache.seatunnel.web.dao.repository.SeaTunnelClientNodeDao;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.Date;
import java.util.List;

@Repository
public class SeaTunnelClientNodeDaoImpl
        extends BaseDao<SeaTunnelClientNode, SeaTunnelClientNodeMapper>
        implements SeaTunnelClientNodeDao {

    @Resource
    private SeaTunnelClientNodeMapper seaTunnelClientNodeMapper;

    public SeaTunnelClientNodeDaoImpl(@NonNull SeaTunnelClientNodeMapper seaTunnelClientNodeMapper) {
        super(seaTunnelClientNodeMapper);
    }

    @Override
    public List<SeaTunnelClientNode> selectByClientId(Long clientId) {
        if (clientId == null || clientId <= 0) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<SeaTunnelClientNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MyBatisColumn.getter(SeaTunnelClientNode::getClientId), clientId)
                .orderByAsc(MyBatisColumn.getter(SeaTunnelClientNode::getNodeRole))
                .orderByDesc(MyBatisColumn.getter(SeaTunnelClientNode::getActiveMaster))
                .orderByAsc(MyBatisColumn.getter(SeaTunnelClientNode::getId));

        List<SeaTunnelClientNode> records = seaTunnelClientNodeMapper.selectList(wrapper);
        return records == null ? Collections.emptyList() : records;
    }

    @Override
    public List<SeaTunnelClientNode> selectByClientIdAndRole(
            Long clientId,
            String nodeRole
    ) {
        if (clientId == null || clientId <= 0 || nodeRole == null || nodeRole.trim().isEmpty()) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<SeaTunnelClientNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MyBatisColumn.getter(SeaTunnelClientNode::getClientId), clientId)
                .eq(MyBatisColumn.getter(SeaTunnelClientNode::getNodeRole), nodeRole.trim())
                .orderByDesc(MyBatisColumn.getter(SeaTunnelClientNode::getActiveMaster))
                .orderByAsc(MyBatisColumn.getter(SeaTunnelClientNode::getHealthStatus))
                .orderByAsc(MyBatisColumn.getter(SeaTunnelClientNode::getId));

        List<SeaTunnelClientNode> records = seaTunnelClientNodeMapper.selectList(wrapper);
        return records == null ? Collections.emptyList() : records;
    }

    @Override
    public void deleteByClientId(Long clientId) {
        if (clientId == null || clientId <= 0) {
            return;
        }

        LambdaQueryWrapper<SeaTunnelClientNode> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MyBatisColumn.getter(SeaTunnelClientNode::getClientId), clientId);

        seaTunnelClientNodeMapper.delete(wrapper);
    }

    @Override
    public void clearActiveMaster(Long clientId) {
        if (clientId == null || clientId <= 0) {
            return;
        }

        LambdaUpdateWrapper<SeaTunnelClientNode> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(MyBatisColumn.getter(SeaTunnelClientNode::getClientId), clientId)
                .eq(MyBatisColumn.getter(SeaTunnelClientNode::getNodeRole), "MASTER")
                .set(MyBatisColumn.getter(SeaTunnelClientNode::getActiveMaster), false)
                .set(MyBatisColumn.getter(SeaTunnelClientNode::getUpdateTime), new Date());

        seaTunnelClientNodeMapper.update(null, wrapper);
    }
}
