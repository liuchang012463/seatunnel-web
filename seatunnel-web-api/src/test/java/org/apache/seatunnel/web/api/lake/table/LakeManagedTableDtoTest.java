package org.apache.seatunnel.web.api.lake.table;

import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableColumnDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTableCreateDTO;
import org.apache.seatunnel.web.spi.bean.dto.LakeManagedTablePreviewDTO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LakeManagedTableDtoTest {

    @Test
    void previewAndCreateRequestsExposeOnlyStructuredContractInputsAndToken() {
        assertFalse(Arrays.stream(LakeManagedTablePreviewDTO.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equalsIgnoreCase("ddl")));
        assertFalse(Arrays.stream(LakeManagedTableColumnDTO.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equalsIgnoreCase("sourceType")));
        assertTrue(Arrays.stream(LakeManagedTableCreateDTO.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("previewToken")));
        assertFalse(Arrays.stream(LakeManagedTableCreateDTO.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equalsIgnoreCase("ddl")));
    }
}
