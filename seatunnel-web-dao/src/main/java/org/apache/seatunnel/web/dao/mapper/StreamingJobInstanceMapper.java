package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.seatunnel.web.dao.entity.StreamingJobInstance;
import org.apache.seatunnel.web.spi.bean.dto.SeaTunnelJobInstanceDTO;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;

@Mapper
public interface StreamingJobInstanceMapper extends BaseMapper<StreamingJobInstance> {

    IPage<JobInstanceVO> pageWithDefinition(
            Page<?> page,
            @Param("dto") SeaTunnelJobInstanceDTO dto);

    JobInstanceVO selectDetailById(@Param("id") Long id);
}
