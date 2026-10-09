package io.agentflow.expense;

import io.agentflow.common.DomainException;
import java.time.LocalDate;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * 可人工采纳的费用字段差异；金额、票据、分摊、补贴依据和审批字段不在白名单内。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseFieldPatch(int lineNo, Field field, String beforeValue, String afterValue, String impact) {
    /** 缺失日期或事由用空文本表达，类型和值必须在入口形成有效建议。 */
    public ExpenseFieldPatch {
        if (lineNo < 1 || lineNo > ExpenseContent.MAX_LINES || field == null || beforeValue == null || afterValue == null
                || beforeValue.length() > 2000 || afterValue.length() > 2000 || beforeValue.equals(afterValue)
                || StringUtils.isBlank(impact) || impact.length() > 500) throw invalid();
        switch (field) {
            case CATEGORY_CODE -> { if (StringUtils.isBlank(afterValue) || afterValue.length() > 64) throw invalid(); }
            case CITY_CODE -> { if (StringUtils.isBlank(afterValue) || afterValue.length() > 128) throw invalid(); }
            case DESCRIPTION -> { if (StringUtils.isBlank(afterValue)) throw invalid(); }
            case INCURRED_ON -> date(afterValue);
            case ENDED_ON -> { if (!afterValue.isEmpty()) date(afterValue); }
            case EXCEPTION_REASON -> { }
        }
    }
    /** 字段身份就是明确来源，不能用客户端提供的任意路径赋值。 */
    public String sourceId() { return "expense:field[" + lineNo + "]." + field.name(); }
    /** 比较值仍匹配原单据才允许应用，其他字段逐字保留。 */
    public ExpenseLine apply(ExpenseLine line) {
        if (line.lineNo() != lineNo || !field.read(line).equals(beforeValue)) throw new DomainException("AGENT_INPUT_CHANGED", "Expense patch before value changed");
        if (line.allowance() != null && field != Field.DESCRIPTION && field != Field.EXCEPTION_REASON) {
            throw new DomainException("INVALID_AGENT_REVIEW", "Allowance inputs require explicit recalculation");
        }
        return new ExpenseLine(line.lineNo(), field == Field.CATEGORY_CODE ? afterValue : line.categoryCode(),
                field == Field.INCURRED_ON ? date(afterValue) : line.incurredOn(),
                field == Field.ENDED_ON ? afterValue.isEmpty() ? null : date(afterValue) : line.endedOn(),
                field == Field.CITY_CODE ? afterValue : line.cityCode(), line.quantity(), line.unit(), line.claimedGross(), line.claimedTax(),
                line.invoiceIds(), line.priorRequest(), line.allocations(), field == Field.DESCRIPTION ? afterValue : line.description(),
                field == Field.EXCEPTION_REASON ? afterValue : line.exceptionReason(), line.allowance());
    }
    /** 逐项合并只修改指定字段，重复字段及不存在的行立即拒绝。 */
    public static ExpenseContent apply(ExpenseContent content, List<ExpenseFieldPatch> selected) {
        if (selected == null || selected.isEmpty() || selected.stream().map(ExpenseFieldPatch::sourceId).distinct().count() != selected.size()) throw invalid();
        var lines = new java.util.ArrayList<>(content.lines());
        for (var patch : selected) {
            int index = -1;
            for (int i = 0; i < lines.size(); i++) if (lines.get(i).lineNo() == patch.lineNo()) { index = i; break; }
            if (index < 0) throw invalid();
            lines.set(index, patch.apply(lines.get(index)));
        }
        return new ExpenseContent(content.legalEntityId(), content.type(), content.title(), lines, content.advanceOffsets());
    }
    private static LocalDate date(String value) {
        try { if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid(); return LocalDate.parse(value); }
        catch (java.time.DateTimeException failure) { throw invalid(); }
    }
    private static DomainException invalid() { return new DomainException("INVALID_AGENT_OUTPUT", "Expense field patch is invalid"); }
    /**
     * 已保存值是模型的比较基线，人工确认后仍执行原费用业务校验。
     * @author owlzhangfq@gmail.com
     */
    public enum Field {
        CATEGORY_CODE, CITY_CODE, INCURRED_ON, ENDED_ON, DESCRIPTION, EXCEPTION_REASON;
        /** 投影一个明确允许发送的字段，空值不会变成文字 null。 */
        public String read(ExpenseLine line) {
            Object value = switch (this) {
                case CATEGORY_CODE -> line.categoryCode(); case CITY_CODE -> line.cityCode(); case INCURRED_ON -> line.incurredOn();
                case ENDED_ON -> line.endedOn(); case DESCRIPTION -> line.description(); case EXCEPTION_REASON -> line.exceptionReason();
            };
            return value == null ? "" : value.toString();
        }
    }
}
