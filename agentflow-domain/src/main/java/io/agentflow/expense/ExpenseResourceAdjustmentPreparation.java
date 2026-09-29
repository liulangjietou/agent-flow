package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AccountingPeriodPort;
import io.agentflow.finance.BudgetConsumptionReversalCommand;
import io.agentflow.finance.BudgetConsumptionReversalOperation;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 独立资源调整的持久只读准备；会计期间就绪后仍须原发起财务明确授权。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseResourceAdjustmentPreparation(Input input, long version, Status status, Instant updatedAt, Instant leaseUntil,
        AccountingPeriodPort.OpenPeriod period, BudgetConsumptionReversalCommand command, String issue) {
    /** 候选绑定全部原财务事实，明确授权前不产生预算写命令。 */
    public ExpenseResourceAdjustmentPreparation {
        if (input == null || version < 1 || status == null || updatedAt == null || updatedAt.isBefore(input.requestedAt())) throw invalid();
        if (status == Status.RUNNING ? leaseUntil == null || !leaseUntil.isAfter(updatedAt) : leaseUntil != null) throw invalid();
        if ((status == Status.READY || status == Status.AUTHORIZED) != (period != null) || (status == Status.AUTHORIZED) != (command != null)
                || (status == Status.UNAVAILABLE || status == Status.VOIDED) != (issue != null)) throw invalid();
        if (issue != null && !issue.matches("[A-Z][A-Z0-9_]{0,63}")
                || period != null && (!period.matches(input.periodRequest(), updatedAt) || !updatedAt.isBefore(evidenceExpiry(period)))) throw invalid();
        if (command != null && (!command.id().equals(input.id()) || !command.adjustmentId().equals(input.id()) || !command.period().equals(period)
                || !command.source().equals(input.basis().consumption().input().command()) || !command.consumed().equals(input.basis().consumption().observation())
                || !command.authorizedBy().equals(input.requestedBy()) || !command.evidenceReference().equals(input.evidenceReference()) || !command.reason().equals(input.reason())
                || !command.createdAt().equals(updatedAt) || command.expiresAt().isAfter(evidenceExpiry(period)))) throw invalid();
    }
    /** 排队固定意图，网络读取由事务外工作器执行。 */
    public static ExpenseResourceAdjustmentPreparation queue(Input input) {
        return new ExpenseResourceAdjustmentPreparation(input, 1, Status.QUEUED, input.requestedAt(), null, null, null, null);
    }
    /** 领取只允许读取期间，不能直接发送预算冲正。 */
    public ExpenseResourceAdjustmentPreparation claim(Instant at, Duration lease) {
        requireTime(at); if (status != Status.QUEUED || lease == null || lease.isNegative() || lease.isZero()) throw conflict();
        return new ExpenseResourceAdjustmentPreparation(input, version + 1, Status.RUNNING, at, at.plus(lease), null, null, null);
    }
    /** 完成时保留真实期间，不延长外部证据有效期；迟到任务只留下超时。 */
    public ExpenseResourceAdjustmentPreparation ready(AccountingPeriodPort.OpenPeriod value, Instant at) {
        requireTime(at); if (status != Status.RUNNING) throw conflict(); if (expired(at)) return fail("TIMEOUT", at);
        if (value == null || !value.matches(input.periodRequest(), at) || !at.isBefore(evidenceExpiry(value))) throw invalid();
        return new ExpenseResourceAdjustmentPreparation(input, version + 1, Status.READY, at, null, value, null, null);
    }
    /** 授权时才固定写命令时刻，不能使用重新排队延长原期间证据。 */
    public ExpenseResourceAdjustmentPreparation authorize(Instant at) {
        requireTime(at); if (!usable(at)) throw conflict(); var original = input.basis().consumption();
        var value = new BudgetConsumptionReversalCommand(input.id(), input.id(), original.input().command(), original.observation(), period,
                input.requestedBy(), input.evidenceReference(), input.reason(), at, evidenceExpiry(period));
        return new ExpenseResourceAdjustmentPreparation(input, version + 1, Status.AUTHORIZED, at, null, period, value, null);
    }
    /** 将已授权准备转换为独立调整输入，仓储仍需核对消费后的准备修订。 */
    public ExpenseResourceAdjustment.Input authorizedInput() {
        if (status != Status.AUTHORIZED) throw conflict(); var original = input.basis().consumption();
        return new ExpenseResourceAdjustment.Input(input.basis(), new BudgetConsumptionReversalOperation.Input(original.version(), command, original.input().targetDigest()));
    }
    /** 网络不可用与明确业务拒绝只记录分类，不产生预算或资源副作用。 */
    public ExpenseResourceAdjustmentPreparation fail(String code, Instant at) {
        requireTime(at); if (!active() || code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) throw conflict();
        return new ExpenseResourceAdjustmentPreparation(input, version + 1, Status.UNAVAILABLE, at, null, null, null, code);
    }
    /** 未授权时来源或财务资格变化使候选失效，已消费准备不可回退。 */
    public ExpenseResourceAdjustmentPreparation voidSource(Instant at) {
        requireTime(at); if (status == Status.AUTHORIZED) throw conflict();
        return new ExpenseResourceAdjustmentPreparation(input, version + 1, Status.VOIDED, at, null, null, null, "SOURCE_CHANGED");
    }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean expired(Instant at) { return status == Status.RUNNING && !leaseUntil.isAfter(at); }
    /** 界面展示与授权入口共享排他到期边界。 */
    public boolean usable(Instant at) { return status == Status.READY && at != null && !at.isBefore(updatedAt) && at.isBefore(evidenceExpiry(period)); }
    private static Instant evidenceExpiry(AccountingPeriodPort.OpenPeriod period) {
        var age = period.observedAt().plus(BudgetConsumptionReversalCommand.MAX_AUTHORIZATION_AGE); return age.isBefore(period.validUntil()) ? age : period.validUntil();
    }
    private void requireTime(Instant at) { if (at == null || at.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_ADJUSTMENT_PREPARATION", "Adjustment preparation must preserve original finance facts and a fresh selected accounting period"); }
    private static DomainException conflict() { return new DomainException("EXPENSE_ADJUSTMENT_PREPARATION_CONFLICT", "Adjustment preparation state or evidence has changed"); }

    /**
     * 人工只选择日期与说明，金额、分摊、身份和外部目标均固定到实际来源。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, ExpenseResourceAdjustmentBasis basis, LocalDate accountingDate, String requestedBy,
            String evidenceReference, String reason, Instant requestedAt) {
        /** 发起准备已要求独立财务身份，公开入口另核对当前角色和完整原字段权限。 */
        public Input {
            if (id == null || basis == null || accountingDate == null || accountingDate.isBefore(basis.consumption().input().command().position().accountingDate())
                    || !text(requestedBy, 128) || !text(evidenceReference, 128) || !text(reason, 2000)) throw invalid();
            basis.requireAuthorization(requestedBy, requestedAt);
        }
        /** 只读取本次选择的法人、币种与日期，不接受网关默认日期。 */
        public AccountingPeriodPort.Request periodRequest() {
            return new AccountingPeriodPort.Request(basis.legalEntityId(), basis.settlement().input().gross().currency(), accountingDate);
        }
        private static boolean text(String value, int max) { return StringUtils.isNotBlank(value) && value.equals(value.trim()) && value.length() <= max && value.chars().noneMatch(Character::isISOControl); }
        @Override public String toString() { return "ExpenseResourceAdjustmentPreparationInput[id=" + id + ", reportId=" + basis.reportId() + "]"; }
    }
    /**
     * 准备只读；授权才允许登记独立预算写命令。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, READY, AUTHORIZED, UNAVAILABLE, VOIDED }
}
