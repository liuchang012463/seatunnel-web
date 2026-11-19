package org.apache.seatunnel.web.core.job.handler.single;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.core.builder.HoconConfigBuilder;
import org.apache.seatunnel.web.core.dag.DagGraph;
import org.apache.seatunnel.web.core.job.bridge.LakeJobBindingResolver;
import org.apache.seatunnel.web.core.job.handler.JobRuntimeContext;
import org.apache.seatunnel.web.core.job.handler.JobRuntimeContextFactory;
import org.apache.seatunnel.web.core.time.IncrementalConfigResolver;
import org.apache.seatunnel.web.core.utils.DagUtil;
import org.apache.seatunnel.web.spi.bean.dto.command.BatchJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

@Service
@Slf4j
public class GuideSingleHoconBuildService {

    private static final Set<String> APPEND_OVERRIDDEN_OPTIONS = Set.of(
            "data_save_mode", "datasavemode",
            "enable_upsert", "enableupsert",
            "primary_key", "primarykey", "primary_keys", "primarykeys",
            "write_mode", "writemode");

    @Resource
    private HoconConfigBuilder hoconConfigBuilder;

    @Resource
    private JobRuntimeContextFactory runtimeContextFactory;

    public String build(Map<String, Object> workflow, JobDefinitionSaveCommand command) {
        return build(workflow, command, false, UnaryOperator.identity());
    }

    public String build(Map<String, Object> workflow, JobDefinitionSaveCommand command,
                        boolean explicitRouting, UnaryOperator<String> formatBlock) {
        if (command == null) {
            throw new IllegalArgumentException("command can not be null");
        }
        if (MapUtils.isEmpty(workflow)) {
            throw new IllegalArgumentException("workflow can not be empty");
        }

        Map<String, Object> buildWorkflow = workflow;
        if (command.getMode() == JobDefinitionMode.GUIDE_SINGLE_INCREMENTAL
                && IncrementalConfigResolver.isHttpAppend(workflow)) {
            buildWorkflow = prepareHttpAppendWorkflow(workflow);
            log.info("Forcing HTTP incremental append sink to APPEND_DATA without upsert keys");
        }

        String dagJson = JSONUtils.toJsonString(buildWorkflow);
        if (StringUtils.isBlank(dagJson)) {
            throw new IllegalArgumentException("workflow can not be blank");
        }

        if (command instanceof BatchJobSaveCommand) {
            IncrementalConfigResolver.resolve(
                    workflow,
                    ((BatchJobSaveCommand) command).getSchedule()
            );
        }

        DagGraph dagGraph = DagUtil.parseAndCheck(dagJson);

        JobRuntimeContext runtimeContext = runtimeContextFactory.create(command);

        if (explicitRouting) {
            return hoconConfigBuilder.build(dagGraph, runtimeContext.getEnv(), runtimeContext.getSchedule(),
                    LakeJobBindingResolver.resolve(command), runtimeContext.getEngineVersion(), true, formatBlock);
        }
        return hoconConfigBuilder.build(
                dagGraph,
                runtimeContext.getEnv(),
                runtimeContext.getSchedule(),
                LakeJobBindingResolver.resolve(command),
                runtimeContext.getEngineVersion()
        );
    }

    static Map<String, Object> prepareHttpAppendWorkflow(Map<String, Object> workflow) {
        if (!IncrementalConfigResolver.isHttpAppend(workflow)) {
            return workflow;
        }
        Map<String, Object> buildWorkflow = JSONUtils.parseObject(
                JSONUtils.toJsonString(workflow), new TypeReference<Map<String, Object>>() {});
        Object rawNodes = buildWorkflow.get("nodes");
        if (!(rawNodes instanceof List<?> nodes)) {
            return workflow;
        }

        for (Object rawNode : nodes) {
            if (!(rawNode instanceof Map<?, ?> node)) {
                continue;
            }
            Object rawData = node.get("data");
            if (!(rawData instanceof Map<?, ?> data)
                    || !"sink".equalsIgnoreCase(String.valueOf(data.get("nodeType")))) {
                continue;
            }

            Map<String, Object> sinkData = stringMap(data);
            removeAppendOverrides(sinkData);
            sinkData.put("writeMode", "append");
            Object rawConfig = sinkData.get("config");
            if (rawConfig instanceof Map<?, ?> config) {
                stringMap(config).put("writeMode", "append");
            }
        }
        return buildWorkflow;
    }

    private static void removeAppendOverrides(Map<String, Object> config) {
        config.keySet().removeIf(key -> APPEND_OVERRIDDEN_OPTIONS.contains(
                String.valueOf(key).toLowerCase(Locale.ROOT)));

        // The JDBC extra-option appender parses params after typed sink options,
        // so legacy rows must not override the forced append settings.
        removeAppendOverridesFromParams(config, "params");
        removeAppendOverridesFromParams(config, "extraParams");

        Object nestedConfig = config.get("config");
        if (nestedConfig instanceof Map<?, ?> nestedOptions) {
            removeAppendOverrides(stringMap(nestedOptions));
        }
    }

    private static void removeAppendOverridesFromParams(Map<String, Object> config, String paramsKey) {
        Object rawParams = config.get(paramsKey);
        if (!(rawParams instanceof List<?> params)) {
            return;
        }

        List<Object> retainedParams = new ArrayList<>(params.size());
        for (Object rawParam : params) {
            if (!(rawParam instanceof Map<?, ?> param)) {
                retainedParams.add(rawParam);
                continue;
            }
            Object rawKey = param.get("key");
            String key = rawKey == null ? "" : String.valueOf(rawKey).trim().toLowerCase(Locale.ROOT);
            if (!APPEND_OVERRIDDEN_OPTIONS.contains(key)) {
                retainedParams.add(rawParam);
            }
        }
        if (retainedParams.size() == params.size()) {
            return;
        }
        if (retainedParams.isEmpty()) {
            config.remove(paramsKey);
        } else {
            config.put(paramsKey, retainedParams);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Map<?, ?> source) {
        return (Map<String, Object>) source;
    }
}
