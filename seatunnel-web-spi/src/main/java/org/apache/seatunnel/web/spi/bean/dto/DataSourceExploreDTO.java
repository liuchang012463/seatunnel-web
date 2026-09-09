package org.apache.seatunnel.web.spi.bean.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** One manual data-source exploration is scoped to one Database and, optionally, one Schema. */
@Data
@Schema(description = "Manual data-source exploration request")
public class DataSourceExploreDTO {

    @Schema(description = "OpenMetadata Database fully qualified name", requiredMode = Schema.RequiredMode.REQUIRED)
    private String databaseFqn;

    @Schema(description = "Optional OpenMetadata DatabaseSchema fully qualified name")
    private String schemaFqn;
}
