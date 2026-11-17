package org.apache.seatunnel.web.api.controller;

import org.apache.seatunnel.web.api.service.TimeVariableService;
import org.apache.seatunnel.web.spi.bean.dto.TimeVariableUpdateDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TimeVariableControllerTest {
    @Test
    void bindsAllIdRoutesWithoutCompilerParameterNames() throws Exception {
        var service = mock(TimeVariableService.class);
        var controller = new TimeVariableController();
        ReflectionTestUtils.setField(controller, "timeVariableService", service);
        var mvc = standaloneSetup(controller).build();
        when(service.update(eq(7L), any())).thenReturn(true);

        // Exercise MVC argument resolution, rather than direct Java method calls.
        mvc.perform(get("/api/v1/time-variable/7")).andExpect(status().isOk());
        mvc.perform(put("/api/v1/time-variable/7").contentType(MediaType.APPLICATION_JSON)
                .content("{\"paramName\":\"biz_date\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        mvc.perform(delete("/api/v1/time-variable/7")).andExpect(status().isOk());
        verify(service).getById(7L);
        verify(service).update(eq(7L), any(TimeVariableUpdateDTO.class));
        verify(service).delete(7L);
    }
}
