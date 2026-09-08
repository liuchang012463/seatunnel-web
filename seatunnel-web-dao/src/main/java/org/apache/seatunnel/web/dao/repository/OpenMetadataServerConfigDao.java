package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.OpenMetadataServerConfig;

public interface OpenMetadataServerConfigDao extends IDao<OpenMetadataServerConfig> {

    OpenMetadataServerConfig querySingleton();

    int updateSingleton(OpenMetadataServerConfig config);
}
