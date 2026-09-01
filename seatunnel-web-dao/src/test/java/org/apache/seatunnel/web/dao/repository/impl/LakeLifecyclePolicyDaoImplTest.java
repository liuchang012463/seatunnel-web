package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.seatunnel.web.common.enums.LakeLifecyclePolicyStatus;
import org.apache.seatunnel.web.common.enums.LakePartitionGranularity;
import org.apache.seatunnel.web.dao.entity.LakeLifecyclePolicy;
import org.apache.seatunnel.web.dao.mapper.LakeLifecyclePolicyMapper;
import org.apache.seatunnel.web.spi.bean.dto.LakeLifecyclePolicyPageDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeLifecyclePolicyDaoImplTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), LakeLifecyclePolicy.class);
    }

    @Test
    void pageWrapperUsesFuzzyNameFiltersAndStableUpdateOrdering() {
        LakeLifecyclePolicyPageDTO request = new LakeLifecyclePolicyPageDTO();
        request.setPolicyName("daily");
        request.setStatus(LakeLifecyclePolicyStatus.ACTIVE);
        request.setGranularity(LakePartitionGranularity.DAY);

        String sql = LakeLifecyclePolicyDaoImpl.buildQueryWrapper(request)
                .getSqlSegment().toUpperCase();

        assertTrue(sql.contains("POLICY_NAME LIKE"));
        assertTrue(sql.contains("STATUS ="));
        assertTrue(sql.contains("GRANULARITY ="));
        assertTrue(sql.contains("UPDATE_TIME DESC"));
        assertTrue(sql.contains("ID DESC"));
    }

    @Test
    void updateCasRejectsMissingInputsAndUsesMapperForExpectedVersion() {
        AtomicReference<Wrapper<LakeLifecyclePolicy>> capturedWrapper = new AtomicReference<>();
        LakeLifecyclePolicyMapper mapper = (LakeLifecyclePolicyMapper) Proxy.newProxyInstance(
                LakeLifecyclePolicyMapper.class.getClassLoader(),
                new Class<?>[] {LakeLifecyclePolicyMapper.class},
                (proxy, method, args) -> {
                    if ("update".equals(method.getName())) {
                        capturedWrapper.set((Wrapper<LakeLifecyclePolicy>) args[1]);
                        return 1;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class || method.getReturnType() == long.class) {
                        return 0;
                    }
                    return null;
                });
        LakeLifecyclePolicyDaoImpl dao = new LakeLifecyclePolicyDaoImpl(mapper);
        LakeLifecyclePolicy entity = new LakeLifecyclePolicy();
        entity.setId(11L);
        entity.setVersion(4);
        entity.setStatus(LakeLifecyclePolicyStatus.ACTIVE);

        assertFalse(dao.updateIfVersion(entity, null));

        assertTrue(dao.updateIfVersion(entity, 3));

        String sql = capturedWrapper.get().getSqlSegment().toUpperCase();
        assertTrue(sql.contains("ID ="));
        assertTrue(sql.contains("VERSION ="));
    }
}
