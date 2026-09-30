package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 两侧分别领取和保存实际结果，原报销锁协调并发，成功一侧不等待另一侧授权或本地资源完成。
 * @author owlzhangfq@gmail.com
 */
@Service
public class ExpensePartialAdjustmentFinance {
    private static final Duration LEASE = Duration.ofSeconds(90);
    private final ExpenseReportRepository reports;
    private final JdbcExpensePartialAdjustmentRepository adjustments;
    private final PaymentPersonnel personnel;
    private final FinanceGatewayConfiguration gateway;

    /** 网络等待在工作器中，事务服务只读取当前人员、固定目的地及持久财务事实。 */
    public ExpensePartialAdjustmentFinance(ExpenseReportRepository reports, JdbcExpensePartialAdjustmentRepository adjustments,
            PaymentPersonnel personnel, FinanceGatewayConfiguration gateway) {
        this.reports = reports; this.adjustments = adjustments; this.personnel = personnel; this.gateway = gateway;
    }

    /** 只有新写入检查当前资格与原来源，未知结果和过期租约沿用原编号查询。 */
    @Transactional
    public BudgetConsumptionReductionOperation claimBudget(String tenant, UUID id, Instant at) {
        var current = locked(tenant, id); if (current == null || current.budget() == null) return null;
        var operation = current.budget(); var now = time(at);
        if (operation.expired(now)) { adjustments.update(current.withBudget(operation.expire(now), now)); return null; }
        if (operation.running() || operation.nextAttemptAt() == null || operation.nextAttemptAt().isAfter(now)) return null;
        if (operation.status() == BudgetConsumptionReductionOperation.Status.QUEUED && now.isBefore(operation.input().command().expiresAt())) {
            try { requireSendSources(current, operation.input().command().authorizedBy(), operation.input().targetDigest(), now); }
            catch (DomainException changed) { adjustments.update(current.withBudget(operation.voidBeforeSend(now), now)); return null; }
        }
        var claimed = operation.claim(now, LEASE); adjustments.update(current.withBudget(claimed, now));
        return claimed.running() ? claimed : null;
    }

    /** ERP 的授权、到期与租约独立于预算，另一侧成功不能重新生成本侧命令。 */
    @Transactional
    public ExpenseAccrualReductionOperation claimAccrual(String tenant, UUID id, Instant at) {
        var current = locked(tenant, id); if (current == null || current.accrual() == null) return null;
        var operation = current.accrual(); var now = time(at);
        if (operation.expired(now)) { adjustments.update(current.withAccrual(operation.expire(now), now)); return null; }
        if (operation.running() || operation.nextAttemptAt() == null || operation.nextAttemptAt().isAfter(now)) return null;
        if (operation.status() == ExpenseAccrualReductionOperation.Status.QUEUED && now.isBefore(operation.input().command().expiresAt())) {
            try { requireSendSources(current, operation.input().command().authorizedBy(), operation.input().targetDigest(), now); }
            catch (DomainException changed) { adjustments.update(current.withAccrual(operation.voidBeforeSend(now), now)); return null; }
        }
        var claimed = operation.claim(now, LEASE); adjustments.update(current.withAccrual(claimed, now));
        return claimed.running() ? claimed : null;
    }

    /** 只比较本侧领取修订，允许另一侧在 HTTP 等待期间独立推进根聚合版本。 */
    @Transactional
    public void finishBudget(BudgetConsumptionReductionOperation claimed, FinanceResult<BudgetConsumptionReductionObservation> result, Instant at) {
        var current = budgetClaim(claimed); if (current == null) return; var now = time(at);
        adjustments.update(current.withBudget(claimed.complete(result, now), now));
    }

    /** ERP 成功先独立提交，后续资源失败不得回滚它或重新外发。 */
    @Transactional
    public void finishAccrual(ExpenseAccrualReductionOperation claimed, FinanceResult<ExpenseAccrualReductionObservation> result, Instant at) {
        var current = accrualClaim(claimed); if (current == null) return; var now = time(at);
        adjustments.update(current.withAccrual(claimed.complete(result, now), now));
    }

    /** 可能已经写入的传输或保存异常只进入原号查询，迟到失败不覆盖已接受结果。 */
    @Transactional
    public void failBudget(BudgetConsumptionReductionOperation claimed, Instant at) {
        var current = budgetClaim(claimed); if (current == null) return; var now = time(at);
        adjustments.update(current.withBudget(claimed.unavailable(BudgetConsumptionReductionOperation.Failure.INTERNAL_ERROR, now), now));
    }

