package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.service.LakeWarehouseService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class LakeWarehouseControllerTest {
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
