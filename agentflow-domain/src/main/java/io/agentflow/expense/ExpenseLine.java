package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * 原币费用明细；金额、税额、发票及成本归属在提交轮次中整体冻结。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseLine(int lineNo, String categoryCode, LocalDate incurredOn, LocalDate endedOn,
                          String cityCode, BigDecimal quantity, Unit unit, Money claimedGross, Money claimedTax,
                          List<UUID> invoiceIds, PriorRequestLine priorRequest, List<CostAllocation> allocations,
                          String description, String exceptionReason) {
    public static final int MAX_INVOICES = 50;
    public static final int MAX_ALLOCATIONS = 50;
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000");

    /** 费用标准由应用服务查验，此处保证单行的金额与分摊自洽。 */
    public ExpenseLine {
        if (lineNo < 1 || lineNo > ExpenseContent.MAX_LINES || StringUtils.isBlank(categoryCode) || categoryCode.length() > 64
                || incurredOn == null || endedOn != null && endedOn.isBefore(incurredOn)
                || StringUtils.isBlank(cityCode) || cityCode.length() > 128 || unit == null
                || quantity == null || quantity.signum() <= 0 || quantity.compareTo(MAX_QUANTITY) > 0
                || quantity.stripTrailingZeros().scale() > 3 || claimedGross == null || claimedGross.value().signum() <= 0
                || claimedTax == null || StringUtils.isBlank(description) || description.length() > 2000
                || exceptionReason != null && exceptionReason.length() > 2000) throw invalid();
        if (claimedTax.compareTo(claimedGross) > 0) throw invalid();
        if (invoiceIds == null || invoiceIds.size() > MAX_INVOICES || invoiceIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(invoiceIds).size() != invoiceIds.size()
                || CollectionUtils.isEmpty(allocations) || allocations.size() > MAX_ALLOCATIONS) throw invalid();
        Money allocated = Money.zero(claimedGross.currency());
        var targets = new HashSet<List<String>>();
        for (var allocation : allocations) {
            if (allocation == null || allocation.amount().value().signum() <= 0
                    || !targets.add(List.of(allocation.costCenter(), allocation.projectCode() == null ? "" : allocation.projectCode()))) throw invalid();
            allocated = allocated.plus(allocation.amount());
        }
        if (allocated.compareTo(claimedGross) != 0) throw new DomainException("ALLOCATION_UNBALANCED", "Cost allocations must equal the line amount");
        quantity = quantity.stripTrailingZeros();
        invoiceIds = List.copyOf(invoiceIds);
        allocations = List.copyOf(allocations);
    }

    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_LINE", "Expense line fields or amounts are invalid"); }

    /**
     * 费用计量单位，由匹配费用标准解释其上限，不能把单位数量当作核定金额。
     * @author owlzhangfq@gmail.com
     */
    public enum Unit { ITEM, DAY, NIGHT, KILOMETER, PERSON }

    /**
     * 引用已批准事前申请的具体行，余额和同一申请人由跨聚合服务校验。
     * @author owlzhangfq@gmail.com
     */
    public record PriorRequestLine(UUID requestId, int lineNo) {
        /** 引用必须明确到事前申请行。 */
        public PriorRequestLine {
            if (requestId == null || lineNo < 1) throw new DomainException("INVALID_PRIOR_REQUEST", "A prior request line is required");
        }
    }
}
