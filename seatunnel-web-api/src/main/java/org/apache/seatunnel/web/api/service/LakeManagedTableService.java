package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.api.lake.table.LakeManagedTableDeleteImpactVO;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTablePreviewVO;
import org.apache.seatunnel.web.api.lake.table.LakeManagedTableVO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableDeleteDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTablePreviewDTO;

/** Physical MANAGED ODS table lifecycle boundary. */
public interface LakeManagedTableService {

    LakeManagedTablePreviewVO preview(LakeManagedTablePreviewDTO request);

    LakeManagedTableVO create(LakeManagedTableCreateDTO request);

    LakeManagedTableVO detail(Long id);

    /** Explicit, read-through reconcile; detail() remains a cached read. */
    LakeManagedTableVO reconcile(Long id);

    LakeManagedTableVO retry(Long id);

    LakeManagedTableDeleteImpactVO deleteImpact(Long id);

    void delete(Long id, LakeManagedTableDeleteDTO request);
}
