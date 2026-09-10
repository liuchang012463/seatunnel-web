package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;

/**
 * Projects persisted binding sync status for API/UI consumers.
 *
 * <p>Unsupported connectors stay {@link MetadataSyncStatus#ERROR} locally so the
 * reconciler stops retrying, but list and cached-status APIs surface them as
 * {@code UNSUPPORTED} so operators do not see a false "同步异常".
 */
public final class MetadataSyncStatusView {

    private MetadataSyncStatusView() {}

    public static String project(MetadataSourceBinding binding) {
        if (binding == null || binding.getSyncStatus() == null) {
            return "NOT_INITIALIZED";
        }
        if (binding.getSyncStatus() == MetadataSyncStatus.ERROR
                && MetadataErrorCode.CONNECTOR_NOT_SUPPORTED.name().equals(binding.getLastSyncErrorCode())) {
            return "UNSUPPORTED";
        }
        return binding.getSyncStatus().name();
    }
}
