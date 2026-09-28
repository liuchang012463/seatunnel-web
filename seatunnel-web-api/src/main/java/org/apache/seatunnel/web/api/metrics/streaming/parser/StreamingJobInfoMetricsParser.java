package org.apache.seatunnel.web.api.metrics.streaming.parser;

import org.apache.seatunnel.web.api.metrics.fetch.EngineJobInfo;
import org.apache.seatunnel.web.api.metrics.streaming.model.StreamingParsedJobMetrics;

public interface StreamingJobInfoMetricsParser {

    StreamingParsedJobMetrics parse(EngineJobInfo jobInfo);
}
