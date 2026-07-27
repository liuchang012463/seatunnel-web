package org.apache.seatunnel.web.spi.bean.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileEntryVO {
    private String name;
    private String path;
    private String type;
    private Long size;
    private Long modifiedTime;
}
