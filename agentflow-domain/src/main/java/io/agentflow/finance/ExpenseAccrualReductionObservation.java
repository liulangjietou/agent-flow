package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.UUID;

/**
 * 每次挂账减额有独立受理修订和累计调整版本，原挂账继续保留原过账事实。
 * @author owlzhangfq@gmail.com
 */
public record ExpenseAccrualReductionObservation(UUID operationId, UUID adjustmentId, String commandDigest, Status status,
        long revision, Instant observedAt, String acceptanceReference, Posting posting, Rejection rejection) {
    /** 查无没有受理事实；完成须同时提供原挂账、完整剩余额摘要和本次实际反向凭证。 */
    public ExpenseAccrualReductionObservation {
        if (operationId == null || adjustmentId == null || commandDigest == null || !commandDigest.matches("[a-f0-9]{64}")
                || status == null || observedAt == null || revision < 0) throw invalid();
        if (status == Status.NOT_FOUND) {
            if (revision != 0 || acceptanceReference != null || posting != null || rejection != null) throw invalid();
        } else if (revision == 0 || !ExpenseAccrualReductionCommand.text(acceptanceReference, 128)
                || (status == Status.POSTED) != (posting != null) || (status == Status.FAILED) != (rejection != null)
                || posting != null && (posting.original().observedAt().isAfter(observedAt) || posting.voucher().postedAt().isAfter(observedAt))) throw invalid();
    }

    /** 结果未知只查询原号；实际过账还须逐项符合原指令，不能将借贷平衡当作成功。 */
    public boolean matches(ExpenseAccrualReductionCommand command, boolean queried, Instant now) {
        return command != null && now != null && operationId.equals(command.id()) && adjustmentId.equals(command.adjustmentId())
                && commandDigest.equals(command.digest()) && !observedAt.isBefore(command.createdAt()) && !observedAt.isAfter(now)
                && (queried || status != Status.NOT_FOUND) && (posting == null || command.matchesPosting(posting));
    }

    /**
     * 原凭证保持有效过账；新凭证保存全部实际差额分录，前后完整位置摘要用于累计对账。
     * @author owlzhangfq@gmail.com
     */
    public record Posting(VoucherObservation original, long adjustmentRevision, String beforeDigest,
            String afterDigest, VoucherReversalPort.Posting voucher) {
        /** 不允许用原凭证编号、已冲销原件或未改变的净额摘要冒充独立调整。 */
        public Posting {
            if (original == null || original.status() != VoucherObservation.Status.POSTED || adjustmentRevision < 1 || voucher == null
                    || beforeDigest == null || !beforeDigest.matches("[a-f0-9]{64}")
                    || afterDigest == null || !afterDigest.matches("[a-f0-9]{64}") || beforeDigest.equals(afterDigest)
                    || voucher.postingReference().equals(original.postingReference()) || voucher.voucherReference().equals(original.voucherReference())
                    || voucher.accountingDate().isBefore(original.accountingDate()) || voucher.postedAt().isBefore(original.postedAt())
                    || original.observedAt().isBefore(voucher.postedAt())) throw invalid();
        }
        @Override public String toString() { return "ExpenseAccrualReductionPosting[originalId=" + original.operationId() + ", adjustmentRevision=" + adjustmentRevision + "]"; }
    }

    /**
     * 单次调整的权威状态；累计版本只在真实过账后增加。
     * @author owlzhangfq@gmail.com
     */
    public enum Status { NOT_FOUND, PENDING, POSTED, FAILED }

    /**
     * ERP 原子过账前的明确拒绝；原件或累计版本冲突不能自动换号覆盖。
     * @author owlzhangfq@gmail.com
     */
    public enum Rejection { ACCOUNTING_PERIOD_CLOSED, ORIGINAL_NOT_POSTED, ORIGINAL_CHANGED, ADJUSTMENT_VERSION_CONFLICT,
        LEGAL_ENTITY_UNAVAILABLE, ACCOUNT_UNAVAILABLE, AUTHORIZATION_REJECTED }

    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_ACCRUAL_REDUCTION_OBSERVATION", "Expense accrual reduction requires independent posting and exact original remaining positions"); }
    @Override public String toString() { return "ExpenseAccrualReductionObservation[operationId=" + operationId + ", status=" + status + ", revision=" + revision + "]"; }
}
