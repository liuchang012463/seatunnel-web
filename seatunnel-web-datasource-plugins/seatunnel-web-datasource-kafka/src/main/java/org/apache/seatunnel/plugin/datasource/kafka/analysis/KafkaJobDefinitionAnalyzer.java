package org.apache.seatunnel.plugin.datasource.kafka.analysis;

import org.apache.seatunnel.plugin.datasource.api.analysis.DatasourceAnalysisContext;
import org.apache.seatunnel.plugin.datasource.api.analysis.jdbc.AbstractJdbcJobDefinitionAnalyzer;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.ArrayList;
import java.util.List;

public class KafkaJobDefinitionAnalyzer extends AbstractJdbcJobDefinitionAnalyzer {

    @Override
    protected String[] guideSingleSourceTableKeys() {
        return new String[] {"topic", "pattern"};
    }

    @Override
    protected String[] guideSingleSinkTableKeys() {
        return new String[] {"topic"};
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
        List<String> topics = new ArrayList<>();
        String topic = resolveFirstConfigTable(context, new String[] {"topic", "pattern"});
        if (!topic.isEmpty()) {
            topics.add(topic);
        }
        return topics;
    }

    @Override
    protected DbType dbType() {
        return DbType.KAFKA;
    }
}
