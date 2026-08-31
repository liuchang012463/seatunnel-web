package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * User-controlled part of one MANAGED target column.  Source type, ordinal
 * and nullability are deliberately absent: the service obtains those facts
 * from the fresh OpenMetadata snapshot.
 */
@Data
public class LakeManagedTableColumnDTO {

    @JsonAlias("sourceName")
    private String sourceField;

    @JsonAlias("targetName")
    private String targetField;

    /** Doris type token, for example VARCHAR(255) or DECIMAL(18,2). */
    private String targetType;

    private Boolean key;
}
