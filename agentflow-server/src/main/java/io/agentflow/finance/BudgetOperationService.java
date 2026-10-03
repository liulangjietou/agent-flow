package io.agentflow.finance;

import io.agentflow.common.DomainException;
import io.agentflow.expense.ExpenseReportRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 财务预算的短事务编排；外发只在领取事务提交后进行，所有业务改变通过同一报销锁排序。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetOperationService {
    private final ExpenseReportRepository reports;
    private final JdbcBudgetOccupationRepository occupations;
    private final JdbcBudgetOperationRepository operations;
    private final Duration lease;
    private final ApplicationEventPublisher events;

    /** 领取租约有界；恢复不依赖当前进程保存状态。 */
    public BudgetOperationService(ExpenseReportRepository reports, JdbcBudgetOccupationRepository occupations,
            JdbcBudgetOperationRepository operations, @Value("${agentflow.budgets.lease-seconds:90}") int leaseSeconds, ApplicationEventPublisher events) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Budget lease must be between 15 and 300 seconds");
        this.reports = reports; this.occupations = occupations; this.operations = operations; this.lease = Duration.ofSeconds(leaseSeconds);
        this.events = events;
    }

    /** 必须加入正式提交或核减事务，金额从刚保存的实际轮次派生，不能传客户端预算命令。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public BudgetOperation reserve(String tenant, UUID reportId, long financialVersion, LocalDate date, String targetDigest, Instant now) {
        reports.lock(tenant, reportId);
        var report = reports.find(tenant, reportId).orElseThrow(BudgetOperationService::notFound);
        if (report.version() != financialVersion) throw new DomainException("CONCURRENCY_CONFLICT", "Financial version changed before budget registration");
        var occupation = occupations.find(tenant, reportId).orElse(null);
        var command = new BudgetCommand(UUID.randomUUID(), tenant,
                occupation == null || occupation.confirmed() == null || occupation.status() == BudgetOccupation.Status.RELEASED
                        ? BudgetCommand.Action.FREEZE : BudgetCommand.Action.ADJUST,
                BudgetPrecheckPort.Request.fromCurrent(report, date), occupation == null || occupation.confirmed() == null ? null : occupation.confirmed().expected());
        return register(occupation, new BudgetOperation.Input(command, targetDigest), now);
    }

    /** 驳回或结算事务使用最后确认的冻结，不能释放尚未查清的外部操作。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public BudgetOperation finalizeOccupation(String tenant, UUID reportId, BudgetCommand.Action action, Instant now) {
        if (action != BudgetCommand.Action.RELEASE && action != BudgetCommand.Action.CONSUME) throw new IllegalArgumentException("A final budget action is required");
        reports.lock(tenant, reportId);
        var occupation = occupations.find(tenant, reportId).orElseThrow(BudgetOperationService::notFound);
        if (occupation.pendingOperationId() != null) throw new DomainException("BUDGET_OPERATION_PENDING", "Previous budget operation must be reconciled first");
        if (occupation.status() != BudgetOccupation.Status.FROZEN) throw new DomainException("BUDGET_NOT_FROZEN", "Budget occupation is not frozen");
        var command = new BudgetCommand(UUID.randomUUID(), tenant, action, occupation.confirmed().position(), occupation.confirmed().expected());
        return register(occupation, new BudgetOperation.Input(command, occupation.targetDigest()), now);
    }

    /** 申请锁内读取领取状态，过期执行只转换为未知，下一次领取查询原操作。 */
    @Transactional
    public BudgetOperation claim(String tenant, UUID id, Instant now) {
        var found = operations.find(tenant, id).orElse(null); if (found == null) return null;
        reports.lock(tenant, found.input().command().position().reportId());
        var current = operations.find(tenant, id).orElseThrow(BudgetOperationService::notFound); now = time(now);
        if (current.expired(now)) { persist(current, current.expire(now)); return null; }
        if (current.terminal() || current.running() || now.isBefore(current.nextAttemptAt())) return null;
        var claimed = current.claim(now, lease); persist(current, claimed); return claimed;
    }

    /** 任务和预算台账一并落库；迟到执行者不能越过领取版本更新任何状态。 */
    @Transactional
    public void finish(BudgetOperation claimed, FinanceResult<BudgetObservation> result, Instant now) {
        var current = currentClaim(claimed); if (current == null) return;
        persist(current, current.complete(result, time(now)));
    }

    /** 未分类本地异常同样保留为未知外部结果。 */
    @Transactional
    public void fail(BudgetOperation claimed, Instant now) {
        var current = currentClaim(claimed); if (current == null) return;
        persist(current, current.unavailable(BudgetOperation.Failure.INTERNAL_ERROR, time(now)));
    }

    private BudgetOperation register(BudgetOccupation previous, BudgetOperation.Input input, Instant now) {
        var occupation = previous == null ? BudgetOccupation.begin(input) : previous.enqueue(input);
        if (previous == null) occupations.create(occupation); else occupations.update(occupation);
        var operation = BudgetOperation.queue(input, time(now)); operations.create(operation); return operation;
    }
    private BudgetOperation currentClaim(BudgetOperation claimed) {
        var command = claimed.input().command(); reports.lock(command.tenantId(), command.position().reportId());
        var current = operations.find(command.tenantId(), command.id()).orElseThrow(BudgetOperationService::notFound);
        return current.version() == claimed.version() && current.running() && current.status() == claimed.status() && current.input().equals(claimed.input()) ? current : null;
    }
    private void persist(BudgetOperation previous, BudgetOperation completed) {
        operations.update(completed);
        if (completed.terminal()) {
            var command = completed.input().command();
            var occupation = occupations.find(command.tenantId(), command.position().reportId()).orElseThrow(BudgetOperationService::notFound);
            occupations.update(occupation.complete(completed));
            events.publishEvent(new BudgetOperationCompleted(completed));
        }
        events.publishEvent(new BudgetOperationChanged(previous, completed));
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException notFound() { return new DomainException("NOT_FOUND", "Budget operation or financial report not found"); }
}
