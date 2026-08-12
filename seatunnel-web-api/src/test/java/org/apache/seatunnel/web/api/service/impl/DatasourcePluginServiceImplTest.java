package org.apache.seatunnel.web.api.service.impl;

import org.apache.seatunnel.web.dao.entity.DataSourcePluginConfig;
import org.apache.seatunnel.web.dao.repository.DataSourcePluginConfigDao;
import org.apache.seatunnel.web.spi.enums.DbType;
import org.apache.seatunnel.web.spi.form.FormFieldConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasourcePluginServiceImplTest {

    @Test
    void preservesConditionalDisplayMetadataFromStoredPluginSchema() {
        DataSourcePluginConfigDao dao = mock(DataSourcePluginConfigDao.class);
        DataSourcePluginConfig config = new DataSourcePluginConfig();
        config.setPluginType(DbType.HTTP);
        config.setConfigSchema("""
                {
                  "fields": [
                    {
                      "key": "baseUrl",
                      "label": "Base URL",
                      "type": "INPUT",
                      "description": "填写 API 服务的根地址",
                      "order": 1
                    },
                    {
                      "key": "username",
                      "label": "用户名",
                      "type": "INPUT",
                      "visibleWhen": "authenticationType=BASIC",
                      "order": 4
                    }
                  ]
                }
                """);
        when(dao.queryByPluginType(DbType.HTTP)).thenReturn(config);

        DatasourcePluginServiceImpl service = new DatasourcePluginServiceImpl();
        ReflectionTestUtils.setField(service, "dataSourcePluginConfigDao", dao);

        List<FormFieldConfig> fields = service.getPluginConfig("HTTP").getFormFields();

        assertEquals("填写 API 服务的根地址", fields.get(0).getDescription());
        assertEquals(1, fields.get(0).getOrder());
        assertEquals("authenticationType=BASIC", fields.get(1).getVisibleWhen());
        assertTrue(fields.get(1).getOrder() > fields.get(0).getOrder());
    }

    @Test
    void refreshesExistingPluginSchemaWhenInstallingPlugin() {
        DataSourcePluginConfigDao dao = mock(DataSourcePluginConfigDao.class);
        DataSourcePluginConfig config = new DataSourcePluginConfig();
        config.setPluginType(DbType.HTTP);
        config.setConfigSchema("{\"fields\":[]}");
        when(dao.queryByPluginType(DbType.HTTP)).thenReturn(config);

        DatasourcePluginServiceImpl service = new DatasourcePluginServiceImpl();
        ReflectionTestUtils.setField(service, "dataSourcePluginConfigDao", dao);

        service.installPlugin("HTTP");

        verify(dao).updatePluginConfig(config);
        assertTrue(config.getConfigSchema().contains("authenticationType=BASIC"));
        assertTrue(config.getConfigSchema().contains("填写 API 服务的根地址"));
    }
}
