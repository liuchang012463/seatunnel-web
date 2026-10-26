package org.apache.seatunnel.web.spi.bean.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Create request for one DMP/SQL offline import task. */
@Data
@Schema(description = "Offline import task create request")
public class OfflineImportTaskCreateDTO {

    @NotBlank(message = "任务名称不能为空")
    @Size(max = 128, message = "任务名称不能超过128个字符")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Size(max = 512, message = "任务描述不能超过512个字符")
    private String description;

    /** One of org.apache.seatunnel.web.spi.enums.OfflineImportType. */
    @NotBlank(message = "导入类型不能为空")
    @Size(max = 32, message = "导入类型不合法")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String importType;

    /** Uploaded file resource backing this task. */
    @NotNull(message = "文件资源不能为空")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Long fileResourceId;

    @NotNull(message = "目标数据源不能为空")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Long targetDatasourceId;

    @Size(max = 128, message = "目标 Schema 不能超过128个字符")
    private String targetSchema;

    @Valid
    private OfflineImportOptions options;
}
