package org.apache.seatunnel.plugin.datasource.vastbase.analysis;

import org.apache.seatunnel.plugin.datasource.api.analysis.DatasourceAnalysisContext;
import org.apache.seatunnel.plugin.datasource.api.analysis.jdbc.AbstractJdbcJobDefinitionAnalyzer;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.List;

/** Analyzes guide and script batch jobs for the Vastbase connector. */
public class VastbaseJobDefinitionAnalyzer extends AbstractJdbcJobDefinitionAnalyzer {

    @Override
    protected DbType dbType() {
        return DbType.VASTBASE;
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
