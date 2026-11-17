package org.apache.seatunnel.web.api.metadata;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Defaults shared by newly generated and already deployed exploration agents. */
public final class MetadataExplorationPipelinePolicy {

    static final int VERSION = 1;
    static final int PROFILER_SAMPLE_ROWS = 50;

    private MetadataExplorationPipelinePolicy() {
    }

    public static String sampleSchedule(String serviceFqn) {
        return schedule(serviceFqn, 0);
    }

    public static String profilerSchedule(String serviceFqn) {
        return schedule(serviceFqn, 1);
    }

    public static boolean isExplorationAgent(String type) {
        return "profiler".equals(type) || "autoClassification".equals(type);
    }

    public static boolean isLegacySchedule(String type, String schedule) {
        return switch (type) {
            case "profiler" -> "0 1 * * *".equals(schedule);
            case "autoClassification" -> "0 0 * * *".equals(schedule);
            default -> false;
        };
    }

    public static ObjectNode profilerRowSample() {
        ObjectNode sample = JsonNodeFactory.instance.objectNode();
        sample.put("sampleConfigType", "STATIC");
        ObjectNode config = sample.putObject("config");
        config.put("profileSample", PROFILER_SAMPLE_ROWS);
        config.put("profileSampleType", "ROWS");
        return sample;
    }

    private static String schedule(String serviceFqn, int hour) {
        // Both agents for a service use the same minute, one hour apart. The
        // first batch starts after the on-the-hour metadata scans.
        int minute = 5 + 10 * Math.floorMod(serviceFqn.hashCode(), 6);
        return minute + " " + hour + " * * *";
    }
}
