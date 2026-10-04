package io.agentflow.budget;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预算财务读取与明确授权分别办理，外部台账读取不占用数据库锁，也不自动消费证据。
 * @author owlzhangfq@gmail.com
 */
@Service
public class BudgetAdjustmentReviewService {
    private final ApprovedBudgetAdjustmentSources sources;
    private final JdbcBudgetAdjustmentReviewRepository reviews;
    private final JdbcBudgetAdjustmentOperationRepository operations;
    private final BudgetAdjustmentExecutionService execution;
    private final Duration lease;
    private final ApplicationEventPublisher events;

    /** 原批准、具名财务和读取原件共同组成财务确认依据。 */
    public BudgetAdjustmentReviewService(ApprovedBudgetAdjustmentSources sources, JdbcBudgetAdjustmentReviewRepository reviews,
            JdbcBudgetAdjustmentOperationRepository operations, BudgetAdjustmentExecutionService execution, ApplicationEventPublisher events,
            @Value("${agentflow.budget-adjustments.review-lease-seconds:90}") int leaseSeconds) {
        if (leaseSeconds < 15 || leaseSeconds > 300) throw new IllegalArgumentException("Budget review lease must be between 15 and 300 seconds");
        this.sources = sources; this.reviews = reviews; this.operations = operations; this.execution = execution; this.events = events; this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** 已获财务角色和原轮次字段权限的入口只能登记读取，不接受自报台账。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public BudgetAdjustmentReview register(ApprovedBudgetAdjustment source, String finance, Instant now) {
        sources.lock(source); requireSource(source, finance);
        var input = new BudgetAdjustmentReview.Input(UUID.randomUUID(), source, finance, reviews.latestAttempt(source.tenantId(), source.requestId(), finance) + 1, time(now));
        var queued = BudgetAdjustmentReview.queue(input); reviews.create(queued); return queued;
    }

    /** 单次读取超时保留失败，明确新读取才产生新的尝试号。 */
    @Transactional
    public BudgetAdjustmentReview claim(String tenant, UUID id, Instant now) {
        var current = locked(tenant, id); if (current == null || !current.active()) return null; now = time(now);
        if (current.status() == BudgetAdjustmentReview.Status.RUNNING) {
            if (!now.isBefore(current.leaseUntil())) persist(current.fail(BudgetAdjustmentReview.Issue.TIMEOUT, now));
            return null;
        }
        if (!available(current, now)) return null;
        var claimed = current.claim(now, lease); persist(claimed); return claimed;
    }

    /** 结果保存前复核原批准和财务资格，迟到响应不能生成可授权证据。 */
    @Transactional
    public void finish(BudgetAdjustmentReview claimed, FinanceResult<BudgetLedgerPort.Snapshot> result, Instant now) {
        var current = currentClaim(claimed); if (current == null) return; now = time(now);
        if (!now.isBefore(current.leaseUntil())) { persist(current.fail(BudgetAdjustmentReview.Issue.TIMEOUT, now)); return; }
        if (available(current, now)) persist(current.complete(result, now));
    }

    /** 执行器异常只写稳定分类，不存储外部响应或手工填补额度。 */
    @Transactional
    public void fail(BudgetAdjustmentReview claimed, Instant now) {
        var current = currentClaim(claimed); if (current == null) return; now = time(now);
        persist(current.fail(BudgetAdjustmentReview.Issue.INTERNAL_ERROR, now));
    }

    /** 同一财务在最新证据窗口内具名确认，消费和原子指令排队一起落库。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public BudgetAdjustmentOperation authorize(String tenant, UUID reviewId, long version, String finance, String reason, Instant now) {
        var current = locked(tenant, reviewId); now = time(now);
        if (current == null || current.version() != version || !current.input().requestedBy().equals(finance) || !current.usable(now)) throw unavailable();
        requireSource(current.input().source(), finance);
        var command = BudgetAdjustmentCommand.authorize(UUID.randomUUID(), current.input().source(), current.ledger(), finance, reason, now);
        var queued = BudgetAdjustmentOperation.queue(command, now); operations.create(queued, reviewId); return queued;
    }

    private void persist(BudgetAdjustmentReview value) {
        reviews.update(value); events.publishEvent(new BudgetAdjustmentReviewChanged(value));
    }
    private BudgetAdjustmentReview locked(String tenant, UUID id) {
        var current = reviews.find(tenant, id).orElse(null); if (current == null) return null;
        sources.lock(current.input().source()); return reviews.find(tenant, id).orElseThrow(BudgetAdjustmentReviewService::unavailable);
    }
    private BudgetAdjustmentReview currentClaim(BudgetAdjustmentReview claimed) {
        var current = locked(claimed.input().source().tenantId(), claimed.input().id());
        return current != null && current.status() == BudgetAdjustmentReview.Status.RUNNING && current.equals(claimed) ? current : null;
    }
    private boolean available(BudgetAdjustmentReview current, Instant now) {
        try { requireSource(current.input().source(), current.input().requestedBy()); return true; }
        catch (DomainException changed) { persist(current.voidSource(now)); return false; }
    }
    private void requireSource(ApprovedBudgetAdjustment source, String finance) {
        execution.requireNewExecution(source, finance);
        if (operations.activeForRequest(source.tenantId(), source.requestId()).isPresent()) {
            throw new DomainException("BUDGET_ADJUSTMENT_ALREADY_AUTHORIZED", "Original budget operation must be safely ended before a new review");
        }
    }
    private static Instant time(Instant value) { return value.truncatedTo(ChronoUnit.MICROS); }
    private static DomainException unavailable() { return new DomainException("BUDGET_ADJUSTMENT_REVIEW_UNAVAILABLE", "Fresh original budget ledger review for the current finance actor is required"); }
}
