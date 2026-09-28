package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 单据的预算台账只保存已确认事实；一个待定操作期间不允许下一次变更或财务放行。
 * @author owlzhangfq@gmail.com
 */
public record BudgetOccupation(String tenantId, UUID reportId, String employeeId, String targetDigest, long version,
                               Status status, Confirmed confirmed, UUID pendingOperationId) {
    /** 初始未冻结没有外部凭据，其余状态必须带最后一次真实生效结果。 */
    public BudgetOccupation {
        if (StringUtils.isBlank(tenantId) || tenantId.length() > 64 || reportId == null || StringUtils.isBlank(employeeId) || employeeId.length() > 128
                || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}") || version < 1 || status == null
                || (status == Status.UNFUNDED) != (confirmed == null)
                || confirmed != null && (!reportId.equals(confirmed.position().reportId()) || !employeeId.equals(confirmed.position().employeeId()))
                || (status == Status.RELEASED || status == Status.CONSUMED) && pendingOperationId != null) throw invalid();
    }

    /** 首次冻结和事务内 outbox 登记一起创建，尚未获得预算成功事实。 */
    public static BudgetOccupation begin(BudgetOperation.Input input) {
        var command = input.command();
        if (command.action() != BudgetCommand.Action.FREEZE) throw conflict();
        return new BudgetOccupation(command.tenantId(), command.position().reportId(), command.position().employeeId(), input.targetDigest(),
                1, Status.UNFUNDED, null, command.id());
    }

    /** 前一操作明确结束后才可登记下一操作，释放和占用必须使用已冻结的精确分摊。 */
    public BudgetOccupation enqueue(BudgetOperation.Input input) {
        var command = input.command();
        if (pendingOperationId != null) throw new DomainException("BUDGET_OPERATION_PENDING", "Previous budget operation must be reconciled first");
        requireCommand(command);
        if (!targetDigest.equals(input.targetDigest())) throw new DomainException("BUDGET_TARGET_CHANGED", "Budget ledger belongs to a different gateway target");
        if (status == Status.RELEASED || status == Status.CONSUMED) throw new DomainException("BUDGET_FINALIZED", "Budget ledger has been finalized");
        if (command.action() == BudgetCommand.Action.ADJUST) {
            var previous = confirmed.position(); var next = command.position();
            if (next.financialVersion() <= previous.financialVersion() || next.roundNo() < previous.roundNo()
                    || next.roundNo() > previous.roundNo() + 1) throw conflict();
        } else if (command.action() != BudgetCommand.Action.FREEZE && !command.position().equals(confirmed.position())) throw conflict();
        return new BudgetOccupation(tenantId, reportId, employeeId, targetDigest, Math.incrementExact(version), status, confirmed, command.id());
    }

    /** 操作终态与台账在同一短事务落库；拒绝保留原冻结，不制造新的成功版本。 */
    public BudgetOccupation complete(BudgetOperation operation) {
        var command = operation.input().command();
        if (!operation.terminal() || !Objects.equals(pendingOperationId, command.id()) || !targetDigest.equals(operation.input().targetDigest())) throw conflict();
        requireCommand(command);
        Status next = status; Confirmed result = confirmed;
        if (operation.status() == BudgetOperation.Status.APPLIED) {
            var observed = operation.observation();
            next = switch (command.action()) {
                case FREEZE, ADJUST -> Status.FROZEN;
                case RELEASE -> Status.RELEASED;
                case CONSUME -> Status.CONSUMED;
            };
            result = new Confirmed(command.position(), observed.ledgerRevision(), observed.reference(), observed.appliedAt());
        }
        return new BudgetOccupation(tenantId, reportId, employeeId, targetDigest, Math.incrementExact(version), next, result, null);
    }

    /** 财务守卫核对完整位置及当前财务版本；旧冻结不能批准已改动的单据。 */
    public boolean frozenFor(BudgetPrecheckPort.Request position) {
        return status == Status.FROZEN && pendingOperationId == null && confirmed.position().equals(position);
    }

    private void requireCommand(BudgetCommand command) {
        if (!tenantId.equals(command.tenantId()) || !reportId.equals(command.position().reportId()) || !employeeId.equals(command.position().employeeId())
                || !Objects.equals(command.expected(), confirmed == null ? null : confirmed.expected())
                || (status == Status.UNFUNDED) != (command.action() == BudgetCommand.Action.FREEZE)) throw conflict();
    }
    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_OCCUPATION", "Budget occupation identity or confirmed state is invalid"); }
    private static DomainException conflict() { return new DomainException("BUDGET_LEDGER_CONFLICT", "Budget command does not match the current confirmed ledger"); }

    /**
     * 未冻结、已冻结和两个最终状态，执行中由 pendingOperationId 表示。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { UNFUNDED, FROZEN, RELEASED, CONSUMED }

    /**
     * 外部事实与完整分摊一起保留，后续操作不能仅凭金额相等复用。
     * @author owlzhangfq@gmail.com
     */
    public record Confirmed(BudgetPrecheckPort.Request position, long revision, String reference, Instant appliedAt) {
        /** 必须有真实的台账版本、凭据和生效时刻。 */
        public Confirmed {
            if (position == null || revision < 1 || StringUtils.isBlank(reference) || reference.length() > 128 || appliedAt == null) throw invalid();
        }
        /** 后续操作引用当前外部版本，不用本地审计版本代替。 */
        public BudgetCommand.Expected expected() { return new BudgetCommand.Expected(revision, reference); }
    }
}
