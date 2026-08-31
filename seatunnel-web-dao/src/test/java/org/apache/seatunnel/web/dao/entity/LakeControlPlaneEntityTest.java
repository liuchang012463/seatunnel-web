package org.apache.seatunnel.web.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.common.enums.LakeConsistencyStatus;
import org.apache.seatunnel.web.common.enums.LakeJobRuntimeType;
import org.apache.seatunnel.web.common.enums.LakeLifecycleBindingStatus;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakeManagementLevel;
import org.apache.seatunnel.web.common.enums.LakeOperationStatus;
import org.apache.seatunnel.web.common.enums.LakeOperationType;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.common.enums.LakeRelationScope;
import org.apache.seatunnel.web.common.enums.LakeRelationStatus;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.common.enums.LakeSourceObjectType;
import org.apache.seatunnel.web.common.enums.LakeTableModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeControlPlaneEntityTest {

    @Test
    void allV14EntitiesPointToNewTables() {
        Map<Class<?>, String> expected = Map.of(
                LakeSourceObjectRef.class, "t_seatunnel_web_lake_source_object_ref",
                LakeOdsDatabaseBinding.class, "t_seatunnel_web_lake_ods_database_binding",
                LakeOdsTableMapping.class, "t_seatunnel_web_lake_ods_table_mapping",
                LakeJobRelation.class, "t_seatunnel_web_lake_job_relation",
                LakeLifecyclePolicy.class, "t_seatunnel_web_lake_lifecycle_policy",
                LakeTableLifecycleBinding.class, "t_seatunnel_web_lake_table_lifecycle_binding",
                LakeExternalCatalogBinding.class, "t_seatunnel_web_lake_external_catalog_binding",
                LakeResourceOperation.class, "t_seatunnel_web_lake_resource_operation");

        expected.forEach((entity, table) -> assertEquals(table, entity.getAnnotation(TableName.class).value()));
    }

    @Test
    void resourceEntitiesCarryGenerationTokenAndOptimisticLock() throws NoSuchFieldException {
        Field lockVersion = LakeResourceEntity.class.getDeclaredField("lockVersion");
        assertTrue(lockVersion.isAnnotationPresent(Version.class));

        LakeOdsDatabaseBinding binding = new LakeOdsDatabaseBinding();
        assertEquals(1, binding.getLockVersion());
        assertEquals(1, binding.getGeneration());
        assertEquals(LakeResourceStatus.PENDING_CREATE, binding.getResourceStatus());
        assertEquals(Boolean.FALSE, binding.getDeleted());

        LakeTableLifecycleBinding lifecycle = new LakeTableLifecycleBinding();
        assertEquals(1, lifecycle.getLockVersion());
        assertEquals(1, lifecycle.getGeneration());
    }

    @Test
    void enumsPersistStableWireCodes() {
        assertEquals("MANAGED", LakeManagementLevel.MANAGED.getCode());
        assertEquals("AUTO_CREATED", LakeManagementLevel.AUTO_CREATED.getCode());
        assertEquals("NAMESPACE", LakeRelationScope.NAMESPACE.getCode());
        assertEquals("STREAMING", LakeJobRuntimeType.STREAMING.getCode());
        assertEquals("DRIFT", LakeConsistencyStatus.DRIFT.getCode());
        assertEquals("ACTIVE", LakeRelationStatus.ACTIVE.getCode());
        assertEquals("ACTIVE", LakeLifecycleBindingStatus.ACTIVE.getCode());
        assertEquals("ACTIVE", LakeLifecyclePolicyStatus.ACTIVE.getCode());
        assertEquals("SUCCEEDED", LakeOperationStatus.SUCCEEDED.getCode());
        assertEquals("CREATE_TABLE", LakeOperationType.CREATE_TABLE.getCode());
        assertEquals("DAY", LakePartitionGranularity.DAY.getCode());
        assertEquals("TABLE", LakeCatalogScope.TABLE.getCode());
        assertEquals("TABLE", LakeSourceObjectType.TABLE.getCode());
        assertEquals("UNIQUE", LakeTableModel.UNIQUE.getCode());
    }

    @Test
    void operationRecordHasOnlyRedactedResultFields() throws NoSuchFieldException {
        assertNotNull(LakeResourceOperation.class.getDeclaredField("errorSummary"));
        assertNotNull(LakeResourceOperation.class.getDeclaredField("requestHash"));
        // The operation journal intentionally has no connectionParams/password/DDL field.
        assertTrue(java.util.Arrays.stream(LakeResourceOperation.class.getDeclaredFields())
                .noneMatch(field -> field.getName().toLowerCase().contains("password")));
    }
}
