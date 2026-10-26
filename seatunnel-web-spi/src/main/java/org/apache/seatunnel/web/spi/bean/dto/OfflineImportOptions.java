package org.apache.seatunnel.web.spi.bean.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Size;

/** User-selectable knobs of a DMP/SQL offline import run. Null fields keep defaults. */
@Data
@Schema(description = "Offline import task options")
public class OfflineImportOptions {

    /** SQL import: abort the whole run on the first failed statement (default true). */
    private Boolean stopOnError;

    /** SQL import: file charset, e.g. UTF-8 / GBK (default UTF-8). */
    @Size(max = 32, message = "文件编码不合法")
    private String encoding;

    /** DMP import: schema name inside the dump file (default: same as target schema). */
    @Size(max = 128, message = "源 Schema 不能超过128个字符")
    private String sourceSchema;

    /** DMP import: keep going on object-exists style errors, like dimp IGNORE=Y (default true). */
    private Boolean ignoreErrors;
}
