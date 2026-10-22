-- Known Engine ids can be reconciled automatically; legacy untracked submissions cannot.
UPDATE t_seatunnel_web_job_instance
SET job_status = CASE WHEN engine_job_id IS NOT NULL AND TRIM(engine_job_id) <> ''
                     THEN 'STARTING' ELSE 'FAILED' END,
    end_time = CASE WHEN engine_job_id IS NOT NULL AND TRIM(engine_job_id) <> ''
                    THEN NULL ELSE CURRENT_TIMESTAMP END,
    error_message = CASE WHEN engine_job_id IS NOT NULL AND TRIM(engine_job_id) <> ''
                         THEN NULL ELSE '历史任务提交未记录 Engine 作业 ID，已自动标记失败。' END
WHERE UPPER(job_status) = 'SUBMISSION_UNKNOWN';
