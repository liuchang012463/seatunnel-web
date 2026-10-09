package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.seatunnel.web.dao.entity.StreamingJobMetrics;

import java.util.List;
import java.util.Map;

@Mapper
public interface StreamingJobMetricsMapper extends BaseMapper<StreamingJobMetrics> {

    @Select("""
            WITH range_keys AS (
                SELECT DISTINCT job_instance_id, pipeline_id
                FROM t_seatunnel_web_streaming_job_metrics_snapshot
                WHERE collect_time_ms >= #{startTimeMs}
                  AND collect_time_ms <= #{endTimeMs}
            ),
            baseline_ranked AS (
                SELECT metric.job_instance_id, metric.pipeline_id, metric.collect_time_ms,
                       metric.write_row_count, metric.write_bytes,
                       ROW_NUMBER() OVER (
                           PARTITION BY metric.job_instance_id, metric.pipeline_id
                           ORDER BY metric.collect_time_ms DESC
                       ) AS row_number_in_baseline
                FROM t_seatunnel_web_streaming_job_metrics_snapshot metric
                JOIN range_keys range_key
                  ON range_key.job_instance_id = metric.job_instance_id
                 AND range_key.pipeline_id = metric.pipeline_id
                WHERE metric.collect_time_ms < #{startTimeMs}
            ),
            relevant_samples AS (
                SELECT sample.job_instance_id, sample.pipeline_id, sample.collect_time_ms,
                       sample.write_row_count, sample.write_bytes, 0 AS is_in_range,
                       CASE WHEN instance.start_time >= FROM_UNIXTIME(#{startTimeMs} / 1000)
                            THEN 1 ELSE 0 END AS started_in_range
                FROM baseline_ranked sample
                LEFT JOIN t_seatunnel_web_streaming_job_instance instance
                       ON instance.id = sample.job_instance_id
                WHERE sample.row_number_in_baseline = 1
                UNION ALL
                SELECT metric.job_instance_id, metric.pipeline_id, metric.collect_time_ms,
                       metric.write_row_count, metric.write_bytes, 1 AS is_in_range,
                       CASE WHEN instance.start_time >= FROM_UNIXTIME(#{startTimeMs} / 1000)
                            THEN 1 ELSE 0 END AS started_in_range
                FROM t_seatunnel_web_streaming_job_metrics_snapshot metric
                LEFT JOIN t_seatunnel_web_streaming_job_instance instance
                       ON instance.id = metric.job_instance_id
                WHERE metric.collect_time_ms >= #{startTimeMs}
                  AND metric.collect_time_ms <= #{endTimeMs}
            ),
            lagged_samples AS (
                SELECT sample.*,
                       LAG(sample.write_row_count) OVER (
                           PARTITION BY sample.job_instance_id, sample.pipeline_id
                           ORDER BY sample.collect_time_ms
                       ) AS previous_write_row_count,
                       LAG(sample.write_bytes) OVER (
                           PARTITION BY sample.job_instance_id, sample.pipeline_id
                           ORDER BY sample.collect_time_ms
                       ) AS previous_write_bytes
                FROM relevant_samples sample
            ),
            sample_deltas AS (
                SELECT job_instance_id, is_in_range,
                       CASE
                           WHEN is_in_range = 0 THEN 0
                           -- Without a predecessor the cumulative counter can only be
                           -- attributed to the window when the job itself started in it;
                           -- otherwise the baseline was pruned and the difference is unknown.
                           WHEN previous_write_row_count IS NULL AND started_in_range = 1
                               THEN write_row_count
                           WHEN previous_write_row_count IS NULL THEN 0
                           WHEN write_row_count >= previous_write_row_count
                               THEN write_row_count - previous_write_row_count
                           ELSE write_row_count
                       END AS records_delta,
                       CASE
                           WHEN is_in_range = 0 THEN 0
                           WHEN previous_write_bytes IS NULL AND started_in_range = 1
                               THEN write_bytes
                           WHEN previous_write_bytes IS NULL THEN 0
                           WHEN write_bytes >= previous_write_bytes
                               THEN write_bytes - previous_write_bytes
                           ELSE write_bytes
                       END AS bytes_delta
                FROM lagged_samples
            ),
            range_instances AS (
                SELECT DISTINCT job_instance_id
                FROM t_seatunnel_web_streaming_job_metrics_snapshot
                WHERE collect_time_ms >= #{startTimeMs}
                  AND collect_time_ms <= #{endTimeMs}
            ),
            range_delay_samples AS (
                SELECT job_instance_id, collect_time_ms, MAX(record_delay) AS record_delay
                FROM t_seatunnel_web_streaming_job_metrics_snapshot
                WHERE collect_time_ms >= #{startTimeMs}
                  AND collect_time_ms <= #{endTimeMs}
                GROUP BY job_instance_id, collect_time_ms
            ),
            range_delays AS (
                SELECT job_instance_id, AVG(record_delay) AS record_delay
                FROM range_delay_samples
                GROUP BY job_instance_id
            ),
            range_status_ranked AS (
                SELECT job_instance_id, job_status,
                       ROW_NUMBER() OVER (
                           PARTITION BY job_instance_id
                           ORDER BY collect_time_ms DESC
                       ) AS row_number_in_range
                FROM t_seatunnel_web_streaming_job_metrics_snapshot
                WHERE collect_time_ms >= #{startTimeMs}
                  AND collect_time_ms <= #{endTimeMs}
            ),
            task_summary AS (
                SELECT range_instance.job_instance_id,
                       COALESCE(range_status.job_status, current_metric.job_status, instance.job_status, '') AS job_status,
                       range_delay.record_delay
                FROM range_instances range_instance
                JOIN t_seatunnel_web_streaming_job_instance instance
                  ON instance.id = range_instance.job_instance_id
                LEFT JOIN range_status_ranked range_status
                  ON range_status.job_instance_id = range_instance.job_instance_id
                 AND range_status.row_number_in_range = 1
                LEFT JOIN t_seatunnel_web_streaming_job_metrics_current current_metric
                  ON current_metric.job_instance_id = range_instance.job_instance_id
                LEFT JOIN range_delays range_delay
                  ON range_delay.job_instance_id = range_instance.job_instance_id
            )
            SELECT COALESCE((SELECT SUM(records_delta) FROM sample_deltas WHERE is_in_range = 1), 0) AS totalRecords,
                   COALESCE((SELECT SUM(bytes_delta) FROM sample_deltas WHERE is_in_range = 1), 0) AS totalBytes,
                   COUNT(1) AS totalTasks,
                   COALESCE(SUM(CASE WHEN UPPER(job_status) = 'FINISHED' THEN 1 ELSE 0 END), 0) AS successTasks,
                   COALESCE(SUM(CASE WHEN UPPER(job_status) IN ('FAILED', 'UNKNOWABLE') THEN 1 ELSE 0 END), 0) AS failedTasks,
                   COALESCE(SUM(CASE WHEN UPPER(job_status) IN ('INITIALIZING', 'CREATED', 'PENDING', 'SCHEDULED', 'RUNNING', 'FAILING', 'DOING_SAVEPOINT', 'CANCELING') THEN 1 ELSE 0 END), 0) AS runningTasks,
                   COALESCE(SUM(CASE WHEN UPPER(job_status) IN ('CANCELED', 'SAVEPOINT_DONE') THEN 1 ELSE 0 END), 0) AS stoppedTasks,
                   COALESCE(AVG(record_delay), 0) AS avgRecordDelay
            FROM task_summary
            """)
    Map<String, Object> selectOverviewSummary(@Param("startTimeMs") long startTimeMs,
                                              @Param("endTimeMs") long endTimeMs);

