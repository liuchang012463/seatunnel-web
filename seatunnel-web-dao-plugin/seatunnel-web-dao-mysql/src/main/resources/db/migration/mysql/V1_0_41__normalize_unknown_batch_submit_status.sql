UPDATE `t_seatunnel_web_job_instance`
SET `job_status` = 'FAILED'
WHERE UPPER(`job_status`) = 'SUBMISSION_UNKNOWN';
