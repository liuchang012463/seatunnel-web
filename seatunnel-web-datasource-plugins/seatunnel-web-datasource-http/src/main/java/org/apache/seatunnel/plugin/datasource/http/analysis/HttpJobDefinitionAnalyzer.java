package org.apache.seatunnel.plugin.datasource.http.analysis;

import org.apache.seatunnel.plugin.datasource.api.analysis.DatasourceAnalysisContext;
import org.apache.seatunnel.plugin.datasource.api.analysis.jdbc.AbstractJdbcJobDefinitionAnalyzer;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.ArrayList;
import java.util.List;

public class HttpJobDefinitionAnalyzer extends AbstractJdbcJobDefinitionAnalyzer {

    @Override
    protected String[] guideSingleSourceTableKeys() {
        return new String[] {"path", "url"};
    }

    @Override
    protected String[] guideSingleSinkTableKeys() {
        return new String[0];
    }

    @Override
    protected String[] scriptSourceTableKeys() {
        return guideSingleSourceTableKeys();
    }

    @Override
    protected String[] scriptSinkTableKeys() {
        return new String[0];
    }

    @Override
    protected List<String> resolveGuideMultiTableList(DatasourceAnalysisContext context) {
        List<String> endpoints = new ArrayList<>();
        String endpoint = resolveFirstConfigTable(context, new String[] {"path", "url"});
        if (!endpoint.isEmpty()) {
            endpoints.add(endpoint);
        }
        return endpoints;
    }

    @Override
    protected DbType dbType() {
        return DbType.HTTP;
    }
}
