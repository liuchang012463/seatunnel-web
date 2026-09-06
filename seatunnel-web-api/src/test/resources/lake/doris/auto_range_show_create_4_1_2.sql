CREATE TABLE `auto_range_probe` (
  `id` varchar(255) NOT NULL,
  `event_time` datetime NOT NULL,
  `payload` text NULL
) ENGINE=OLAP
DUPLICATE KEY(`id`, `event_time`)
AUTO PARTITION BY RANGE (date_trunc(`event_time`, 'day'))
()
DISTRIBUTED BY HASH(`id`) BUCKETS AUTO
PROPERTIES (
"replication_allocation" = "tag.location.default: 1",
"min_load_replica_num" = "-1",
"is_being_synced" = "false",
"partition.retention_count" = "5",
"storage_medium" = "hdd",
"storage_format" = "V2",
"inverted_index_storage_format" = "V3",
"light_schema_change" = "true",
"disable_auto_compaction" = "false",
"group_commit_interval_ms" = "10000",
"group_commit_data_bytes" = "134217728"
);
