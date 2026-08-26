package org.apache.seatunnel.web.common.utils;

/**
 * Stable technical names for OpenMetadata objects owned by a SeaTunnel data source.
 *
 * <p>The helper deliberately has no persistence or OpenMetadata dependency.  It is
 * used by the future reconciler to derive names from the immutable data source ID.</p>
 */
public final class MetadataStableName {

    private static final String DATA_SOURCE_PREFIX = "st_ds_";

    private MetadataStableName() {
    }

    public static String serviceName(Long dataSourceId) {
        return DATA_SOURCE_PREFIX + requireId(dataSourceId);
    }

    public static String serviceFqn(Long dataSourceId) {
        return serviceName(dataSourceId);
    }

    public static String metadataPipelineName(Long dataSourceId) {
        return serviceName(dataSourceId) + "_metadata";
    }

    public static String metadataPipelineFqn(Long dataSourceId) {
        return serviceFqn(dataSourceId) + "." + metadataPipelineName(dataSourceId);
    }

    public static String profilerPipelineName(Long dataSourceId) {
        return serviceName(dataSourceId) + "_profiler";
    }

    public static String profilerPipelineFqn(Long dataSourceId) {
        return serviceFqn(dataSourceId) + "." + profilerPipelineName(dataSourceId);
    }

    private static long requireId(Long dataSourceId) {
        if (dataSourceId == null || dataSourceId <= 0) {
            throw new IllegalArgumentException("dataSourceId must be positive");
        }
        return dataSourceId;
    }
}
