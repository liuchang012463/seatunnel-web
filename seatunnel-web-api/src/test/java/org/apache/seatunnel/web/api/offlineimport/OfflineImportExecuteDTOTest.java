package org.apache.seatunnel.web.api.offlineimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.seatunnel.web.spi.bean.dto.OfflineDatapackInspectDTO;
import org.apache.seatunnel.web.spi.bean.dto.OfflineImportExecuteDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OfflineImportExecuteDTOTest {

    @Test
    void acceptsPasswordOnlyAsInputAndNeverSerializesItOrPrintsIt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // WRITE_ONLY intentionally leaves no response properties on this input-only DTO.
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        OfflineImportExecuteDTO dto = mapper.readValue("{\"password\":\"temporary-secret\"}",
                OfflineImportExecuteDTO.class);

        assertArrayEquals("temporary-secret".toCharArray(), dto.getPassword());
        assertEquals("{}", mapper.writeValueAsString(dto));
        assertFalse(dto.toString().contains("temporary-secret"));

        OfflineDatapackInspectDTO inspect = mapper.readValue(
                "{\"fileResourceId\":7,\"password\":\"temporary-secret\"}",
                OfflineDatapackInspectDTO.class);
        assertArrayEquals("temporary-secret".toCharArray(), inspect.getPassword());
        String inspectJson = mapper.writeValueAsString(inspect);
        assertFalse(inspectJson.contains("temporary-secret"));
        assertFalse(inspect.toString().contains("temporary-secret"));
    }
}
