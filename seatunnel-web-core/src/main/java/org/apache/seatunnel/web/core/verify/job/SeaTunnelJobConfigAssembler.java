package org.apache.seatunnel.web.core.verify.job;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigRenderOptions;
import org.springframework.stereotype.Component;

@Component
public class SeaTunnelJobConfigAssembler {

    public String assemble(Config envConfig,
                           String sourcePluginName,
                           Config sourceConfig,
                           String sinkPluginName,
                           Config sinkConfig) {

        StringBuilder sb = new StringBuilder(512);

        sb.append(renderSection("env", envConfig));
        sb.append("\n\n");

        sb.append(renderSection("source", wrapPlugin(sourcePluginName, sourceConfig)));
        sb.append("\n\n");

        sb.append(renderSection("sink", wrapPlugin(sinkPluginName, sinkConfig)));
        sb.append("\n");

        return sb.toString();
    }

    private Config wrapPlugin(String pluginName, Config pluginConfig) {
        return ConfigFactory.empty().withValue(pluginName, pluginConfig.root());
    }

    private String renderSection(String sectionName, Config sectionConfig) {
        Config wrapped = ConfigFactory.empty().withValue(sectionName, sectionConfig.root());
        return render(wrapped);
    }

    private String render(Config config) {
        return config.root().render(
                ConfigRenderOptions.defaults()
                        .setOriginComments(false)
                        .setComments(false)
                        .setFormatted(true)
                        .setJson(false)
        );
    }
}
