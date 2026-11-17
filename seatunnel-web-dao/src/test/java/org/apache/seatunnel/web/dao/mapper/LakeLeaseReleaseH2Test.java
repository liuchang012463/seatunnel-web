package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.seatunnel.web.dao.repository.impl.LakeOdsTableMappingDaoImpl;
import org.apache.seatunnel.web.dao.repository.impl.LakeTableLifecycleBindingDaoImpl;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real mapper updates catch ignored NULLs that mocked gateway/DAO tests cannot observe. */
class LakeLeaseReleaseH2Test {

    @Test
    void completedTableAndLifecycleOperationsClearLeaseAndPreviousError() throws Exception {
        String url = "jdbc:h2:mem:lake_lease_release;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        var dataSource = new UnpooledDataSource("org.h2.Driver", url, "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/h2").load().migrate();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO t_seatunnel_web_lake_ods_table_mapping
                    (id, source_object_ref_id, ods_database_binding_id, lake_data_source_id,
                     database_name, target_table_name, management_level, operation_token, error_code, error_message)
                    VALUES (1, 2, 3, 4, 'ods', 'users', 'MANAGED', 'lease', 'OLD', 'old failure')
                    """);
            statement.executeUpdate("""
                    INSERT INTO t_seatunnel_web_lake_table_lifecycle_binding
                    (id, table_mapping_id, partition_column, granularity, operation_token, error_code, error_message)
                    VALUES (10, 1, 'created_at', 'DAY', 'lease', 'OLD', 'old failure')
                    """);
        }
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(LakeOdsTableMappingMapper.class);
        configuration.addMapper(LakeTableLifecycleBindingMapper.class);
        var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        try (var session = factory.openSession(true)) {
            var tables = new LakeOdsTableMappingDaoImpl(session.getMapper(LakeOdsTableMappingMapper.class));
            var table = tables.queryActiveById(1L);
            table.setOperationToken(null);
            table.setErrorCode(null);
            table.setErrorMessage(null);
            assertTrue(tables.updateIfTokenAndVersion(table, "lease", 1));
            assertNull(tables.queryActiveById(1L).getOperationToken());
            assertNull(tables.queryActiveById(1L).getErrorCode());
            assertNull(tables.queryActiveById(1L).getErrorMessage());
            // A cleared lease can be acquired again; the former token cannot modify it.
            table = tables.queryActiveById(1L);
            table.setOperationToken("retry");
            assertTrue(tables.updateIfTokenAndVersion(table, null, 2));
            assertFalse(tables.updateIfTokenAndVersion(table, "lease", 3));

            var lifecycles = new LakeTableLifecycleBindingDaoImpl(session.getMapper(LakeTableLifecycleBindingMapper.class));
            var lifecycle = lifecycles.queryByTableMappingId(1L);
            lifecycle.setOperationToken(null);
            lifecycle.setErrorCode(null);
            lifecycle.setErrorMessage(null);
            assertTrue(lifecycles.updateIfTokenAndVersion(lifecycle, "lease", 1));
            assertNull(lifecycles.queryByTableMappingId(1L).getOperationToken());
            assertNull(lifecycles.queryByTableMappingId(1L).getErrorCode());
            assertNull(lifecycles.queryByTableMappingId(1L).getErrorMessage());
        }
    }
}
