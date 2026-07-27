package org.apache.seatunnel.plugin.datasource.ftp.analysis;

import org.apache.seatunnel.plugin.datasource.api.analysis.DatasourceAnalysisContext;
import org.apache.seatunnel.plugin.datasource.api.analysis.jdbc.AbstractJdbcJobDefinitionAnalyzer;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.Collections;
import java.util.List;

public class RemoteFileJobDefinitionAnalyzer extends AbstractJdbcJobDefinitionAnalyzer {
    private final DbType type;
    public RemoteFileJobDefinitionAnalyzer(DbType type) { this.type = type; }
    @Override protected String[] guideSingleSourceTableKeys() { return new String[] {"path"}; }
    @Override protected String[] guideSingleSinkTableKeys() { return new String[] {"targetPath", "path"}; }
    @Override protected String[] scriptSourceTableKeys() { return guideSingleSourceTableKeys(); }
    @Override protected String[] scriptSinkTableKeys() { return guideSingleSinkTableKeys(); }
    @Override protected List<String> resolveGuideMultiTableList(DatasourceAnalysisContext context) {
        String path = resolveFirstConfigTable(context, guideSingleSourceTableKeys());
        return path.isEmpty() ? Collections.emptyList() : Collections.singletonList(path);
    }
    @Override protected DbType dbType() { return type; }
}
