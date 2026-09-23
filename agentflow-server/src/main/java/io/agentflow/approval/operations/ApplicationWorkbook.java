package io.agentflow.approval.operations;

import io.agentflow.common.Actor;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 申请摘要的 Excel 表示，文本保持原值，不生成公式、超链接或外部引用。
 * @author owlzhangfq@gmail.com
 */
final class ApplicationWorkbook {
    static final String MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String[] HEADINGS = {"申请标识", "业务单号", "标题", "流程标识", "流程版本", "申请人账号", "状态代码", "当前轮次", "创建时间（UTC）", "更新时间（UTC）"};
    private static final int[] WIDTHS = {38, 28, 46, 30, 12, 22, 20, 12, 30, 30};
    private static final Pattern ESCAPE_PREFIX = Pattern.compile("_(?=x[0-9A-Fa-f]{4}_)");

    private ApplicationWorkbook() { }

    /** 使用字符串类型保留前导零、长单号、换行及以等号开头的原文。 */
    static byte[] write(List<ApplicationSearchPort.Item> items, Actor actor, ApplicationSearchPort.Query query, Instant startedAt) throws IOException {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("申请记录");
            var headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var font = workbook.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(font);
            var textStyle = workbook.createCellStyle(); textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            var header = sheet.createRow(0);
            for (int column = 0; column < HEADINGS.length; column++) {
                var cell = header.createCell(column, CellType.STRING); cell.setCellValue(HEADINGS[column]); cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(column, WIDTHS[column] * 256);
            }
            for (var item : items) {
                var row = sheet.createRow(sheet.getLastRowNum() + 1);
                String[] values = {item.id().toString(), item.businessNo(), item.title(), item.processKey(), Long.toString(item.definitionVersion()),
                        item.createdBy(), item.status(), Integer.toString(item.roundNo()), item.createdAt().toString(), item.updatedAt().toString()};
                for (int column = 0; column < values.length; column++) {
                    var cell = row.createCell(column, CellType.STRING); cell.setCellValue(excelText(values[column])); cell.setCellStyle(textStyle);
                }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, sheet.getLastRowNum(), 0, HEADINGS.length - 1));
            var info = workbook.createSheet("导出说明"); info.setColumnWidth(0, 26 * 256); info.setColumnWidth(1, 70 * 256);
            String[][] metadata = {{"租户", actor.tenantId()}, {"导出账号", actor.userId()}, {"生成开始时间（UTC）", startedAt.toString()},
                    {"记录数", Integer.toString(items.size())}, {"标题或业务单号包含", query.text()}, {"状态代码", query.status()},
                    {"流程标识", query.processKey()}, {"流程版本", query.definitionVersion() == null ? "" : query.definitionVersion().toString()},
                    {"申请人账号", query.applicant()}, {"创建时间起点（含，UTC）", query.createdFrom() == null ? "" : query.createdFrom().toString()},
                    {"创建时间终点（不含，UTC）", query.createdBefore() == null ? "" : query.createdBefore().toString()},
                    {"范围", "本次查询全部匹配申请的当前摘要，不含表单正文、评论或审批意见。"},
                    {"时效", "单次数据库查询结果；审批状态之后可能变化。空筛选表示该项不限。"},
                    {"状态说明", "DRAFT 草稿；IN_APPROVAL 审批中；RETURNED 已退回；WITHDRAWN 已撤回；APPROVED 已批准；REJECTED 已驳回；CANCELLED 已作废；REVOKED 已撤销。"}};
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
