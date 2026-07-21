package org.apache.seatunnel.plugin.datasource.jdbc.option;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractSourceOptionRule;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;
import org.apache.seatunnel.plugin.datasource.jdbc.builder.JdbcBatchBuilder;

@AutoService(SourceOptionRule.class)
public class JdbcSourceOptionRule extends AbstractSourceOptionRule {

    @Override
    public String pluginName() {
        return JdbcBatchBuilder.PLUGIN_NAME;
    }
}
