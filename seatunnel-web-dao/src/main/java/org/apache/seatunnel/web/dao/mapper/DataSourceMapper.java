package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.seatunnel.web.dao.entity.DataSource;

import java.util.List;

@Mapper
public interface DataSourceMapper extends BaseMapper<DataSource> {

    @Select("SELECT DISTINCT data_source_unit "
            + "FROM t_seatunnel_web_datasource "
            + "WHERE data_source_unit IS NOT NULL "
            + "AND TRIM(data_source_unit) <> '' "
            + "ORDER BY data_source_unit")
    List<String> selectDataSourceUnits();
}
