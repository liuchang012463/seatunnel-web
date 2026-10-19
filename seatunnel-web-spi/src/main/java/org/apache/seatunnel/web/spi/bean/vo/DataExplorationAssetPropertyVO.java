package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

/** One ordered, display-ready property of an exploration asset. */
@Data
public class DataExplorationAssetPropertyVO {
    private String key;
    private String label;
    private Object value;
    /** TEXT, NUMBER, SIZE, DURATION, CODE or LIST. */
    private String displayType;
}
