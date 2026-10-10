package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
        AtomicReference<LambdaUpdateWrapper<MetadataSourceBinding>> captured = new AtomicReference<>();

        boolean reserved = new MetadataBindingDaoImpl(capturingMapper(captured))
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

    @Test
    void resettingForAChangedInstanceClearsThePreviousOmIds() {
        AtomicReference<LambdaUpdateWrapper<MetadataSourceBinding>> captured = new AtomicReference<>();
        MetadataSourceBinding binding = new MetadataSourceBinding();
        binding.setId(11L);
        binding.setVersion(9L);
        binding.setSyncStatus(MetadataSyncStatus.PENDING);
        binding.setConfigVersion(5L);
        binding.setRetryCount(0);
        binding.setUpdateTime(new Date(1_700_000_000_000L));

        boolean reset = new MetadataBindingDaoImpl(capturingMapper(captured))
                .resetForInstanceChange(binding, 8L);

        assertTrue(reset);
        var update = captured.get();
        // An entity update would skip these nulls, so the reset has to set them explicitly.
        assertTrue(update.getSqlSet().contains("om_service_id"));
        assertTrue(update.getSqlSet().contains("om_metadata_pipeline_id"));
        assertTrue(update.getSqlSet().contains("om_profiler_pipeline_id"));
        assertTrue(update.getSqlSet().contains("next_retry_time"));
        assertTrue(update.getSqlSet().contains("last_sync_error_code"));
        assertTrue(update.getSqlSet().contains("last_sync_error"));
        assertTrue(update.getParamNameValuePairs().containsValue(null));
        assertTrue(update.getParamNameValuePairs().containsValue(9L));
        assertTrue(update.getParamNameValuePairs().containsValue(MetadataSyncStatus.PENDING));
    }

    private static MetadataSourceBindingMapper capturingMapper(
            AtomicReference<LambdaUpdateWrapper<MetadataSourceBinding>> captured) {
        return (MetadataSourceBindingMapper) Proxy.newProxyInstance(
                MetadataSourceBindingMapper.class.getClassLoader(),
                new Class<?>[] {MetadataSourceBindingMapper.class},
                (proxy, method, args) -> {
                    if ("update".equals(method.getName()) && args != null && args.length == 2) {
                        @SuppressWarnings("unchecked")
                        LambdaUpdateWrapper<MetadataSourceBinding> update =
                                (LambdaUpdateWrapper<MetadataSourceBinding>) args[1];
                        captured.set(update);
                        return 1;
                    }
                    return method.getReturnType() == int.class ? 0 : null;
                });
    }
}
