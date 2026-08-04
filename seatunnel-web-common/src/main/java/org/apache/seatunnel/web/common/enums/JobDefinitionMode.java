package org.apache.seatunnel.web.common.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;


@Getter
@AllArgsConstructor
public enum JobDefinitionMode {

    SCRIPT("SCRIPT", "脚本模式"),
    GUIDE_SINGLE("GUIDE_SINGLE", "单表模式"),
    GUIDE_SINGLE_INCREMENTAL("GUIDE_SINGLE_INCREMENTAL", "单表增量微批模式"),
    GUIDE_MULTI("GUIDE_MULTI", "多表模式"),
    FILE_SYNC("FILE_SYNC", "文件同步模式");

    @EnumValue
    private final String code;
    private final String description;
}
