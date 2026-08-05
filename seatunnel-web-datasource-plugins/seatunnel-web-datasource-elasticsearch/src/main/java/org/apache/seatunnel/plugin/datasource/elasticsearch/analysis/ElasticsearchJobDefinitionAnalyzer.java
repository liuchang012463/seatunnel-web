package org.apache.seatunnel.plugin.datasource.elasticsearch.analysis;

import org.apache.seatunnel.plugin.datasource.api.analysis.DatasourceAnalysisContext;
import org.apache.seatunnel.plugin.datasource.api.analysis.jdbc.AbstractJdbcJobDefinitionAnalyzer;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.ArrayList;
import java.util.List;

public class ElasticsearchJobDefinitionAnalyzer extends AbstractJdbcJobDefinitionAnalyzer {

    @Override
    protected String[] guideSingleSourceTableKeys() {
        return new String[] {"index", "table", "table_path"};
    }

    @Override
    protected String[] guideSingleSinkTableKeys() {
        return new String[] {"index", "targetIndex", "targetTableName", "table", "table_path"};
    }

    @Override
    protected String[] scriptSourceTableKeys() {
        return guideSingleSourceTableKeys();
    }

    @Override
    protected String[] scriptSinkTableKeys() {
        return guideSingleSinkTableKeys();
    }

    @Override
    protected List<String> resolveGuideMultiTableList(DatasourceAnalysisContext context) {
        List<String> indexes = new ArrayList<>();
        indexes.addAll(safeGetStringList(context.getPluginConfig(), "index_list"));
        if (indexes.isEmpty()) {
            String index = resolveFirstConfigTable(context, guideSingleSourceTableKeys());
            if (!index.isEmpty()) {
                indexes.add(index);
            }
        }
        return distinct(indexes);
    }

    @Override
    protected DbType dbType() {
        return DbType.ELASTICSEARCH;
    }
}
