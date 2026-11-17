-- Repair leases retained by the former NOT_NULL update strategy.
-- Only a matching terminal operation can release a lease; running operations stay untouched.

UPDATE t_seatunnel_web_lake_source_object_ref resource
SET operation_token = NULL, lock_version = lock_version + 1, update_time = CURRENT_TIMESTAMP
WHERE resource.operation_token IS NOT NULL AND EXISTS (
    SELECT 1 FROM t_seatunnel_web_lake_resource_operation operation
    WHERE operation.operation_token = resource.operation_token
      AND operation.resource_type = 'SOURCE_OBJECT_REF'
      AND operation.resource_id = resource.id
      AND operation.generation = resource.generation
      AND operation.status IN ('SUCCEEDED', 'FAILED', 'IGNORED')
);

UPDATE t_seatunnel_web_lake_ods_database_binding resource
SET operation_token = NULL, lock_version = lock_version + 1, update_time = CURRENT_TIMESTAMP
WHERE resource.operation_token IS NOT NULL AND EXISTS (
    SELECT 1 FROM t_seatunnel_web_lake_resource_operation operation
    WHERE operation.operation_token = resource.operation_token
      AND operation.resource_type = 'ODS_DATABASE_BINDING'
      AND operation.resource_id = resource.id
      AND operation.generation = resource.generation
      AND operation.status IN ('SUCCEEDED', 'FAILED', 'IGNORED')
);

UPDATE t_seatunnel_web_lake_ods_table_mapping resource
SET operation_token = NULL, lock_version = lock_version + 1, update_time = CURRENT_TIMESTAMP
WHERE resource.operation_token IS NOT NULL AND EXISTS (
    SELECT 1 FROM t_seatunnel_web_lake_resource_operation operation
    WHERE operation.operation_token = resource.operation_token
      AND operation.resource_type = 'ODS_TABLE_MAPPING'
      AND operation.resource_id = resource.id
      AND operation.generation = resource.generation
      AND operation.status IN ('SUCCEEDED', 'FAILED', 'IGNORED')
);

UPDATE t_seatunnel_web_lake_external_catalog_binding resource
SET operation_token = NULL, lock_version = lock_version + 1, update_time = CURRENT_TIMESTAMP
WHERE resource.operation_token IS NOT NULL AND EXISTS (
    SELECT 1 FROM t_seatunnel_web_lake_resource_operation operation
    WHERE operation.operation_token = resource.operation_token
      AND operation.resource_type = 'EXTERNAL_CATALOG_BINDING'
      AND operation.resource_id = resource.id
      AND operation.generation = resource.generation
      AND operation.status IN ('SUCCEEDED', 'FAILED', 'IGNORED')
);

-- A lifecycle create may share the managed table's journal and operation token.
UPDATE t_seatunnel_web_lake_table_lifecycle_binding resource
SET operation_token = NULL, lock_version = lock_version + 1, update_time = CURRENT_TIMESTAMP
WHERE resource.operation_token IS NOT NULL AND EXISTS (
    SELECT 1 FROM t_seatunnel_web_lake_resource_operation operation
    WHERE operation.operation_token = resource.operation_token
      AND ((operation.resource_type = 'TABLE_LIFECYCLE' AND operation.resource_id = resource.id)
        OR (operation.resource_type = 'ODS_TABLE_MAPPING' AND operation.resource_id = resource.table_mapping_id))
      AND operation.generation = resource.generation
      AND operation.status IN ('SUCCEEDED', 'FAILED', 'IGNORED')
);
