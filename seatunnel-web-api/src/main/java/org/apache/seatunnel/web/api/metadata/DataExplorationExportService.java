package org.apache.seatunnel.web.api.metadata;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.seatunnel.web.spi.bean.dto.DataInventoryFilterDTO;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.Date;

/** Writes the four normalized data-exploration sheets without local metadata persistence. */
@Service
public class DataExplorationExportService {

    private final DataInventoryService dataInventoryService;

    public DataExplorationExportService(DataInventoryService dataInventoryService) {
        this.dataInventoryService = dataInventoryService;
    }

    /**
     * Uses POI SXSSF's bounded row window.  The workbook owns temporary sheet
     * files and closes/disposes them when the write completes.
     */
    public void write(DataInventoryFilterDTO filter, OutputStream outputStream) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            SXSSFSheet sourceSheet = workbook.createSheet("数据源清查");
            SXSSFSheet tableSheet = workbook.createSheet("数据表清查");
            SXSSFSheet columnSheet = workbook.createSheet("字段探查");
            SXSSFSheet featureSheet = workbook.createSheet("特征统计");
            header(sourceSheet, "单位", "业务系统", "数据源名称", "数据源类型", "自动扫描状态",
                    "最近扫描成功时间", "探查状态", "最近探查成功时间");
            header(tableSheet, "单位", "业务系统", "数据源", "Database", "Schema", "Table", "Table Type",
                    "Description", "Column Count", "Row Count");
            header(columnSheet, "Database", "Schema", "Table", "Column", "Data Type", "Nullable", "Constraint",
                    "Description");
            header(featureSheet, "Table", "Column", "valuesCount", "nullCount", "nullProportion", "distinctCount",
                    "distinctProportion", "uniqueCount", "uniqueProportion", "min", "max", "mean", "minLength",
                    "maxLength", "qualityStatus", "profileTime");
            int[] sourceRow = {1};
            int[] tableRow = {1};
            int[] columnRow = {1};
            int[] featureRow = {1};
            dataInventoryService.streamForExport(filter, new DataInventoryService.InventoryExportWriter() {
                @Override
                public void onSource(DataInventoryService.InventoryExportSourceRow item) {
                    values(sourceSheet.createRow(sourceRow[0]++), item.unit(), item.businessSystem(), item.dataSource(),
                            item.sourceType(), item.scanStatus(), item.scanLastSuccessTime(), item.profileStatus(),
                            item.profileLastSuccessTime());
                }

                @Override
                public void onTable(DataInventoryService.InventoryExportTableRow item) {
                    values(tableSheet.createRow(tableRow[0]++), item.unit(), item.businessSystem(), item.dataSource(),
                            item.database(), item.schema(), item.table(), item.tableType(), item.description(),
                            item.columnCount(), item.rowCount());
                }

                @Override
                public void onColumn(DataInventoryService.InventoryExportColumnRow item) {
                    values(columnSheet.createRow(columnRow[0]++), item.database(), item.schema(), item.table(),
                            item.column(), item.dataType(), item.nullable(), item.constraint(), item.description());
                }

                @Override
                public void onFeature(DataInventoryService.InventoryExportFeatureRow item) {
                    values(featureSheet.createRow(featureRow[0]++), item.table(), item.column(), item.valuesCount(),
                            item.nullCount(), item.nullProportion(), item.distinctCount(), item.distinctProportion(),
                            item.uniqueCount(), item.uniqueProportion(), item.min(), item.max(), item.mean(),
                            item.minLength(), item.maxLength(), item.qualityStatus(), item.profileTime());
                }
            });
            workbook.write(outputStream);
        }
    }

    private static void header(SXSSFSheet sheet, String... headers) {
        Row row = sheet.createRow(0);
        values(row, (Object[]) headers);
        sheet.createFreezePane(0, 1);
    }

    private static void values(Row row, Object... values) {
        for (int index = 0; index < values.length; index++) {
            Cell cell = row.createCell(index);
            Object value = values[index];
            if (value == null) {
                cell.setBlank();
            } else if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
            } else if (value instanceof Date date) {
                cell.setCellValue(date);
            } else if (value instanceof BigDecimal decimal) {
                cell.setCellValue(decimal.doubleValue());
            } else {
                cell.setCellValue(String.valueOf(value));
            }
        }
    }
}
