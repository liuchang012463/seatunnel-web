package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.seatunnel.web.common.enums.LakeResourceStatus;
import org.apache.seatunnel.web.dao.entity.LakeExternalCatalogBinding;
import org.apache.seatunnel.web.dao.mapper.LakeExternalCatalogBindingMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeExternalCatalogBindingDaoImplTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                LakeExternalCatalogBinding.class);
    }

    @Test
    void activePageUsesLocalFiltersAndStableOrdering() {
        AtomicReference<Wrapper<LakeExternalCatalogBinding>> captured = new AtomicReference<>();
        LakeExternalCatalogBindingMapper mapper = mapper(captured);
        LakeExternalCatalogBindingDaoImpl dao = new LakeExternalCatalogBindingDaoImpl(mapper);

        IPage<LakeExternalCatalogBinding> page = new Page<>(2, 20);
        IPage<LakeExternalCatalogBinding> result = dao.queryActivePage(
                page, 99L, 17L, "orders", "MYSQL", "READY", "MATCH");

        assertSame(page, result);
        String sql = captured.get().getSqlSegment().toUpperCase();
        assertTrue(sql.contains("DELETED ="));
        assertTrue(sql.contains("LAKE_DATA_SOURCE_ID ="));
        assertTrue(sql.contains("SOURCE_DATA_SOURCE_ID ="));
        assertTrue(sql.contains("CATALOG_NAME LIKE"));
        assertTrue(sql.contains("ADAPTER ="));
        assertTrue(sql.contains("RESOURCE_STATUS ="));
        assertTrue(sql.contains("VALIDATION_STATUS ="));
        assertTrue(sql.contains("UPDATE_TIME DESC"));
        assertTrue(sql.contains("ID DESC"));
    }

    @Test
    void versionAndTokenCasRequireTheExpectedLeaseAndIncrementVersion() {
        AtomicReference<Wrapper<LakeExternalCatalogBinding>> captured = new AtomicReference<>();
        LakeExternalCatalogBindingMapper mapper = mapper(captured);
        LakeExternalCatalogBindingDaoImpl dao = new LakeExternalCatalogBindingDaoImpl(mapper);
        LakeExternalCatalogBinding entity = new LakeExternalCatalogBinding();
        entity.setId(11L);
        entity.setLockVersion(4);
        entity.setResourceStatus(LakeResourceStatus.CREATING);

        assertTrue(dao.updateIfTokenAndVersion(entity, null, 4));
        assertEquals(5, entity.getLockVersion());
        String sql = captured.get().getSqlSegment().toUpperCase();
        assertTrue(sql.contains("ID ="));
        assertTrue(sql.contains("LOCK_VERSION ="));
        assertTrue(sql.contains("DELETED ="));
        assertTrue(sql.contains("OPERATION_TOKEN IS NULL"));
    }

    private static LakeExternalCatalogBindingMapper mapper(
            AtomicReference<Wrapper<LakeExternalCatalogBinding>> captured) {
        return (LakeExternalCatalogBindingMapper) Proxy.newProxyInstance(
                LakeExternalCatalogBindingMapper.class.getClassLoader(),
                new Class<?>[] {LakeExternalCatalogBindingMapper.class},
                (proxy, method, args) -> {
                    if ("selectPage".equals(method.getName())) {
                        captured.set((Wrapper<LakeExternalCatalogBinding>) args[1]);
                        return args[0];
                    }
                    if ("update".equals(method.getName())) {
                        captured.set((Wrapper<LakeExternalCatalogBinding>) args[1]);
                        return 1;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class
                            || method.getReturnType() == long.class) {
                        return 0;
                    }
                    return null;
                });
    }
}
