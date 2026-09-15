package org.apache.seatunnel.web.dao.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.seatunnel.web.dao.entity.JobDefinitionContentEntity;

@Mapper
public interface JobDefinitionContentMapper extends BaseMapper<JobDefinitionContentEntity> {

    @Select("SELECT COUNT(1) > 0 FROM t_seatunnel_web_job_definition_content "
            + "WHERE definition_content LIKE CONCAT('%\"fileResourceId\":', #{fileResourceId}, '%') "
            + "OR definition_content LIKE CONCAT('%\"fileResourceId\":\"', #{fileResourceId}, '\"%') "
            + "OR definition_content LIKE CONCAT('%\"file_resource_id\":', #{fileResourceId}, '%') "
            + "OR definition_content LIKE CONCAT('%\"file_resource_id\":\"', #{fileResourceId}, '\"%')")
    boolean existsByFileResourceId(@Param("fileResourceId") String fileResourceId);

}
