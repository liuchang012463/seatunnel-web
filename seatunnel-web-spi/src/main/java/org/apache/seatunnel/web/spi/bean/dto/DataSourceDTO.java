package org.apache.seatunnel.web.spi.bean.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.seatunnel.web.common.enums.ConnStatus;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.EnvironmentEnum;
import org.apache.seatunnel.web.spi.bean.dto.pagination.PaginationBaseDTO;
import org.apache.seatunnel.web.spi.enums.DbType;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Data source DTO for creating and updating data sources")
public class DataSourceDTO extends PaginationBaseDTO {

    private Long id;

    private String name;

    private String dataSourceUnit;

    /** Canonical owning business system. Required for create/update requests. */
    private Long businessSystemId;

    /** Optional query-only unit filter; the unit is derived through BusinessSystem. */
    private Long unitId;

    private DbType dbType;

    /** Compatible multi-type filter used by category-based datasource management. */
    private List<DbType> dbTypes;

    private EnvironmentEnum environment;

    private String originalJson;

    private String connectionParams;

    private String remark;

    private ConnStatus connStatus;

    private DataSourceLifecycleStatus status;

    /** Internal query flag used by lake source pages; never accepted from public APIs. */
    @JsonIgnore
    private Boolean excludeSystemManaged;
}
