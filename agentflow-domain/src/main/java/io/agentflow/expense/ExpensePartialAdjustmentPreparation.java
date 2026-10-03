package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 单侧准备固定明确意图和实际原件；另一侧正常推进不使准备失效，授权后证据只能消费一次。
 * @author owlzhangfq@gmail.com
 */
public record ExpensePartialAdjustmentPreparation(Input input, long version, Status status, Instant updatedAt,
        Instant leaseUntil, ExpenseAdjustmentFundingSource readSource, Evidence evidence, Long authorizedVersion, String issue) {
    /** 历史快照以当时保存时间验证，不能由当前时间改写已授权命令或有效期。 */
    public ExpensePartialAdjustmentPreparation {
        if (input == null || version < 1 || status == null || updatedAt == null || updatedAt.isBefore(input.requestedAt())
                || (status == Status.RUNNING) != (leaseUntil != null) || leaseUntil != null && !leaseUntil.isAfter(updatedAt)
                || (status == Status.READY || status == Status.AUTHORIZED) != (evidence != null)
                || (status == Status.AUTHORIZED) != (authorizedVersion != null)
                || authorizedVersion != null && authorizedVersion <= input.adjustment().version()
                || (status == Status.UNAVAILABLE || status == Status.VOIDED) != (issue != null)
                || issue != null && !issue.matches("[A-Z][A-Z0-9_]{0,63}")) throw invalid();
        if (status == Status.QUEUED && readSource != null || (status == Status.RUNNING || evidence != null) && readSource == null) throw invalid();
        if (readSource != null) input.adjustment().input().basis().funding().requireContinuation(readSource);
        if (evidence != null) { readSource.requireContinuation(evidence.source()); evidence.requireFor(input, updatedAt); }
    }

    /** 只排队读取原件和期间，不创建财务写操作。 */
    public static ExpensePartialAdjustmentPreparation queue(Input input) {
        return new ExpensePartialAdjustmentPreparation(input, 1, Status.QUEUED, input.requestedAt(), null, null, null, null, null);
    }

    /** 领取固定实际持久来源，后续读取不能覆盖领取后产生的更新事实。 */
    public ExpensePartialAdjustmentPreparation claim(ExpenseAdjustmentFundingSource source, Instant at, Duration lease) {
        requireTime(at);
        if (status != Status.QUEUED || source == null || lease == null || lease.isNegative() || lease.isZero()) throw conflict();
        return new ExpensePartialAdjustmentPreparation(input, Math.incrementExact(version), Status.RUNNING, at, at.plus(lease), source, null, null, null);
    }

    /** 原件和期间同时就绪仍只供人工确认，租约迟到不能生成新授权。 */
    public ExpensePartialAdjustmentPreparation ready(Evidence value, Instant at) {
        requireTime(at); if (status != Status.RUNNING) throw conflict(); if (expired(at)) return fail("TIMEOUT", at);
        return new ExpensePartialAdjustmentPreparation(input, Math.incrementExact(version), Status.READY, at, null, readSource, value, null, null);
    }

    /** 独立财务明确采用本人准备，当前原件和所选侧必须仍符合原意图。 */
    public ExpensePartialAdjustmentPreparation authorize(ExpensePartialAdjustment current, Instant at) {
        requireTime(at); requireCurrent(current); if (!usable(at)) throw conflict();
        var authorized = new ExpensePartialAdjustmentPreparation(input, Math.incrementExact(version), Status.AUTHORIZED, at, null,
                readSource, evidence, Math.incrementExact(current.version()), null);
        authorized.authorizedAdjustment(current); return authorized;
    }

    /** 从同一份实际证据派生固定命令，消费证明与根聚合授权须由仓储同事务保存。 */
    public ExpensePartialAdjustment authorizedAdjustment(ExpensePartialAdjustment before) {
        if (status != Status.AUTHORIZED || before.version() + 1 != authorizedVersion) throw conflict(); requireCurrent(before);
        var financial = evidence.source().financial(); var previous = before.input().basis().previous(); var expires = evidence.expiresAt();
        if (input.side() == Side.BUDGET) {
            var command = BudgetConsumptionReductionCommand.forExpense(input.id(), before.id(), financial, previous == null ? null : previous.budget(),
                    evidence.period(), input.requestedBy(), input.evidenceReference(), input.reason(), updatedAt, expires);
            var value = BudgetConsumptionReductionOperation.queue(new BudgetConsumptionReductionOperation.Input(financial.consumption().version(), command,
                    financial.consumption().input().targetDigest()), updatedAt);
            return before.authorizeBudget(value, updatedAt);
        }
        var command = ExpenseAccrualReductionCommand.forExpense(input.id(), before.id(), financial, previous == null ? null : previous.accrual(),
                evidence.period(), input.requestedBy(), input.evidenceReference(), input.reason(), updatedAt, expires);
        var value = ExpenseAccrualReductionOperation.queue(new ExpenseAccrualReductionOperation.Input(financial.accrual().version(), command,
                financial.accrual().input().targetDigest()), updatedAt);
        return before.authorizeAccrual(value, updatedAt);
    }

    /** 比较所选侧原状态，另一侧成功或执行中允许继续独立准备，整体复核或结束禁止新授权。 */
    public void requireCurrent(ExpensePartialAdjustment current) {
        if (current == null || !input.adjustment().input().equals(current.input()) || current.version() < input.adjustment().version()
                || current.issue() != null || current.retirement() != null || current.completion() != null
                || input.side() == Side.BUDGET && !Objects.equals(current.budget(), input.adjustment().budget())
                || input.side() == Side.ACCRUAL && !Objects.equals(current.accrual(), input.adjustment().accrual())) throw conflict();
    }

    /** 读取故障只保留稳定分类，财务可新建准备，不能直接延长旧证据。 */
    public ExpensePartialAdjustmentPreparation fail(String code, Instant at) {
        requireTime(at); if (!active()) throw conflict();
        return new ExpensePartialAdjustmentPreparation(input, Math.incrementExact(version), Status.UNAVAILABLE, at, null, readSource, null, null, code);
    }

    /** 来源或人员变化停用未消费准备，已授权结果不回退。 */
    public ExpensePartialAdjustmentPreparation voidSource(Instant at) {
        requireTime(at); if (status == Status.AUTHORIZED || status == Status.VOIDED) throw conflict();
        return new ExpensePartialAdjustmentPreparation(input, Math.incrementExact(version), Status.VOIDED, at, null, readSource, null, null, "SOURCE_CHANGED");
    }
    public boolean active() { return status == Status.QUEUED || status == Status.RUNNING; }
    public boolean expired(Instant at) { return status == Status.RUNNING && !leaseUntil.isAfter(at); }
    /** 到期边界排他，页面与授权使用相同结果。 */
    public boolean usable(Instant at) { return status == Status.READY && at != null && !at.isBefore(updatedAt) && at.isBefore(evidence.expiresAt()); }
    private void requireTime(Instant at) { if (at == null || at.isBefore(updatedAt)) throw conflict(); }
    private static DomainException invalid() { return new DomainException("INVALID_PARTIAL_ADJUSTMENT_PREPARATION", "Partial preparation must preserve one eligible side and fresh actual finance evidence"); }
    private static DomainException conflict() { return new DomainException("PARTIAL_ADJUSTMENT_PREPARATION_CONFLICT", "Partial preparation or the selected original operation changed"); }

    /**
     * 页面选择日期、侧别与说明，完整调整从当前持久修订恢复。
     * @author owlzhangfq@gmail.com
     */
    public record Input(UUID id, ExpensePartialAdjustment adjustment, Side side, LocalDate accountingDate, String requestedBy,
            String evidenceReference, String reason, Instant requestedAt) {
        /** 成功或未知操作不能被新准备替换，旧队列须先明确停止。 */
        public Input {
            if (id == null || adjustment == null || side == null || accountingDate == null || requestedAt == null || id.equals(adjustment.id())
                    || !text(requestedBy, 128) || !text(evidenceReference, 128) || !text(reason, 2000)
                    || requestedAt.isBefore(adjustment.updatedAt()) || adjustment.issue() != null || adjustment.retirement() != null || adjustment.completion() != null
                    || side == Side.BUDGET && adjustment.budget() != null && (!adjustment.budget().safelyUnexecuted() || adjustment.budget().status() == BudgetConsumptionReductionOperation.Status.QUEUED)
                    || side == Side.ACCRUAL && adjustment.accrual() != null && (adjustment.accrual().retirementBasis() == null || adjustment.accrual().status() == ExpenseAccrualReductionOperation.Status.QUEUED)) throw invalid();
            var basis = adjustment.input().basis(); basis.funding().requireAuthorization(requestedBy, requestedAt);
            var original = basis.funding().financial().accrual().input().command();
            if (accountingDate.isBefore(original.accountingDate()) || basis.previous() != null && accountingDate.isBefore(side == Side.BUDGET
                    ? basis.previous().budget().posting().accountingDate() : basis.previous().accrual().posting().voucher().accountingDate())) throw invalid();
        }
        /** 法人与币种从原报销派生，不接受页面另传财务目的地。 */
        public AccountingPeriodPort.Request periodRequest() {
            var command = adjustment.input().basis().funding().financial().accrual().input().command();
            return new AccountingPeriodPort.Request(command.legalEntityId(), command.totals().gross().currency(), accountingDate);
        }
        private static boolean text(String value, int length) { return StringUtils.isNotBlank(value) && value.equals(value.trim()) && value.length() <= length && value.chars().noneMatch(Character::isISOControl); }
        @Override public String toString() { return "PartialAdjustmentPreparationInput[id=" + id + ", adjustmentId=" + adjustment.id() + ", side=" + side + "]"; }
    }

    /**
     * 新鲜证据保存真实已接受原修订、原完整回款及当前期间，不直接代表外部差额已执行。
     * @author owlzhangfq@gmail.com
     */
    public record Evidence(ExpenseAdjustmentFundingSource source, ExpensePaymentReturnPort.Receipt bank, AccountingPeriodPort.OpenPeriod period, Instant checkedAt) {
        /** 没有原银行付款的报销不得制造银行证据；原付款和回款身份逐项一致。 */
        public Evidence {
            if (source == null || period == null || checkedAt == null || !period.matches(period.request(), checkedAt)) throw invalid();
            if (source.payment() == null ? bank != null : bank == null || !bank.matches(source.returns().request(), checkedAt)
                    || !bank.sameReturns(source.latestRegistration().receipt()) || bank.revision() < source.latestRegistration().receipt().revision()
                    || bank.observedAt().isBefore(source.latestRegistration().receipt().observedAt()) || !bank.current().equals(source.payment().observation())) throw invalid();
            if (source.financial().accrual().observation().observedAt().isAfter(checkedAt)
                    || source.paymentVoucher() != null && source.paymentVoucher().observation().observedAt().isAfter(checkedAt)) throw invalid();
        }
        /** 所有原件的最早到期共同限制授权，不能通过新请求延长已经读取的证据。 */
        public Instant expiresAt() {
            var expiry = period.observedAt().plus(BudgetConsumptionReductionCommand.MAX_AUTHORIZATION_AGE);
            if (period.validUntil().isBefore(expiry)) expiry = period.validUntil();
            var accrual = source.financial().accrual().observation().observedAt().plus(VoucherReversalPort.MAX_EVIDENCE_AGE);
            if (accrual.isBefore(expiry)) expiry = accrual;
            if (bank != null && bank.validUntil().isBefore(expiry)) expiry = bank.validUntil();
            if (source.paymentVoucher() != null) {
                var payment = source.paymentVoucher().observation().observedAt().plus(VoucherReversalPort.MAX_EVIDENCE_AGE); if (payment.isBefore(expiry)) expiry = payment;
            }
            return expiry;
        }
        /** 原意图和新鲜窗口同时成立，才允许形成展示或授权结果。 */
        public void requireFor(Input input, Instant at) {
            input.adjustment().input().basis().funding().requireContinuation(source);
            if (at == null || at.isBefore(checkedAt) || !at.isBefore(expiresAt()) || !period.matches(input.periodRequest(), at)) throw invalid();
            source.requireAuthorization(input.requestedBy(), at);
        }
        @Override public String toString() { return "PartialAdjustmentPreparationEvidence[checkedAt=" + checkedAt + "]"; }
    }
    /**
     * 每次明确确认仅授权其中一侧，另一侧可按自己的期间和新鲜证据办理。
     * @author owlzhangfq@gmail.com
     */
    public enum Side { BUDGET, ACCRUAL }
    /**
     * 只读准备成功与人工消费分开，未知读取不形成写命令。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, RUNNING, READY, AUTHORIZED, UNAVAILABLE, VOIDED }
}
