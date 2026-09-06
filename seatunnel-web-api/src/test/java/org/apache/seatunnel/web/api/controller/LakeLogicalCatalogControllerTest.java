package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.lake.catalog.LakeJdbcAdapterType;
import org.apache.seatunnel.web.api.lake.catalog.LakeLogicalCapabilityVO;
import org.apache.seatunnel.web.api.service.LakeLogicalCatalogService;
import org.apache.seatunnel.web.common.enums.LakeCatalogScope;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeExternalCatalogPageDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.entity.Result;
import org.apache.seatunnel.web.spi.bean.vo.LakeExternalCatalogVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class LakeLogicalCatalogControllerTest {

    @Test
    void capabilityBindsPathVariableAndRequestParametersWithoutMethodParameterMetadata()
            throws Exception {
        LakeLogicalCatalogService service = mock(LakeLogicalCatalogService.class);
        LakeLogicalCapabilityVO capability = new LakeLogicalCapabilityVO();
        when(service.capability(22416910285280L, LakeJdbcAdapterType.MYSQL,
                LakeCatalogScope.DATABASE)).thenReturn(capability);
        MockMvc mockMvc = standaloneSetup(new LakeLogicalCatalogController(service)).build();

        mockMvc.perform(get(
                        "/api/v1/lake/logical/datasources/{sourceDataSourceId}/capability",
                        22416910285280L)
                        .param("adapter", "MYSQL")
                        .param("scope", "DATABASE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));

        verify(service).capability(22416910285280L, LakeJdbcAdapterType.MYSQL,
                LakeCatalogScope.DATABASE);
    }

    @Test
    void exposesCapabilityPageAndDetailRoutesThroughService() {
        LakeLogicalCatalogService service = mock(LakeLogicalCatalogService.class);
        LakeLogicalCapabilityVO capability = new LakeLogicalCapabilityVO();
        LakeExternalCatalogVO detail = new LakeExternalCatalogVO();
        @SuppressWarnings("unchecked")
        PaginationResult<LakeExternalCatalogVO> page = mock(PaginationResult.class);
        when(service.capability(7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.ALL))
                .thenReturn(capability);
        when(service.page(null)).thenReturn(page);
        when(service.detail(11L)).thenReturn(detail);
        when(service.create(any(LakeExternalCatalogCreateDTO.class))).thenReturn(detail);
        when(service.validate(11L)).thenReturn(detail);
        LakeLogicalCatalogController controller = new LakeLogicalCatalogController(service);

        Result<?> capabilityResult = controller.capability(
                7L, "MYSQL", LakeCatalogScope.ALL);
        PaginationResult<LakeExternalCatalogVO> pageResult = controller.page(null);
        Result<?> detailResult = controller.detail(11L);
        Result<?> createResult = controller.create(new LakeExternalCatalogCreateDTO());
        Result<?> validateResult = controller.validate(11L);

        assertNotNull(capabilityResult);
        assertNotNull(pageResult);
        assertNotNull(detailResult);
        assertNotNull(createResult);
        assertNotNull(validateResult);
        verify(service).capability(7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.ALL);
        verify(service).page(null);
        verify(service).detail(eq(11L));
        verify(service).create(any(LakeExternalCatalogCreateDTO.class));
        verify(service).validate(11L);
    }

    @Test
    void probeBindsPathVariableAndRequestParametersWithoutFallingBackOnInvalidAdapter()
            throws Exception {
        LakeLogicalCatalogService service = mock(LakeLogicalCatalogService.class);
        LakeLogicalCapabilityVO capability = new LakeLogicalCapabilityVO();
        when(service.probe(7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.ALL))
                .thenReturn(capability);
        when(service.capability(7L, null, LakeCatalogScope.ALL)).thenReturn(capability);
        MockMvc mockMvc = standaloneSetup(new LakeLogicalCatalogController(service)).build();

        mockMvc.perform(post(
                        "/api/v1/lake/logical/datasources/{sourceDataSourceId}/capability/probe",
                        7L)
                        .param("adapter", "MYSQL")
                        .param("scope", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));

        mockMvc.perform(post(
                        "/api/v1/lake/logical/datasources/{sourceDataSourceId}/capability/probe",
                        7L)
                        .param("adapter", "not-a-real-adapter")
                        .param("scope", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));

        verify(service).probe(7L, LakeJdbcAdapterType.MYSQL, LakeCatalogScope.ALL);
        verify(service).capability(7L, null, LakeCatalogScope.ALL);
    }
}
