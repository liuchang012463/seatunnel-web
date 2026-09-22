package org.apache.seatunnel.plugin.datasource.vastbase.option;

import com.google.auto.service.AutoService;
import org.apache.seatunnel.plugin.datasource.api.jdbc.AbstractSourceOptionRule;
import org.apache.seatunnel.plugin.datasource.api.jdbc.SourceOptionRule;

@AutoService(SourceOptionRule.class)
public class VastbaseSourceOptionRule extends AbstractSourceOptionRule {

    @Override
    public String pluginName() {
        return "JDBC-VASTBASE";
    }
}