    /** ERP 异常保留原指令、最高回执和另一侧状态。 */
    @Transactional
    public void failAccrual(ExpenseAccrualReductionOperation claimed, Instant at) {
        var current = accrualClaim(claimed); if (current == null) return; var now = time(at);
        adjustments.update(current.withAccrual(claimed.unavailable(ExpenseAccrualReductionOperation.Failure.INTERNAL_ERROR, now), now));
    }

    /** 明确查询仅面向可能发送的命令；完成后的复核保留原资源完成并冻结后续新调整。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpensePartialAdjustment queryBudget(String tenant, UUID id, long expected, Instant at) {
        var current = version(tenant, id, expected); if (current.budget() == null || current.budget().attempts() == 0) throw conflict();
        var now = time(at); var next = current.withBudget(current.budget().requestQuery(now), now); adjustments.update(next); return next;
    }

    /** 原挂账差额查询不需要延长过期授权，也不改写原会计期间。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpensePartialAdjustment queryAccrual(String tenant, UUID id, long expected, Instant at) {
        var current = version(tenant, id, expected); if (current.accrual() == null || current.accrual().attempts() == 0) throw conflict();
        var now = time(at); var next = current.withAccrual(current.accrual().requestQuery(now), now); adjustments.update(next); return next;
    }

    /** 权威查无须由原授权人明确重发，仍保留原编号、期间和有效期。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpensePartialAdjustment resendBudget(String tenant, UUID id, long expected, String actor, Instant at) {
        var current = version(tenant, id, expected); var operation = current.budget(); var now = time(at);
        if (operation == null || !operation.input().command().authorizedBy().equals(actor)) throw conflict();
        requireSendSources(current, actor, operation.input().targetDigest(), now);
        var next = current.withBudget(operation.retryNotFound(now), now); adjustments.update(next); return next;
    }

    /** ERP 未受理才允许原授权人重发，成功或矛盾回执始终不能重新排队写入。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public ExpensePartialAdjustment resendAccrual(String tenant, UUID id, long expected, String actor, Instant at) {
        var current = version(tenant, id, expected); var operation = current.accrual(); var now = time(at);
        if (operation == null || !operation.input().command().authorizedBy().equals(actor)) throw conflict();
        requireSendSources(current, actor, operation.input().targetDigest(), now);
        var next = current.withAccrual(operation.retryNotFound(now), now); adjustments.update(next); return next;
    }

    /** 包内动作预览复用发送前的当前来源、人员与目的地判断，不领取或保存操作。 */
    void requireSendSources(ExpensePartialAdjustment current, String actor, String target, Instant now) {
        var basis = current.input().basis(); var source = adjustments.dispatchSource(current);
        source.requireAuthorization(actor, now);
        personnel.requireEligible(basis.tenantId(), actor, source.financial().accrual().input().command().legalEntityId());
        if (gateway.destination(basis.tenantId()).filter(value -> value.digest(basis.tenantId()).equals(target)).isEmpty()) {
            throw new DomainException("FINANCE_TARGET_CHANGED", "Original partial adjustment destination is unavailable before sending");
        }
    }
    private ExpensePartialAdjustment budgetClaim(BudgetConsumptionReductionOperation claimed) {
        var command = claimed.input().command(); var current = locked(command.source().tenantId(), command.adjustmentId());
        return current != null && claimed.running() && claimed.equals(current.budget()) ? current : null;
    }
    private ExpensePartialAdjustment accrualClaim(ExpenseAccrualReductionOperation claimed) {
        var command = claimed.input().command(); var current = locked(command.source().command().tenantId(), command.adjustmentId());
        return current != null && claimed.running() && claimed.equals(current.accrual()) ? current : null;
    }
    private ExpensePartialAdjustment locked(String tenant, UUID id) {
        var initial = adjustments.find(tenant, id).orElse(null); if (initial == null) return null;
        reports.lock(tenant, initial.input().basis().reportId());
        return adjustments.find(tenant, id).filter(value -> value.retirement() == null).orElse(null);
    }
    private ExpensePartialAdjustment version(String tenant, UUID id, long expected) {
        var current = locked(tenant, id); if (current == null || current.version() != expected) throw conflict(); return current;
    }
    private static Instant time(Instant at) {
        // 请求可能先等待另一侧释放原报销锁，领取和到期判断必须使用取得锁之后的时间。
        var now = Instant.now(); return (at.isAfter(now) ? at : now).truncatedTo(ChronoUnit.MICROS);
    }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Partial adjustment or its original authorized operation changed"); }
}