    @Select("""
            WITH range_keys AS (
                SELECT DISTINCT job_instance_id, pipeline_id
                FROM t_seatunnel_web_streaming_job_metrics_snapshot
                WHERE collect_time_ms >= #{startTimeMs}
                  AND collect_time_ms <= #{endTimeMs}
            ),
            baseline_ranked AS (
                SELECT metric.job_instance_id, metric.pipeline_id, metric.collect_time_ms,
                       metric.collect_time,
                       metric.write_row_count, metric.write_bytes,
                       ROW_NUMBER() OVER (
                           PARTITION BY metric.job_instance_id, metric.pipeline_id
                           ORDER BY metric.collect_time_ms DESC
                       ) AS row_number_in_baseline
                FROM t_seatunnel_web_streaming_job_metrics_snapshot metric
                JOIN range_keys range_key
                  ON range_key.job_instance_id = metric.job_instance_id
                 AND range_key.pipeline_id = metric.pipeline_id
                WHERE metric.collect_time_ms < #{startTimeMs}
            ),
            relevant_samples AS (
                SELECT sample.job_instance_id, sample.pipeline_id, sample.collect_time_ms,
                       sample.collect_time,
                       sample.write_row_count, sample.write_bytes, 0 AS is_in_range,
                       CAST(0 AS DECIMAL(20, 4)) AS write_qps,
                       CAST(0 AS DECIMAL(20, 4)) AS write_bps,
                       CASE WHEN instance.start_time >= FROM_UNIXTIME(#{startTimeMs} / 1000)
                            THEN 1 ELSE 0 END AS started_in_range
                FROM baseline_ranked sample
                LEFT JOIN t_seatunnel_web_streaming_job_instance instance
                       ON instance.id = sample.job_instance_id
                WHERE sample.row_number_in_baseline = 1
                UNION ALL
                SELECT metric.job_instance_id, metric.pipeline_id, metric.collect_time_ms,
                       metric.collect_time,
                       metric.write_row_count, metric.write_bytes, 1 AS is_in_range,
                       metric.write_qps, metric.write_bps,
                       CASE WHEN instance.start_time >= FROM_UNIXTIME(#{startTimeMs} / 1000)
                            THEN 1 ELSE 0 END AS started_in_range
                FROM t_seatunnel_web_streaming_job_metrics_snapshot metric
                LEFT JOIN t_seatunnel_web_streaming_job_instance instance
                       ON instance.id = metric.job_instance_id
                WHERE metric.collect_time_ms >= #{startTimeMs}
                  AND metric.collect_time_ms <= #{endTimeMs}
            ),
            lagged_samples AS (
                SELECT sample.*,
                       LAG(sample.write_row_count) OVER (
                           PARTITION BY sample.job_instance_id, sample.pipeline_id
                           ORDER BY sample.collect_time_ms
                       ) AS previous_write_row_count,
                       LAG(sample.write_bytes) OVER (
                           PARTITION BY sample.job_instance_id, sample.pipeline_id
                           ORDER BY sample.collect_time_ms
                       ) AS previous_write_bytes
                FROM relevant_samples sample
            ),
            sample_deltas AS (
                SELECT lagged.*,
                       CASE
                           WHEN is_in_range = 0 THEN 0
                           -- Without a predecessor the cumulative counter can only be
                           -- attributed to the window when the job itself started in it;
                           -- otherwise the baseline was pruned and the difference is unknown.
                           WHEN previous_write_row_count IS NULL AND started_in_range = 1
                               THEN write_row_count
                           WHEN previous_write_row_count IS NULL THEN 0
                           WHEN write_row_count >= previous_write_row_count
                               THEN write_row_count - previous_write_row_count
                           ELSE write_row_count
                       END AS records_delta,
                       CASE
                           WHEN is_in_range = 0 THEN 0
                           WHEN previous_write_bytes IS NULL AND started_in_range = 1
                               THEN write_bytes
                           WHEN previous_write_bytes IS NULL THEN 0
                           WHEN write_bytes >= previous_write_bytes
                               THEN write_bytes - previous_write_bytes
                           ELSE write_bytes
                       END AS bytes_delta
                FROM lagged_samples lagged
            ),
            bucket_metrics AS (
                SELECT CASE #{granularity}
                           WHEN 'MINUTE' THEN DATE_FORMAT(collect_time, '%Y-%m-%d %H:%i')
                           WHEN 'HOUR' THEN DATE_FORMAT(collect_time, '%Y-%m-%d %H:00')
                           WHEN 'DAY' THEN DATE_FORMAT(collect_time, '%Y-%m-%d')
                           ELSE DATE_FORMAT(collect_time, '%Y-%m-%d %H:%i')
                       END AS bucket,
                       job_instance_id,
                       pipeline_id,
                       SUM(records_delta) AS records_delta,
                       SUM(bytes_delta) AS bytes_delta,
                       AVG(write_qps) AS write_qps,
                       AVG(write_bps) AS write_bps
                FROM sample_deltas
                WHERE is_in_range = 1
                GROUP BY bucket, job_instance_id, pipeline_id
            )
            SELECT bucket AS date,
                   COALESCE(SUM(records_delta), 0) AS recordsValue,
                   COALESCE(SUM(bytes_delta), 0) AS bytesValue,
                   COALESCE(SUM(write_qps), 0) AS recordsSpeed,
                   COALESCE(SUM(write_bps), 0) AS bytesSpeed
            FROM bucket_metrics
            GROUP BY bucket
            ORDER BY bucket ASC
            """)
    List<Map<String, Object>> selectOverviewTrend(@Param("startTimeMs") long startTimeMs,
                                                  @Param("endTimeMs") long endTimeMs,
                                                  @Param("granularity") String granularity);
}
