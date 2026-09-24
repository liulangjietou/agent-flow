package io.agentflow.approval.operations;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 申请与审计摘要共用的纯文本工作簿表示，不生成公式、链接或外部引用。
 * @author owlzhangfq@gmail.com
 */
final class TextWorkbook {
    static final String MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Pattern ESCAPE_PREFIX = Pattern.compile("_(?=x[0-9A-Fa-f]{4}_)");

    private TextWorkbook() { }

    /** 写入固定列的摘要及筛选说明，缺失元数据保留为空文本。 */
    static byte[] write(String sheetName, String[] headings, int[] widths, List<String[]> values, String[][] metadata) throws IOException {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(sheetName);
            var headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(font);
            var textStyle = workbook.createCellStyle(); textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            var header = sheet.createRow(0);
            for (int column = 0; column < headings.length; column++) {
                var cell = header.createCell(column, CellType.STRING); cell.setCellValue(headings[column]); cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(column, widths[column] * 256);
            }
            for (var value : values) {
                var row = sheet.createRow(sheet.getLastRowNum() + 1);
                for (int column = 0; column < value.length; column++) {
                    var cell = row.createCell(column, CellType.STRING); cell.setCellValue(excelText(value[column])); cell.setCellStyle(textStyle);
                }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, sheet.getLastRowNum(), 0, headings.length - 1));
            var info = workbook.createSheet("导出说明"); info.setColumnWidth(0, 26 * 256); info.setColumnWidth(1, 70 * 256);
            for (int index = 0; index < metadata.length; index++) {
                var row = info.createRow(index);
                for (int column = 0; column < 2; column++) {
                    var cell = row.createCell(column, CellType.STRING); cell.setCellValue(excelText(metadata[index][column])); cell.setCellStyle(textStyle);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static String excelText(String value) {
        if (value == null) return "";
        // OOXML 会把字面量 _xHHHH_ 解码为字符，先转义其下划线，避免原文在打开文件后变化。
        String escaped = ESCAPE_PREFIX.matcher(value).replaceAll("_x005F_");
        var result = new StringBuilder(escaped.length());
        for (int index = 0; index < escaped.length(); index++) {
            char current = escaped.charAt(index);
            if (current < 0x20 && current != '\t' && current != '\n' && current != '\r' || current == '\uFFFE' || current == '\uFFFF') {
                result.append(String.format("_x%04X_", (int) current));
            } else { result.append(current); }
        }
        return result.toString();
    }
}
