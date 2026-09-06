package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.seatunnel.web.dao.entity.DataSource;

import java.util.List;

@Mapper
public interface DataSourceMapper extends BaseMapper<DataSource> {

    IPage<DataSource> selectPageByLakeResourceStatus(
            IPage<DataSource> page,
            @Param("keyword") String keyword,
            @Param("resourceStatus") String resourceStatus);

    @Select("SELECT DISTINCT data_source_unit "
            + "FROM t_seatunnel_web_datasource "
            + "WHERE data_source_unit IS NOT NULL "
            + "AND TRIM(data_source_unit) <> '' "
            + "ORDER BY data_source_unit")
    List<String> selectDataSourceUnits();
}
