package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.api.security.CurrentUserProvider;
import org.apache.seatunnel.web.api.service.MetadataBindingCommandService;
import org.apache.seatunnel.web.common.enums.DataSourceLifecycleStatus;
import org.apache.seatunnel.web.common.enums.JobDefinitionMode;
import org.apache.seatunnel.web.core.exceptions.ServiceException;
import org.apache.seatunnel.web.core.job.handler.script.ScriptJobDefinitionParser;
import org.apache.seatunnel.web.dao.entity.DataSource;
import org.apache.seatunnel.web.dao.entity.JobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.JobDefinitionContentEntity;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionEntity;
import org.apache.seatunnel.web.dao.entity.StreamingJobDefinitionContentEntity;
import org.apache.seatunnel.web.dao.repository.BusinessSystemDao;
import org.apache.seatunnel.web.dao.repository.DataSourceDao;
import org.apache.seatunnel.web.dao.repository.DataSourceUnitDao;
import org.apache.seatunnel.web.dao.repository.JobDefinitionContentDao;
import org.apache.seatunnel.web.dao.repository.JobDefinitionDao;
import org.apache.seatunnel.web.dao.repository.LakeExternalCatalogBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeOdsDatabaseBindingDao;
import org.apache.seatunnel.web.dao.repository.LakeSourceObjectRefDao;
import org.apache.seatunnel.web.dao.repository.MetadataBindingDao;
import org.apache.seatunnel.web.dao.repository.OfflineImportTaskDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionContentDao;
import org.apache.seatunnel.web.dao.repository.StreamingJobDefinitionDao;
import org.apache.seatunnel.web.spi.bean.dto.config.ScriptJobContent;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataSourceUsageUpdateTest {

    private static final Long DATA_SOURCE_ID = 73L;

    @Mock private DataSourceDao dataSourceDao;
    @Mock private BusinessSystemDao businessSystemDao;
    @Mock private DataSourceUnitDao dataSourceUnitDao;
    @Mock private MetadataBindingCommandService metadataBindingCommandService;
    @Mock private MetadataBindingDao metadataBindingDao;
    @Mock private JobDefinitionDao jobDefinitionDao;
    @Mock private StreamingJobDefinitionDao streamingJobDefinitionDao;
    @Mock private JobDefinitionContentDao jobDefinitionContentDao;
    @Mock private StreamingJobDefinitionContentDao streamingJobDefinitionContentDao;
    @Mock private LakeOdsDatabaseBindingDao lakeOdsDatabaseBindingDao;
    @Mock private LakeExternalCatalogBindingDao lakeExternalCatalogBindingDao;
    @Mock private LakeSourceObjectRefDao lakeSourceObjectRefDao;
    @Mock private OfflineImportTaskDao offlineImportTaskDao;
    @Mock private CurrentUserProvider currentUserProvider;

    @InjectMocks private DataSourceServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "scriptJobDefinitionParser", new ScriptJobDefinitionParser());
    }

    @Test
    void allowsAddingTargetPurposeToAJobReferencedSourceWithoutChangingConnectionSettings() {
        DataSource existing = dataSource(true, false);
        when(dataSourceDao.queryById(DATA_SOURCE_ID)).thenReturn(existing);
        when(currentUserProvider.getCurrentUserId()).thenReturn(9);

        assertTrue(service.updateDataSourceUsage(DATA_SOURCE_ID, true, true));

        ArgumentCaptor<DataSource> saved = ArgumentCaptor.forClass(DataSource.class);
        verify(dataSourceDao).updateById(saved.capture());
        assertEquals(DATA_SOURCE_ID, saved.getValue().getId());
        assertEquals(true, saved.getValue().getSourceEnabled());
        assertEquals(true, saved.getValue().getSinkEnabled());
        assertEquals(9, saved.getValue().getUpdateUserId());
        assertEquals(null, saved.getValue().getConnectionParams());
        assertEquals(null, saved.getValue().getBusinessSystemId());
        verify(metadataBindingCommandService, never()).markConfigurationChanged(DATA_SOURCE_ID);
    }

    @Test
    void protectsASecondScriptSourceIdNotStoredInTheDefinitionScalarColumn() {
        when(dataSourceDao.queryById(DATA_SOURCE_ID)).thenReturn(dataSource(true, true));
        when(jobDefinitionDao.existsBySourceDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(streamingJobDefinitionDao.existsBySourceDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(lakeOdsDatabaseBindingDao.existsActiveBySourceDataSourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(lakeExternalCatalogBindingDao.existsActiveBySourceDataSourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(lakeSourceObjectRefDao.existsActiveBySourceDataSourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(jobDefinitionDao.queryAll()).thenReturn(List.of(batchDefinition(1L, 1)));
        when(jobDefinitionContentDao.queryAll()).thenReturn(List.of(batchScript(1L, """
                source = [
                  { plugin_name = "Jdbc", dataSourceId = 11 },
                  { plugin_name = "Jdbc", dataSourceId = 73 }
                ]
                sink = [{ plugin_name = "Jdbc", dataSourceId = 22 }]
                """)));

        assertThrows(ServiceException.class,
                () -> service.updateDataSourceUsage(DATA_SOURCE_ID, false, true));
        verify(dataSourceDao, never()).updateById(any(DataSource.class));
    }

    @Test
    void protectsASecondStreamingScriptSinkIdNotStoredInTheDefinitionScalarColumn() {
        when(dataSourceDao.queryById(DATA_SOURCE_ID)).thenReturn(dataSource(true, true));
        when(jobDefinitionDao.existsBySinkDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(streamingJobDefinitionDao.existsBySinkDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(offlineImportTaskDao.existsByTargetDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(streamingJobDefinitionDao.queryAll()).thenReturn(List.of(streamingDefinition(2L, 1)));
        when(streamingJobDefinitionContentDao.queryAll()).thenReturn(List.of(streamingScript(2L, """
                source = [{ plugin_name = "Jdbc", dataSourceId = 11 }]
                sink = [
                  { plugin_name = "Jdbc", dataSourceId = 21 },
                  { plugin_name = "Jdbc", dataSourceId = 73 }
                ]
                """)));

        assertThrows(ServiceException.class,
                () -> service.updateDataSourceUsage(DATA_SOURCE_ID, true, false));
        verify(dataSourceDao, never()).updateById(any(DataSource.class));
    }

    @Test
    void protectsAnExtraSinkReferenceWhenTheSameConnectionIsTheEntitySource() {
        when(dataSourceDao.queryById(DATA_SOURCE_ID)).thenReturn(dataSource(true, true));
        when(jobDefinitionDao.existsBySinkDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(streamingJobDefinitionDao.existsBySinkDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(offlineImportTaskDao.existsByTargetDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(jobDefinitionDao.queryAll()).thenReturn(List.of(batchDefinition(3L, 1)));
        when(jobDefinitionContentDao.queryAll()).thenReturn(List.of(batchScript(3L, """
                source = [{ plugin_name = "Jdbc", dataSourceId = 73 }]
                sink = [
                  { plugin_name = "Jdbc", dataSourceId = 21 },
                  { plugin_name = "Jdbc", dataSourceId = 73 }
                ]
                """)));

        assertThrows(ServiceException.class,
                () -> service.updateDataSourceUsage(DATA_SOURCE_ID, true, false));
        verify(dataSourceDao, never()).updateById(any(DataSource.class));
    }

    @Test
    void ignoresScriptContentThatIsNotTheDefinitionCurrentVersion() {
        when(dataSourceDao.queryById(DATA_SOURCE_ID)).thenReturn(dataSource(true, true));
        when(currentUserProvider.getCurrentUserId()).thenReturn(9);
        when(jobDefinitionDao.existsBySourceDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(streamingJobDefinitionDao.existsBySourceDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(lakeOdsDatabaseBindingDao.existsActiveBySourceDataSourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(lakeExternalCatalogBindingDao.existsActiveBySourceDataSourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(lakeSourceObjectRefDao.existsActiveBySourceDataSourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(jobDefinitionDao.queryAll()).thenReturn(List.of(batchDefinition(4L, 2)));
        when(jobDefinitionContentDao.queryAll()).thenReturn(List.of(batchScript(4L, 1, """
                source = [{ plugin_name = "Jdbc", dataSourceId = 11 }]
                sink = [{ plugin_name = "Jdbc", dataSourceId = 73 }]
                """)));

        assertTrue(service.updateDataSourceUsage(DATA_SOURCE_ID, false, true));
        verify(dataSourceDao).updateById(any(DataSource.class));
    }

    @Test
    void ignoresStreamingScriptContentThatIsNotTheDefinitionCurrentVersion() {
        when(dataSourceDao.queryById(DATA_SOURCE_ID)).thenReturn(dataSource(true, true));
        when(currentUserProvider.getCurrentUserId()).thenReturn(9);
        when(jobDefinitionDao.existsBySinkDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(streamingJobDefinitionDao.existsBySinkDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(offlineImportTaskDao.existsByTargetDatasourceId(DATA_SOURCE_ID)).thenReturn(false);
        when(streamingJobDefinitionDao.queryAll()).thenReturn(List.of(streamingDefinition(5L, 2)));
        when(streamingJobDefinitionContentDao.queryAll()).thenReturn(List.of(streamingScript(5L, 1, """
                source = [{ plugin_name = "Jdbc", dataSourceId = 11 }]
                sink = [{ plugin_name = "Jdbc", dataSourceId = 73 }]
                """)));

        assertTrue(service.updateDataSourceUsage(DATA_SOURCE_ID, true, false));
        verify(dataSourceDao).updateById(any(DataSource.class));
    }

    private static DataSource dataSource(boolean sourceEnabled, boolean sinkEnabled) {
        DataSource dataSource = new DataSource();
        dataSource.setId(DATA_SOURCE_ID);
        dataSource.setDbType(DbType.MYSQL);
        dataSource.setStatus(DataSourceLifecycleStatus.ENABLED);
        dataSource.setSourceEnabled(sourceEnabled);
        dataSource.setSinkEnabled(sinkEnabled);
        return dataSource;
    }

    private static JobDefinitionContentEntity batchScript(Long jobId, String hocon) {
        return batchScript(jobId, 1, hocon);
    }

    private static JobDefinitionContentEntity batchScript(Long jobId, int version, String hocon) {
        ScriptJobContent script = new ScriptJobContent();
        script.setScriptType("HOCON");
        script.setHoconContent(hocon);
        return JobDefinitionContentEntity.builder()
                .jobDefinitionId(jobId)
                .version(version)
                .mode(JobDefinitionMode.SCRIPT)
                .definitionContent(org.apache.seatunnel.web.common.utils.JSONUtils.toJsonString(script))
                .build();
    }

    private static JobDefinitionEntity batchDefinition(Long jobId, int version) {
        JobDefinitionEntity definition = new JobDefinitionEntity();
        definition.setId(jobId);
        definition.setJobVersion(version);
        definition.setMode(JobDefinitionMode.SCRIPT);
        return definition;
    }

    private static StreamingJobDefinitionEntity streamingDefinition(Long jobId, int version) {
        StreamingJobDefinitionEntity definition = new StreamingJobDefinitionEntity();
        definition.setId(jobId);
        definition.setJobVersion(version);
        definition.setMode(JobDefinitionMode.SCRIPT);
        return definition;
    }

    private static StreamingJobDefinitionContentEntity streamingScript(Long jobId, String hocon) {
        return streamingScript(jobId, 1, hocon);
    }

    private static StreamingJobDefinitionContentEntity streamingScript(Long jobId, int version, String hocon) {
        ScriptJobContent script = new ScriptJobContent();
        script.setScriptType("HOCON");
        script.setHoconContent(hocon);
        return StreamingJobDefinitionContentEntity.builder()
                .jobDefinitionId(jobId)
                .version(version)
                .mode(JobDefinitionMode.SCRIPT)
                .definitionContent(org.apache.seatunnel.web.common.utils.JSONUtils.toJsonString(script))
                .build();
    }
}
