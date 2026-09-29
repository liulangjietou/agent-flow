package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.BudgetConsumptionReversalObservation;
import io.agentflow.finance.BudgetConsumptionReversalOperation;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.JdbcBudgetConsumptionReversalRepository;
import io.agentflow.finance.PaymentPersonnel;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报销调整的预算外发领取与结果登记，短事务之间的网络等待由工作器负责。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpenseResourceAdjustmentBudgetExecution {
    private static final Duration LEASE = Duration.ofSeconds(90);
    private final ExpenseReportRepository reports;
    private final JdbcExpenseResourceAdjustmentRepository adjustments;
    private final JdbcBudgetConsumptionReversalRepository operations;
    private final ExpenseResourceAdjustmentSources sources;
    private final PaymentPersonnel personnel;
    private final FinanceGatewayConfiguration gateway;
    /** 原报销锁同时保护预算领取、安全结束和资源执行，避免释放后迟到外发。 */
    public ExpenseResourceAdjustmentBudgetExecution(ExpenseReportRepository reports, JdbcExpenseResourceAdjustmentRepository adjustments,
            JdbcBudgetConsumptionReversalRepository operations, ExpenseResourceAdjustmentSources sources, PaymentPersonnel personnel, FinanceGatewayConfiguration gateway) {
        this.reports = reports; this.adjustments = adjustments; this.operations = operations; this.sources = sources; this.personnel = personnel; this.gateway = gateway;
    }
    /** 首次发送复核原授权，过期与失效只停止新写入；未知结果继续查询原命令。 */
    @Transactional
    public BudgetConsumptionReversalOperation claim(String tenant, UUID id, Instant at) {
        var current = locked(tenant, id); var now = time(at);
        if (current.expired(now)) { operations.update(current.expire(now)); return null; }
        if (current.running() || current.nextAttemptAt() == null || current.nextAttemptAt().isAfter(now)) return null;
        if (current.status() == BudgetConsumptionReversalOperation.Status.QUEUED && now.isBefore(current.input().command().expiresAt())) {
            try { requireSendSources(current); }
            catch (DomainException changed) { var stopped = current.voidBeforeSend(now); operations.update(stopped); sync(stopped, now); return null; }
        }
        var claimed = current.claim(now, LEASE); operations.update(claimed);
        if (!claimed.running()) { sync(claimed, now); return null; } return claimed;
    }
    /** 接受同一领取版本的回执，明确预算成功才允许后续本地资源执行。 */
    @Transactional
    public void finish(BudgetConsumptionReversalOperation claimed, FinanceResult<BudgetConsumptionReversalObservation> result, Instant at) {
        var current = current(claimed); if (current == null) return; var now = time(at); var completed = current.complete(result, now);
        operations.update(completed); sync(completed, now);
    }
    /** HTTP 或完成事务异常后保留未知；不能通过生成新命令掩盖外部结果。 */
    @Transactional
    public void fail(BudgetConsumptionReversalOperation claimed, Instant at) {
        var current = current(claimed); if (current == null) return; var now = time(at);
        var unknown = current.unavailable(BudgetConsumptionReversalOperation.Failure.INTERNAL_ERROR, now); operations.update(unknown); sync(unknown, now);
    }
    /** 人工只读查询保留原输入，已完成或待执行资源先转复核，查询期间不能重复释放资源。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public BudgetConsumptionReversalOperation query(String tenant, UUID id, long expected, Instant at) {
        var current = locked(tenant, id); requireVersion(current, expected); if (current.attempts() == 0) throw conflict(); var now = time(at);
        var next = current.requestQuery(now); operations.update(next);
        var adjustment = adjustment(tenant, id);
        if (adjustment.status() == ExpenseResourceAdjustment.Status.READY || adjustment.status() == ExpenseResourceAdjustment.Status.APPLIED) {
            adjustments.update(adjustment.requireReview("BUDGET_RECHECK_REQUIRED", now));
        }
        return next;
    }
    /** 原授权人明确重发权威查无命令，仍使用原编号和有效期，不重新延长授权。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public BudgetConsumptionReversalOperation resend(String tenant, UUID id, long expected, Instant at) {
        var current = locked(tenant, id); requireVersion(current, expected); requireSendSources(current);
        var next = current.retryNotFound(time(at)); operations.update(next); return next;
    }
    /** 安全结束先持久停止未外发命令，调整占用与结束审计由外层同一事务处理。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public BudgetConsumptionReversalOperation stop(String tenant, UUID id, long expected, Instant at) {
        var current = locked(tenant, id); requireVersion(current, expected);
        if (current.status() == BudgetConsumptionReversalOperation.Status.QUEUED) {
            var stopped = current.voidBeforeSend(time(at)); operations.update(stopped); return stopped;
        }
        return current;
    }
    void requireSendSources(BudgetConsumptionReversalOperation operation) {
        var command = operation.input().command(); var adjustment = adjustment(command.source().tenantId(), command.adjustmentId());
        if (!adjustment.input().budget().equals(operation.input()) || adjustment.budgetReversal() != null || adjustment.resourcesReversed()) throw conflict();
        if (gateway.destination(command.source().tenantId()).filter(value -> value.digest(command.source().tenantId()).equals(operation.input().targetDigest())).isEmpty()) {
            throw new DomainException("FINANCE_TARGET_CHANGED", "Original budget destination is unavailable before sending");
        }
        if (operation.attempts() == 0) sources.requireCurrent(adjustment.input().basis()); else sources.requireSupported(adjustment.input().basis());
        personnel.requireEligible(command.source().tenantId(), command.authorizedBy(), command.source().position().legalEntityId());
    }
    private void sync(BudgetConsumptionReversalOperation operation, Instant at) {
        var command = operation.input().command(); var current = adjustment(command.source().tenantId(), command.adjustmentId());
        if (operation.status() == BudgetConsumptionReversalOperation.Status.APPLIED && current.status() == ExpenseResourceAdjustment.Status.WAITING_BUDGET) {
            adjustments.update(current.budgetApplied(operation, at)); return;
        }
        String issue = switch (operation.status()) {
            case REJECTED -> "BUDGET_" + operation.observation().rejection().name();
            case EXPIRED -> "BUDGET_AUTHORIZATION_EXPIRED";
            case VOIDED -> "EXPENSE_ADJUSTMENT_SOURCE_CHANGED";
            case RECONCILING -> "BUDGET_RECONCILING";
            default -> null;
        };
        if (issue != null) {
            var next = current.requireReview(issue, at); if (next != current) adjustments.update(next);
        }
    }
    private BudgetConsumptionReversalOperation current(BudgetConsumptionReversalOperation claimed) {
        var command = claimed.input().command(); var value = locked(command.source().tenantId(), command.id());
        return value.equals(claimed) && value.running() ? value : null;
    }
    private BudgetConsumptionReversalOperation locked(String tenant, UUID id) {
        var initial = operations.find(tenant, id).orElseThrow(ExpenseResourceAdjustmentBudgetExecution::conflict);
        reports.lock(tenant, initial.input().command().source().position().reportId()); adjustment(tenant, id);
        return operations.find(tenant, id).orElseThrow(ExpenseResourceAdjustmentBudgetExecution::conflict);
    }
    private ExpenseResourceAdjustment adjustment(String tenant, UUID id) {
        var current = adjustments.find(tenant, id).orElseThrow(ExpenseResourceAdjustmentBudgetExecution::conflict);
        if (current.status() == ExpenseResourceAdjustment.Status.RETIRED) throw conflict(); return current;
    }
    private static void requireVersion(BudgetConsumptionReversalOperation value, long expected) { if (value.version() != expected) throw conflict(); }
    private static Instant time(Instant at) { return at.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Expense budget reversal or its original adjustment changed"); }
}
