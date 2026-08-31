package org.apache.seatunnel.web.dao.repository;


import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.seatunnel.web.common.enums.ConnStatus;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceDTO;

import java.util.Collection;
import java.util.List;

public interface DataSourceDao extends IDao<DataSource> {

    boolean checkName(String name);

    boolean checkNameExcludeId(String name, Long id);

    IPage<DataSource> queryPage(DataSourceDTO dto);

    /**
     * Queries data sources while constraining their business systems. A unit
     * filter is resolved to system IDs by the API service before calling this
     * method, keeping the data-source table free of a redundant unit_id column.
     */
    IPage<DataSource> queryPage(DataSourceDTO dto, Collection<Long> businessSystemIds);

    /**
     * Queries a page constrained by the physical data-source identifiers. The
     * identifier predicate is applied to the paged SQL query so callers do not
     * have to page first and filter the result in memory.
     */
    IPage<DataSource> queryPageByDataSourceIds(DataSourceDTO dto, Collection<Long> dataSourceIds);

    List<DataSource> queryByDbType(String dbType);

    List<String> queryDataSourceUnits();

    int updateConnStatus(Long id, ConnStatus status);

    boolean existsByBusinessSystemId(Long businessSystemId);


}
