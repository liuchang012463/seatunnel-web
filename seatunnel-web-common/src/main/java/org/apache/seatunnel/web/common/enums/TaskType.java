package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Stable business-level task type.
 *
 * <p>This is intentionally separate from {@link JobMode}, which represents
 * the SeaTunnel Engine runtime mode ({@code BATCH}/{@code STREAMING}).
 * Legacy rows with a {@code null} value are resolved by the service layer
 * using their definition mode and content during the compatibility period.</p>
 */
@AllArgsConstructor
@Getter
public enum TaskType {
    BATCH("BATCH", "批量数据引接"),
    STREAM("STREAM", "实时数据引接"),
    FILE_INGEST("FILE_INGEST", "文件数据引接"),
    FILE_TRANSFER("FILE_TRANSFER", "文件传输");

    @EnumValue
    private final String code;

    private final String description;
}
