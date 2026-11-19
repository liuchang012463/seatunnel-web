package org.apache.seatunnel.web.core.builder;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.seatunnel.web.core.builder.context.DagBuildContext;
import org.apache.seatunnel.web.core.builder.sink.SinkNodeConfigBuilder;
import org.apache.seatunnel.web.core.builder.source.SourceNodeConfigBuilder;
import org.apache.seatunnel.web.core.builder.transform.TransformNodeConfigBuilder;
import org.apache.seatunnel.web.core.dag.DagGraph;
import org.apache.seatunnel.web.core.utils.SeaTunnelConfigUtil;
import org.apache.seatunnel.web.spi.bean.dto.config.JobEnvConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;
import org.springframework.stereotype.Component;
import org.apache.seatunnel.web.common.utils.NonNullFunctions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Builds a complete SeaTunnel job configuration in HOCON format.
 */
@Component
@Slf4j
public class HoconConfigBuilder {

    private static final String KEY_ID = "id";
    private static final String KEY_DATA = "data";
    private static final String KEY_NODE_TYPE = "nodeType";

    @Resource
    private NodeConfigBuilderRegistry registry;

    @Resource
    private EnvConfigBuilder envConfigBuilder;

    public String build(DagGraph dagGraph, JobEnvConfig envConfig) {
        return build(dagGraph, envConfig, null, null);
    }

    public String build(DagGraph dagGraph,
                        JobEnvConfig envConfig,
                        JobScheduleConfig scheduleConfig,
                        Long odsDatabaseBindingId) {
        return build(dagGraph, envConfig, scheduleConfig, odsDatabaseBindingId, null);
    }

    public String build(DagGraph dagGraph,
                        JobEnvConfig envConfig,
                        JobScheduleConfig scheduleConfig,
                        Long odsDatabaseBindingId,
                        String engineVersion) {
        return build(dagGraph, envConfig, scheduleConfig, odsDatabaseBindingId, engineVersion,
                false, UnaryOperator.identity());
    }

    public String build(DagGraph dagGraph,
                        JobEnvConfig envConfig,
                        JobScheduleConfig scheduleConfig,
                        Long odsDatabaseBindingId,
                        String engineVersion,
                        boolean explicitRouting,
                        UnaryOperator<String> formatBlock) {
        DagBuildContext context = DagBuildContext.from(
                dagGraph, scheduleConfig, odsDatabaseBindingId, engineVersion);

        NodeGroup group = groupNodes(dagGraph.getNodesAsConfig(), context, explicitRouting);

        // Mask each preview block before joining. Parsing the full HOCON would merge
        // repeated connector keys that the SeaTunnel job parser reads as separate plugins.
        return SeaTunnelConfigUtil.generateConfig(
                formatBlock.apply(envConfigBuilder.build(envConfig)),
                render(group.sources(), formatBlock),
                render(group.transforms(), formatBlock),
                render(group.sinks(), formatBlock)
        );
    }

    private NodeGroup groupNodes(List<Config> nodes, DagBuildContext context, boolean explicitRouting) {
        NodeGroup nodeGroup = new NodeGroup();

        nodes.stream()
                .filter(n -> n.hasPath(KEY_DATA))
                .map(this::resolveNodeDataWithId)
                .forEach(data -> classify(data, nodeGroup, context, explicitRouting));

        return nodeGroup;
    }

    /**
     * Keep outer node id in data.
     *
     * <p>
     * ReactFlow node id is outside data, but source/sink plugin_input/plugin_output
     * needs this id when transform exists.
     * </p>
     */
    private Config resolveNodeDataWithId(Config node) {
        Config data = node.getConfig(KEY_DATA);

        if (!node.hasPath(KEY_ID)) {
            return data;
        }

        String nodeId = node.getString(KEY_ID);

        Map<String, Object> idMap = new HashMap<>();
        idMap.put(KEY_ID, nodeId);

        return ConfigFactory.parseMap(idMap).withFallback(data).resolve();
    }

    private void classify(Config data, NodeGroup g, DagBuildContext context, boolean explicitRouting) {
        String nodeType = data.getString(KEY_NODE_TYPE);
        NodeConfigBuilder<?> builder = registry.get(nodeType);

        if (builder instanceof SourceNodeConfigBuilder) {
            SourceNodeConfigBuilder sourceBuilder = (SourceNodeConfigBuilder) builder;
            Config source = sourceBuilder.build(data, context);
            g.addSource(sourceBuilder.connectorName(data), explicitRouting
                    ? withRoute(source, "plugin_output", data.getString("config.pluginOutput")) : source);
            return;
        }

        if (builder instanceof SinkNodeConfigBuilder) {
            SinkNodeConfigBuilder sinkBuilder = (SinkNodeConfigBuilder) builder;
            Config sink = sinkBuilder.build(data, context);
            g.addSink(sinkBuilder.connectorName(data), explicitRouting
                    ? withRoute(sink, "plugin_input", data.getString("config.pluginInput")) : sink);
            return;
        }

        if (builder instanceof TransformNodeConfigBuilder) {
            TransformNodeConfigBuilder transformBuilder = (TransformNodeConfigBuilder) builder;
            g.addTransform(transformBuilder.build(data, context));
            return;
        }

        log.warn("Unknown builder type: {}", builder.getClass());
    }

    // Connector builders may omit routing when there is no transform.
    private Config withRoute(Config config, String key, String route) {
        return config.withValue(key, ConfigValueFactory.fromAnyRef(route));
    }

    public String build(DagGraph dagGraph, JobEnvConfig envConfig, JobScheduleConfig scheduleConfig) {
        return build(dagGraph, envConfig, scheduleConfig, null);
    }

    private String render(List<RenderedItem> items, UnaryOperator<String> formatBlock) {
        return items.stream()
                .map(NonNullFunctions.from(RenderedItem::toHocon))
                .map(formatBlock)
                .collect(Collectors.joining("\n"));
    }
}
