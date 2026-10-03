package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 冲销前持久只读准备，当前原件和新期间经过核验后仍须原发起财务明确授权。
 * @author owlzhangfq@gmail.com
 */
public record VoucherReversalPreparation(Input input, long version, Status status, Instant updatedAt, Instant leaseUntil,
                                         VoucherReversalCommand command, String issue) {
    /** 预备证据只属于同一原件、财务、日期和材料，恢复时不得被替换。 */
    public VoucherReversalPreparation {
        if (input == null || version < 1 || status == null || updatedAt == null || updatedAt.isBefore(input.requestedAt())) throw invalid();
        if (status == Status.RUNNING ? leaseUntil == null || !leaseUntil.isAfter(updatedAt) : leaseUntil != null) throw invalid();
        if ((status == Status.READY || status == Status.AUTHORIZED) != (command != null)) throw invalid();
        if ((status == Status.UNAVAILABLE || status == Status.VOIDED) != (issue != null)) throw invalid();
        if (command != null && (!command.id().equals(input.id()) || !command.source().equals(input.source()) || !command.authorizedBy().equals(input.requestedBy())
                || !command.evidenceReference().equals(input.evidenceReference()) || !command.reason().equals(input.reason())
                || !command.period().request().accountingDate().equals(input.accountingDate()) || command.createdAt().isBefore(input.requestedAt())
                || command.createdAt().isAfter(updatedAt))) throw invalid();
    }
    /** 排队只固定意图，不能在提交事务中调用 ERP。 */
    public static VoucherReversalPreparation queue(Input input) { return new VoucherReversalPreparation(input, 1, Status.QUEUED, input.requestedAt(), null, null, null); }
    /** 领取后只做原件和期间读取，不发送过账命令。 */
    public VoucherReversalPreparation claim(Instant now, Duration lease) {
        requireTime(now); if (status != Status.QUEUED || lease == null || lease.isZero() || lease.isNegative()) throw conflict();
        return new VoucherReversalPreparation(input, version + 1, Status.RUNNING, now, now.plus(lease), null, null);
    }
    /** 结果绑定已保存意图，证据截止时间取原件、期间和本次准备三者最早值。 */
    public VoucherReversalPreparation ready(VoucherObservation original, AccountingPeriodPort.OpenPeriod period, Instant now) {
        requireTime(now); if (status != Status.RUNNING) throw conflict(); if (expired(now)) return fail("TIMEOUT", now);
        Instant expiry = now.plus(VoucherReversalPort.MAX_EVIDENCE_AGE);
        if (original.observedAt().plus(VoucherReversalPort.MAX_EVIDENCE_AGE).isBefore(expiry)) expiry = original.observedAt().plus(VoucherReversalPort.MAX_EVIDENCE_AGE);
        if (period.validUntil().isBefore(expiry)) expiry = period.validUntil();
        var value = new VoucherReversalCommand(input.id(), input.source(), original, period, input.requestedBy(), input.evidenceReference(), input.reason(), now, expiry);
        return new VoucherReversalPreparation(input, version + 1, Status.READY, now, null, value, null);
    }
    /** 人工授权只消费一次原准备，不能重新延长核验时效。 */
    public VoucherReversalPreparation authorize(Instant now) {
        requireTime(now); if (status != Status.READY) throw conflict(); command.requireSendAt(now);
        return new VoucherReversalPreparation(input, version + 1, Status.AUTHORIZED, now, null, command, null);
    }
    /** 查询失败没有财务副作用，新准备由财务明确发起。 */
    public VoucherReversalPreparation fail(String reason, Instant now) {
        requireTime(now); if (!active() || StringUtils.isBlank(reason) || !reason.matches("[A-Z_]{1,64}")) throw conflict();
        return new VoucherReversalPreparation(input, version + 1, Status.UNAVAILABLE, now, null, null, reason);
    }
    /** 原件或人员变化使未授权候选作废，不影响历史过账。 */
    public VoucherReversalPreparation voidSource(Instant now) {
        requireTime(now); if (status == Status.AUTHORIZED) throw conflict();
        return new VoucherReversalPreparation(input, version + 1, Status.VOIDED, now, null, null, "SOURCE_CHANGED");
    }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean expired(Instant now) { return status == Status.RUNNING && !leaseUntil.isAfter(now); }
    private void requireTime(Instant now) { if (now == null || now.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_VOUCHER_REVERSAL_PREPARATION", "Reversal preparation must preserve original source, finance actor, date and authorization evidence"); }
    private static DomainException conflict() { return new DomainException("VOUCHER_REVERSAL_PREPARATION_CONFLICT", "Reversal preparation can no longer perform this transition"); }
    /**
     * 选择日期和说明来自财务，原件、目标及三种版本来自已授权服务器事实。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, long originalVersion, long operationVersion, long applicationVersion, long businessVersion,
                        VoucherReversalPort.Request source, String targetDigest, LocalDate accountingDate, String requestedBy,
                        String evidenceReference, String reason, Instant requestedAt) {
        /** 同一准备固定全部审阅范围，不接受客户端科目或金额。 */
        public Input {
            if (id == null || originalVersion < 1 || operationVersion < originalVersion || applicationVersion < 1 || businessVersion < 1 || source == null
                    || targetDigest == null || !targetDigest.matches("[a-f0-9]{64}") || accountingDate == null || accountingDate.isBefore(source.command().accountingDate())
                    || StringUtils.isBlank(requestedBy) || requestedBy.length() > 128 || !VoucherDisputeResolution.independent(source.command(), requestedBy)
                    || StringUtils.isBlank(evidenceReference) || evidenceReference.length() > 128 || evidenceReference.chars().anyMatch(Character::isISOControl)
                    || StringUtils.isBlank(reason) || reason.length() > 2000 || requestedAt == null || requestedAt.isBefore(source.original().observedAt())) throw invalid();
        }
        @Override public String toString() { return "VoucherReversalPreparationInput[id=" + id + ", originalId=" + source.command().id() + "]"; }
    }
    /**
     * 准备就绪不等于授权，更不等于 ERP 已经执行。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, READY, AUTHORIZED, UNAVAILABLE, VOIDED }
}
