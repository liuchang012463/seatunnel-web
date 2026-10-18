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

    @Test
    void shouldSortByConfigurationTimeDescendingByDefault() {
        BatchJobDefinitionQueryDTO dto = new BatchJobDefinitionQueryDTO();
        Map<String, Object> params = new HashMap<>();
        params.put("dto", dto);
        params.put("offset", 0);
        params.put("pageSize", 10);

        String sql = pageSql(params);

        assertTrue(sql.contains("ORDER BY D0.CREATE_TIME DESC"), sql);
    }

    @Test
    void shouldAllowNameAscendingSort() {
        BatchJobDefinitionQueryDTO dto = new BatchJobDefinitionQueryDTO();
        dto.setSortField("name");
        dto.setSortOrder("asc");
        Map<String, Object> params = new HashMap<>();
        params.put("dto", dto);
        params.put("offset", 0);
        params.put("pageSize", 10);

        String sql = pageSql(params);

        assertTrue(sql.contains("ORDER BY D0.JOB_NAME ASC"), sql);
    }

    @Test
    void shouldApplyTheSameStatusBucketToPageAndCountQueries() {
        BatchJobDefinitionQueryDTO dto = new BatchJobDefinitionQueryDTO();
        dto.setStatus("RUNNING");

        Map<String, Object> params = new HashMap<>();
        params.put("dto", dto);
        params.put("offset", 0);
        params.put("pageSize", 10);

        // The list buckets a coarse UI status; the count feeds the same pagination total and must
        // match the same rows, otherwise a filtered page reports a wrong total.
        assertTrue(pageSql(params).contains("'SUBMISSION_UNKNOWN'"), pageSql(params));
        assertTrue(countSql(params).contains("'SUBMISSION_UNKNOWN'"), countSql(params));
        assertTrue(pageSql(params).contains("JOB_STATUS IN ("), pageSql(params));
        assertTrue(countSql(params).contains("JOB_STATUS IN ("), countSql(params));
    }

    @Test
    void shouldApplyStatusFilterBeforePaging() {
        BatchJobDefinitionQueryDTO dto = new BatchJobDefinitionQueryDTO();
        dto.setStatus("RUNNING");

        Map<String, Object> params = new HashMap<>();
        params.put("dto", dto);
        params.put("offset", 20);
        params.put("pageSize", 10);

        String sql = pageSql(params);

        // Filtering the derived page after LIMIT drops matching rows and makes the page disagree
        // with the count query. The predicate must be part of the paged subquery.
        assertTrue(sql.indexOf("JOB_STATUS IN (") < sql.indexOf("LIMIT ?"),
                "status predicate must run before LIMIT: " + sql);
    }

    @Test
    void shouldKeepAnExactStatusFilterForUnbucketedValues() {
        BatchJobDefinitionQueryDTO dto = new BatchJobDefinitionQueryDTO();
        dto.setStatus("CANCELED");

        Map<String, Object> params = new HashMap<>();
        params.put("dto", dto);
        params.put("offset", 0);
        params.put("pageSize", 10);

        assertTrue(pageSql(params).contains("JOB_STATUS = ?"), pageSql(params));
        assertTrue(countSql(params).contains("JOB_STATUS = ?"), countSql(params));
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

    private static String pageSql(Map<String, Object> params) {
        MappedStatement statement = configuration.getMappedStatement(
                JobDefinitionMapper.class.getName() + ".selectPageWithLatestInstance");
        return statement.getBoundSql(params)
                .getSql()
                .replaceAll("\\s+", " ")
                .toUpperCase();
    }

    private static String countSql(Map<String, Object> params) {
        MappedStatement statement = configuration.getMappedStatement(
                JobDefinitionMapper.class.getName() + ".selectDefinitionCount");
        return statement.getBoundSql(params)
                .getSql()
                .replaceAll("\\s+", " ")
                .toUpperCase();
    }
}
