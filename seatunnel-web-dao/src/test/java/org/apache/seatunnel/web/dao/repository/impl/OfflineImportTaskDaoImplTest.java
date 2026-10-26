package org.apache.seatunnel.web.dao.repository.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.seatunnel.web.dao.entity.OfflineImportTask;
import org.apache.seatunnel.web.dao.mapper.OfflineImportTaskMapper;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportTaskQueryDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineImportTaskDaoImplTest {

    private static final Integer OWNER_ID = 17;

    private OfflineImportTaskMapper mapper;
    private OfflineImportTaskDaoImpl dao;
    private Wrapper<OfflineImportTask> capturedWrapper;

    @BeforeAll
    static void initializeTableMetadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OfflineImportTask.class);
    }

    @BeforeEach
    void setUp() {
        mapper = (OfflineImportTaskMapper) Proxy.newProxyInstance(
                OfflineImportTaskMapper.class.getClassLoader(),
                new Class<?>[] {OfflineImportTaskMapper.class},
                (proxy, method, args) -> {
                    if ("selectPage".equals(method.getName())) {
                        capturedWrapper = (Wrapper<OfflineImportTask>) args[1];
                        return args[0];
                    }
                    if ("update".equals(method.getName())) {
                        capturedWrapper = (Wrapper<OfflineImportTask>) args[1];
                        return 1;
                    }
                    if ("delete".equals(method.getName())) {
                        capturedWrapper = (Wrapper<OfflineImportTask>) args[0];
                        return 1;
                    }
                    return null;
                });
        dao = new OfflineImportTaskDaoImpl(mapper);
    }

    @Test
    void pageAlwaysFiltersByOwner() {
        OfflineImportTaskQueryDTO query = new OfflineImportTaskQueryDTO();

        dao.queryPage(query, OWNER_ID);

        String sql = capturedWrapper.getSqlSegment().toUpperCase();
        assertTrue(sql.contains("CREATE_USER_ID"));
        assertTrue(parameters(capturedWrapper).containsValue(OWNER_ID));
    }

    @Test
    void nullOwnerCannotSeeAnyTasks() {
        OfflineImportTaskQueryDTO query = new OfflineImportTaskQueryDTO();

        dao.queryPage(query, null);

        assertTrue(capturedWrapper.getSqlSegment().contains("1 = 0"));
    }

    @Test
    void requeueClearsPreviousRunStateAndRequiresOwner() {
        assertTrue(dao.markQueuedIfExecutable(3L, OWNER_ID, new Date()));

        LambdaUpdateWrapper<OfflineImportTask> wrapper = (LambdaUpdateWrapper<OfflineImportTask>) capturedWrapper;
        String where = wrapper.getSqlSegment().toUpperCase();
        String set = wrapper.getSqlSet().toUpperCase();
        assertTrue(where.contains("CREATE_USER_ID"));
        assertTrue(where.contains("STATUS IN"));
        assertTrue(set.contains("ERROR_MESSAGE"));
        assertTrue(set.contains("EXECUTION_LOG"));
        assertTrue(set.contains("START_TIME"));
        assertTrue(set.contains("END_TIME"));
        assertTrue(set.contains("TOTAL_STATEMENTS"));
        assertTrue(set.contains("EXECUTED_STATEMENTS"));
        assertTrue(set.contains("FAILED_STATEMENTS"));
        assertTrue(parameters(wrapper).containsValue(null));
        assertTrue(parameters(wrapper).values().stream()
                .filter(value -> Integer.valueOf(0).equals(value))
                .count() >= 3);
    }

    @Test
    void deleteIsAtomicWithTaskStateAndOwner() {
        assertTrue(dao.deleteIfNotRunning(3L, OWNER_ID));

        Wrapper<OfflineImportTask> wrapper = capturedWrapper;
        String where = wrapper.getSqlSegment().toUpperCase();
        assertTrue(where.contains("CREATE_USER_ID"));
        assertTrue(where.contains("STATUS IN"));
        assertFalse(parameters(wrapper).containsValue("RUNNING"));
        assertTrue(parameters(wrapper).containsValue("QUEUED"));
    }

    @Test
    void executorRejectionOnlyFailsAnOwnedQueuedTask() {
        assertTrue(dao.failQueued(3L, OWNER_ID, "队列不可用", new Date()));

        String where = capturedWrapper.getSqlSegment().toUpperCase();
        assertTrue(where.contains("CREATE_USER_ID"));
        assertTrue(where.contains("STATUS ="));
        assertTrue(parameters(capturedWrapper).containsValue("QUEUED"));
    }

    private java.util.Map<String, Object> parameters(Wrapper<OfflineImportTask> wrapper) {
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    }
}
