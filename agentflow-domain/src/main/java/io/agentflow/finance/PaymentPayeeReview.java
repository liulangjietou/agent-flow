package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 原付款结束后的本人账户复核；读取成功不等于财务已同意使用该账户付款。
 * @author owlzhangfq@gmail.com
 */
public record PaymentPayeeReview(Input input, long version, Status status, int attempts, Instant updatedAt, Instant leaseUntil,
                                 EmployeeAccountPort.Account account, Instant checkedAt, UUID consumedAuthorizationId, Issue issue) {
    public static final Duration MAX_EVIDENCE_AGE = Duration.ofMinutes(5);

    /** 恢复时核对证据身份、有效期及单次消费，不能把读取中的账户当成已复核事实。 */
    public PaymentPayeeReview {
        if (input == null || version < 1 || status == null || attempts < 0 || updatedAt == null || updatedAt.isBefore(input.requestedAt())) throw invalid();
        if (status == Status.RUNNING ? attempts == 0 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || issue != null : leaseUntil != null) throw invalid();
        boolean checked = status == Status.READY || status == Status.CONSUMED;
        if (checked) {
            if (attempts == 0 || account == null || checkedAt == null || checkedAt.isBefore(input.requestedAt()) || checkedAt.isAfter(updatedAt)
                    || !sameOwner(input, account) || !account.validUntil().isAfter(updatedAt) || account.validUntil().isAfter(checkedAt.plus(MAX_EVIDENCE_AGE)) || issue != null) throw invalid();
            if (status == Status.READY && (!checkedAt.equals(updatedAt) || consumedAuthorizationId != null)
                    || status == Status.CONSUMED && (consumedAuthorizationId == null || consumedAuthorizationId.equals(input.original().id()))) throw invalid();
        } else if (account != null || checkedAt != null || consumedAuthorizationId != null) throw invalid();
        if ((status == Status.UNAVAILABLE || status == Status.BLOCKED || status == Status.VOIDED) && issue == null) throw invalid();
    }

    /** 复核只绑定已关闭的原授权及仍可用的原挂账，不接受前端的账户或金额声明。 */
    public static PaymentPayeeReview queue(UUID id, PaymentAuthorization original, VoucherOperation voucher, String actor, Instant now) {
        if (!ended(original) || !original.matchesVoucher(voucher, now) || now == null || now.isBefore(original.updatedAt())) throw sourceChanged();
        var input = new Input(id, original.terms(), original.version(), voucher.version(), actor, now);
        return new PaymentPayeeReview(input, 1, Status.QUEUED, 0, now, null, null, null, null, null);
    }

    /** 账户复查租约没有资金副作用，过期后恢复同一复核记录。 */
    public PaymentPayeeReview claim(Instant now, Duration lease) {
        requireTime(now);
        if (status != Status.QUEUED || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        return new PaymentPayeeReview(input, Math.incrementExact(version), Status.RUNNING, Math.incrementExact(attempts), now, now.plus(lease), null, null, null, null);
    }

    /** 只读取同法人本人账户，证据窗口最多五分钟，过期必须重新读取而非续期。 */
    public PaymentPayeeReview complete(FinanceResult<EmployeeAccountPort.Account> result, Instant now) {
        requireRunning(now); if (leaseExpired(now)) return expireLease(now);
        if (result instanceof FinanceResult.Success<EmployeeAccountPort.Account> success) {
            var observed = success.value();
            if (!sameOwner(input, observed) || !observed.validUntil().isAfter(now)) return fail(Issue.INVALID_RESPONSE, now);
            var until = observed.validUntil().isBefore(now.plus(MAX_EVIDENCE_AGE)) ? observed.validUntil() : now.plus(MAX_EVIDENCE_AGE);
            return new PaymentPayeeReview(input, Math.incrementExact(version), Status.READY, attempts, now, null,
                    new EmployeeAccountPort.Account(observed.snapshot(), until), now, null, null);
        }
        if (result instanceof FinanceResult.Rejected<EmployeeAccountPort.Account>) return changed(Status.BLOCKED, now, Issue.ACCOUNT_REJECTED);
        return fail(result instanceof FinanceResult.Unavailable<EmployeeAccountPort.Account> unavailable ? Issue.valueOf(unavailable.failure().name()) : Issue.INVALID_RESPONSE, now);
    }

    /** 暂时失败保留原意图及分类，后续显式复核会形成另一条读取记录。 */
    public PaymentPayeeReview fail(Issue reason, Instant now) {
        requireRunning(now); if (leaseExpired(now)) return expireLease(now);
        if (reason == null) throw invalid();
        return changed(Status.UNAVAILABLE, now, reason);
    }

    /** 迟到读取不能在旧租约上落成可授权结果。 */
    public PaymentPayeeReview expireLease(Instant now) {
        requireTime(now); if (!leaseExpired(now)) throw conflict();
        return changed(Status.QUEUED, now, Issue.LEASE_EXPIRED);
    }

    /** 原批准、财务任职、凭证或业务占用变化后只保留失效记录。 */
    public PaymentPayeeReview voidSource(Instant now) {
        requireTime(now); if (!active()) throw conflict(); return changed(Status.VOIDED, now, Issue.SOURCE_CHANGED);
    }

    /** 新授权明确使用本次复核账户，且只能由发起复核的财务在证据期限内消费一次。 */
    public PaymentPayeeReview consume(PaymentAuthorization authorization, Instant now) {
        requireTime(now);
        if (!usable(now) || authorization.status() != PaymentAuthorization.Status.AUTHORIZED || !authorization.decision().authorizedAt().equals(now)
                || !matchesAuthorization(authorization)) throw unavailable();
        return new PaymentPayeeReview(input, Math.incrementExact(version), Status.CONSUMED, attempts, now, null, account, checkedAt, authorization.terms().id(), null);
    }

    /** 已消费证据过期不改写原授权；实际执行仍通过独立账户读取核对当前主数据。 */
    public boolean supports(PaymentAuthorization authorization) {
        return status == Status.CONSUMED && consumedAuthorizationId.equals(authorization.terms().id())
                && updatedAt.equals(authorization.decision().authorizedAt()) && matchesAuthorization(authorization);
    }

    /** 重读来源时要求同一原授权修订和同一挂账展示版本。 */
    public boolean matchesSource(PaymentAuthorization original, VoucherOperation voucher, Instant now) {
        return ended(original) && original.version() == input.authorizationVersion() && original.terms().equals(input.original())
                && voucher != null && voucher.version() == input.voucherVersion() && original.matchesVoucher(voucher, now);
    }

    /** 有效期为排他边界，不会在显示或恢复时自动延长。 */
    public boolean usable(Instant now) { return status == Status.READY && now != null && !now.isBefore(checkedAt) && now.isBefore(account.validUntil()); }
    /** 未结束的只读请求才参与后台调度。 */
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    /** 领取完成时再次判断租约，超时结果不会成为授权依据。 */
    public boolean leaseExpired(Instant now) { return status == Status.RUNNING && !now.isBefore(leaseUntil); }
    /** 账户复核必须建立在旧授权已不再占用原业务的事实之上。 */
    public static boolean ended(PaymentAuthorization value) {
        return value != null && (value.status() == PaymentAuthorization.Status.RETIRED || value.status() == PaymentAuthorization.Status.VOIDED || value.status() == PaymentAuthorization.Status.EXPIRED);
    }

    private boolean matchesAuthorization(PaymentAuthorization authorization) {
        var terms = authorization.terms(); var original = input.original();
        return !terms.id().equals(original.id()) && terms.tenantId().equals(original.tenantId()) && terms.purpose() == original.purpose()
                && terms.binding().equals(original.binding()) && terms.amount().equals(original.amount()) && terms.payee().equals(account.snapshot())
                && terms.voucherOperationId().equals(original.voucherOperationId()) && terms.voucherCommandDigest().equals(original.voucherCommandDigest())
                && terms.voucherReference().equals(original.voucherReference()) && terms.voucherRevision() >= original.voucherRevision()
                && terms.targetDigest().equals(original.targetDigest()) && authorization.decision().authorizedBy().equals(input.requestedBy());
    }
    private static boolean sameOwner(Input input, EmployeeAccountPort.Account account) {
        return account != null && input.original().payee().employeeId().equals(account.snapshot().employeeId())
                && input.original().payee().legalEntityId().equals(account.snapshot().legalEntityId());
    }
    private PaymentPayeeReview changed(Status next, Instant now, Issue problem) {
        return new PaymentPayeeReview(input, Math.incrementExact(version), next, attempts, now, null, null, null, null, problem);
    }
    private void requireRunning(Instant now) { requireTime(now); if (status != Status.RUNNING) throw conflict(); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_PAYEE_REVIEW", "Payee review must preserve original source and single-use account evidence"); }
    private static DomainException conflict() { return new DomainException("PAYMENT_PAYEE_REVIEW_STATE_CONFLICT", "Payee review no longer allows this transition"); }
    private static DomainException sourceChanged() { return new DomainException("PAYMENT_SOURCE_CHANGED", "A safely ended original authorization and current posted source are required"); }
    private static DomainException unavailable() { return new DomainException("PAYMENT_PAYEE_REVIEW_UNAVAILABLE", "Fresh account review for the current finance actor is required"); }
    /** 日志只保留复核标识和状态，不输出账户、金额或人员。 */
    @Override public String toString() { return "PaymentPayeeReview[id=" + input.id() + ", status=" + status + "]"; }

    /**
     * 原条款冻结来源，新的账户只可来自后台读取，不进入请求输入。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, PaymentAuthorization.Terms original, long authorizationVersion, long voucherVersion, String requestedBy, Instant requestedAt) {
        /** 租户与主体始终由已保存的原授权及认证财务生成。 */
        public Input {
            if (id == null || original == null || id.equals(original.id()) || authorizationVersion < 2 || authorizationVersion > 3 || voucherVersion < 1
                    || StringUtils.isBlank(requestedBy) || requestedBy.length() > 128 || requestedBy.equals(original.payee().employeeId()) || requestedAt == null) throw invalid();
        }
        /** 原条款包含受控账户快照，禁止默认日志展开。 */
        @Override public String toString() { return "PaymentPayeeReviewInput[id=" + id + "]"; }
    }

    /**
     * READY 等待人工决定，CONSUMED 只代表绑定了新授权，不是银行到账。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, READY, CONSUMED, UNAVAILABLE, BLOCKED, VOIDED }
    /**
     * 读取失败不产生资金状态，稳定分类可用于受控页面提示。
     * @author owlzhangfq@gmail.com
     */
    public enum Issue { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, INTERNAL_ERROR, ACCOUNT_REJECTED, SOURCE_CHANGED, LEASE_EXPIRED }
}
