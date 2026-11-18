package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.service.DataSourceCatalogService;
import org.apache.seatunnel.web.api.service.LakeOdsDatabaseService;
import org.apache.seatunnel.web.api.service.LakeWarehouseService;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class LakeWarehouseControllerTest {
    @Test
    void catalogDatabaseLabelsKeepTheActualNamesAsRequestValues() throws Exception {
        var warehouse = mock(LakeWarehouseService.class);
        var catalog = mock(DataSourceCatalogService.class);
        var ods = mock(LakeOdsDatabaseService.class);
        var controller = new LakeWarehouseController();
        ReflectionTestUtils.setField(controller, "lakeWarehouseService", warehouse);
        ReflectionTestUtils.setField(controller, "dataSourceCatalogService", catalog);
        ReflectionTestUtils.setField(controller, "lakeOdsDatabaseService", ods);
        String actualName = "ods_unit_system_orders";
        when(warehouse.requireSystemDataSourceId()).thenReturn(99L);
        when(catalog.listDatabase(99L)).thenReturn(
                List.of(database(actualName), database("sample"), database("information_schema")));
        when(ods.catalogDatabaseLabels(99L, Set.of(actualName, "sample")))
                .thenReturn(Map.of(actualName, "orders"));

        standaloneSetup(controller).build().perform(get("/api/v1/lake/warehouse/catalog/databases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].value").value(actualName))
                .andExpect(jsonPath("$.data[0].label").value("orders"))
                .andExpect(jsonPath("$.data[1].label").value("sample"));
    }

    private static OptionVO database(String name) {
        OptionVO option = new OptionVO();
        option.setValue(name);
        option.setLabel(name);
        return option;
    }

    @Test
    void driverRoutesBindParametersWithoutCompilerParameterNames() throws Exception {
        var service = mock(LakeWarehouseService.class);
        var controller = new LakeWarehouseController();
        ReflectionTestUtils.setField(controller, "lakeWarehouseService", service);
        var mvc = standaloneSetup(controller).build();

        // Real MVC binding catches missing annotation names that direct method tests miss.
        mvc.perform(post("/api/v1/lake/warehouse/drivers/register")
                        .param("adapter", "POSTGRESQL").param("driverLocation", "postgresql.jar")
                        .param("driverClass", "org.postgresql.Driver").param("sha256", "sha")
                        .param("dorisMd5", "md5"))
                .andExpect(status().isOk());
        verify(service).registerDriver("POSTGRESQL", null, "postgresql.jar", "org.postgresql.Driver", "sha", "md5");

        mvc.perform(multipart("/api/v1/lake/warehouse/drivers/upload")
                        .file(new MockMultipartFile("file", "postgresql.jar", "application/java-archive", new byte[]{1}))
                        .param("adapter", "POSTGRESQL"))
                .andExpect(status().isOk());
        verify(service).uploadDriver(any(), eq("POSTGRESQL"), isNull(), eq(true));
    }
}
