package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 事前申请每轮冻结原币计划、财务法人、本位币汇率与成本分摊，后续不随目录变化改写。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePlanRound(int roundNo, long submittedPlanVersion, String submittedBy, Instant submittedAt,
                               ExpensePlanContent content, FinanceCatalog.LegalEntity legalEntity, String catalogVersion,
                               List<FrozenLine> lines) {
    /** 重建快照时仍核对逐行汇兑和分摊，不信任持久化的派生金额。 */
    public ExpensePlanRound {
        if (roundNo < 1 || submittedPlanVersion < 1 || StringUtils.isBlank(submittedBy) || submittedBy.length() > 128 || submittedAt == null
                || content == null || legalEntity == null || !legalEntity.id().equals(content.legalEntityId())
                || StringUtils.isBlank(catalogVersion) || catalogVersion.length() > 128 || CollectionUtils.isEmpty(lines)
                || lines.size() != content.lines().size()) throw invalid();
        LocalDate date = LocalDate.ofInstant(submittedAt, ZoneId.of(legalEntity.timeZone()));
        Money total = Money.zero(legalEntity.baseCurrency());
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            if (line == null || !line.original().equals(content.lines().get(index)) || !date.equals(line.rate().rateDate())
                    || !legalEntity.baseCurrency().equals(line.amount().currency())) throw invalid();
            total = total.plus(line.amount());
        }
        lines = List.copyOf(lines);
    }

    /** 路由和最终可核销额度使用相同的逐行本位币结果。 */
    public Money total() { return lines.stream().map(FrozenLine::amount).reduce(Money.zero(legalEntity.baseCurrency()), Money::plus); }

    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PLAN_ROUND", "Expense plan round and conversion facts must match"); }

    /**
     * 一行批准请求的候选金额；是否批准仍由实际审批结果决定。
     * @author owlzhangfq@gmail.com
     */
    public record FrozenLine(ExpensePlanContent.Line original, ExpenseExchangeRate rate, Money amount, List<CostAllocation> allocations) {
        /** 原币、汇率、折算额和按整分平衡的成本分摊必须一致。 */
        public FrozenLine {
            if (original == null || rate == null || amount == null || amount.value().signum() <= 0
                    || !amount.equals(rate.convert(original.amount())) || !CostAllocation.apportion(original.allocations(), amount).equals(allocations)) throw invalid();
            allocations = List.copyOf(allocations);
        }
    }
}
