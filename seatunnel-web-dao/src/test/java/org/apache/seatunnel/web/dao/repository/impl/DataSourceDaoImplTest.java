package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceDTO;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSourceDaoImplTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DataSource.class);
    }

    @Test
    void shouldUseMultiTypeFilterAndNameLikeQuery() {
        DataSourceDTO dto = new DataSourceDTO();
        dto.setName("orders");
        dto.setDbType(DbType.MYSQL);
        dto.setDbTypes(List.of(DbType.HTTP, DbType.KAFKA));

        LambdaQueryWrapper<DataSource> wrapper = DataSourceDaoImpl.buildQueryWrapper(dto);
        String sql = wrapper.getSqlSegment().toUpperCase();

        assertTrue(sql.contains("NAME LIKE"));
        assertTrue(sql.contains("DB_TYPE IN"));
        assertFalse(sql.contains("DB_TYPE ="));
        assertTrue(wrapper.getParamNameValuePairs().values().stream()
                .map(String::valueOf)
                .anyMatch(value -> value.contains("orders")));
        assertTrue(wrapper.getParamNameValuePairs().values().stream()
                .map(String::valueOf)
                .anyMatch(value -> value.contains("HTTP")));
        assertTrue(wrapper.getParamNameValuePairs().values().stream()
                .map(String::valueOf)
                .anyMatch(value -> value.contains("KAFKA")));
    }

    @Test
    void shouldKeepCompatibleSingleDbTypeQuery() {
        DataSourceDTO dto = new DataSourceDTO();
        dto.setDbType(DbType.MYSQL);

        String sql = DataSourceDaoImpl.buildQueryWrapper(dto)
                .getSqlSegment()
                .toUpperCase();

        assertTrue(sql.contains("DB_TYPE ="));
        assertFalse(sql.contains("DB_TYPE IN"));
    }

    @Test
    void shouldFilterByUnitAndLifecycleStatus() {
        DataSourceDTO dto = new DataSourceDTO();
        dto.setDataSourceUnit("市局云搜");
        dto.setStatus(DataSourceLifecycleStatus.DISABLED);

        String sql = DataSourceDaoImpl.buildQueryWrapper(dto)
                .getSqlSegment()
                .toUpperCase();

        assertTrue(sql.contains("DATA_SOURCE_UNIT ="));
        assertTrue(sql.contains("STATUS ="));
    }
}
