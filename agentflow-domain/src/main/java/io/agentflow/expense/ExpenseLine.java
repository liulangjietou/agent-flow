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
                          String description, String exceptionReason, ExpenseAllowanceBasis allowance) {
    public static final int MAX_INVOICES = 50;
    public static final int MAX_ALLOCATIONS = 50;
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000");

    /** 普通费用和历史行没有补贴依据，仍按原计量及发票规则处理。 */
    public ExpenseLine(int lineNo, String categoryCode, LocalDate incurredOn, LocalDate endedOn,
                       String cityCode, BigDecimal quantity, Unit unit, Money claimedGross, Money claimedTax,
                       List<UUID> invoiceIds, PriorRequestLine priorRequest, List<CostAllocation> allocations,
                       String description, String exceptionReason) {
        this(lineNo, categoryCode, incurredOn, endedOn, cityCode, quantity, unit, claimedGross, claimedTax,
                invoiceIds, priorRequest, allocations, description, exceptionReason, null);
    }

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
        if (allowance != null) {
            var calculation = allowance.calculation();
            if (unit != Unit.DAY || !incurredOn.equals(calculation.startsOn()) || !java.util.Objects.equals(endedOn, calculation.endsOn())
                    || quantity.compareTo(BigDecimal.valueOf(calculation.days())) != 0 || !claimedGross.equals(calculation.gross())
                    || claimedTax.value().signum() != 0 || !invoiceIds.isEmpty()) {
                throw new DomainException("ALLOWANCE_CALCULATION_MISMATCH", "Allowance line must retain its calculated dates, days and gross, zero deductible tax and no invoices");
            }
        }
        quantity = quantity.stripTrailingZeros();
        invoiceIds = List.copyOf(invoiceIds);
        allocations = List.copyOf(allocations);
    }

    /** 保存服务绑定实际规则后复用单行不变量，不能自动放过手改金额或数量。 */
    public ExpenseLine withAllowance(ExpenseAllowanceBasis basis) {
        return new ExpenseLine(lineNo, categoryCode, incurredOn, endedOn, cityCode, quantity, unit, claimedGross, claimedTax,
                invoiceIds, priorRequest, allocations, description, exceptionReason, basis);
    }

    /** 提交判定不能把旧制度的补贴解释成普通手填费用，也不能扩大计算所得的可报金额。 */
    public void requireCurrentAllowance(ManagedExpensePolicy managed, UUID legalEntityId,
            ExpensePolicySnapshot assessed, ExpenseExchangeRate rate, Money deductibleTax) {
        var receipt = assessed.managedPolicy();
        var rule = managed == null || receipt == null ? null : managed.definition().rules().stream()
                .filter(candidate -> candidate.key().equals(receipt.ruleKey())).findFirst().orElse(null);
        boolean fixed = rule != null && rule.constraints().fixedAllowance() != null;
        if (allowance == null && !fixed) return;
        if (allowance == null || !fixed || !managed.selection().equals(receipt.selection())) {
            throw new DomainException("ALLOWANCE_RECALCULATION_REQUIRED", "Allowance must be recalculated against the current published rule");
        }
        var current = ExpenseAllowanceBasis.calculate(receipt, rule, legalEntityId, this);
        allowance.requireCurrent(current);
        if (!assessed.assessedGross().equals(rate.convert(current.calculation().gross()))
                || !assessed.allowedGross().equals(assessed.assessedGross()) || deductibleTax.value().signum() != 0
                || assessed.decision() != ExpensePolicySnapshot.Decision.WITHIN_LIMIT) {
            throw new DomainException("ALLOWANCE_ASSESSMENT_MISMATCH", "Allowance assessment must preserve the calculated amount and zero deductible tax");
        }
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
