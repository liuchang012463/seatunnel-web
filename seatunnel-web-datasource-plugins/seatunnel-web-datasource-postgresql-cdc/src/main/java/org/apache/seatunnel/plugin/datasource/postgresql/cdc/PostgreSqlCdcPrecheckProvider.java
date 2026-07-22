package org.apache.seatunnel.plugin.datasource.postgresql.cdc;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.cdc.CdcDatasourcePrecheckProvider;
import org.apache.seatunnel.plugin.datasource.api.cdc.CdcDatasourcePrecheckResult;
import org.apache.seatunnel.web.spi.enums.DbType;

@AutoService(CdcDatasourcePrecheckProvider.class)
public class PostgreSqlCdcPrecheckProvider implements CdcDatasourcePrecheckProvider {

    private final PostgreSqlCdcPrecheckService precheckService = new PostgreSqlCdcPrecheckService();

    @Override
    public DbType dbType() {
        return DbType.POSTGRE_SQL;
    }

    @Override
    public String pluginName() {
        return "POSTGRESQL-CDC";
    }

    @Override
    public CdcDatasourcePrecheckResult check(String connectionParams) {
        return precheckService.check(connectionParams);
    }
}
