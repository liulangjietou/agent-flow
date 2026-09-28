package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.CostAllocation;
import io.agentflow.expense.ExpenseContent;
import io.agentflow.expense.ExpenseLine;
import io.agentflow.expense.ExpenseReport;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * 预算只读预检检查核定含税分摊；通过不代表已冻结，也不允许扣减预算。
 * @author owlzhangfq@gmail.com
 */
public interface BudgetPrecheckPort {
    /** 企业按实际预算科目与期间检查；服务不可用不能当作无限预算。 */
    FinanceResult<Assessment> precheck(String tenantId, Request request);

    /**
     * 单据和当前待提交轮次的精确分摊；外部重提检查只能抵扣同单上一轮的原冻结。
     * @author owlzhangfq@gmail.com
     */
    record Request(UUID reportId, int roundNo, long financialVersion, String employeeId, UUID legalEntityId,
                   String baseCurrency, LocalDate accountingDate, List<Allocation> allocations) {
        /** 不接受跨币种、重复位置或无界分摊，不把业务金额转成浮点数。 */
        public Request {
            if (reportId == null || roundNo < 1 || financialVersion < 1 || StringUtils.isBlank(employeeId) || employeeId.length() > 128
                    || legalEntityId == null || accountingDate == null || CollectionUtils.isEmpty(allocations)
                    || allocations.size() > ExpenseContent.MAX_LINES * ExpenseLine.MAX_ALLOCATIONS) throw invalid();
            Money.zero(baseCurrency);
            var positions = new HashSet<String>();
            for (var allocation : allocations) {
                if (allocation == null || !baseCurrency.equals(allocation.cost().amount().currency())
                        || !positions.add(allocation.expenseLineNo() + ":" + allocation.allocationNo())) throw invalid();
            }
            allocations = List.copyOf(allocations);
        }

        /** 从通过领域核算的预提交快照派生，不使用客户端自行填写的预算分摊。 */
        public static Request from(ExpenseReport prepared, LocalDate accountingDate) {
            var round = prepared.currentRound(); var allocations = new ArrayList<Allocation>();
            for (int index = 0; index < round.approvedLines().size(); index++) {
                var approved = round.approvedLines().get(index); var original = round.originalLines().get(index).original();
                for (int allocationNo = 0; allocationNo < approved.allocations().size(); allocationNo++) {
                    allocations.add(new Allocation(approved.lineNo(), allocationNo + 1, original.categoryCode(), approved.allocations().get(allocationNo)));
                }
            }
            return new Request(prepared.id(), round.roundNo(), round.submittedFinancialVersion(), prepared.employeeId(),
                    round.content().legalEntityId(), round.baseCurrency(), accountingDate, allocations);
        }

        /** 每项均已按本币整分核定，预算检查不再折算汇率或扣除借款冲销。 */
        public Money total() { return allocations.stream().map(value -> value.cost().amount()).reduce(Money.zero(baseCurrency), Money::plus); }
    }

    /**
     * 费用类别由企业预算服务映射为真实预算科目，本地不假造科目编码。
     * @author owlzhangfq@gmail.com
     */
    record Allocation(int expenseLineNo, int allocationNo, String categoryCode, CostAllocation cost) {
        /** 零分摊允许出现在整分分配后，但不能形成重复位置。 */
        public Allocation {
            if (expenseLineNo < 1 || expenseLineNo > ExpenseContent.MAX_LINES || allocationNo < 1 || allocationNo > ExpenseLine.MAX_ALLOCATIONS
                    || StringUtils.isBlank(categoryCode) || categoryCode.length() > 64 || cost == null) throw invalid();
        }
    }

    /**
     * 返回完整核对请求及短期证据；后续冻结仍须幂等外部操作，不能把此结果当作冻结凭证。
     * @author owlzhangfq@gmail.com
     */
    record Assessment(Request request, String reference, Instant checkedAt, Instant validUntil) {
        /** 没有来源或有效期不能成为提交预检事实。 */
        public Assessment {
            if (request == null || StringUtils.isBlank(reference) || reference.length() > 128 || checkedAt == null
                    || validUntil == null || !validUntil.isAfter(checkedAt)) throw invalid();
        }
    }

    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_PRECHECK", "Budget precheck context, allocations or evidence are invalid"); }
}
