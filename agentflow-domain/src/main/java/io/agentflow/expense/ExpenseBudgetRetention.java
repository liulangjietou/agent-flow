package io.agentflow.expense;

import io.agentflow.approval.model.SubmissionRound;
import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetCommand;
import io.agentflow.finance.BudgetOperation;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 一次退回或撤回的预算保留记录；期限固定于实际结束轮次，修改草稿不会重新起算。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseBudgetRetention(String tenantId, UUID reportId, UUID applicationId, int roundNo,
        SubmissionRound.Status stoppedStatus, Instant retainedAt, Policy policy, Instant expiresAt,
        Status status, UUID releaseOperationId, String issue, long version, Instant updatedAt) {
    /** 恢复时复核固定期限和释放命令归属，排队及未知结果都不等同于已经释放。 */
    public ExpenseBudgetRetention {
        if (StringUtils.isBlank(tenantId) || tenantId.length() > 64 || reportId == null || applicationId == null
                || roundNo < 1 || stoppedStatus != SubmissionRound.Status.RETURNED && stoppedStatus != SubmissionRound.Status.WITHDRAWN
                || retainedAt == null || policy == null || expiresAt == null || !expiresAt.equals(policy.expiresAt(retainedAt))
                || status == null || version < 1 || updatedAt == null || updatedAt.isBefore(retainedAt)
                || (status == Status.RELEASE_QUEUED || status == Status.RELEASED || status == Status.RELEASE_REJECTED) != (releaseOperationId != null)
                || (status == Status.RELEASE_REJECTED) != (issue != null) || issue != null && (issue.isBlank() || issue.length() > 64)) throw invalid();
    }

    /** 只在真实轮次结束时创建，之后的配置变化不能移动此期限。 */
    public static ExpenseBudgetRetention retain(String tenant, UUID report, UUID application, int round,
            SubmissionRound.Status stopped, Instant at, Policy policy) {
        return new ExpenseBudgetRetention(tenant, report, application, round, stopped, at, policy, policy.expiresAt(at),
                Status.RETAINED, null, null, 1, at);
    }

    /** 外部原冻结或调整尚未查明时，只等待原操作，不能先发送新的释放。 */
    public ExpenseBudgetRetention reconcile(Instant now) {
        requireDue(now); return status == Status.RECONCILING ? this : change(Status.RECONCILING, null, null, now);
    }

    /** 同事务登记释放任务后保留其唯一编号；实际结果由预算执行器确认。 */
    public ExpenseBudgetRetention queue(BudgetOperation operation, Instant now) {
        requireDue(now); var command = operation.input().command();
        if (command.action() != BudgetCommand.Action.RELEASE || !tenantId.equals(command.tenantId())
                || !reportId.equals(command.position().reportId()) || command.position().roundNo() > roundNo
                || operation.status() != BudgetOperation.Status.QUEUED) throw conflict();
        return change(Status.RELEASE_QUEUED, command.id(), null, now);
    }

    /** 原号查询成功和首次执行成功使用同一终态，拒绝不会生成无限的新释放命令。 */
    public ExpenseBudgetRetention complete(BudgetOperation operation, Instant now) {
        var command = operation.input().command();
        if (status != Status.RELEASE_QUEUED || !releaseOperationId.equals(command.id())
                || command.action() != BudgetCommand.Action.RELEASE || !tenantId.equals(command.tenantId())
                || !reportId.equals(command.position().reportId())) throw conflict();
        if (!operation.terminal()) return this;
        return operation.status() == BudgetOperation.Status.APPLIED
                ? change(Status.RELEASED, releaseOperationId, null, now)
                : change(Status.RELEASE_REJECTED, releaseOperationId, operation.observation().rejection().name(), now);
    }

    /** 已重新提交或进入其他结论时，未发送的到期任务失效；已发送任务必须继续核对原号。 */
    public ExpenseBudgetRetention supersede(Instant now) { requireUnqueued(); return change(Status.SUPERSEDED, null, null, now); }

    /** 原冻结明确未成功或已经没有冻结时，不凭空创建释放凭据。 */
    public ExpenseBudgetRetention unfrozen(Instant now) { requireDue(now); return change(Status.NO_FROZEN_BUDGET, null, null, now); }

    /** 只有未结束记录参与有界扫描，未知外部结果继续留在队列。 */
    public boolean terminal() { return status != Status.RETAINED && status != Status.RECONCILING && status != Status.RELEASE_QUEUED; }

    private void requireUnqueued() { if (status != Status.RETAINED && status != Status.RECONCILING) throw conflict(); }
    private void requireDue(Instant now) { requireUnqueued(); if (now.isBefore(expiresAt)) throw conflict(); }
    private ExpenseBudgetRetention change(Status next, UUID operation, String failure, Instant now) {
        if (now.isBefore(updatedAt)) throw conflict();
        return new ExpenseBudgetRetention(tenantId, reportId, applicationId, roundNo, stoppedStatus, retainedAt, policy, expiresAt,
                next, operation, failure, Math.incrementExact(version), now);
    }
    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_RETENTION", "Budget retention must preserve its source round, policy and deadline"); }
    private static DomainException conflict() { return new DomainException("BUDGET_RETENTION_CONFLICT", "Budget retention no longer permits this transition"); }

    /**
     * 保留天数必须显式配置；每一天为连续 24 小时，不按编辑时间或工作日重新计算。
     * @author owlzhangfq@gmail.com
     */
    public record Policy(int retentionDays) {
        public static final int MAX_DAYS = 3660;
        /** 有界配置避免错误数量造成日期溢出，不提供企业默认保留期。 */
        public Policy { if (retentionDays < 1 || retentionDays > MAX_DAYS) throw invalid(); }
        /** 使用轮次实际结束时刻计算绝对期限，避免跨时区和夏令时重新解释。 */
        public Instant expiresAt(Instant retainedAt) { return retainedAt.plus(retentionDays, ChronoUnit.DAYS); }
    }

    /**
     * 原预算待核对与释放命令已登记分别记录，终态保留原释放结果或被新轮次取代的事实。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { RETAINED, RECONCILING, RELEASE_QUEUED, RELEASED, SUPERSEDED, NO_FROZEN_BUDGET, RELEASE_REJECTED }
}
