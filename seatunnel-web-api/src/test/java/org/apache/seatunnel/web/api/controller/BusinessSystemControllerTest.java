package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.service.BusinessSystemService;
import org.apache.seatunnel.web.spi.bean.dto.BusinessSystemDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.BusinessSystemVO;
import org.apache.seatunnel.web.spi.bean.vo.OptionVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BusinessSystemControllerTest {

    @Test
    void updateBindsPathVariableWithoutMethodParameterMetadata() throws Exception {
        RecordingBusinessSystemService service = new RecordingBusinessSystemService();
        BusinessSystemController controller = new BusinessSystemController();
        ReflectionTestUtils.setField(controller, "businessSystemService", service);
        MockMvc mockMvc = standaloneSetup(controller).build();

        mockMvc.perform(put("/api/v1/business-systems/{id}", 11L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitId\":7,\"systemCode\":\"SYSTEM_001\","
                                + "\"systemName\":\"门户系统\",\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data", is(true)));

        assertEquals(11L, service.updatedId);
        assertEquals("门户系统", service.updatedDto.getSystemName());
    }

    private static final class RecordingBusinessSystemService implements BusinessSystemService {

        private Long updatedId;
        private BusinessSystemDTO updatedDto;

        @Override
        public Long create(BusinessSystemDTO dto) {
            return null;
        }

        @Override
        public Boolean update(Long id, BusinessSystemDTO dto) {
            updatedId = id;
            updatedDto = dto;
            return true;
        }

        @Override
        public BusinessSystemVO getById(Long id) {
            return null;
        }

        @Override
        public PaginationResult<BusinessSystemVO> pageQuery(BusinessSystemDTO dto) {
            return null;
        }

        @Override
        public void delete(Long id) {
        }

        @Override
        public List<BusinessSystemVO> listByUnitId(Long unitId) {
            return List.of();
        }

        @Override
        public List<OptionVO> options(Long unitId) {
            return List.of();
        }
    }
}
