-- 精细化策略范围：空值表示不限，不改变已应用表的策略快照。
ALTER TABLE t_seatunnel_web_lake_lifecycle_policy ADD COLUMN data_source_type VARCHAR(64);
ALTER TABLE t_seatunnel_web_lake_lifecycle_policy ADD COLUMN source_data_source_id BIGINT;
ALTER TABLE t_seatunnel_web_lake_lifecycle_policy ADD COLUMN unit_id BIGINT;
ALTER TABLE t_seatunnel_web_lake_lifecycle_policy ADD COLUMN business_system_id BIGINT;
