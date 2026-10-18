-- V1_0_42 created the snapshot columns with VARCHAR(512), but the definition table stores
-- table names in VARCHAR(1024) and whole-database sync joins many names into one value.
ALTER TABLE t_seatunnel_web_job_instance
    ALTER COLUMN source_table VARCHAR(1024);

ALTER TABLE t_seatunnel_web_job_instance
    ALTER COLUMN sink_table VARCHAR(1024);
