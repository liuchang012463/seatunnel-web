package org.apache.seatunnel.web.dao.repository;

import org.apache.seatunnel.web.dao.entity.SeaTunnelClient;

import java.util.Date;
import java.util.List;

public interface SeaTunnelClientDao extends IDao<SeaTunnelClient> {

    SeaTunnelClient selectById(Long clientId);

    List<SeaTunnelClient> listProbeClients();

    int updateHealthStatus(Long clientId, Integer healthStatus, Date heartbeatTime);
}
