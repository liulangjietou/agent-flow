package io.agentflow.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 外部预算对某个不可变命令的事实；收到响应不等于已冻结，未找到也不等于业务拒绝。
 * @author owlzhangfq@gmail.com
 */
public record BudgetObservation(UUID operationId, String commandDigest, Status status, Long ledgerRevision,
                                String reference, Instant appliedAt, Rejection rejection,
                                @JsonInclude(JsonInclude.Include.NON_NULL) BudgetExceptionOffer exceptionOffer) {
    /** 原拒绝和成功结果不补造柔性凭据。 */
    public BudgetObservation(UUID operationId, String commandDigest, Status status, Long ledgerRevision,
                             String reference, Instant appliedAt, Rejection rejection) {
        this(operationId, commandDigest, status, ledgerRevision, reference, appliedAt, rejection, null);
    }
    /** 每种结果只有一种合法形状，防止把待处理或拒绝结果解释为预算成功。 */
    public BudgetObservation {
        if (operationId == null || commandDigest == null || !commandDigest.matches("[a-f0-9]{64}") || status == null) throw invalid();
        if (status == Status.APPLIED) {
            if (ledgerRevision == null || ledgerRevision < 1 || StringUtils.isBlank(reference) || reference.length() > 128
                    || appliedAt == null || rejection != null) throw invalid();
        } else if (ledgerRevision != null || reference != null || appliedAt != null
                || (status == Status.REJECTED) != (rejection != null)) throw invalid();
        if ((rejection == Rejection.BUDGET_EXCEPTION_REQUIRED) != (exceptionOffer != null)) throw invalid();
    }

    /** 核对编号、完整命令摘要和递增版本；未找到只能来自专门的只读查询。 */
    public boolean matches(BudgetCommand command, boolean queried, Instant now) {
        return operationId.equals(command.id()) && commandDigest.equals(command.digest())
                && (status != Status.NOT_FOUND || queried)
                && (status != Status.APPLIED || ledgerRevision == (command.expected() == null ? 1 : command.expected().revision() + 1)
                        && !appliedAt.isAfter(now))
                && (rejection != Rejection.BUDGET_INSUFFICIENT && rejection != Rejection.BUDGET_EXCEPTION_REQUIRED
                        || command.action() == BudgetCommand.Action.FREEZE || command.action() == BudgetCommand.Action.ADJUST);
    }

    /**
     * 终态结果在外部按幂等号保留；NOT_FOUND 必须是权威查询，不能用缓存缺失冒充。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { APPLIED, REJECTED, PENDING, NOT_FOUND }

    /**
     * 已确认本次命令没有产生变更的业务拒绝；版本冲突不能通过换编号自动覆盖。
     * @author owlzhangfq@gmail.com
     */
    public enum Rejection { BUDGET_INSUFFICIENT, BUDGET_POLICY_UNAVAILABLE, ACCOUNTING_PERIOD_CLOSED,
        COST_OBJECT_UNAVAILABLE, LEGAL_ENTITY_UNAVAILABLE, EMPLOYEE_UNAVAILABLE, LEDGER_VERSION_CONFLICT, RESERVATION_FINALIZED, BUDGET_EXCEPTION_REQUIRED }

    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_OBSERVATION", "Budget observation must identify the original command and a consistent outcome"); }
}
