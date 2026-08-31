package org.apache.seatunnel.web.spi.bean.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** One manual data-source exploration is intentionally scoped to one Database. */
@Data
@Schema(description = "Manual data-source exploration request")
public class DataSourceExploreDTO {

    @Schema(description = "OpenMetadata Database fully qualified name", requiredMode = Schema.RequiredMode.REQUIRED)
    private String databaseFqn;
}
