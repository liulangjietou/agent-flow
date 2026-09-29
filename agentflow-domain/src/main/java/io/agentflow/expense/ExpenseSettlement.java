package io.agentflow.expense;

import io.agentflow.approval.model.BusinessReference;
import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import io.agentflow.finance.VoucherPreparation;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 报销核销独立于审批和银行状态；本地资源与预算实际占用确认后才完成结算。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseSettlement(Input input, long version, Status status, boolean resourcesConsumed,
                                UUID budgetOperationId, String issue, Instant createdAt, Instant updatedAt) {
    /** 已核销标记不可由重试清除，预算排队不能冒充实际占用。 */
    public ExpenseSettlement {
        if (input == null || version < 1 || status == null || createdAt == null || updatedAt == null
                || updatedAt.isBefore(createdAt) || createdAt.isBefore(input.fundingConfirmedAt())) throw invalid();
        boolean budgetStage = status == Status.BUDGET_PENDING || status == Status.BUDGET_REJECTED || status == Status.SETTLED;
        if (budgetStage && (!resourcesConsumed || budgetOperationId == null)
                || (status == Status.QUEUED || status == Status.BLOCKED) && budgetOperationId != null
                || !resourcesConsumed && budgetOperationId != null) throw invalid();
        boolean problem = status == Status.BLOCKED || status == Status.BUDGET_REJECTED || status == Status.REVIEW_REQUIRED;
        if (problem ? !validIssue(issue) : issue != null) throw invalid();
    }

    /** 实际成功付款、零应付凭证或全额核减依据明确后只登记待核销工作。 */
    public static ExpenseSettlement queue(Input input, Instant at) {
        return new ExpenseSettlement(input, 1, Status.QUEUED, false, null, null, at, at);
    }

    /** 原资源核销和预算消费命令登记必须与本次转换同事务完成。 */
    public ExpenseSettlement consumed(UUID budgetId, Instant at) {
        requireStatus(Status.QUEUED); Objects.requireNonNull(budgetId);
        return changed(Status.BUDGET_PENDING, true, budgetId, null, at);
    }

    /** 资源或当前凭据不满足时留下可处理原因，原银行到账证据保持。 */
    public ExpenseSettlement block(String code, Instant at) {
        requireStatus(Status.QUEUED); return changed(Status.BLOCKED, resourcesConsumed, null, code, at);
    }

    /** 财务明确重试只重做未完成部分，已核销资源永不重复消耗。 */
    public ExpenseSettlement retry(Instant at) {
        if (status != Status.BLOCKED && status != Status.BUDGET_REJECTED) throw conflict();
        return changed(Status.QUEUED, resourcesConsumed, null, null, at);
    }

    /** 只接受本次原消费命令的终态；争议冻结不会因预算迟到成功自动解除。 */
    public ExpenseSettlement budgetResolved(UUID operationId, boolean applied, String rejectedCode, Instant at) {
        if (!Objects.equals(budgetOperationId, operationId) || !resourcesConsumed) throw conflict();
        if (status == Status.REVIEW_REQUIRED) return this;
        requireStatus(Status.BUDGET_PENDING);
        return changed(applied ? Status.SETTLED : Status.BUDGET_REJECTED, true, operationId, applied ? null : rejectedCode, at);
    }

    /** 退票或凭证/付款冲突保留原核销事实，后续人工裁决不能隐式重开旧资源。 */
    public ExpenseSettlement requireReview(String code, Instant at) {
        if (status == Status.REVIEW_REQUIRED) return this;
        return changed(Status.REVIEW_REQUIRED, resourcesConsumed, budgetOperationId, code, at);
    }

    /** 锁后核对完整业务身份与冻结版本，不能将补正单或另一轮的资源用于本结算。 */
    public void requireReport(ExpenseReport report) {
        var source = input.source(); var round = report.requireFrozenRound();
        if (!source.tenantId().equals(report.tenantId()) || !source.businessId().equals(report.id())
                || !source.applicationId().equals(report.applicationId()) || !source.employeeId().equals(report.employeeId())
                || source.businessVersion() != report.version() || source.roundNo() != round.roundNo()
                || !input.gross().equals(round.approvedGross()) || !input.offsets().equals(round.offsetTotal())) {
            throw new DomainException("EXPENSE_SETTLEMENT_SOURCE_CHANGED", "Settlement must retain its original frozen expense source");
        }
    }

    private ExpenseSettlement changed(Status next, boolean consumed, UUID budget, String code, Instant at) {
        if (at == null || at.isBefore(updatedAt)) throw conflict();
        return new ExpenseSettlement(input, Math.incrementExact(version), next, consumed, budget, code, createdAt, at);
    }
    private void requireStatus(Status expected) { if (status != expected) throw conflict(); }
    private static boolean validIssue(String value) { return value != null && value.matches("[A-Z][A-Z0-9_]{0,63}"); }
    private static boolean validDigest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static boolean reference(String value) { return StringUtils.isNotBlank(value) && value.length() <= 128; }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_SETTLEMENT", "Expense settlement funding and resource lifecycle are inconsistent"); }
    private static DomainException conflict() { return new DomainException("EXPENSE_SETTLEMENT_STATE_CONFLICT", "Expense settlement no longer allows this transition"); }

    /**
     * 归档和付款凭证仍是独立步骤，SETTLED 仅表示本地资源及预算实际占用完成。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { QUEUED, BLOCKED, BUDGET_PENDING, BUDGET_REJECTED, SETTLED, REVIEW_REQUIRED }

    /**
     * 金额和原凭证/付款标识由真实持久事实派生，不接收客户端成功声明。
     * @author owlzhangfq@gmail.com
     */
    public record Input(VoucherPreparation.Source source, Money gross, Money offsets, UUID voucherOperationId,
                        String voucherDigest, Payment payment, Instant fundingConfirmedAt) {
        /** 全额核减不制造零额凭证；全额借款冲销不制造零额付款。 */
        public Input {
            if (source == null || source.businessType() != BusinessReference.Type.EXPENSE || gross == null || offsets == null
                    || fundingConfirmedAt == null || offsets.compareTo(gross) > 0) throw invalid();
            boolean zero = gross.value().signum() == 0;
            if (zero ? voucherOperationId != null || voucherDigest != null : voucherOperationId == null || !validDigest(voucherDigest)) throw invalid();
            var payable = gross.minus(offsets);
            if (payable.value().signum() == 0 ? payment != null : payment == null || !payable.equals(payment.amount())) throw invalid();
            if (payment != null && !fundingConfirmedAt.equals(payment.completedAt())) throw invalid();
        }
        public Money payable() { return gross.minus(offsets); }
    }

    /**
     * 最小到账依据保留原交易及回单，不复制完整收付款账户。
     * @author owlzhangfq@gmail.com
     */
    public record Payment(UUID operationId, String commandDigest, Money amount, String paymentReference, String receiptReference, Instant completedAt) {
        /** 到账金额必须为正，零应付使用无付款的独立路径。 */
        public Payment {
            if (operationId == null || !validDigest(commandDigest) || amount == null || amount.value().signum() <= 0
                    || !reference(paymentReference) || !reference(receiptReference) || completedAt == null) throw invalid();
        }
    }
}
