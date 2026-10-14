package org.apache.seatunnel.plugin.datasource.all;

import org.apache.seatunnel.plugin.datasource.api.jdbc.DataSourceProcessor;
import org.apache.seatunnel.plugin.datasource.api.plugin.DataSourceProcessorProvider;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataSourceProcessorProviderTest {

    @Test
    void registersEveryCreatableDatasourceType() {
        Set<DbType> expected = EnumSet.of(
                DbType.JDBC,
                DbType.MYSQL,
                DbType.ORACLE,
                DbType.POSTGRE_SQL,
                DbType.ZEONEDB,
                DbType.VASTBASE,
                DbType.DORIS,
                DbType.ELASTICSEARCH,
                DbType.KINGBASE,
                DbType.DAMENG,
                DbType.KAFKA,
                DbType.HTTP,
                DbType.FTP,
                DbType.SFTP,
                DbType.S3,
                DbType.MINIO);

        Set<DbType> registered = DataSourceProcessorProvider.getDataSourceProcessorMap().values()
                .stream()
                .map(DataSourceProcessor::getDbType)
                .collect(Collectors.toSet());

        assertEquals(expected, registered);
    }
}
