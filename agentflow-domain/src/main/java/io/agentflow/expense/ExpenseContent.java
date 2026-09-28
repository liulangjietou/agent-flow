package io.agentflow.expense;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * 申请人可编辑的完整费用内容，不包含外部查验结果和财务核定额。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseContent(UUID legalEntityId, Type type, String title, List<ExpenseLine> lines,
                             List<AdvanceOffset> advanceOffsets) {
    public static final int MAX_LINES = 200;
    public static final int MAX_ADVANCES = 50;

    /** 允许空明细草稿，同一张发票在一份单据中只能归属于一行。 */
    public ExpenseContent {
        if (legalEntityId == null || type == null || StringUtils.isBlank(title) || title.length() > 256
                || lines == null || lines.size() > MAX_LINES || advanceOffsets == null || advanceOffsets.size() > MAX_ADVANCES) {
            throw new DomainException("INVALID_EXPENSE_CONTENT", "Expense report content is invalid");
        }
        var numbers = new HashSet<Integer>(); var invoices = new HashSet<UUID>(); var advances = new HashSet<UUID>();
        for (var line : lines) {
            if (line == null || !numbers.add(line.lineNo())) throw new DomainException("INVALID_EXPENSE_LINE", "Expense line numbers must be unique");
            for (var invoiceId : line.invoiceIds()) {
                if (!invoices.add(invoiceId)) throw new DomainException("INVOICE_REUSED", "An invoice can only belong to one expense line");
            }
        }
        for (var offset : advanceOffsets) {
            if (offset == null || offset.amount().value().signum() <= 0 || !advances.add(offset.advanceId())) {
                throw new DomainException("INVALID_ADVANCE_OFFSET", "Advance selections must be unique and positive");
            }
        }
        lines = List.copyOf(lines); advanceOffsets = List.copyOf(advanceOffsets);
    }

    /**
     * 报销业务种类，具体制度由租户的版本化标准决定。
     * @author owlzhangfq@gmail.com
     */
    public enum Type { TRAVEL, DAILY, ENTERTAINMENT, TRAINING, OTHER }
}
