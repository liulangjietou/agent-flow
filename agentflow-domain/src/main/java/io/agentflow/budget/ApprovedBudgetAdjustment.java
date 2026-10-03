package io.agentflow.budget;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 预算执行的不可变来源，只接受实际批准后的原申请和同一冻结轮次。
 * @author owlzhangfq@gmail.com
 */
public record ApprovedBudgetAdjustment(String tenantId, UUID requestId, UUID applicationId, String employeeId,
        long approvedRequestVersion, BudgetAdjustmentRound round, BudgetAdjustmentRequest.Approval approval) {
    /** 批准必须紧随原提交修订，不能用普通表单或另一个人的批准替换原申请。 */
    public ApprovedBudgetAdjustment {
        if (invalidText(tenantId, 64) || requestId == null || applicationId == null || invalidText(employeeId, 128)
                || approvedRequestVersion < 1 || round == null || approval == null || !round.submittedBy().equals(employeeId)
                || approvedRequestVersion != round.submittedRequestVersion() + 2 || approval.roundNo() != round.roundNo()
                || approval.approvedAt().isBefore(round.submittedAt())) throw changed();
    }

    /** 应用层从真实持久申请读取来源，后续版本复核仍由原仓储和审批聚合完成。 */
    public static ApprovedBudgetAdjustment from(BudgetAdjustmentRequest request) {
        if (request == null || request.approval() == null || !request.content().equals(request.currentRound().content())) throw changed();
        return new ApprovedBudgetAdjustment(request.tenantId(), request.id(), request.applicationId(), request.employeeId(),
                request.version(), request.currentRound(), request.approval());
    }

    /** 财务可确认新的额度和占用，但不得更换已批准的预算、日期、币种或期间。 */
    public void requireCurrentLedger(BudgetLedgerPort.Snapshot current, Instant now) {
        if (current == null || now == null || now.isBefore(approval.approvedAt()) || current.observedAt().isBefore(approval.approvedAt())
                || !current.matches(round.content().ledgerRequest(employeeId), now)) throw changed();
        for (var position : current.positions()) {
            var original = round.ledger().position(position.reference());
            if (!original.periodReference().equals(position.periodReference()) || !original.periodStart().equals(position.periodStart())
                    || !original.periodEnd().equals(position.periodEnd()) || !original.limit().currency().equals(position.limit().currency())) throw changed();
        }
        round.content().changes(current);
    }

    /** 日志只显示申请标识，预算依据留在受控持久事实中。 */
    @Override public String toString() { return "ApprovedBudgetAdjustment[requestId=" + requestId + "]"; }
    private static boolean invalidText(String value, int maximum) {
        return StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
    }
    private static DomainException changed() { return new DomainException("BUDGET_ADJUSTMENT_SOURCE_CHANGED", "Budget execution requires the original approved request and matching current ledger scope"); }
}
