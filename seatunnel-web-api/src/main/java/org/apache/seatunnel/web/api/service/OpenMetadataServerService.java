package org.apache.seatunnel.web.api.service;

import org.apache.seatunnel.web.spi.bean.dto.OpenMetadataServerConfigDTO;
import org.apache.seatunnel.web.spi.bean.vo.OpenMetadataServerConfigVO;

public interface OpenMetadataServerService {

    OpenMetadataServerConfigVO getConfig();

    OpenMetadataServerConfigVO saveConfig(OpenMetadataServerConfigDTO request);

    OpenMetadataServerConfigVO testConfig(OpenMetadataServerConfigDTO request);
}
