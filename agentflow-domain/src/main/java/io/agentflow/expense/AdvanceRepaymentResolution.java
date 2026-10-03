package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceRepaymentAdjustmentPort;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 逐笔还款争议的人工决定，真实退回追加独立调整而不删除原收款记录。
 * @author owlzhangfq@gmail.com
 */
public record AdvanceRepaymentResolution(UUID id, String tenantId, UUID checkId, AdvanceRepaymentAdjustmentPort.Receipt receipt,
        String resolvedBy, Instant resolvedAt, String evidenceReference, String reason) {
    /** 财务只能采纳有效完整终态，原申请人不能自行恢复资金使用。 */
    public AdvanceRepaymentResolution {
        if (id == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || checkId == null || receipt == null
                || receipt.status() == AdvanceRepaymentAdjustmentPort.Status.UNRESOLVED || !receipt.matches(receipt.request(), resolvedAt)
                || StringUtils.isBlank(resolvedBy) || resolvedBy.length() > 128 || resolvedBy.equals(receipt.request().original().request().employeeId())
                || StringUtils.isBlank(evidenceReference) || evidenceReference.length() > 128 || evidenceReference.chars().anyMatch(Character::isISOControl)
                || StringUtils.isBlank(reason) || reason.length() > 2000) throw invalid();
        evidenceReference = evidenceReference.trim(); reason = reason.trim();
    }
    /** 决定必须属于原借款内已经计入余额的那笔收款，不能串用另一个员工或法人。 */
    public boolean belongsTo(EmployeeAdvance advance) {
        var request = receipt.request(); var source = request.original().request();
        return tenantId.equals(advance.tenantId()) && source.advanceId().equals(advance.id()) && source.employeeId().equals(advance.employeeId())
                && source.legalEntityId().equals(advance.legalEntityId()) && source.paymentReference().equals(advance.paymentReference())
                && advance.repayments().stream().anyMatch(entry -> entry.id().equals(request.repaymentId()) && entry.receiptReference().equals(source.receiptReference())
                    && entry.amount().equals(request.original().funding().amount()));
    }
    /** 只有真实已退资金才产生借款余额调整；再次确认相同调整不再加回金额。 */
    public ReturnEntry returnEntry() {
        var entries = returnEntries(); if (entries.size() != 1) throw invalid(); return entries.get(0);
    }
    /** 每一笔实际退回有自己的资金和会计身份，同次裁决可以确认多笔新增事实。 */
    public List<ReturnEntry> returnEntries() {
        return receipt.returns().stream().map(value -> new ReturnEntry(id, receipt.request().repaymentId(), value.fundsReturn(), value.posting())).toList();
    }
    @Override public String toString() { return "AdvanceRepaymentResolution[id=" + id + ", repaymentId=" + receipt.request().repaymentId() + "]"; }

    /**
     * 借款聚合保留每笔真实退回首次入账的不可变归属，后续争议决定不覆盖它。
     * @author owlzhangfq@gmail.com
     */
    public record ReturnEntry(UUID resolutionId, UUID repaymentId, AdvanceRepaymentAdjustmentPort.FundsReturn fundsReturn,
                              AdvanceRepaymentAdjustmentPort.ReturnPosting posting) {
        /** 每笔真实退回只登记一次，资金与借方分录金额必须相等。 */
        public ReturnEntry {
            if (resolutionId == null || repaymentId == null || fundsReturn == null || posting == null || !fundsReturn.amount().equals(posting.amount())) throw invalid();
        }
        public Money amount() { return fundsReturn.amount(); }
        public AdvanceRepaymentAdjustmentPort.ReturnItem proof() { return new AdvanceRepaymentAdjustmentPort.ReturnItem(fundsReturn, posting); }
        /** 新观测可以增加版本，已退款流水及已入账分录不能换成另一个。 */
        public boolean matches(AdvanceRepaymentResolution decision) {
            return repaymentId.equals(decision.receipt().request().repaymentId()) && decision.receipt().returns().contains(proof());
        }
    }
    private static DomainException invalid() { return new DomainException("INVALID_REPAYMENT_RESOLUTION", "Repayment decision requires recent original evidence and independent finance review"); }
}
