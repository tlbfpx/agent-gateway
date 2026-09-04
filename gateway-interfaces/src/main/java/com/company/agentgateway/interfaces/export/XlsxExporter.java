package com.company.agentgateway.interfaces.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * XLSX 导出工具(Round 9):
 * 基于 Apache POI SXSSFWorkbook(流式 API,适合大数据)。
 *
 * <p>输入:列名数组 + 行数据(每行 Object 数组,顺序与列名一致)。
 * 输出:byte[] — Spring Controller 直接写入 ResponseEntity<byte[]>。
 */
public final class XlsxExporter {

    private XlsxExporter() {}

    public static byte[] export(String sheetName, List<String> columns, List<List<Object>> rows) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {  // 100 行 in-memory,其余 flush 到磁盘
            Sheet sheet = wb.createSheet(sheetName != null ? sheetName : "Sheet1");

            // Header row
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(columns.get(i));
            }

            // Data rows
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<Object> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    Cell cell = row.createCell(c);
                    Object v = values.get(c);
                    if (v == null) cell.setBlank();
                    else if (v instanceof String s) cell.setCellValue(s);
                    else if (v instanceof Number n) cell.setCellValue(n.doubleValue());
                    else if (v instanceof Boolean b) cell.setCellValue(b);
                    else cell.setCellValue(v.toString());
                }
            }

            // Auto-size first 10 columns(max 50 chars) — streaming api 限制
            for (int i = 0; i < Math.min(columns.size(), 10); i++) {
                sheet.setColumnWidth(i, 50 * 256);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            wb.dispose();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("XLSX export failed: " + e.getMessage(), e);
        }
    }
}