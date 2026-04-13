package com.amhs.swim.test.util;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Utility for generating standardized Excel (.xlsx) session reports.
 * 
 * Compliant with ICAO-style audit requirements, this class extracts test 
 * results from the {@link ResultManager} and formats them into a structured 
 * spreadsheet containing message payloads, statuses, and manual verification fields.
 */
public class ExcelReportExporter {

    /**
     * Exports all results stored in the current session to the specified Excel file.
     * @param filePath The destination path for the .xlsx file.
     * @throws IOException If the file cannot be written.
     */
    public static void exportToExcel(String filePath) throws IOException {
        List<TestResult> results = ResultManager.getInstance().getResults();

        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("AMHS-SWIM Test Results");

            // Header style
            CellStyle headerStyle = workbook.createCellStyle();
            Font headFont = workbook.createFont();
            headFont.setBold(true);
            headerStyle.setFont(headFont);
            headerStyle.setBorderBottom(BorderStyle.THIN);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // Columns as per Requirement 4
            String[] headers = {
                "CASE CODE (CTSWxxx)", 
                "Attempts", 
                "Message Index", 
                "Detailed Message Payloads", 
                "Send Status", 
                "SWIM Receive Status", 
                "AMHS Receive Status", 
                "Result"
            };

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (TestResult res : results) {
                Row row = sheet.createRow(rowIdx++);

                row.createCell(0).setCellValue(res.getCaseCode());
                row.createCell(1).setCellValue(res.getAttempt());
                row.createCell(2).setCellValue("Msg-" + res.getMessageIndex());
                row.createCell(3).setCellValue(res.getPayloadSummary());
                row.createCell(4).setCellValue(res.getSendStatus());
                row.createCell(5).setCellValue(res.getSwimReceiveStatus());
                row.createCell(6).setCellValue(res.getAmhsReceiveStatus()); // (manual verify)
                row.createCell(7).setCellValue(res.getResult());            // (manual)
            }

            // Auto-size columns
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            try (FileOutputStream out = new FileOutputStream(filePath)) {
                workbook.write(out);
            }
        }
    }
}
