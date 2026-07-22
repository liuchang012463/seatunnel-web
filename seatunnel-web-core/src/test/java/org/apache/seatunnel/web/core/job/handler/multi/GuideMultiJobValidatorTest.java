package org.apache.seatunnel.web.core.job.handler.multi;

import org.apache.seatunnel.web.spi.bean.dto.config.GuideMultiJobContent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class GuideMultiJobValidatorTest {

    @Test
    void kafkaFlowDoesNotRequireRelationalTableMatch() {
        GuideMultiJobContent content = new GuideMultiJobContent();

        GuideMultiJobContent.WorkflowSourceConfig source =
                new GuideMultiJobContent.WorkflowSourceConfig();
        source.setDatasourceId("1");
        source.setDbType("KAFKA");
        source.setPluginName("KAFKA");
        content.setSource(source);

        GuideMultiJobContent.WorkflowTargetConfig target =
                new GuideMultiJobContent.WorkflowTargetConfig();
        target.setDatasourceId("2");
        target.setDbType("KAFKA");
        target.setPluginName("KAFKA");
        content.setTarget(target);

        assertDoesNotThrow(() -> new GuideMultiJobValidator().validate(content));
    }
}
