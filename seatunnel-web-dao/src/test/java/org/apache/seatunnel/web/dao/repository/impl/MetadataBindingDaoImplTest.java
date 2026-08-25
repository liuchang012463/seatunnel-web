package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.seatunnel.web.common.enums.MetadataSyncStatus;
import org.apache.seatunnel.web.dao.entity.MetadataSourceBinding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
}
