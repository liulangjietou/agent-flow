package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceRepaymentPort;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.UUID;

/**
 * 财务明确采纳的一笔实际还款，保留外部收款和入账原文，不改写原放款金额。
 * @author owlzhangfq@gmail.com
 */
public record AdvanceRepayment(UUID id, String tenantId, UUID checkId, AdvanceRepaymentPort.Receipt receipt,
                               String recordedBy, Instant recordedAt, String reason) {
    /** 只有独立财务在原观测有效期内才能形成还款事实，不能以手填金额或说明代替原凭据。 */
    public AdvanceRepayment {
        if (id == null || checkId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || receipt == null
                || receipt.status() != AdvanceRepaymentPort.Status.CONFIRMED || !receipt.matches(receipt.request(), recordedAt)
                || StringUtils.isBlank(recordedBy) || recordedBy.length() > 128 || recordedBy.equals(receipt.request().employeeId())
                || StringUtils.isBlank(reason) || reason.length() > 2000) throw invalid();
        reason = reason.trim();
    }
    /** 还款金额永远来自原外部收款，调用方不能另填分配金额。 */
    public Money amount() { return receipt.funding().amount(); }
    /** 同一收款只能冲减同法人本人原借款。 */
    public boolean belongsTo(EmployeeAdvance advance) {
        var source = receipt.request();
        return tenantId.equals(advance.tenantId()) && source.advanceId().equals(advance.id()) && source.legalEntityId().equals(advance.legalEntityId())
                && source.employeeId().equals(advance.employeeId()) && source.paymentReference().equals(advance.paymentReference())
                && source.currency().equals(advance.balance().limit().currency());
    }
    /** 聚合只保留余额计算及防重所需归属，完整凭据由独立只追加仓储保存。 */
    public Entry entry() { return new Entry(id, receipt.request().receiptReference(), amount()); }
    /** 日志不展开还款或审核说明。 */
    @Override public String toString() { return "AdvanceRepayment[id=" + id + ", advanceId=" + receipt.request().advanceId() + "]"; }
    /**
     * 已入账还款在余额中的不可变归属，不能伪装成报销冲销。
     * @author owlzhangfq@gmail.com
     */
    public record Entry(UUID id, String receiptReference, Money amount) {
        /** 只有正金额和明确外部凭据才进入已还款合计。 */
        public Entry {
            if (id == null || StringUtils.isBlank(receiptReference) || receiptReference.length() > 128 || amount == null || amount.value().signum() <= 0) throw invalid();
        }
    }
    private static DomainException invalid() { return new DomainException("INVALID_ADVANCE_REPAYMENT", "A verified repayment must retain the original receipt and an independent finance actor"); }
}
