package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.seatunnel.web.dao.entity.OpenMetadataServerConfig;
import org.apache.seatunnel.web.dao.repository.impl.OpenMetadataServerConfigDaoImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Exercises the real MyBatis-Plus update path used when the scope is cleared. */
class OpenMetadataServerConfigDaoH2Test {

    @Test
    void updateSingletonPersistsClearedDomainScope() throws Exception {
        var dataSource = new UnpooledDataSource(
                "org.h2.Driver",
                "jdbc:h2:mem:openmetadata_server_config_clear;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_seatunnel_web_openmetadata_config (
                        id BIGINT PRIMARY KEY,
                        config_key VARCHAR(64),
                        enabled BOOLEAN,
                        base_url VARCHAR(255),
                        token VARCHAR(255),
                        default_domain_fqn VARCHAR(255),
                        connect_timeout_ms INT,
                        read_timeout_ms INT,
                        expected_server_version VARCHAR(64),
                        expected_ingestion_patch VARCHAR(64),
                        config_version BIGINT,
                        conn_status VARCHAR(32),
                        last_error VARCHAR(255),
                        create_user_id INT,
                        update_user_id INT,
                        create_time TIMESTAMP,
                        update_time TIMESTAMP)
                    """);
        }

        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(OpenMetadataServerConfigMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);

        try (var session = factory.openSession(true)) {
            var dao = new OpenMetadataServerConfigDaoImpl(session.getMapper(OpenMetadataServerConfigMapper.class));
            OpenMetadataServerConfig config = new OpenMetadataServerConfig();
            config.setId(1L);
            config.setConfigKey(OpenMetadataServerConfigDaoImpl.CONFIG_KEY);
            config.setDefaultDomainFqn("asset_assert");
            assertEquals(1, dao.insert(config));
            assertEquals("asset_assert", dao.querySingleton().getDefaultDomainFqn());

            config = dao.querySingleton();
            config.setDefaultDomainFqn(null);
            config.initUpdate();
            assertEquals(1, dao.updateSingleton(config));

            assertNull(dao.querySingleton().getDefaultDomainFqn());
        }
    }
}
