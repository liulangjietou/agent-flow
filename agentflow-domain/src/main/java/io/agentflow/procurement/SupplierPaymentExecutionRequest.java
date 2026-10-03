package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.PaymentAccountsPort;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 出纳明确选择的持久意图，只读复查完成后才能登记唯一银行命令。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPaymentExecutionRequest(Input input, long version, Status status, int attempts, Instant createdAt,
        Instant updatedAt, Instant nextAttemptAt, Instant leaseUntil, Failure failure) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;

    /** 读取租约不能当作可能发送，终止或完成的意图不能恢复为待首次执行。 */
    public SupplierPaymentExecutionRequest {
        if (input == null || version < 1 || status == null || attempts < 0 || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)) throw invalid();
        if (status == Status.RUNNING ? attempts == 0 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || failure != null : leaseUntil != null) throw invalid();
        if (status == Status.QUEUED ? nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt) : nextAttemptAt != null) throw invalid();
        if (status == Status.READY && (attempts == 0 || failure != null) || status == Status.BLOCKED && failure != Failure.EVIDENCE_CHANGED
                || status == Status.VOIDED && failure != Failure.SOURCE_CHANGED || status == Status.EXPIRED && failure != Failure.AUTHORIZATION_EXPIRED) throw invalid();
        if (version == 1 && (status != Status.QUEUED || attempts != 0 || failure != null || !createdAt.equals(updatedAt) || !createdAt.equals(nextAttemptAt))) throw invalid();
    }

    /** 身份与所见原预留在入口核验；只冻结出纳选择，不把金额或收款账户作为输入。 */
    public static SupplierPaymentExecutionRequest queue(UUID id, SupplierPayableHoldOperation hold, String cashier, String debitReference, String debitVersion, Instant now) {
        if (hold == null || hold.status() != SupplierPayableHoldOperation.Status.HELD || now == null || now.isBefore(hold.updatedAt())) throw conflict();
        hold.command().authorization().requireCashier(cashier, now);
        var input = new Input(id, hold.command().tenantId(), hold.command().id(), hold.version(), cashier, debitReference, debitVersion);
        return new SupplierPaymentExecutionRequest(input, 1, Status.QUEUED, 0, now, now, now, null, null);
    }

    /** 领取只允许复查原预留、应付和账户，崩溃恢复保持原出纳及选择。 */
    public SupplierPaymentExecutionRequest claim(Instant now, Duration lease) {
        requireTime(now); if (status != Status.QUEUED || now.isBefore(nextAttemptAt) || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        return new SupplierPaymentExecutionRequest(input, Math.incrementExact(version), Status.RUNNING, Math.incrementExact(attempts), createdAt, now, null, now.plus(lease), null);
    }

    /** 只读租约过期不产生银行未知状态，可重新读取同一请求。 */
    public SupplierPaymentExecutionRequest expireLease(Instant now) {
        if (!leaseExpired(now)) throw conflict();
        return changed(Status.QUEUED, now, now, Failure.LEASE_EXPIRED);
    }

    /** 暂时读取故障保留原选择并退避，不建立付款编号或外部发送记录。 */
    public SupplierPaymentExecutionRequest unavailable(Failure reason, Instant now) {
        requireTime(now); if (status != Status.RUNNING) throw conflict(); if (leaseExpired(now)) return expireLease(now);
        if (reason == null || reason == Failure.SOURCE_CHANGED || reason == Failure.EVIDENCE_CHANGED || reason == Failure.AUTHORIZATION_EXPIRED) throw conflict();
        return changed(Status.QUEUED, now, now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6))), reason);
    }

    /** 原预留或账户不再成立，停止此次选择；重新选择必须先确认没有银行命令。 */
    public SupplierPaymentExecutionRequest block(Instant now) { return terminal(Status.BLOCKED, Failure.EVIDENCE_CHANGED, now); }
    /** 原批准、授权或任职失效，不能继续创建银行命令。 */
    public SupplierPaymentExecutionRequest voidSource(Instant now) { return terminal(Status.VOIDED, Failure.SOURCE_CHANGED, now); }
    /** 财务授权到期不能由出纳复查延长。 */
    public SupplierPaymentExecutionRequest expireAuthorization(Instant now) { return terminal(Status.EXPIRED, Failure.AUTHORIZATION_EXPIRED, now); }

    /** 以本次领取后的复查登记指令，当前目录的账户版本仍须与人工确认完全相同。 */
    public SupplierPaymentCommand register(SupplierPayableHoldOperation original, SupplierPayableHoldObservation verified,
            PaymentAccountsPort.Directory directory, ProcurementPayablePort.Payable payable, Instant now) {
        requireTime(now); if (status != Status.RUNNING || leaseExpired(now) || original == null
                || !original.command().tenantId().equals(input.tenantId()) || !original.command().id().equals(input.authorizationId())
                || original.version() < input.holdVersion()) throw conflict();
        var command = SupplierPaymentCommand.register(original, verified, directory, input.debitReference(), input.cashier(), now);
        if (!command.debitAccount().sourceVersion().equals(input.debitVersion())) throw evidenceChanged();
        SupplierPaymentEvidence.checked(command, directory, payable, verified, now);
        return command;
    }

    /** 只有对应原选择的命令与队列同事务保存后才标记 READY，仍不声明银行到账。 */
    public SupplierPaymentExecutionRequest ready(SupplierPaymentCommand command, Instant now) {
        requireTime(now); if (status != Status.RUNNING) throw conflict(); if (leaseExpired(now)) return expireLease(now);
        if (command == null || !command.id().equals(input.authorizationId()) || !command.tenantId().equals(input.tenantId())
                || !command.cashier().equals(input.cashier()) || !command.debitAccount().reference().equals(input.debitReference())
                || !command.debitAccount().sourceVersion().equals(input.debitVersion()) || command.registeredAt().isBefore(updatedAt) || command.registeredAt().isAfter(now)) throw conflict();
        return changed(Status.READY, now, null, null);
    }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean ownsAuthorization() { return active() || status == Status.READY; }
    public boolean leaseExpired(Instant now) { return status == Status.RUNNING && !leaseUntil.isAfter(now); }

    private SupplierPaymentExecutionRequest terminal(Status next, Failure reason, Instant now) {
        requireTime(now); if (!active()) throw conflict(); return changed(next, now, null, reason);
    }
    private SupplierPaymentExecutionRequest changed(Status next, Instant now, Instant retry, Failure reason) {
        return new SupplierPaymentExecutionRequest(input, Math.incrementExact(version), next, attempts, createdAt, now, retry, null, reason);
    }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYMENT_EXECUTION_REQUEST", "Supplier cashier request must preserve the original hold and account selection"); }
    private static DomainException conflict() { return new DomainException("SUPPLIER_PAYMENT_EXECUTION_STATE_CONFLICT", "Supplier cashier request no longer permits this transition"); }
    private static DomainException evidenceChanged() { return new DomainException("SUPPLIER_PAYMENT_EVIDENCE_CHANGED", "Selected supplier payment debit account version changed"); }
    /** 原选择只在受控存储中保存，不进入日志。 */
    @Override public String toString() { return "SupplierPaymentExecutionRequest[id=" + input.id() + ", version=" + version + ", status=" + status + "]"; }

    /**
     * 原预留修订绑定出纳实际看到的依据，完成时仍须重新读取当前事实。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, String tenantId, UUID authorizationId, long holdVersion, String cashier, String debitReference, String debitVersion) {
        /** 不接收 URL、金额、收款账户或可伪造的已付款结论。 */
        public Input {
            if (id == null || invalidText(tenantId, 64) || authorizationId == null || holdVersion < 1
                    || invalidText(cashier, 128) || invalidText(debitReference, 128) || invalidText(debitVersion, 128)) throw invalid();
        }
        private static boolean invalidText(String value, int maximum) {
            return StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
        }
        /** 标识可用于排障，账户选择不可被默认输出。 */
        @Override public String toString() { return "SupplierPaymentExecutionInput[id=" + id + ", authorizationId=" + authorizationId + "]"; }
    }
    /**
     * READY 已占用原授权的唯一银行命令身份；其他终止状态只有在没有命令时才允许另行选择。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, READY, BLOCKED, VOIDED, EXPIRED }
    /**
     * 只读阶段失败不会伪造银行失败或释放原 ERP 预留。
     * @author owlzhangfq@gmail.com
     */
    public enum Failure { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, INTERNAL_ERROR, LEASE_EXPIRED, SOURCE_CHANGED, EVIDENCE_CHANGED, AUTHORIZATION_EXPIRED }
}
