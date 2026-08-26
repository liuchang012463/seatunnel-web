package org.apache.seatunnel.web.spi.bean.vo;

import lombok.Data;
import org.apache.seatunnel.web.spi.enums.DataSourceTopologyNodeType;

import java.util.ArrayList;
import java.util.List;

/** A deliberately shallow topology node; descendants are loaded lazily. */
@Data
public class DataSourceTopologyNodeVO {

    private String id;
    private DataSourceTopologyNodeType nodeType;
    private String name;
    private List<DataSourceTopologyNodeVO> children = new ArrayList<>();
}
