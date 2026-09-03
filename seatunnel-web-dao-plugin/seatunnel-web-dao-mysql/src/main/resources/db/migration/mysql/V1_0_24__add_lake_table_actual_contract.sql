-- Cache the last explicitly observed Doris structural contract.  GET detail
-- remains read-only; the value is refreshed only by an explicit reconcile.
ALTER TABLE `t_seatunnel_web_lake_ods_table_mapping`
    ADD COLUMN `actual_contract_json` longtext DEFAULT NULL
        COMMENT '脱敏 Doris 实际结构快照，仅由显式对账写入' AFTER `target_contract_json`;
