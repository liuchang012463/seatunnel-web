package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Page envelope for tables discovered by an OpenMetadata scan. */
@Data
public class DataExplorationTablePageVO {
    private List<DataExplorationTableVO> records = new ArrayList<>();
    private long total;
    private int pageNo;
    private int pageSize;
}
