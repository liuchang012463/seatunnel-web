package org.apache.seatunnel.plugin.datasource.api.datasource;

import org.apache.seatunnel.web.spi.bean.vo.OptionVO;

import java.util.List;

/** Lists the top-level resources exposed by a datasource (tables, topics, and so on). */
public interface DataSourceCatalog {

    List<OptionVO> listOptions();
}
