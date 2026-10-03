package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次付款登记的持久执行状态；账户读取与可能发送分开，未知结果永远追踪原命令。
 * @author owlzhangfq@gmail.com
 */
public record PaymentOperation(Input input, long version, Status status, int attempts, int dispatches,
                               Instant createdAt, Instant updatedAt, Instant nextAttemptAt, Instant leaseUntil,
                               AccountEvidence accountEvidence, PaymentObservation observation,
                               PaymentObservation conflictingObservation, long highestRevision, Failure failure) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;
    public static final Duration DISPUTE_EVIDENCE_LIFETIME = Duration.ofMinutes(5);

    /** 恢复时不能把可能发送的记录改回未经查询的首次执行，也不能把账户复查租约当作发送事实。 */
    public PaymentOperation {
        if (input == null || status == null || version < 1 || attempts < 0 || dispatches < 0 || dispatches > attempts
                || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)
                || createdAt.isBefore(input.command().authorization().authorizedAt()) || highestRevision < 0) throw invalid();
        boolean running = status == Status.CHECKING || status == Status.SENDING || status == Status.QUERYING;
        boolean scheduled = status == Status.QUEUED || status == Status.UNKNOWN;
        if (running ? attempts == 0 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || failure != null : leaseUntil != null) throw invalid();
        if (scheduled ? nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt) : nextAttemptAt != null) throw invalid();
        if (observation != null && (!observation.matches(input.command(), true, updatedAt) || observation.revision() > highestRevision)
                || conflictingObservation != null && (!conflictingObservation.matches(input.command(), true, updatedAt) || conflictingObservation.revision() > highestRevision)
                || observation == null && highestRevision != 0 && conflictingObservation == null) throw invalid();
        if ((status == Status.QUEUED || status == Status.CHECKING || status == Status.SENDING)
                && (highestRevision != 0 || conflictingObservation != null || observation != null && observation.status() != PaymentObservation.Status.NOT_FOUND)) throw invalid();
        if ((status == Status.QUEUED || status == Status.CHECKING) && (accountEvidence != null || dispatches > 0 && observation == null)) throw invalid();
        if (status == Status.SENDING && (dispatches < 1 || accountEvidence == null || !accountEvidence.matches(input, updatedAt)
                || dispatches > 1 && observation == null)) throw invalid();
        if (status == Status.SENDING) input.command().requireSendAt(updatedAt);
        if ((status == Status.QUERYING || status == Status.UNKNOWN || status == Status.RECONCILING) && dispatches == 0) throw invalid();
        if (status == Status.SUCCEEDED || status == Status.FAILED || status == Status.REVERSED || status == Status.NOT_FOUND) {
            if (dispatches == 0 || observation == null || !observation.status().name().equals(status.name()) || conflictingObservation != null
                    || failure != null || highestRevision != observation.revision()) throw invalid();
        }
        if (status == Status.UNKNOWN && failure == null && (observation == null || observation.status() != PaymentObservation.Status.PENDING)
                || status == Status.RECONCILING && (conflictingObservation == null || failure == null)
                || status == Status.EXPIRED && (failure != Failure.AUTHORIZATION_EXPIRED || highestRevision != 0 || conflictingObservation != null)
                || status == Status.VOIDED && (failure != Failure.SOURCE_CHANGED && failure != Failure.ACCOUNT_CHANGED && failure != Failure.FINANCE_RETIRED || highestRevision != 0 || conflictingObservation != null)
                || failure == Failure.FINANCE_RETIRED && (status != Status.VOIDED || dispatches != 0 || observation != null)) throw invalid();
    }

    /** 只允许由已保存的单次出纳执行登记建队列，尚未调用资金系统。 */
    public static PaymentOperation queue(PaymentAuthorization authorization, Instant now) {
        if (authorization.status() != PaymentAuthorization.Status.EXECUTION_REGISTERED || now == null || now.isBefore(authorization.updatedAt())) throw conflict();
        var execution = authorization.execution(); execution.command().requireSendAt(now);
        return new PaymentOperation(new Input(execution.command(), authorization.terms().targetDigest(), execution.debitAccount()),
                1, Status.QUEUED, 0, 0, now, now, now, null, null, null, null, 0, null);
    }

    /** 排队先领取只读检查；可能已发送的恢复只领取原交易查询。 */
    public PaymentOperation claim(Instant now, Duration lease) {
        requireTime(now);
        if ((status != Status.QUEUED && status != Status.UNKNOWN) || now.isBefore(nextAttemptAt) || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        if (status == Status.QUEUED && !now.isBefore(input.command().authorization().expiresAt())) return changed(Status.EXPIRED, now, null, null, accountEvidence, observation, conflictingObservation, highestRevision, Failure.AUTHORIZATION_EXPIRED);
        return new PaymentOperation(input, Math.incrementExact(version), status == Status.QUEUED ? Status.CHECKING : Status.QUERYING,
                Math.incrementExact(attempts), dispatches, createdAt, now, null, now.plus(lease), accountEvidence, observation, conflictingObservation, highestRevision, null);
    }

    /** 账户复查完成后才持久标记可能发送；调用方须在此事务中复核原批准、凭证和实际执行权限。 */
    public PaymentOperation readyToSend(PaymentAccountsPort.Directory directory, EmployeeAccountPort.Account payee, Instant now) {
        requireTime(now); if (status != Status.CHECKING) throw conflict(); if (expired(now)) return expire(now);
        if (!now.isBefore(input.command().authorization().expiresAt())) return changed(Status.EXPIRED, now, null, null, null, observation, conflictingObservation, highestRevision, Failure.AUTHORIZATION_EXPIRED);
        input.command().requireSendAt(now);
        var evidence = AccountEvidence.checked(input, directory, payee, now);
        return new PaymentOperation(input, Math.incrementExact(version), Status.SENDING, attempts, Math.incrementExact(dispatches), createdAt, now,
                null, leaseUntil, evidence, observation, conflictingObservation, highestRevision, null);
    }

    /** 确认尚未进入发送阶段的暂时读取故障可以重读，保留原命令和出纳。 */
    public PaymentOperation unavailableBeforeSend(Failure problem, Instant now) {
        requireTime(now); if (status != Status.CHECKING) throw conflict(); if (expired(now)) return expire(now);
        return changed(Status.QUEUED, now, retryAt(now), null, null, observation, conflictingObservation, highestRevision, Objects.requireNonNull(problem));
    }

    /** 实际依据变化只能停止尚未发送的命令，不能用本方法取消可能已受理的交易。 */
    public PaymentOperation voidBeforeSend(Failure reason, Instant now) {
        requireTime(now);
        if (status != Status.QUEUED && status != Status.CHECKING || reason != Failure.SOURCE_CHANGED && reason != Failure.ACCOUNT_CHANGED) throw conflict();
        return changed(Status.VOIDED, now, null, null, null, observation, conflictingObservation, highestRevision, reason);
    }

    /** 查无和退回不证明可以另起交易；仅从未进入发送或无矛盾的银行终态失败可供财务结束。 */
    public RetirementBasis retirementBasis() {
        if (status == Status.FAILED) return RetirementBasis.CONFIRMED_FAILED;
        if (dispatches == 0 && highestRevision == 0 && observation == null && conflictingObservation == null
                && (status == Status.QUEUED || status == Status.CHECKING || status == Status.VOIDED || status == Status.EXPIRED)) {
            return RetirementBasis.NEVER_DISPATCHED;
        }
        return null;
    }

    /** 使未发送队列及正在复查账户的旧领取失效，已确认失败和已停止状态保留原外部事实。 */
    public PaymentOperation stopForRetirement(Instant now) {
        requireTime(now);
        if (retirementBasis() == null) throw new DomainException("PAYMENT_RETIREMENT_UNSAFE", "Original payment is not proven safely finished");
        return status == Status.QUEUED || status == Status.CHECKING
                ? changed(Status.VOIDED, now, null, null, null, null, null, 0, Failure.FINANCE_RETIRED) : this;
    }

    /**
     * 结束依据只来自本地发送记录或原资金交易的终态失败，不由客户端声明。
     * @author owlzhangfq@gmail.com
     */
    public enum RetirementBasis { NEVER_DISPATCHED, CONFIRMED_FAILED }

    /** 检查租约过期仍可检查；发送或查询租约过期后只查询原授权。 */
    public PaymentOperation expire(Instant now) {
        if (!expired(now)) throw conflict();
        return changed(status == Status.CHECKING ? Status.QUEUED : Status.UNKNOWN, now, now, null,
                status == Status.CHECKING ? null : accountEvidence, observation, conflictingObservation, highestRevision, Failure.LEASE_EXPIRED);
    }

    /** 资金事实按原命令、外部版本和单调状态核验，矛盾保留先前已确认的到账证据。 */
    public PaymentOperation complete(FinanceResult<PaymentObservation> result, Instant now) {
        requireTime(now); if (status != Status.SENDING && status != Status.QUERYING) throw conflict(); if (expired(now)) return expire(now);
        if (!(result instanceof FinanceResult.Success<PaymentObservation> success)) return unavailable(result instanceof FinanceResult.Unavailable<PaymentObservation> problem
                ? Failure.valueOf(problem.failure().name()) : Failure.INVALID_RESPONSE, now);
        var incoming = success.value();
        if (!incoming.matches(input.command(), status == Status.QUERYING, now)) return unavailable(Failure.INVALID_RESPONSE, now);
        long highest = Math.max(highestRevision, incoming.revision());
        if (incoming.revision() < highestRevision) return reconcile(incoming, highest, Failure.STALE_OBSERVATION, now);
        if (conflictingObservation != null || observation != null && (incoming.revision() == observation.revision()
                ? !sameFact(observation, incoming) : incoming.observedAt().isBefore(observation.observedAt()) || !allowed(observation, incoming))) {
            return reconcile(incoming, highest, Failure.INCONSISTENT_OBSERVATION, now);
        }
        var accepted = observation != null && sameFact(observation, incoming) && incoming.observedAt().isBefore(observation.observedAt()) ? observation : incoming;
        return changed(switch (incoming.status()) {
            case PENDING -> Status.UNKNOWN;
            case SUCCEEDED -> Status.SUCCEEDED;
            case FAILED -> Status.FAILED;
            case REVERSED -> Status.REVERSED;
            case NOT_FOUND -> Status.NOT_FOUND;
        }, now, incoming.status() == PaymentObservation.Status.PENDING ? retryAt(now) : null, null, accountEvidence, accepted, null, highest, null);
    }

    /** 网络或本地落库问题不能证明未付款，后续只能查询原交易。 */
    public PaymentOperation unavailable(Failure problem, Instant now) {
        requireTime(now); if (status != Status.SENDING && status != Status.QUERYING) throw conflict(); if (expired(now)) return expire(now);
        return changed(Status.UNKNOWN, now, retryAt(now), null, accountEvidence, observation, conflictingObservation, highestRevision, Objects.requireNonNull(problem));
    }

    /** 原交易可在授权到期或业务撤销后继续对账；查询不清除已经记录的矛盾。 */
    public PaymentOperation requestQuery(Instant now) {
        requireTime(now); if (running() || status == Status.QUEUED || dispatches == 0) throw conflict();
        return changed(Status.UNKNOWN, now, now, null, accountEvidence, observation, conflictingObservation, highestRevision, Failure.RECHECK_REQUESTED);
    }

    /** 已认证信号可停止查无后的重发检查，但不能打断正在发送或查询的原领取。 */
    public PaymentOperation requestCallbackQuery(Instant now) {
        requireTime(now); if (status == Status.SENDING || status == Status.QUERYING || dispatches == 0) throw conflict();
        return changed(Status.UNKNOWN, now, now, null, accountEvidence, observation, conflictingObservation, highestRevision, Failure.RECHECK_REQUESTED);
    }

    /** 人工裁决只采用刚查询到的原交易终态，不能倒退外部版本或改写已经确认的到账回单。 */
    public ResolutionIssue resolutionIssue(Instant now, PaymentObservation originalSuccess, boolean fundingObserved) {
        if (status != Status.RECONCILING) return ResolutionIssue.NOT_DISPUTED;
        var candidate = conflictingObservation;
        if (candidate.status() != PaymentObservation.Status.SUCCEEDED && candidate.status() != PaymentObservation.Status.FAILED
                && candidate.status() != PaymentObservation.Status.REVERSED) return ResolutionIssue.NON_TERMINAL;
        if (candidate.revision() != highestRevision || observation != null && candidate.observedAt().isBefore(observation.observedAt())) return ResolutionIssue.STALE_EVIDENCE;
        if (now.isBefore(updatedAt) || !now.isBefore(candidate.observedAt().plus(DISPUTE_EVIDENCE_LIFETIME))) return ResolutionIssue.EXPIRED_EVIDENCE;
        if (observation != null && observation.paymentReference() != null && !observation.paymentReference().equals(candidate.paymentReference())) return ResolutionIssue.DIFFERENT_PAYMENT;
        if (candidate.status() == PaymentObservation.Status.FAILED && (fundingObserved || funding(observation))) return ResolutionIssue.FUNDING_ALREADY_OBSERVED;
        var paid = originalSuccess != null ? originalSuccess : observation != null && observation.status() == PaymentObservation.Status.SUCCEEDED ? observation : null;
        if (paid != null && (candidate.status() == PaymentObservation.Status.SUCCEEDED && !sameSettlement(paid, candidate)
                || candidate.status() == PaymentObservation.Status.REVERSED && (!paid.paymentReference().equals(candidate.paymentReference())
                    || candidate.completedAt().isBefore(paid.completedAt())))) return ResolutionIssue.DIFFERENT_SETTLEMENT;
        return null;
    }

    /** 清除当前冲突需要单独保存裁决证据，原冲突修订由仓储永久保留；本动作不发送资金命令。 */
    public PaymentOperation resolveDispute(PaymentObservation.Status outcome, PaymentObservation originalSuccess, boolean fundingObserved, Instant now) {
        requireTime(now);
        if (resolutionIssue(now, originalSuccess, fundingObserved) != null || conflictingObservation.status() != outcome) {
            throw new DomainException("PAYMENT_DISPUTE_UNRESOLVABLE", "A recent terminal observation of the unchanged original payment is required");
        }
        return changed(Status.valueOf(outcome.name()), now, null, null, accountEvidence, conflictingObservation, null, highestRevision, null);
    }

    private static boolean funding(PaymentObservation value) {
        return value != null && (value.status() == PaymentObservation.Status.SUCCEEDED || value.status() == PaymentObservation.Status.REVERSED);
    }

    /**
     * 可解释的拒绝原因不包含资金系统原文，页面不能通过选择结果绕过这些条件。
     * @author owlzhangfq@gmail.com
     */
    public enum ResolutionIssue { NOT_DISPUTED, NON_TERMINAL, STALE_EVIDENCE, EXPIRED_EVIDENCE, DIFFERENT_PAYMENT, FUNDING_ALREADY_OBSERVED, DIFFERENT_SETTLEMENT }

    /** 权威查无不自动付款；显式重发仍使用原编号、原金额、原出纳，且必须重新检查账户。 */
    public PaymentOperation retryNotFound(Instant now) {
        requireTime(now); if (status != Status.NOT_FOUND || highestRevision != 0 || conflictingObservation != null) throw conflict();
        input.command().requireSendAt(now);
        return changed(Status.QUEUED, now, now, null, null, observation, null, highestRevision, null);
    }

    /** 实际网络发送前再次检查租约、授权和短期账户依据，过期不发送。 */
    public void requireSendAt(Instant now) {
        requireTime(now); if (status != Status.SENDING || expired(now) || !accountEvidence.matches(input, now)) throw conflict();
        input.command().requireSendAt(now);
    }
    public boolean running() { return status == Status.CHECKING || status == Status.SENDING || status == Status.QUERYING; }
    public boolean expired(Instant now) { return running() && !leaseUntil.isAfter(now); }
    /** 只有当前无矛盾的已到账结果可首次生成本地结算事实。 */
    public boolean settleable() { return status == Status.SUCCEEDED; }

    private PaymentOperation reconcile(PaymentObservation incoming, long highest, Failure reason, Instant now) {
        return changed(Status.RECONCILING, now, null, null, accountEvidence, observation, incoming, highest, reason);
    }
    private PaymentOperation changed(Status next, Instant now, Instant retry, Instant lease, AccountEvidence accounts, PaymentObservation accepted,
                                     PaymentObservation disputed, long highest, Failure reason) {
        return new PaymentOperation(input, Math.incrementExact(version), next, attempts, dispatches, createdAt, now, retry, lease, accounts, accepted, disputed, highest, reason);
    }
    private static boolean allowed(PaymentObservation before, PaymentObservation after) {
        if (before.status() == PaymentObservation.Status.NOT_FOUND) return true;
        if (!Objects.equals(before.paymentReference(), after.paymentReference())) return false;
        return switch (before.status()) {
            case PENDING -> after.status() != PaymentObservation.Status.NOT_FOUND;
            case SUCCEEDED -> after.status() == PaymentObservation.Status.SUCCEEDED && sameSettlement(before, after)
                    || after.status() == PaymentObservation.Status.REVERSED && after.paidAmount().equals(before.paidAmount())
                    && after.accountDigest().equals(before.accountDigest()) && !after.completedAt().isBefore(before.completedAt());
            case FAILED -> after.status() == PaymentObservation.Status.FAILED && before.failure() == after.failure();
            case REVERSED -> after.status() == PaymentObservation.Status.REVERSED && sameSettlement(before, after);
            case NOT_FOUND -> true;
        };
    }
    private static boolean sameFact(PaymentObservation before, PaymentObservation after) {
        return before.authorizationId().equals(after.authorizationId()) && before.commandDigest().equals(after.commandDigest())
                && before.status() == after.status() && before.revision().equals(after.revision()) && sameSettlement(before, after) && before.failure() == after.failure();
    }
    private static boolean sameSettlement(PaymentObservation before, PaymentObservation after) {
        return Objects.equals(before.paymentReference(), after.paymentReference()) && Objects.equals(before.paidAmount(), after.paidAmount())
                && Objects.equals(before.accountDigest(), after.accountDigest()) && Objects.equals(before.completedAt(), after.completedAt()) && Objects.equals(before.receiptReference(), after.receiptReference());
    }
    private Instant retryAt(Instant now) { return now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6))); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_OPERATION", "Payment recovery state must preserve original command and external facts"); }
    private static DomainException conflict() { return new DomainException("PAYMENT_OPERATION_STATE_CONFLICT", "Payment operation no longer allows this transition"); }

    /** 日志只带状态和计数，不暴露金额、人员或账户。 */
    @Override public String toString() { return "PaymentOperation[id=" + input.command().id() + ", version=" + version + ", status=" + status + "]"; }

    /**
     * 出纳登记时固定的原命令、财务目标与目录中选定的出款账户。
     * @author owlzhangfq@gmail.com
     */
    public record Input(PaymentCommand command, String targetDigest, PaymentAccountsPort.DebitAccount debitAccount) {
        /** 出款引用与币种不可脱离原命令。 */
        public Input {
            if (command == null || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}") || debitAccount == null
                    || !debitAccount.reference().equals(command.debitAccountReference()) || !debitAccount.currency().equals(command.amount().currency())) throw invalid();
        }
    }
    /**
     * 只保留被选中账户的当前依据，不保存出纳目录中的其他账户。
     * @author owlzhangfq@gmail.com
     */
    public record AccountEvidence(PaymentAccountsPort.Request request, String directoryVersion, PaymentAccountsPort.DebitAccount debitAccount,
                                  EmployeeAccountSnapshot payee, Instant observedAt, Instant checkedAt, Instant validUntil) {
        /** 证据只在检查发生前已观测且未过期时成立。 */
        public AccountEvidence {
            if (request == null || StringUtils.isBlank(directoryVersion) || directoryVersion.length() > 128 || debitAccount == null || payee == null
                    || observedAt == null || checkedAt == null || validUntil == null || checkedAt.isBefore(observedAt) || !validUntil.isAfter(checkedAt)) throw invalid();
        }
        private static AccountEvidence checked(Input input, PaymentAccountsPort.Directory directory, EmployeeAccountPort.Account payee, Instant now) {
            var request = new PaymentAccountsPort.Request(input.command().payee().legalEntityId(), input.command().amount().currency(), input.command().authorization().executedBy());
            if (directory == null || !directory.matches(request, now) || !directory.account(input.debitAccount().reference(), now).equals(input.debitAccount())
                    || payee == null || !payee.snapshot().equals(input.command().payee()) || !payee.validUntil().isAfter(now)) {
                throw new DomainException("PAYMENT_ACCOUNT_CHANGED", "Original payment accounts must remain available and unchanged");
            }
            return new AccountEvidence(request, directory.sourceVersion(), input.debitAccount(), payee.snapshot(), directory.observedAt(), now,
                    directory.validUntil().isBefore(payee.validUntil()) ? directory.validUntil() : payee.validUntil());
        }
        /** 发送时使用原出纳、法人、币种、两端账户与原短期有效窗口。 */
        public boolean matches(Input input, Instant now) {
            return !now.isBefore(checkedAt) && now.isBefore(validUntil) && debitAccount.equals(input.debitAccount()) && payee.equals(input.command().payee())
                    && request.equals(new PaymentAccountsPort.Request(payee.legalEntityId(), input.command().amount().currency(), input.command().authorization().executedBy()));
        }
        /** 审计日志不展开账户内容。 */
        @Override public String toString() { return "PaymentAccountEvidence[checkedAt=" + checkedAt + "]"; }
    }
    /**
     * 只读检查和可能发送使用不同状态，到账、退回及矛盾分别保留。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, CHECKING, SENDING, QUERYING, UNKNOWN, SUCCEEDED, FAILED, NOT_FOUND, EXPIRED, VOIDED, RECONCILING, REVERSED }
    /**
     * 封闭错误分类不携带资金系统响应正文。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, LEASE_EXPIRED, INTERNAL_ERROR, AUTHORIZATION_EXPIRED, SOURCE_CHANGED, ACCOUNT_CHANGED,
        STALE_OBSERVATION, INCONSISTENT_OBSERVATION, RECHECK_REQUESTED, FINANCE_RETIRED }
}
