package org.apache.seatunnel.web.spi.bean.dto.batch;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.spi.bean.dto.command.BatchJobSaveCommand;
import org.apache.seatunnel.web.spi.bean.dto.command.GuideSingleJobContentCommand;
import org.apache.seatunnel.web.spi.bean.dto.config.BatchJobEnvConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.JobBasicConfig;
import org.apache.seatunnel.web.spi.bean.dto.config.JobScheduleConfig;

import java.util.Map;

/**
 * Single-table bounded incremental micro-batch definition.
 *
 * <p>The separate command and mode keep full-load entry points and
 * incremental entry points from silently changing each other's semantics.</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class BatchGuideSingleIncrementalJobSaveCommand
        implements BatchJobSaveCommand, GuideSingleJobContentCommand {

    private Long id;

    private JobBasicConfig basic;

    private Map<String, Object> workflow;

    private JobScheduleConfig schedule;

    private BatchJobEnvConfig env;

    private Long odsDatabaseBindingId;

    @Override
    public JobDefinitionMode getMode() {
        return JobDefinitionMode.GUIDE_SINGLE_INCREMENTAL;
    }
}
