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
import org.apache.seatunnel.web.spi.bean.dto.SeaTunnelJobInstanceDTO;
import org.apache.seatunnel.web.spi.bean.vo.JobInstanceVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes the streaming instance mapper SQL against H2 to distinguish missing metrics from zero counters. */
class StreamingJobInstanceMapperH2MetricsAvailabilityTest {

    private static final String MAPPER_NAMESPACE =
            "org.apache.seatunnel.web.dao.mapper.StreamingJobInstanceMapper";
    private static SqlSessionFactory sessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource dataSource = new UnpooledDataSource(
                "org.h2.Driver",
                "jdbc:h2:mem:streaming_instance_metrics_availability;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        createSchema(dataSource);

        Environment environment = new Environment(
                "test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        String resource = "org/apache/seatunnel/web/dao/mapper/StreamingJobInstanceMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(
                    input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void detailAndPageQueriesDistinguishMissingMetricsFromMeasuredZero() {
        try (SqlSession session = sessionFactory.openSession()) {
            StreamingJobInstanceMapper mapper = session.getMapper(StreamingJobInstanceMapper.class);

            JobInstanceVO detailWithoutMetrics = mapper.selectDetailById(101L);
            JobInstanceVO detailWithZeroMetrics = mapper.selectDetailById(102L);
            assertFalse(detailWithoutMetrics.getMetricsAvailable());
            assertTrue(detailWithZeroMetrics.getMetricsAvailable());
            assertEquals(0L, detailWithoutMetrics.getReadBytes());
            assertEquals(0L, detailWithZeroMetrics.getReadBytes());

            List<JobInstanceVO> page = session.selectList(
                    MAPPER_NAMESPACE + ".pageWithDefinition",
                    Map.of("dto", new SeaTunnelJobInstanceDTO()));
            JobInstanceVO pageWithoutMetrics = findById(page, 101L);
            JobInstanceVO pageWithZeroMetrics = findById(page, 102L);
            assertFalse(pageWithoutMetrics.getMetricsAvailable());
            assertTrue(pageWithZeroMetrics.getMetricsAvailable());
            assertEquals(0L, pageWithoutMetrics.getReadBytes());
            assertEquals(0L, pageWithZeroMetrics.getReadBytes());
        }
    }

    private static JobInstanceVO findById(List<JobInstanceVO> instances, long id) {
        return instances.stream()
                .filter(instance -> instance.getId() == id)
                .findFirst()
                .orElseThrow();
    }

    private static void createSchema(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_seatunnel_web_streaming_job_definition (
                        id BIGINT PRIMARY KEY,
                        job_name VARCHAR(255),
                        job_desc VARCHAR(255),
                        mode VARCHAR(32),
                        job_type VARCHAR(32),
                        task_type VARCHAR(32),
                        client_id BIGINT,
                        job_version INT,
                        release_state VARCHAR(32),
                        source_type VARCHAR(32),
                        sink_type VARCHAR(32),
                        source_table VARCHAR(255),
                        sink_table VARCHAR(255),
                        source_datasource_id BIGINT,
                        sink_datasource_id BIGINT)
                    """);
            statement.execute("""
                    CREATE TABLE t_seatunnel_web_streaming_job_instance (
                        id BIGINT PRIMARY KEY,
                        job_definition_id BIGINT,
                        client_id BIGINT,
                        run_mode VARCHAR(32),
                        job_status VARCHAR(32),
                        trigger_source VARCHAR(32),
                        retry_count INT,
                        engine_job_id VARCHAR(255),
                        savepoint_path VARCHAR(255),
                        runtime_config VARCHAR(255),
                        log_path VARCHAR(255),
                        error_message VARCHAR(255),
                        submit_time TIMESTAMP,
                        start_time TIMESTAMP,
                        end_time TIMESTAMP,
                        create_time TIMESTAMP,
                        update_time TIMESTAMP,
                        job_name VARCHAR(255),
                        source_table VARCHAR(255),
                        sink_table VARCHAR(255),
                        source_datasource_id BIGINT,
                        sink_datasource_id BIGINT)
                    """);
            statement.execute("""
                    CREATE TABLE t_seatunnel_web_streaming_job_metrics_current (
                        job_instance_id BIGINT PRIMARY KEY,
                        read_row_count BIGINT,
                        write_row_count BIGINT,
                        read_qps DECIMAL(20, 4),
                        write_qps DECIMAL(20, 4),
                        read_bytes BIGINT,
                        write_bytes BIGINT,
                        read_bps DECIMAL(20, 4),
                        write_bps DECIMAL(20, 4),
                        intermediate_queue_size BIGINT,
                        lag_count BIGINT,
                        record_delay BIGINT)
                    """);

            statement.execute("""
                    INSERT INTO t_seatunnel_web_streaming_job_definition
                    (id, job_name, mode, job_type, task_type, release_state)
                    VALUES (1, 'without-metrics', 'SCRIPT', 'STREAMING', 'STREAM', 'UNPUBLISHED'),
                           (2, 'zero-metrics', 'SCRIPT', 'STREAMING', 'STREAM', 'UNPUBLISHED')
                    """);
            statement.execute("""
                    INSERT INTO t_seatunnel_web_streaming_job_instance
                    (id, job_definition_id, client_id, run_mode, job_status, create_time, job_name)
                    VALUES (101, 1, 1, 'MANUAL', 'RUNNING', TIMESTAMP '2026-01-01 01:00:00', 'without-metrics'),
                           (102, 2, 1, 'MANUAL', 'RUNNING', TIMESTAMP '2026-01-01 02:00:00', 'zero-metrics')
                    """);
            statement.execute("""
                    INSERT INTO t_seatunnel_web_streaming_job_metrics_current
                    (job_instance_id, read_row_count, write_row_count, read_qps, write_qps,
                     read_bytes, write_bytes, read_bps, write_bps, intermediate_queue_size,
                     lag_count, record_delay)
                    VALUES (102, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                    """);
        }
    }
}
