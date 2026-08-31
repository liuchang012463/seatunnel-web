package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Table node used by the interactive ER canvas. */
@Data
public class DataExplorationErNodeVO {
    private String id;
    private String name;
    private String displayName;
    private String description;
    private String fullyQualifiedName;
    private List<DataExplorationErColumnVO> columns = new ArrayList<>();
}
