package org.apache.seatunnel.plugin.datasource.jdbc.analysis;

import org.apache.seatunnel.plugin.datasource.api.analysis.jdbc.AbstractJdbcJobDefinitionAnalyzer;
import org.apache.seatunnel.web.spi.enums.DbType;

public class JdbcJobDefinitionAnalyzer extends AbstractJdbcJobDefinitionAnalyzer {

    @Override
    protected DbType dbType() {
        return DbType.JDBC;
    }
}
