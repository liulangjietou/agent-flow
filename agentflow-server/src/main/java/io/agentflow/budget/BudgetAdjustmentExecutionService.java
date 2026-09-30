package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceGatewayConfiguration;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.PaymentPersonnel;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原子预算指令的短事务编排，首次发送复核当前资格，未知结果始终保留原号查询。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetAdjustmentExecutionService {
    private final ApprovedBudgetAdjustmentSources sources;
    private final JdbcBudgetAdjustmentOperationRepository operations;
    private final PaymentPersonnel personnel;
    private final FinanceGatewayConfiguration configuration;
    private final Duration lease;

    /** 租约覆盖一次有界网关调用，数据库事务中不执行任何 HTTP。 */
    public BudgetAdjustmentExecutionService(ApprovedBudgetAdjustmentSources sources, JdbcBudgetAdjustmentOperationRepository operations,
            PaymentPersonnel personnel, FinanceGatewayConfiguration configuration,
            @Value("${agentflow.budget-adjustments.execution-lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Budget execution lease must be between 15 and 300 seconds");
        this.sources = sources; this.operations = operations; this.personnel = personnel; this.configuration = configuration; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 只读守卫沿用调用方事务，拒绝后由调用方决定保存停止状态或整体回滚。 */
    public void requireNewExecution(ApprovedBudgetAdjustment source, String finance) {
        sources.requireCurrent(source);
        personnel.requireEligible(source.tenantId(), finance, source.round().content().legalEntityId());
        if (!configuration.destination(source.tenantId()).map(target -> target.digest(source.tenantId()).equals(source.round().targetDigest())).orElse(false)) {
            throw new DomainException("BUDGET_ADJUSTMENT_DESTINATION_UNAVAILABLE", "The original approved budget destination is no longer available");
        }
    }

    /** 从未发送的任务受当前资格约束；过期运行只转原号查询，禁止再次当成首次发送。 */
    @Transactional
    public BudgetAdjustmentOperation claim(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current == null || operations.retirement(tenant, id).isPresent()) return null; now = time(now);
        if (current.expired(now)) { operations.update(current.expire(now)); return null; }
        if (current.running() || current.nextAttemptAt() == null || now.isBefore(current.nextAttemptAt())) return null;
        if (current.status() == BudgetAdjustmentOperation.Status.QUEUED) {
            try { requireNewExecution(current.command().source(), current.command().authorizedBy()); }
            catch (DomainException changed) { operations.update(current.voidBeforeSend(now)); return null; }
        }
        var claimed = current.claim(now, lease); operations.update(claimed); return claimed.running() ? claimed : null;
    }

    /** 只接收仍持有原领取版本的响应，迟到进程不覆盖已恢复的新版本。 */
    @Transactional
    public void finish(BudgetAdjustmentOperation claimed, FinanceResult<BudgetAdjustmentObservation> result, Instant now) {
        var current = currentClaim(claimed); if (current != null) operations.update(current.complete(result, time(now)));
    }

    /** 领取后发生本地异常只保留未知，不把异常当作无副作用拒绝。 */
    @Transactional
    public void fail(BudgetAdjustmentOperation claimed, Instant now) {
        var current = currentClaim(claimed); if (current != null) operations.update(current.unavailable(BudgetAdjustmentOperation.Failure.INTERNAL_ERROR, time(now)));
    }

    /** 已通过权限入口的财务明确请求复核原操作，不生成新命令。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public BudgetAdjustmentOperation query(String tenant, UUID id, long version, Instant now) {
        var current = currentVersion(tenant, id, version); var next = current.requestQuery(time(now)); operations.update(next); return next;
    }

    /** 查无重发只接受原授权人，仍须在原期限内且原批准、任职和目标有效。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public BudgetAdjustmentOperation resend(String tenant, UUID id, long version, String finance, Instant now) {
        var current = currentVersion(tenant, id, version);
        if (!finance.equals(current.command().authorizedBy())) throw new DomainException("FORBIDDEN", "Only the original finance authorizer may resend this budget command");
        requireNewExecution(current.command().source(), finance);
        var next = current.retryNotFound(time(now)); operations.update(next); return next;
    }

    /** 安全结束不依赖旧目的地仍可用，但必须由当前法人内独立财务作出具名决定。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public BudgetAdjustmentRetirement retire(String tenant, UUID id, long version, String finance, Instant now) {
        var current = currentVersion(tenant, id, version); now = time(now);
        personnel.requireEligible(tenant, finance, current.command().source().round().content().legalEntityId());
        if (!current.safelyUnexecuted()) throw new DomainException("BUDGET_ADJUSTMENT_RETIREMENT_UNSAFE", "Original budget operation must prove no external adjustment before replacement");
        if (current.status() == BudgetAdjustmentOperation.Status.QUEUED) { current = current.voidBeforeSend(now); operations.update(current); }
        var decision = BudgetAdjustmentRetirement.from(current, finance, now); operations.retire(tenant, decision); return decision;
    }

    private BudgetAdjustmentOperation locked(String tenant, UUID id) {
        var current = operations.find(tenant, id).orElse(null); if (current == null) return null;
        sources.lock(current.command().source()); return operations.find(tenant, id).orElseThrow(BudgetAdjustmentExecutionService::conflict);
    }
    private BudgetAdjustmentOperation currentClaim(BudgetAdjustmentOperation claimed) {
        var current = locked(claimed.command().tenantId(), claimed.command().id());
        return current != null && current.running() && current.equals(claimed) ? current : null;
    }
    private BudgetAdjustmentOperation currentVersion(String tenant, UUID id, long version) {
        var current = locked(tenant, id);
        if (current == null || current.version() != version || operations.retirement(tenant, id).isPresent()) throw conflict(); return current;
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException conflict() { return new DomainException("CONCURRENCY_CONFLICT", "Original budget operation or retirement version changed"); }
}
