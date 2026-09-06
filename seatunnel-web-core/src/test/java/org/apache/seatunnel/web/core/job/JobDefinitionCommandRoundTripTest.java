package org.apache.seatunnel.web.core.job;

import org.apache.seatunnel.web.common.utils.JSONUtils;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchFileSyncJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleIncrementalJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.junit.jupiter.api.Test;

import java.util.Map;

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

    @Test
    void multiAndWholeTargetConfigRetainBindingAlongsideTheCommand() {
        GuideMultiJobContent content = new GuideMultiJobContent();
        GuideMultiJobContent.WorkflowTargetConfig target =
                new GuideMultiJobContent.WorkflowTargetConfig();
        target.setDatasourceId("99");
        target.setOdsDatabaseBindingId(42L);
        content.setTarget(target);

        GuideMultiJobContent.TableMatchConfig whole =
                new GuideMultiJobContent.TableMatchConfig();
        whole.setMode("4");
        content.setTableMatch(whole);

        BatchGuideMultiJobSaveCommand batch = new BatchGuideMultiJobSaveCommand();
        batch.setContent(content);
        batch.setOdsDatabaseBindingId(42L);
        BatchGuideMultiJobSaveCommand batchRoundTrip = JSONUtils.parseObject(
                JSONUtils.toJsonString(batch), BatchGuideMultiJobSaveCommand.class);

        assertBinding(42L, batchRoundTrip.getOdsDatabaseBindingId());
        assertBinding(42L, batchRoundTrip.getContent().getTarget().getOdsDatabaseBindingId());
        assertEquals("4", batchRoundTrip.getContent().getTableMatch().getMode());

        StreamingGuideMultiJobSaveCommand streaming = new StreamingGuideMultiJobSaveCommand();
        streaming.setContent(content);
        streaming.setOdsDatabaseBindingId(42L);
        StreamingGuideMultiJobSaveCommand streamingRoundTrip = JSONUtils.parseObject(
                JSONUtils.toJsonString(streaming), StreamingGuideMultiJobSaveCommand.class);
        assertBinding(42L, streamingRoundTrip.getOdsDatabaseBindingId());
        assertBinding(42L, streamingRoundTrip.getContent().getTarget().getOdsDatabaseBindingId());
    }

    @Test
    void singleWorkflowTargetNodeRetainsBindingInStructuredJson() {
        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setOdsDatabaseBindingId(42L);
        command.setWorkflow(Map.of(
                "nodes", java.util.List.of(Map.of(
                        "data", Map.of(
                                "nodeType", "sink",
                                "config", Map.of("odsDatabaseBindingId", 42L))))));

        BatchGuideSingleJobSaveCommand roundTrip = JSONUtils.parseObject(
                JSONUtils.toJsonString(command), BatchGuideSingleJobSaveCommand.class);
        assertBinding(42L, roundTrip.getOdsDatabaseBindingId());
        Map<?, ?> node = (Map<?, ?>) ((java.util.List<?>) roundTrip.getWorkflow().get("nodes")).get(0);
        Map<?, ?> data = (Map<?, ?>) node.get("data");
        Map<?, ?> config = (Map<?, ?>) data.get("config");
        assertEquals(42, ((Number) config.get("odsDatabaseBindingId")).intValue());
    }

    private <T extends org.apache.seatunnel.web.spi.bean.dto.command.JobDefinitionSaveCommand> T command(T command) {
        command.setOdsDatabaseBindingId(42L);
        return command;
    }

    private void assertBinding(Long expected, Long actual) {
        assertEquals(expected, actual);
    }
}
