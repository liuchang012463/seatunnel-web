package org.apache.seatunnel.web.spi.bean.dto.command;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Creates copies of one or more existing job definitions.
 *
 * <p>The copied definitions are always created offline. This keeps batch
 * creation safe: a copy must be explicitly reviewed and brought online before
 * it can be executed.</p>
 */
@Data
public class JobDefinitionBatchCreateCommand {

    @NotEmpty(message = "templateJobDefinitionIds cannot be empty")
    private List<Long> templateJobDefinitionIds;

    @NotNull(message = "copiesPerTemplate cannot be null")
    @Min(value = 1, message = "copiesPerTemplate must be at least 1")
    @Max(value = 20, message = "copiesPerTemplate must be at most 20")
    private Integer copiesPerTemplate;

    @Size(max = 128, message = "jobNamePrefix must be at most 128 characters")
    private String jobNamePrefix;
}
