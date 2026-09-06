package org.apache.seatunnel.web.core.job.bridge;

import org.apache.commons.lang3.StringUtils;
import org.apache.seatunnel.web.spi.bean.dto.command.GuideMultiJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.GuideSingleJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;

import java.util.List;
import java.util.Map;

/**
 * Resolves the binding id carried by a structured job command.
 *
 * <p>The command-level value is the canonical bridge field.  The nested
 * target value is retained for the existing workflow payload shape, but when
 * both are present they must agree.  This prevents an edit payload from
 * silently switching the ODS database by choosing whichever value happens to
 * be read first.</p>
 */
public final class LakeJobBindingResolver {

    private static final String KEY_NODES = "nodes";
    private static final String KEY_DATA = "data";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_NODE_TYPE = "nodeType";
    private static final String KEY_SINK = "sink";
    private static final String KEY_BINDING_ID = "odsDatabaseBindingId";
    private static final String KEY_BINDING_ID_SNAKE = "ods_database_binding_id";

    private LakeJobBindingResolver() {
    }

    /**
     * Return the one effective binding id for a structured command.
     * Ordinary script commands and commands without a binding return null.
     */
    public static Long resolve(JobDefinitionSaveCommand command) {
        if (command == null) {
            return null;
        }

        Long commandBindingId = command.getOdsDatabaseBindingId();
        Long targetBindingId = resolveTargetBindingId(command);

        if (commandBindingId != null && targetBindingId != null
                && !commandBindingId.equals(targetBindingId)) {
            throw new IllegalArgumentException(
                    "odsDatabaseBindingId differs between command and target config");
        }

        return commandBindingId == null ? targetBindingId : commandBindingId;
    }

    /**
     * Extract the binding from the structured target payload without applying
     * any database lookup or side effect.
     */
    public static Long resolveTargetBindingId(JobDefinitionSaveCommand command) {
        if (command instanceof GuideMultiJobContentCommand multiCommand) {
            GuideMultiJobContent content = multiCommand.getContent();
            return content == null || content.getTarget() == null
                    ? null
                    : content.getTarget().getOdsDatabaseBindingId();
        }

        if (command instanceof GuideSingleJobContentCommand singleCommand) {
            return resolveSingleTargetBindingId(singleCommand.getWorkflow());
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Long resolveSingleTargetBindingId(Map<String, Object> workflow) {
        if (workflow == null || !(workflow.get(KEY_NODES) instanceof List<?> nodes)) {
            return null;
        }

        Long result = null;
        for (Object rawNode : nodes) {
            if (!(rawNode instanceof Map<?, ?> node)) {
                continue;
            }

            Map<String, Object> data = asStringObjectMap(node.get(KEY_DATA));
            String nodeType = firstString(data.get(KEY_NODE_TYPE), node.get(KEY_NODE_TYPE), node.get("type"));
            if (!KEY_SINK.equalsIgnoreCase(nodeType)) {
                continue;
            }

            Map<String, Object> config = asStringObjectMap(data.get(KEY_CONFIG));
            Long nested = firstLong(
                    config.get(KEY_BINDING_ID),
                    config.get(KEY_BINDING_ID_SNAKE),
                    data.get(KEY_BINDING_ID),
                    data.get(KEY_BINDING_ID_SNAKE),
                    node.get(KEY_BINDING_ID),
                    node.get(KEY_BINDING_ID_SNAKE));
            if (nested == null) {
                continue;
            }
            if (result != null && !result.equals(nested)) {
                throw new IllegalArgumentException(
                        "multiple sink nodes contain different odsDatabaseBindingId values");
            }
            result = nested;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return (Map<String, Object>) (Map<?, ?>) map;
    }

    private static String firstString(Object... values) {
        for (Object value : values) {
            if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private static Long firstLong(Object... values) {
        for (Object value : values) {
            if (value == null || StringUtils.isBlank(String.valueOf(value))) {
                continue;
            }
            if (value instanceof Number number) {
                return number.longValue();
            }
            try {
                return Long.valueOf(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid odsDatabaseBindingId in target config", e);
            }
        }
        return null;
    }
}
