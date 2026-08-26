package org.apache.seatunnel.web.api.metadata;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.seatunnel.web.spi.bean.dto.DataInventoryFilterDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class DataExplorationExportServiceTest {

    @Mock private DataInventoryService dataInventoryService;

    @Test
    void writesAValidXlsxWorkbookWithTheFourRequiredSheets() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new DataExplorationExportService(dataInventoryService).write(new DataInventoryFilterDTO(), output);

        byte[] bytes = output.toByteArray();
        assertTrue(bytes.length > 100);
        assertTrue(bytes[0] == 'P' && bytes[1] == 'K');
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertTrue(workbook.getSheet("数据源清查") != null);
            assertTrue(workbook.getSheet("数据表清查") != null);
            assertTrue(workbook.getSheet("字段探查") != null);
            assertTrue(workbook.getSheet("特征统计") != null);
        }
    }
}
