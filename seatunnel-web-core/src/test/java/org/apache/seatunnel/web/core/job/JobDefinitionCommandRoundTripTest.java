package org.apache.seatunnel.web.core.job;

import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchFileSyncJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleIncrementalJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideSingleJobSaveCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobDefinitionCommandRoundTripTest {

    @Test
    void structuredCommandsRetainOdsDatabaseBindingIdAcrossJsonRoundTrip() {
        assertBinding(42L, JSONUtils.parseObject(
                JSONUtils.toJsonString(command(new BatchGuideSingleJobSaveCommand())),
                BatchGuideSingleJobSaveCommand.class).getOdsDatabaseBindingId());
        assertBinding(42L, JSONUtils.parseObject(
                JSONUtils.toJsonString(command(new BatchGuideSingleIncrementalJobSaveCommand())),
                BatchGuideSingleIncrementalJobSaveCommand.class).getOdsDatabaseBindingId());
        assertBinding(42L, JSONUtils.parseObject(
                JSONUtils.toJsonString(command(new BatchGuideMultiJobSaveCommand())),
                BatchGuideMultiJobSaveCommand.class).getOdsDatabaseBindingId());
        assertBinding(42L, JSONUtils.parseObject(
                JSONUtils.toJsonString(command(new BatchFileSyncJobSaveCommand())),
                BatchFileSyncJobSaveCommand.class).getOdsDatabaseBindingId());
        assertBinding(42L, JSONUtils.parseObject(
                JSONUtils.toJsonString(command(new StreamingGuideSingleJobSaveCommand())),
                StreamingGuideSingleJobSaveCommand.class).getOdsDatabaseBindingId());
        assertBinding(42L, JSONUtils.parseObject(
                JSONUtils.toJsonString(command(new StreamingGuideMultiJobSaveCommand())),
                StreamingGuideMultiJobSaveCommand.class).getOdsDatabaseBindingId());
    }

    private <T extends org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand> T command(T command) {
        command.setOdsDatabaseBindingId(42L);
        return command;
    }

    private void assertBinding(Long expected, Long actual) {
        assertEquals(expected, actual);
    }
}
