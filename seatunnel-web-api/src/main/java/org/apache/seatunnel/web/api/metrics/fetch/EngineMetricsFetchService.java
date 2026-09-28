package org.apache.seatunnel.web.api.metrics.fetch;


public interface EngineMetricsFetchService {

    EngineJobInfo fetchJobInfo(Long clientId, String engineJobId);
}
