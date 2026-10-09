-- The metrics overview queries filter the snapshot table by collect_time_ms alone, and every
-- existing index of this table leads with another column, so each aggregation scanned the whole
-- table (instances x pipelines x collect interval, retained for the configured retention).
ALTER TABLE `t_seatunnel_web_streaming_job_metrics_snapshot`
    ADD KEY `idx_streaming_metrics_collect_time` (`collect_time_ms`);
