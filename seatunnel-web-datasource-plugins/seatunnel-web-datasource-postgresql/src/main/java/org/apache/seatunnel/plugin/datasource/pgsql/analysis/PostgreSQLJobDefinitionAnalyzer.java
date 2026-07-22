package org.apache.seatunnel.plugin.datasource.pgsql.analysis;


import org.apache.seatunnel.plugin.datasource.api.analysis.jdbc.AbstractJdbcJobDefinitionAnalyzer;
import org.apache.seatunnel.plugin.datasource.api.analysis.DatasourceAnalysisContext;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.List;

public class PostgreSQLJobDefinitionAnalyzer extends AbstractJdbcJobDefinitionAnalyzer {

    @Override
    protected DbType dbType() {
        return DbType.POSTGRE_SQL;
    }

    @Override
    protected String[] guideSingleSourceTableKeys() {
        return new String[]{
                "table_path",
                "table",
                "table_name"
        };
    }

    @Override
    protected String[] guideSingleSinkTableKeys() {
        return new String[]{
                "targetTableName",
                "table",
                "table_path",
                "table_name"
        };
    }

    @Override
    protected String resolveSourceTable(DatasourceAnalysisContext context) {
        String table = super.resolveSourceTable(context);
        if (!table.isEmpty()) {
            return table;
        }

        List<String> tables = safeGetStringList(context.getPluginConfig(), "table-names");
        return tables.isEmpty() ? "" : String.join(",", tables);
    }

    @Override
    protected List<String> resolveGuideMultiTableList(DatasourceAnalysisContext context) {
        List<String> tables = super.resolveGuideMultiTableList(context);
        return tables.isEmpty()
                ? safeGetStringList(context.getPluginConfig(), "table-names")
                : tables;
    }
}
