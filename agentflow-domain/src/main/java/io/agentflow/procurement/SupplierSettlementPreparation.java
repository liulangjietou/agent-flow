package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 财务明确原银行付款和指定会计日期后保存结算意图；后台复查只读依据，再登记一次固定结算。
 * @author owlzhangfq@gmail.com
 */
public record SupplierSettlementPreparation(Input input, long version, Status status, int attempts, Instant updatedAt,
        Instant nextAttemptAt, Instant leaseUntil, Issue issue) {
    private static final long INITIAL_BACKOFF_SECONDS = 5;
    private static final long MAX_BACKOFF_SECONDS = 300;

    /** 准备状态不会伪造 ERP 结算，READY 只表示下游不可变命令已登记。 */
    public SupplierSettlementPreparation {
        if (input == null || version < 1 || status == null || attempts < 0 || updatedAt == null || updatedAt.isBefore(input.requestedAt())) throw invalid();
        if (status == Status.QUEUED ? nextAttemptAt == null || nextAttemptAt.isBefore(updatedAt) : nextAttemptAt != null) throw invalid();
        if (status == Status.RUNNING ? attempts == 0 || leaseUntil == null || !leaseUntil.isAfter(updatedAt) || issue != null : leaseUntil != null) throw invalid();
        if (status == Status.READY && (attempts == 0 || issue != null) || status == Status.BLOCKED && (attempts == 0 || issue == null)
                || status == Status.VOIDED && issue != Issue.SOURCE_CHANGED) throw invalid();
        if (version == 1 && (status != Status.QUEUED || attempts != 0 || !updatedAt.equals(input.requestedAt()) || !nextAttemptAt.equals(updatedAt) || issue != null)) throw invalid();
    }

    /** 页面只提供已展示的原银行编号、版本与明确日期，完整来源由应用服务从实际成功记录派生。 */
    public static SupplierSettlementPreparation queue(UUID id, SupplierPaymentOperation payment, String finance, LocalDate accountingDate, Instant now) {
        return new SupplierSettlementPreparation(new Input(id, payment, finance, accountingDate, now), 1, Status.QUEUED, 0, now, now, null, null);
    }
    /** 独立租约只允许读取原银行、原预留和指定会计期间。 */
    public SupplierSettlementPreparation claim(Instant now, Duration lease) {
        requireTime(now); if (status != Status.QUEUED || now.isBefore(nextAttemptAt) || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        return new SupplierSettlementPreparation(input, Math.incrementExact(version), Status.RUNNING, Math.incrementExact(attempts), now, null, now.plus(lease), null);
    }
    /** 期间明确关闭或原银行/预留变化，停止本次准备而不产生结算写入。 */
    public SupplierSettlementPreparation block(Issue reason, Instant now) {
        requireRunning(now); if (leaseExpired(now)) return expireLease(now);
        if (reason != Issue.ACCOUNTING_PERIOD_REJECTED && reason != Issue.EVIDENCE_CHANGED) throw conflict();
        return changed(Status.BLOCKED, now, null, reason);
    }
    /** 暂时读取失败保留同一明确意图退避重读，不另建结算号。 */
    public SupplierSettlementPreparation unavailable(Issue reason, Instant now) {
        requireRunning(now); if (leaseExpired(now)) return expireLease(now);
        if (reason == null || reason == Issue.SOURCE_CHANGED || reason == Issue.ACCOUNTING_PERIOD_REJECTED || reason == Issue.EVIDENCE_CHANGED) throw conflict();
        return changed(Status.QUEUED, now, now.plusSeconds(Math.min(MAX_BACKOFF_SECONDS, INITIAL_BACKOFF_SECONDS << Math.min(attempts - 1, 6))), reason);
    }
    /** 原来源或财务资格变化只终止还未登记下游命令的读取意图。 */
    public SupplierSettlementPreparation voidSource(Instant now) {
        requireTime(now); if (!active()) throw conflict(); return changed(Status.VOIDED, now, null, Issue.SOURCE_CHANGED);
    }
    /** 只读领取过期可重新读取；迟到回执必须由持久版本拒绝。 */
    public SupplierSettlementPreparation expireLease(Instant now) {
        requireTime(now); if (!leaseExpired(now)) throw conflict(); return changed(Status.QUEUED, now, now, Issue.LEASE_EXPIRED);
    }
    /** 下游命令与 READY 必须同事务保存，财务身份、原银行修订和指定日期不能在后台替换。 */
    public SupplierSettlementPreparation ready(SupplierPayableSettlementCommand command, Instant now) {
        requireRunning(now); if (leaseExpired(now)) throw conflict();
        if (command == null || !command.id().equals(input.id()) || !command.registeredFrom(input.payment()) || !command.financeActor().equals(input.financeActor())
                || !command.period().request().accountingDate().equals(input.accountingDate()) || !command.registeredAt().equals(now)) throw conflict();
        return changed(Status.READY, now, null, null);
    }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean leaseExpired(Instant now) { return status == Status.RUNNING && !now.isBefore(leaseUntil); }
    private void requireRunning(Instant now) { requireTime(now); if (status != Status.RUNNING) throw conflict(); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private SupplierSettlementPreparation changed(Status next, Instant now, Instant retry, Issue reason) { return new SupplierSettlementPreparation(input, Math.incrementExact(version), next, attempts, now, retry, null, reason); }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_SETTLEMENT_PREPARATION", "Supplier settlement preparation must preserve actual paid source, finance actor and accounting date"); }
    private static DomainException conflict() { return new DomainException("SUPPLIER_SETTLEMENT_PREPARATION_CONFLICT", "Supplier settlement preparation no longer allows this transition"); }
    /** 日志仅保存意图标识与准备状态。 */
    @Override public String toString() { return "SupplierSettlementPreparation[id=" + input.id() + ", status=" + status + "]"; }

    /**
     * 实际银行成功修订与明确的财务记账意图一起固定，不接受客户端拼装原付款。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, SupplierPaymentOperation payment, String financeActor, LocalDate accountingDate, Instant requestedAt) {
        /** 财务与申请人、出纳分离，指定记账日期不能早于原法人的实际到账日期。 */
        public Input {
            if (id == null || payment == null || !payment.settleable() || accountingDate == null || requestedAt == null || requestedAt.isBefore(payment.updatedAt())
                    || StringUtils.isBlank(financeActor) || financeActor.length() > 128 || !financeActor.equals(financeActor.trim()) || financeActor.chars().anyMatch(Character::isISOControl)
                    || financeActor.equals(payment.command().cashier()) || financeActor.equals(payment.command().holdCommand().authorization().source().reservation().source().employeeId())
                    || accountingDate.isBefore(payment.observation().completedAt().atZone(ZoneId.of(payment.command().holdCommand().authorization().source().reservation().source().round().legalEntity().timeZone())).toLocalDate())) throw invalid();
        }
        /** 新鲜三项证据形成原意图的唯一命令；实际当前银行和财务任职仍由应用服务锁后核对。 */
        public SupplierPayableSettlementCommand command(SupplierPayableSettlementEvidence evidence, Instant now) {
            if (evidence == null || now == null || now.isBefore(evidence.checkedAt()) || evidence.checkedAt().isBefore(requestedAt)
                    || !now.isBefore(evidence.validUntil()) || !evidence.period().request().accountingDate().equals(accountingDate)
                    || !payment.command().matchesHold(evidence.hold(), now)) throw conflict();
            return SupplierPayableSettlementCommand.register(id, payment, evidence.paid(), evidence.period(), financeActor, now);
        }
        /** 原始银行及采购资料仅用于受控持久编排。 */
        @Override public String toString() { return "SupplierSettlementPreparationInput[id=" + id + "]"; }
    }
    /**
     * READY 仍不是 ERP 成功，只有排队与读取中的意图占用当前准备位置。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, READY, BLOCKED, VOIDED }
    /**
     * 只保留稳定分类，财务系统错误正文不落入准备状态。
     * @author owlzhangfq@gmail.com
     */
    public enum Issue { NOT_CONFIGURED, TARGET_CHANGED, TIMEOUT, CONNECTION, AUTHENTICATION, REMOTE_FAILURE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, INTERNAL_ERROR, LEASE_EXPIRED, SOURCE_CHANGED, ACCOUNTING_PERIOD_REJECTED, EVIDENCE_CHANGED }
}
