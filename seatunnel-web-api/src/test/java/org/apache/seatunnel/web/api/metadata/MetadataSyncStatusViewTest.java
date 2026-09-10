package org.apache.seatunnel.web.api.metadata;

import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetadataSyncStatusViewTest {

    @Test
    void projectsNullBindingAsNotInitialized() {
        assertEquals("NOT_INITIALIZED", MetadataSyncStatusView.project(null));
    }

    @Test
    void projectsConnectorNotSupportedErrorAsUnsupported() {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setSyncStatus(MetadataSyncStatus.ERROR);
        binding.setLastSyncErrorCode(MetadataErrorCode.CONNECTOR_NOT_SUPPORTED.name());

        assertEquals("UNSUPPORTED", MetadataSyncStatusView.project(binding));
    }

    @Test
    void projectsOrdinaryErrorUnchanged() {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setSyncStatus(MetadataSyncStatus.ERROR);
        binding.setLastSyncErrorCode(MetadataErrorCode.OM_SERVICE_SYNC_ERROR.name());

        assertEquals("ERROR", MetadataSyncStatusView.project(binding));
    }

    @Test
    void projectsReadyUnchanged() {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setSyncStatus(MetadataSyncStatus.READY);

        assertEquals("READY", MetadataSyncStatusView.project(binding));
    }
}
