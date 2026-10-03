package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 出纳确认的唯一账户选择；只读复查成功后才登记付款命令，本对象自身没有资金副作用。
 * @author owlzhangfq@gmail.com
 */
public record PaymentExecutionRequest(Input input, long version, Status status, int attempts, Instant createdAt,
                                      Instant updatedAt, Instant nextAttemptAt, Instant leaseUntil, Failure failure) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;
    /** 恢复保持原出纳、授权版本和账户选择，终态不能再次进入首次检查。 */
    public PaymentExecutionRequest {
        if (input == null || version < 1 || status == null || attempts < 0 || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)) throw invalid();
        if (status == Status.RUNNING ? attempts == 0 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || failure != null : leaseUntil != null) throw invalid();
        if (status == Status.QUEUED ? nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt) : nextAttemptAt != null) throw invalid();
        if (status == Status.READY && (attempts == 0 || failure != null) || status == Status.BLOCKED && failure != Failure.ACCOUNT_CHANGED
                || status == Status.VOIDED && failure != Failure.SOURCE_CHANGED || status == Status.EXPIRED && failure != Failure.AUTHORIZATION_EXPIRED) throw invalid();
        if (version == 1 && (status != Status.QUEUED || attempts != 0 || failure != null || !createdAt.equals(updatedAt))) throw invalid();
    }
    /** 入口已经核验真实角色和所见版本，只保存执行意图，不把 HTTP 等待放入幂等事务。 */
    public static PaymentExecutionRequest queue(UUID id, PaymentAuthorization authorization, String cashier, String debitReference, String debitVersion, Instant now) {
        if (authorization == null || authorization.status() != PaymentAuthorization.Status.AUTHORIZED || now == null || now.isBefore(authorization.updatedAt())
                || !now.isBefore(authorization.decision().expiresAt())) throw conflict();
        if (Objects.equals(cashier, authorization.decision().authorizedBy()) || Objects.equals(cashier, authorization.terms().payee().employeeId())) {
            throw new DomainException("PAYMENT_SEPARATION_REQUIRED", "Applicant, payment authorizer and cashier must be different people");
        }
        var input = new Input(id, authorization.terms().tenantId(), authorization.terms().id(), authorization.version(), cashier, debitReference, debitVersion);
        return new PaymentExecutionRequest(input, 1, Status.QUEUED, 0, now, now, now, null, null);
    }
    /** 领取仍是只读账户复查，不保留任何已付款含义。 */
    public PaymentExecutionRequest claim(Instant now, Duration lease) {
        requireTime(now); if (status != Status.QUEUED || now.isBefore(nextAttemptAt) || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        return new PaymentExecutionRequest(input, Math.incrementExact(version), Status.RUNNING, Math.incrementExact(attempts), createdAt, now, null, now.plus(lease), null);
    }
    /** 读取阶段崩溃只重读原选择，无法换出纳、换账户或推导出银行状态。 */
    public PaymentExecutionRequest expireLease(Instant now) {
        if (!leaseExpired(now)) throw conflict();
        return changed(Status.QUEUED, now, now, Failure.LEASE_EXPIRED);
    }
    /** 可恢复的配置或网络故障按原请求退避，授权窗口由服务在每次领取时核对。 */
    public PaymentExecutionRequest unavailable(Failure reason, Instant now) {
        requireTime(now); if (status != Status.RUNNING) throw conflict(); if (leaseExpired(now)) return expireLease(now);
        if (reason == null || reason == Failure.ACCOUNT_CHANGED || reason == Failure.SOURCE_CHANGED || reason == Failure.AUTHORIZATION_EXPIRED) throw conflict();
        return changed(Status.QUEUED, now, now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6))), reason);
    }
    /** 明确账户变化停止本次选择，财务可在尚未生成付款命令时作废原授权。 */
    public PaymentExecutionRequest block(Instant now) { return terminal(Status.BLOCKED, Failure.ACCOUNT_CHANGED, now); }
    /** 真实批准或授权已变化，不继续创建付款命令。 */
    public PaymentExecutionRequest voidSource(Instant now) { return terminal(Status.VOIDED, Failure.SOURCE_CHANGED, now); }
    /** 原授权到期与网络不可用不同，不允许自动延长期限。 */
    public PaymentExecutionRequest expireAuthorization(Instant now) { return terminal(Status.EXPIRED, Failure.AUTHORIZATION_EXPIRED, now); }
    /** 仅与实际执行登记和队列在同一事务成功保存后标记 READY。 */
    public PaymentExecutionRequest ready(PaymentAuthorization authorization, Instant now) {
        requireTime(now); if (status != Status.RUNNING) throw conflict(); if (leaseExpired(now)) return expireLease(now);
        if (!matchesRegistered(authorization) || authorization.execution().registeredAt().isBefore(createdAt)
                || authorization.execution().registeredAt().isAfter(now)) throw conflict();
        return changed(Status.READY, now, null, null);
    }
    /** 当前目录必须精确对应已确认的出纳、引用和账户版本，不能把另一选择代入执行。 */
    public PaymentAuthorization register(PaymentAuthorization authorization, PaymentAccountsPort.Directory directory, EmployeeAccountPort.Account account, VoucherOperation voucher, Instant now) {
        requireTime(now); if (status != Status.RUNNING || leaseExpired(now) || authorization == null || authorization.status() != PaymentAuthorization.Status.AUTHORIZED
                || !authorization.terms().tenantId().equals(input.tenantId()) || !authorization.terms().id().equals(input.authorizationId()) || authorization.version() != input.authorizationVersion()) throw conflict();
        if (directory == null || !directory.account(input.debitReference(), now).sourceVersion().equals(input.debitVersion())) {
            throw new DomainException("PAYMENT_ACCOUNT_CHANGED", "Selected debit account version is no longer current");
        }
        return authorization.registerExecution(input.cashier(), directory, input.debitReference(), account, voucher, now);
    }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean leaseExpired(Instant now) { return status == Status.RUNNING && !leaseUntil.isAfter(now); }
    private boolean matchesRegistered(PaymentAuthorization value) {
        return value != null && value.status() == PaymentAuthorization.Status.EXECUTION_REGISTERED && value.terms().tenantId().equals(input.tenantId())
                && value.terms().id().equals(input.authorizationId()) && value.version() == input.authorizationVersion() + 1
                && value.execution().command().authorization().executedBy().equals(input.cashier())
                && value.execution().debitAccount().reference().equals(input.debitReference()) && value.execution().debitAccount().sourceVersion().equals(input.debitVersion());
    }
    private PaymentExecutionRequest terminal(Status next, Failure reason, Instant now) {
        requireTime(now); if (!active()) throw conflict();
        return changed(next, now, null, reason);
    }
    private PaymentExecutionRequest changed(Status next, Instant now, Instant retry, Failure reason) {
        return new PaymentExecutionRequest(input, Math.incrementExact(version), next, attempts, createdAt, now, retry, null, reason);
    }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_PAYMENT_EXECUTION_REQUEST", "Payment execution request must preserve original cashier and account selection"); }
    private static DomainException conflict() { return new DomainException("PAYMENT_EXECUTION_REQUEST_STATE_CONFLICT", "Payment execution request no longer permits this transition"); }

    /** 日志不展开出纳或账户引用。 */
    @Override public String toString() { return "PaymentExecutionRequest[id=" + input.id() + ", version=" + version + ", status=" + status + "]"; }
    /**
     * 只保存认证主体确认的选择，金额、收款账户、目标及凭证来自原授权。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, String tenantId, UUID authorizationId, long authorizationVersion, String cashier, String debitReference, String debitVersion) {
        /** 授权第一版是唯一可登记执行的版本，引用不能用空值表达默认账户。 */
        public Input {
            if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || authorizationId == null || authorizationVersion != 1
                    || invalidText(cashier) || invalidText(debitReference) || invalidText(debitVersion)) throw invalid();
        }
        private static boolean invalidText(String value) { return StringUtils.isBlank(value) || value.length() > 128; }
        /** 明细只存于受控的持久记录。 */
        @Override public String toString() { return "PaymentExecutionRequestInput[id=" + id + ", authorizationId=" + authorizationId + "]"; }
    }
    /**
     * READY 仅说明付款命令已登记，资金结果由 PaymentOperation 追踪。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, READY, BLOCKED, VOIDED, EXPIRED }
    /**
     * 检查失败分类不携带远端正文，也不伪造银行失败。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, LEASE_EXPIRED, INTERNAL_ERROR, ACCOUNT_CHANGED, SOURCE_CHANGED, AUTHORIZATION_EXPIRED }
}
