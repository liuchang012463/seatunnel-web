package org.apache.seatunnel.web.core.job.bridge;

import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.batch.BatchGuideSingleJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.streaming.StreamingGuideMultiJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LakeJobBindingResolverTest {

    @Test
    void singleTargetBindingIsUsedWhenCommandBindingIsAbsent() {
        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setWorkflow(singleWorkflow(17L));

        assertEquals(17L, LakeJobBindingResolver.resolve(command));
    }

    @Test
    void multiAndWholeUseTheCommandBindingWhenTargetAgrees() {
        BatchGuideMultiJobSaveCommand command = new BatchGuideMultiJobSaveCommand();
        command.setOdsDatabaseBindingId(17L);
        command.setContent(multiContent(17L, "4"));

        assertEquals(17L, LakeJobBindingResolver.resolve(command));
    }

    @Test
    void commandAndNestedTargetBindingMismatchIsRejected() {
        StreamingGuideMultiJobSaveCommand command = new StreamingGuideMultiJobSaveCommand();
        command.setOdsDatabaseBindingId(17L);
        command.setContent(multiContent(18L, "4"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> LakeJobBindingResolver.resolve(command));

        assertEquals("odsDatabaseBindingId differs between command and target config",
                exception.getMessage());
    }

    @Test
    void singleCommandAndNestedSinkBindingMismatchIsRejected() {
        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        command.setOdsDatabaseBindingId(17L);
        command.setWorkflow(singleWorkflow(18L));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> LakeJobBindingResolver.resolve(command));

        assertEquals("odsDatabaseBindingId differs between command and target config",
                exception.getMessage());
    }

    @Test
    void multipleSingleSinkValuesCannotSelectDifferentBindings() {
        BatchGuideSingleJobSaveCommand command = new BatchGuideSingleJobSaveCommand();
        Map<String, Object> workflow = new HashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("sink", 17L));
        nodes.add(node("sink", 18L));
        workflow.put("nodes", nodes);
        command.setWorkflow(workflow);

        assertThrows(IllegalArgumentException.class,
                () -> LakeJobBindingResolver.resolve(command));
    }

    private GuideMultiJobContent multiContent(Long bindingId, String mode) {
        GuideMultiJobContent content = new GuideMultiJobContent();
        GuideMultiJobContent.WorkflowTargetConfig target =
                new GuideMultiJobContent.WorkflowTargetConfig();
        target.setOdsDatabaseBindingId(bindingId);
        content.setTarget(target);
        GuideMultiJobContent.TableMatchConfig tableMatch =
                new GuideMultiJobContent.TableMatchConfig();
        tableMatch.setMode(mode);
        content.setTableMatch(tableMatch);
        return content;
    }

    private Map<String, Object> singleWorkflow(Long bindingId) {
        Map<String, Object> workflow = new HashMap<>();
        workflow.put("nodes", List.of(node("source", null), node("sink", bindingId)));
        return workflow;
    }

    private Map<String, Object> node(String nodeType, Long bindingId) {
        Map<String, Object> data = new HashMap<>();
        data.put("nodeType", nodeType);
        if (bindingId != null) {
            data.put("config", new HashMap<>(Map.of("odsDatabaseBindingId", bindingId)));
        }
        return new HashMap<>(Map.of("data", data));
    }
}
