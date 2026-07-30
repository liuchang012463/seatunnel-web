package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.spi.bean.dto.BatchJobDefinitionQueryDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JobDefinitionMapperModeFilterTest {

    private static MybatisConfiguration configuration;

    @BeforeAll
    static void loadMapper() throws Exception {
        configuration = new MybatisConfiguration();
        String resource = "org/apache/seatunnel/web/dao/mapper/JobDefinitionMapper.xml";
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
    void shouldApplyModeIsolationToPageAndCountQueries() {
        BatchJobDefinitionQueryDTO dto = new BatchJobDefinitionQueryDTO();
        dto.setMode(JobDefinitionMode.FILE_SYNC);
        dto.setExcludeMode(JobDefinitionMode.SCRIPT);

        Map<String, Object> params = new HashMap<>();
        params.put("dto", dto);
        params.put("offset", 0);
        params.put("pageSize", 10);

        assertModePredicates("selectPageWithLatestInstance", params);
        assertModePredicates("selectDefinitionCount", params);
    }

    private static void assertModePredicates(
            String statementName, Map<String, Object> params) {
        MappedStatement statement = configuration.getMappedStatement(
                JobDefinitionMapper.class.getName() + "." + statementName);
        String sql = statement.getBoundSql(params)
                .getSql()
                .replaceAll("\\s+", " ")
                .toUpperCase();

        assertTrue(sql.contains("MODE = ?"), sql);
        assertTrue(sql.contains("MODE != ?"), sql);
    }
}
