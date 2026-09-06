package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.service.DataSourceUnitService;
import org.apache.seatunnel.web.spi.bean.dto.DataSourceUnitDTO;
import org.apache.seatunnel.web.spi.bean.entity.PaginationResult;
import org.apache.seatunnel.web.spi.bean.vo.DataSourceUnitVO;
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

class DataSourceUnitControllerTest {

    @Test
    void updateBindsPathVariableWithoutMethodParameterMetadata() throws Exception {
        RecordingDataSourceUnitService service = new RecordingDataSourceUnitService();
        DataSourceUnitController controller = new DataSourceUnitController();
        ReflectionTestUtils.setField(controller, "dataSourceUnitService", service);
        MockMvc mockMvc = standaloneSetup(controller).build();

        mockMvc.perform(put("/api/v1/data-source-units/{id}", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitCode\":\"UNIT_001\",\"unitName\":\"测试单位\",\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data", is(true)));

        assertEquals(7L, service.updatedId);
        assertEquals("测试单位", service.updatedDto.getUnitName());
    }

    private static final class RecordingDataSourceUnitService implements DataSourceUnitService {

        private Long updatedId;
        private DataSourceUnitDTO updatedDto;

        @Override
        public Long create(DataSourceUnitDTO dto) {
            return null;
        }

        @Override
        public Boolean update(Long id, DataSourceUnitDTO dto) {
            updatedId = id;
            updatedDto = dto;
            return true;
        }

        @Override
        public DataSourceUnitVO getById(Long id) {
            return null;
        }

        @Override
        public PaginationResult<DataSourceUnitVO> pageQuery(DataSourceUnitDTO dto) {
            return null;
        }

        @Override
        public void delete(Long id) {
        }

        @Override
        public List<DataSourceUnitVO> listActive() {
            return List.of();
        }

        @Override
        public List<OptionVO> options() {
            return List.of();
        }
    }
}
