package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * 事前申请的计划费用；不包含已批准额度、票面或已放款事实。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePlanContent(UUID legalEntityId, ExpenseContent.Type type, String title, List<Line> lines) {
    /** 草稿允许没有明细，提交时必须有至少一行完整计划。 */
    public ExpensePlanContent {
        if (legalEntityId == null || type == null || StringUtils.isBlank(title) || title.length() > 256
                || lines == null || lines.size() > ExpenseContent.MAX_LINES || lines.stream().anyMatch(java.util.Objects::isNull)
                || lines.stream().map(Line::lineNo).distinct().count() != lines.size()) throw invalid();
        title = title.trim(); lines = List.copyOf(lines);
    }

    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PLAN", "Expense plan fields and line numbers must be valid"); }

    /**
     * 原币计划金额和成本归属整体审批，不能把其他批准行的余额挪来替代。
     * @author owlzhangfq@gmail.com
     */
    public record Line(int lineNo, String categoryCode, LocalDate plannedOn, LocalDate endedOn, String cityCode,
                       Money amount, List<CostAllocation> allocations, String description) {
        /** 分摊使用精确金额，同一成本对象只能出现一次。 */
        public Line {
            if (lineNo < 1 || lineNo > ExpenseContent.MAX_LINES || StringUtils.isBlank(categoryCode) || categoryCode.length() > 64
                    || plannedOn == null || endedOn != null && endedOn.isBefore(plannedOn) || StringUtils.isBlank(cityCode) || cityCode.length() > 128
                    || amount == null || amount.value().signum() <= 0 || StringUtils.isBlank(description) || description.length() > 2000
                    || CollectionUtils.isEmpty(allocations) || allocations.size() > ExpenseLine.MAX_ALLOCATIONS) throw invalid();
            var targets = new HashSet<List<String>>(); Money allocated = Money.zero(amount.currency());
            for (var allocation : allocations) {
                if (allocation == null || allocation.amount().value().signum() <= 0
                        || !targets.add(List.of(allocation.costCenter(), allocation.projectCode() == null ? "" : allocation.projectCode()))) throw invalid();
                allocated = allocated.plus(allocation.amount());
            }
            if (allocated.compareTo(amount) != 0) throw new DomainException("ALLOCATION_UNBALANCED", "Expense plan allocations must equal the planned amount");
            allocations = List.copyOf(allocations); description = description.trim();
        }
    }
}
