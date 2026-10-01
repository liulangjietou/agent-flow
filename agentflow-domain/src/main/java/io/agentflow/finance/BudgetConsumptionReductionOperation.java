package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 已消费预算差额的持久执行状态，未知结果和过期租约始终查询原命令。
 * @author owlzhangfq@gmail.com
 */
public record BudgetConsumptionReductionOperation(Input input, long version, Status status, int attempts, Instant createdAt,
        Instant updatedAt, Instant nextAttemptAt, Instant leaseUntil, BudgetConsumptionReductionObservation observation,
        BudgetConsumptionReductionObservation conflictingObservation, Failure failure) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;
    public static final Duration DISPUTE_EVIDENCE_LIFETIME = Duration.ofMinutes(5);

    /** 快照中的接收证据与状态必须一致，不能把已发送操作恢复成未经发送。 */
    public BudgetConsumptionReductionOperation {
        if (input == null || version < 1 || status == null || attempts < 0 || createdAt == null || updatedAt == null
                || createdAt.isBefore(input.command().createdAt()) || updatedAt.isBefore(createdAt)) throw invalid();
        boolean running = status == Status.EXECUTING || status == Status.QUERYING;
        boolean scheduled = status == Status.QUEUED || status == Status.UNKNOWN;
        if (running ? attempts < 1 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || failure != null : leaseUntil != null) throw invalid();
        if (scheduled ? nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt) : nextAttemptAt != null) throw invalid();
        if (attempts == 0 && (observation != null || conflictingObservation != null)) throw invalid();
        if (observation != null && !observation.matches(input.command(), true, updatedAt)
                || conflictingObservation != null && !conflictingObservation.matches(input.command(), true, updatedAt)) throw invalid();
        if ((status == Status.QUEUED || status == Status.EXECUTING) && (conflictingObservation != null || failure != null
                || observation != null && observation.status() != BudgetConsumptionReductionObservation.Status.NOT_FOUND
                || attempts == 0 && observation != null)) throw invalid();
        if (status == Status.QUEUED && attempts > 0 && observation == null || status == Status.EXECUTING && attempts > 1 && observation == null) throw invalid();
        if ((status == Status.APPLIED || status == Status.REJECTED || status == Status.NOT_FOUND)
                && (observation == null || !status.name().equals(observation.status().name()) || conflictingObservation != null || failure != null)) throw invalid();
        if (status == Status.UNKNOWN && failure == null && (observation == null || observation.status() != BudgetConsumptionReductionObservation.Status.PENDING)
                || status == Status.RECONCILING && (observation == null || conflictingObservation == null || failure == null)
                || status == Status.EXPIRED && failure != Failure.AUTHORIZATION_EXPIRED || status == Status.VOIDED && failure != Failure.SOURCE_CHANGED) throw invalid();
        if ((status == Status.EXPIRED || status == Status.VOIDED) && (conflictingObservation != null
                || observation != null && observation.status() != BudgetConsumptionReductionObservation.Status.NOT_FOUND)) throw invalid();
    }

    /** 明确授权后先入队，原事务提交前没有外部预算效果。 */
    public static BudgetConsumptionReductionOperation queue(Input input, Instant now) {
        input.command().requireSendAt(now);
        return new BudgetConsumptionReductionOperation(input, 1, Status.QUEUED, 0, now, now, now, null, null, null, null);
    }

    /** 授权过期阻止新发送，已产生未知结果时仍可领取只读查询。 */
    public BudgetConsumptionReductionOperation claim(Instant now, Duration lease) {
        requireTime(now);
        if ((status != Status.QUEUED && status != Status.UNKNOWN) || now.isBefore(nextAttemptAt) || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        if (status == Status.QUEUED && !now.isBefore(input.command().expiresAt())) return changed(Status.EXPIRED, now, null, observation, null, Failure.AUTHORIZATION_EXPIRED);
        return new BudgetConsumptionReductionOperation(input, Math.incrementExact(version), status == Status.QUEUED ? Status.EXECUTING : Status.QUERYING,
                Math.incrementExact(attempts), createdAt, now, null, now.plus(lease), observation, conflictingObservation, null);
    }

    /** 崩溃不能证明未执行，迟到结果交给原命令查询恢复。 */
    public BudgetConsumptionReductionOperation expire(Instant now) {
        if (!expired(now)) throw conflict();
        return changed(Status.UNKNOWN, now, now, observation, conflictingObservation, Failure.LEASE_EXPIRED);
    }

    /** 原消费回执和新的差额回执分别保留；待处理以后查无或回执矛盾进入人工核对。 */
    public BudgetConsumptionReductionOperation complete(FinanceResult<BudgetConsumptionReductionObservation> result, Instant now) {
        requireTime(now); if (!running()) throw conflict(); if (expired(now)) return expire(now);
        if (!(result instanceof FinanceResult.Success<BudgetConsumptionReductionObservation> success)) {
            return unavailable(result instanceof FinanceResult.Unavailable<BudgetConsumptionReductionObservation> problem
                    ? Failure.valueOf(problem.failure().name()) : Failure.INVALID_RESPONSE, now);
        }
        var incoming = success.value();
        if (!incoming.matches(input.command(), status == Status.QUERYING, now)) return unavailable(Failure.INVALID_RESPONSE, now);
        if (conflictingObservation != null || observation != null && (incoming.observedAt().isBefore(observation.observedAt()) || !allowed(observation, incoming))) {
            return changed(Status.RECONCILING, now, null, observation, incoming, Failure.INCONSISTENT_OBSERVATION);
        }
        return changed(incoming.status() == BudgetConsumptionReductionObservation.Status.PENDING ? Status.UNKNOWN : Status.valueOf(incoming.status().name()),
                now, incoming.status() == BudgetConsumptionReductionObservation.Status.PENDING ? retryAt(now) : null, incoming, null, null);
    }

    /** 传输或本地异常保留未知状态，不解除资源保护。 */
    public BudgetConsumptionReductionOperation unavailable(Failure issue, Instant now) {
        requireTime(now); if (!running()) throw conflict(); if (expired(now)) return expire(now);
        return changed(Status.UNKNOWN, now, retryAt(now), observation, conflictingObservation, Objects.requireNonNull(issue));
    }

    /** 人工查询继续使用原命令，保留既有回执和争议。 */
    public BudgetConsumptionReductionOperation requestQuery(Instant now) {
        requireTime(now); if (running() || status == Status.QUEUED) throw conflict();
        return changed(Status.UNKNOWN, now, now, observation, conflictingObservation, Failure.RECHECK_REQUESTED);
    }

    /** 只有权威查无且未曾接收处理中事实时，才能明确重发尚未过期的原命令。 */
    public BudgetConsumptionReductionOperation retryNotFound(Instant now) {
        requireTime(now); if (status != Status.NOT_FOUND || conflictingObservation != null) throw conflict();
        input.command().requireSendAt(now); return changed(Status.QUEUED, now, now, observation, null, null);
    }

    /** 原业务资格变化只中止尚待发送的命令，已发出的未知结果继续对账。 */
    public BudgetConsumptionReductionOperation voidBeforeSend(Instant now) {
        requireTime(now); if (status != Status.QUEUED) throw conflict();
        return changed(Status.VOIDED, now, null, observation, null, Failure.SOURCE_CHANGED);
    }
    public boolean running() { return status == Status.EXECUTING || status == Status.QUERYING; }
    public boolean expired(Instant now) { return running() && !leaseUntil.isAfter(now); }
    /** 只有从未发送或明确无副作用的拒绝可以结束占用；未知、查无和矛盾继续保留。 */
    public boolean safelyUnexecuted() {
        if (conflictingObservation != null) return false;
        return attempts == 0 && observation == null && (status == Status.QUEUED || status == Status.EXPIRED || status == Status.VOIDED)
                || status == Status.REJECTED;
    }

    /** 只允许采用近期原号终态，任何历史成功或已冲回提示都不能改判成无效果。 */
    public ResolutionIssue resolutionIssue(ResolutionHistory history, Instant now) {
        var candidate = conflictingObservation;
        if (status != Status.RECONCILING || candidate == null) return ResolutionIssue.NOT_DISPUTED;
        if (candidate.status() != BudgetConsumptionReductionObservation.Status.APPLIED
                && candidate.status() != BudgetConsumptionReductionObservation.Status.REJECTED) return ResolutionIssue.NON_TERMINAL;
        if (history == null || history.firstApplied() != null && !history.firstApplied().matches(input.command(), true, now)) return ResolutionIssue.HISTORY_CHANGED;
        if (observation != null && candidate.observedAt().isBefore(observation.observedAt())
                || history.latestObservedAt() != null && candidate.observedAt().isBefore(history.latestObservedAt())) return ResolutionIssue.STALE_EVIDENCE;
        if (now.isBefore(updatedAt) || !now.isBefore(candidate.observedAt().plus(DISPUTE_EVIDENCE_LIFETIME))) return ResolutionIssue.EXPIRED_EVIDENCE;
        if (candidate.status() == BudgetConsumptionReductionObservation.Status.REJECTED
                && (history.appliedObserved() || applicationRisk(observation) || applicationRisk(candidate))) return ResolutionIssue.EFFECT_ALREADY_OBSERVED;
        var original = history.firstApplied() != null ? history.firstApplied()
                : observation != null && observation.status() == BudgetConsumptionReductionObservation.Status.APPLIED ? observation : null;
        if (original != null && !original.posting().equals(candidate.posting())) return ResolutionIssue.DIFFERENT_POSTING;
        return null;
    }

    /** 显式采用已保存候选，原命令、发送次数和期限保持；普通执行回放不接受这种转换。 */
    public BudgetConsumptionReductionOperation resolveDispute(BudgetConsumptionReductionObservation.Status outcome, ResolutionHistory history, Instant now) {
        requireTime(now);
        if (resolutionIssue(history, now) != null || conflictingObservation.status() != outcome)
            throw new DomainException("BUDGET_REDUCTION_DISPUTE_UNRESOLVABLE", "Budget reduction resolution requires recent terminal evidence preserving all historical effects");
        return changed(Status.valueOf(outcome.name()), now, null, conflictingObservation, null, null);
    }

    /** 已生效和原消费已冲回均不能证明本次账本无变化。 */
    public static boolean applicationRisk(BudgetConsumptionReductionObservation value) {
        return value != null && (value.status() == BudgetConsumptionReductionObservation.Status.APPLIED
                || value.rejection() == BudgetConsumptionReductionObservation.Rejection.CONSUMPTION_ALREADY_REVERSED);
    }

    /**
     * 仓储从原操作连续修订提取历史；冲突候选中的成功同样保留风险。
     * @author owlzhangfq@gmail.com
     */
    public record ResolutionHistory(BudgetConsumptionReductionObservation firstApplied, boolean appliedObserved, Instant latestObservedAt) {
        /** 首次接受事实和最新观察时间必须相容，不能同时声明未出现过效果。 */
        public ResolutionHistory {
            if (firstApplied != null && (firstApplied.status() != BudgetConsumptionReductionObservation.Status.APPLIED || !appliedObserved
                    || latestObservedAt == null || latestObservedAt.isBefore(firstApplied.observedAt()))) throw invalid();
        }
    }
    /**
     * 能力投影使用同一业务边界，不能通过客户端备注豁免。
     * @author owlzhangfq@gmail.com
     */
    public enum ResolutionIssue { NOT_DISPUTED, NON_TERMINAL, HISTORY_CHANGED, STALE_EVIDENCE, EXPIRED_EVIDENCE, EFFECT_ALREADY_OBSERVED, DIFFERENT_POSTING }

    /** 所属调整保存状态前回放一次领域转换，禁止快照跳过发送或替换已接受事实。 */
    public boolean acceptsSuccessor(BudgetConsumptionReductionOperation next) {
        if (next == null || version == Long.MAX_VALUE || next.version() != version + 1 || !input.equals(next.input())) return false;
        try {
            var at = next.updatedAt();
            var expected = switch (next.status()) {
                case EXECUTING, QUERYING -> claim(at, Duration.between(at, next.leaseUntil()));
                case EXPIRED -> claim(at, Duration.ofSeconds(1));
                case VOIDED -> voidBeforeSend(at);
                case QUEUED -> retryNotFound(at);
                case UNKNOWN -> {
                    if (!running()) yield requestQuery(at);
                    if (next.failure() == Failure.LEASE_EXPIRED) yield expire(at);
                    if (next.failure() != null) yield unavailable(next.failure(), at);
                    yield complete(new FinanceResult.Success<>(next.observation()), at);
                }
                case APPLIED, REJECTED, NOT_FOUND, RECONCILING -> complete(new FinanceResult.Success<>(
                        next.conflictingObservation() == null ? next.observation() : next.conflictingObservation()), at);
            };
            return expected.equals(next);
        } catch (DomainException invalidTransition) { return false; }
    }
    private BudgetConsumptionReductionOperation changed(Status next, Instant at, Instant nextAt, BudgetConsumptionReductionObservation accepted,
            BudgetConsumptionReductionObservation disputed, Failure issue) {
        requireTime(at);
        return new BudgetConsumptionReductionOperation(input, Math.incrementExact(version), next, attempts, createdAt, at, nextAt, null, accepted, disputed, issue);
    }
    private static boolean allowed(BudgetConsumptionReductionObservation previous, BudgetConsumptionReductionObservation next) {
        return switch (previous.status()) {
            case NOT_FOUND -> true;
            case PENDING -> next.status() != BudgetConsumptionReductionObservation.Status.NOT_FOUND;
            case REJECTED -> next.status() == previous.status() && next.rejection() == previous.rejection();
            case APPLIED -> next.status() == previous.status() && next.posting().equals(previous.posting());
        };
    }
    private Instant retryAt(Instant now) { return now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6))); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_BUDGET_REDUCTION_OPERATION", "Budget reduction state must preserve its immutable command and accepted evidence"); }
    private static DomainException conflict() { return new DomainException("BUDGET_REDUCTION_OPERATION_CONFLICT", "Budget reduction cannot perform this transition"); }
    /**
     * 原消费修订由仓储外键固定，所有恢复都保留原财务目的地。
     * @author owlzhangfq@gmail.com
     */
    public record Input(long consumedVersion, BudgetConsumptionReductionCommand command, String targetDigest) {
        /** 持久事实必须先于实际外发形成。 */
        public Input { if (consumedVersion < 1 || command == null || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}")) throw invalid(); }
    }
    /**
     * 查无等待明确重发，矛盾保留人工核对，完成不代表原报销资源已自动释放。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, EXECUTING, QUERYING, UNKNOWN, APPLIED, REJECTED, NOT_FOUND, EXPIRED, VOIDED, RECONCILING }
    /**
     * 只保存稳定错误分类，不保存外部错误正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, LEASE_EXPIRED, INTERNAL_ERROR, AUTHORIZATION_EXPIRED, SOURCE_CHANGED, INCONSISTENT_OBSERVATION, RECHECK_REQUESTED }
}
