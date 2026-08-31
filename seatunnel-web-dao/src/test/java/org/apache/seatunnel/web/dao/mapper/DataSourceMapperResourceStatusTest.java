package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSourceMapperResourceStatusTest {

    private static MybatisConfiguration configuration;

    @BeforeAll
    static void loadMapper() throws Exception {
        configuration = new MybatisConfiguration();
        String resource = "org/apache/seatunnel/web/dao/mapper/DataSourceMapper.xml";
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            XMLMapperBuilder builder = new XMLMapperBuilder(
                    inputStream,
                    configuration,
                    resource,
                    configuration.getSqlFragments());
            builder.parse();
        }
    }

    @Test
    void statusAndKeywordAreAppliedByOnePagedExistsStatement() {
        Map<String, Object> params = new HashMap<>();
        params.put("page", new Page<>(2, 10));
        params.put("keyword", "orders");
        params.put("resourceStatus", LakeResourceStatus.READY.getCode());

        MappedStatement statement = configuration.getMappedStatement(
                DataSourceMapper.class.getName() + ".selectPageByLakeResourceStatus");
        String sql = statement.getBoundSql(params)
                .getSql()
                .replaceAll("\\s+", " ")
                .toUpperCase();

        assertTrue(sql.contains("FROM T_SEATUNNEL_WEB_DATASOURCE D"), sql);
        assertTrue(sql.contains("EXISTS ( SELECT 1 FROM T_SEATUNNEL_WEB_LAKE_ODS_DATABASE_BINDING B"), sql);
        assertTrue(sql.contains("B.RESOURCE_STATUS = ?"), sql);
        assertTrue(sql.contains("B.DELETED = 0"), sql);
        assertTrue(sql.contains("D.NAME LIKE CONCAT('%', ?, '%')"), sql);
        assertFalse(sql.contains(" IN "), sql);
    }
}
