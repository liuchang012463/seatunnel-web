package org.apache.seatunnel.web.dao.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.seatunnel.web.spi.bean.dto.BatchJobDefinitionQueryDTO;
import org.apache.seatunnel.web.spi.bean.vo.BatchJobDefinitionVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Executes the paging SQL against a real H2 database. The string-only mapper test cannot catch a
 * filter that runs after LIMIT, because that defect only appears with more rows than one page.
 */
class JobDefinitionMapperH2PagingTest {

    private static SqlSessionFactory sessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource dataSource = new UnpooledDataSource(
                "org.h2.Driver",
                "jdbc:h2:mem:job_definition_paging;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        createSchema(dataSource);

        Environment environment = new Environment(
                "test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        String resource = "org/apache/seatunnel/web/dao/mapper/JobDefinitionMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(
                    input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void statusFilterIsAppliedBeforePaging() {
        BatchJobDefinitionQueryDTO dto = new BatchJobDefinitionQueryDTO();
        dto.setStatus("RUNNING");

        try (SqlSession session = sessionFactory.openSession()) {
            JobDefinitionMapper mapper = session.getMapper(JobDefinitionMapper.class);

            assertEquals(2L, mapper.selectDefinitionCount(dto));

            List<BatchJobDefinitionVO> firstPage =
                    mapper.selectPageWithLatestInstance(dto, 0, 1);
            List<BatchJobDefinitionVO> secondPage =
                    mapper.selectPageWithLatestInstance(dto, 1, 1);

            assertEquals(1, firstPage.size());
            assertEquals(1, secondPage.size());
            assertEquals(
                    Set.of(1L, 3L),
                    Set.of(firstPage.get(0).getId(), secondPage.get(0).getId()));
        }
    }

    @Test
    void failedBucketUsesTheLatestInstanceStatus() {
        BatchJobDefinitionQueryDTO dto = new BatchJobDefinitionQueryDTO();
        dto.setStatus("FAILED");

        try (SqlSession session = sessionFactory.openSession()) {
            JobDefinitionMapper mapper = session.getMapper(JobDefinitionMapper.class);

            assertEquals(1L, mapper.selectDefinitionCount(dto));
            List<BatchJobDefinitionVO> page =
                    mapper.selectPageWithLatestInstance(dto, 0, 10);
            assertEquals(1, page.size());
            assertEquals(2L, page.get(0).getId());
        }
    }

    /**
     * A job writes one metrics row per pipeline. Joining the metrics table directly would
     * duplicate the definition row and break the page size.
     */
    @Test
    void aJobWithSeveralPipelinesIsListedOnce() {
        BatchJobDefinitionQueryDTO dto = new BatchJobDefinitionQueryDTO();

        try (SqlSession session = sessionFactory.openSession()) {
            JobDefinitionMapper mapper = session.getMapper(JobDefinitionMapper.class);

            statement(session, "INSERT INTO t_seatunnel_web_job_metrics "
                    + "(job_instance_id, read_qps, write_qps, read_row_count, write_row_count) "
                    + "VALUES (12, 1.0, 2.0, 10, 20), (12, 3.0, 4.0, 30, 40), (31, 5.0, 6.0, 50, 60)");

            List<BatchJobDefinitionVO> page =
                    mapper.selectPageWithLatestInstance(dto, 0, 10);

            assertEquals(3, page.size());
            assertEquals(3, page.stream().map(BatchJobDefinitionVO::getId).distinct().count());
            BatchJobDefinitionVO first = page.stream()
                    .filter(definition -> definition.getId() == 1L)
                    .findFirst()
                    .orElseThrow();
            // The metrics of both pipelines are summed instead of producing a second row.
            assertEquals(40L, first.getReadRowCount());
            assertEquals(60L, first.getWriteRowCount());
        }
    }

    private static void statement(SqlSession session, String sql) {
        try {
            session.getConnection().createStatement().execute(sql);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static void createSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_seatunnel_web_job_definition (
                        id BIGINT PRIMARY KEY,
                        job_name VARCHAR(255),
                        job_desc VARCHAR(255),
                        mode VARCHAR(32),
                        job_type VARCHAR(32),
                        task_type VARCHAR(32),
                        client_id BIGINT,
                        job_version INT,
                        source_type VARCHAR(32),
                        source_table VARCHAR(255),
                        source_datasource_id BIGINT,
                        sink_datasource_id BIGINT,
                        sink_type VARCHAR(32),
                        sink_table VARCHAR(255),
                        release_state VARCHAR(32),
                        create_user_id INT,
                        update_user_id INT,
                        create_time TIMESTAMP,
                        update_time TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE t_seatunnel_web_job_instance (
                        id BIGINT PRIMARY KEY,
                        job_definition_id BIGINT,
                        engine_job_id VARCHAR(255),
                        job_status VARCHAR(32),
                        start_time TIMESTAMP,
                        end_time TIMESTAMP,
                        error_message VARCHAR(255),
                        run_mode VARCHAR(32),
                        create_time TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE t_seatunnel_web_job_metrics (
                        job_instance_id BIGINT,
                        read_qps DOUBLE,
                        write_qps DOUBLE,
                        read_row_count BIGINT,
                        write_row_count BIGINT,
                        record_delay BIGINT)
                    """);
            statement.execute("""
                    CREATE TABLE t_seatunnel_web_job_schedule (
                        id BIGINT,
                        job_definition_id BIGINT,
                        execution_mode VARCHAR(32),
                        cron_expression VARCHAR(64),
                        schedule_status VARCHAR(32),
                        last_schedule_time TIMESTAMP,
                        next_schedule_time TIMESTAMP,
                        schedule_config VARCHAR(255))
                    """);
            statement.execute("""
                    CREATE TABLE t_seatunnel_web_datasource (
                        id BIGINT PRIMARY KEY,
                        name VARCHAR(255))
                    """);

            statement.execute("INSERT INTO t_seatunnel_web_job_definition "
                    + "(id, job_name, create_time) VALUES "
                    + "(1, 'first', TIMESTAMP '2026-01-01 00:00:00'),"
                    + "(2, 'second', TIMESTAMP '2026-01-02 00:00:00'),"
                    + "(3, 'third', TIMESTAMP '2026-01-03 00:00:00')");
            // Definition 1's latest instance is RUNNING; its older FAILED row must be ignored.
            statement.execute("INSERT INTO t_seatunnel_web_job_instance "
                    + "(id, job_definition_id, job_status, create_time) VALUES "
                    + "(11, 1, 'FAILED', TIMESTAMP '2026-01-01 01:00:00'),"
                    + "(12, 1, 'RUNNING', TIMESTAMP '2026-01-01 02:00:00'),"
                    + "(21, 2, 'RUNNING', TIMESTAMP '2026-01-02 01:00:00'),"
                    + "(22, 2, 'FAILED', TIMESTAMP '2026-01-02 02:00:00'),"
                    + "(31, 3, 'RUNNING', TIMESTAMP '2026-01-03 01:00:00')");
        }
    }
}
