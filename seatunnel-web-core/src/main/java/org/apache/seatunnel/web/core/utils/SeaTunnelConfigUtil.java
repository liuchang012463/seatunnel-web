package org.apache.seatunnel.web.core.utils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for generating SeaTunnel job configuration text.
 *
 * <p>
 * This class assembles a complete SeaTunnel configuration
 * by injecting env, source, transform, and sink sections
 * into a predefined template.
 * </p>
 */
public class SeaTunnelConfigUtil {

    private static final String INDENT = "    ";

    /**
     * Placeholders are matched in a single pass so user-provided block
     * content is never re-scanned: a script or SQL block that itself
     * contains a placeholder literal must stay untouched.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "env_placeholder|source_placeholder|transform_placeholder|sink_placeholder");

    /**
     * Base SeaTunnel configuration template.
     *
     * <p>
     * Placeholders are replaced with user-provided configuration blocks:
     * </p>
     * <ul>
     *   <li>{@code env_placeholder}</li>
     *   <li>{@code source_placeholder}</li>
     *   <li>{@code transform_placeholder}</li>
     *   <li>{@code sink_placeholder}</li>
     * </ul>
     */
    private static final String CONFIG_TEMPLATE =
            "env {\n"
                    + "env_placeholder"
                    + "}\n"
                    + "source {\n"
                    + "source_placeholder"
                    + "}\n"
                    + "transform {\n"
                    + "transform_placeholder"
                    + "}\n"
                    + "sink {\n"
                    + "sink_placeholder"
                    + "}\n";

    /**
     * Generate a complete SeaTunnel configuration string.
     *
     * @param env        environment configuration section
     * @param sources    source configuration section
     * @param transforms transform configuration section
     * @param sinks      sink configuration section
     * @return assembled SeaTunnel configuration text
     */
    public static String generateConfig(
            String env,
            String sources,
            String transforms,
            String sinks
    ) {
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("env_placeholder", indentBlock(env));
        blocks.put("source_placeholder", indentBlock(sources));
        blocks.put("transform_placeholder", indentBlock(transforms));
        blocks.put("sink_placeholder", indentBlock(sinks));

        Matcher matcher = PLACEHOLDER.matcher(CONFIG_TEMPLATE);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String block = blocks.getOrDefault(matcher.group(), "");
            // Quote the replacement so block content like ${table_name}
            // is never interpreted as a regex group reference.
            matcher.appendReplacement(builder, Matcher.quoteReplacement(block));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    /**
     * Add one indentation level to every non-empty line.
     */
    private static String indentBlock(String block) {
        if (block == null || block.trim().isEmpty()) {
            return "";
        }

        String normalized = block.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);

        StringBuilder builder = new StringBuilder();

        for (String line : lines) {
            if (line.trim().isEmpty()) {
                builder.append('\n');
            } else {
                builder.append(INDENT).append(line).append('\n');
            }
        }

        return builder.toString();
    }
}
