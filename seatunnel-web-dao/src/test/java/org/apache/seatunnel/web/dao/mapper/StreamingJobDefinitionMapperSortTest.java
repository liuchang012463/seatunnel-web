package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.seatunnel.web.spi.bean.dto.StreamingJobDefinitionQueryDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingJobDefinitionMapperSortTest {

    private static MybatisConfiguration configuration;

    @BeforeAll
    static void loadMapper() throws Exception {
        configuration = new MybatisConfiguration();
        String resource = "org/apache/seatunnel/web/dao/mapper/StreamingJobDefinitionMapper.xml";
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
    void shouldSortByUpdateTimeAndExposeDatasourceNames() {
        StreamingJobDefinitionQueryDTO dto = new StreamingJobDefinitionQueryDTO();
        dto.setSortField("updateTime");
        dto.setSortOrder("desc");

        String sql = pageSql(dto);

        assertTrue(sql.contains("DS_SOURCE.NAME AS SOURCE_DATASOURCE_NAME"), sql);
        assertTrue(sql.contains("DS_SINK.NAME AS SINK_DATASOURCE_NAME"), sql);
        assertTrue(sql.contains("ORDER BY D.UPDATE_TIME DESC, D.ID DESC"), sql);
    }

    @Test
    void shouldPreserveExplicitCreateTimeSort() {
        StreamingJobDefinitionQueryDTO dto = new StreamingJobDefinitionQueryDTO();
        dto.setSortField("createTime");
        dto.setSortOrder("asc");

        String sql = pageSql(dto);

        assertTrue(sql.contains("ORDER BY D.CREATE_TIME ASC, D.ID DESC"), sql);
    }

    private static String pageSql(StreamingJobDefinitionQueryDTO dto) {
        Map<String, Object> params = new HashMap<>();
        params.put("dto", dto);
        params.put("offset", 0);
        params.put("pageSize", 10);

        MappedStatement statement = configuration.getMappedStatement(
                StreamingJobDefinitionMapper.class.getName() + ".selectPageWithLatestInstance");
        return statement.getBoundSql(params)
                .getSql()
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*,\\s*", ", ")
                .toUpperCase();
    }
}
