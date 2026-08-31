package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.api.lake.table.LakeManagedTableVO;
import org.apache.seatunnel.web.spi.bean.dto.LakeUnmanagedTableBindDTO;

/** Explicit association lifecycle for existing, Web-unmanaged Doris tables. */
public interface LakeUnmanagedTableBindingService {

    LakeManagedTableVO bind(LakeUnmanagedTableBindDTO request);

    LakeManagedTableVO unbind(Long mappingId);
}
