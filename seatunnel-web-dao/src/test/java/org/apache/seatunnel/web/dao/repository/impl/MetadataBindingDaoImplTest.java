package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.apache.seatunnel.web.dao.mapper.MetadataSourceBindingMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataBindingDaoImplTest {

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MetadataSourceBinding.class);
    }

    @Test
    void bindingEntityUsesTheExpectedLocalStatusValues() {
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setSyncStatus(MetadataSyncStatus.PENDING);
        binding.setDataSourceId(1024L);

        assertTrue(binding.getSyncStatus() == MetadataSyncStatus.PENDING);
        assertTrue(binding.getDataSourceId() == 1024L);
    }

    @Test
    void reservingAnExplorationAtomicallyResetsItsRunBaseline() {
        AtomicReference<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MetadataSourceBinding>>
                captured = new AtomicReference<>();
        MetadataSourceBindingMapper mapper = (MetadataSourceBindingMapper) Proxy.newProxyInstance(
                MetadataSourceBindingMapper.class.getClassLoader(),
                new Class<?>[] {MetadataSourceBindingMapper.class},
                (proxy, method, args) -> {
                    if ("update".equals(method.getName()) && args != null && args.length == 2) {
                        @SuppressWarnings("unchecked")
                        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MetadataSourceBinding>
                                update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MetadataSourceBinding>) args[1];
                        captured.set(update);
                        return 1;
                    }
                    return method.getReturnType() == int.class ? 0 : null;
                });

        boolean reserved = new MetadataBindingDaoImpl(mapper)
                .reserveRun(42L, 7L, false, null, new Date(1_700_000_000_000L), "reservation-token");

        assertTrue(reserved);
        var update = captured.get();
        assertTrue(update.getSqlSet().contains("profile_run_reservation_token"));
        assertTrue(update.getSqlSet().contains("profile_profiler_run_id_baseline"));
        assertTrue(update.getSqlSet().contains("profile_sample_run_id_baseline"));
        assertTrue(update.getSqlSet().contains("profile_run_baseline_captured"));
        assertTrue(update.getSqlSet().contains("profile_run_baseline_captured_at"));
        assertTrue(update.getParamNameValuePairs().containsValue("reservation-token"));
        assertTrue(update.getParamNameValuePairs().containsValue(false));
        assertTrue(update.getParamNameValuePairs().containsValue(null));
    }
}
