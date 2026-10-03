package io.agentflow.expense;

import io.agentflow.common.DomainException;
import io.agentflow.finance.AdvanceDisbursementReturnPort;
import io.agentflow.finance.Money;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 财务明确采纳的原放款核对决定，独立追加真实退回而不改写原付款。
 * @author owlzhangfq@gmail.com
 */
public record AdvanceDisbursementReturn(UUID id, String tenantId, UUID checkId, AdvanceDisbursementReturnPort.Receipt receipt,
        String resolvedBy, Instant resolvedAt, String evidenceReference, String reason) {
    /** 申请人和原出纳不能确认，材料说明也不能代替完整资金与会计证据。 */
    public AdvanceDisbursementReturn {
        if (id == null || checkId == null || StringUtils.isBlank(tenantId) || tenantId.length() > 64 || receipt == null
                || !tenantId.equals(receipt.request().command().tenantId()) || receipt.status() == AdvanceDisbursementReturnPort.Status.UNRESOLVED
                || !receipt.matches(receipt.request(), resolvedAt) || StringUtils.isBlank(resolvedBy) || resolvedBy.length() > 128
                || resolvedBy.equals(receipt.request().command().payee().employeeId()) || resolvedBy.equals(receipt.request().command().authorization().executedBy())
                || StringUtils.isBlank(evidenceReference) || evidenceReference.length() > 128 || evidenceReference.chars().anyMatch(Character::isISOControl)
                || StringUtils.isBlank(reason) || reason.length() > 2000) throw invalid();
        evidenceReference = evidenceReference.trim(); reason = reason.trim();
    }
    /** 归属只比较原放款事实，不追随当前账户或已用余额变化。 */
    public boolean belongsTo(EmployeeAdvance advance) {
        var command = receipt.request().command(); var original = receipt.request().original();
        return tenantId.equals(advance.tenantId()) && command.binding().businessId().equals(advance.id())
                && command.payee().legalEntityId().equals(advance.legalEntityId()) && command.payee().employeeId().equals(advance.employeeId())
                && original.paymentReference().equals(advance.paymentReference()) && command.amount().equals(advance.balance().limit());
    }
    /** 已采纳条目保留自己的首次决定，新累计决定不能更换旧归属。 */
    public List<Entry> entries() { return receipt.returns().stream().map(item -> new Entry(id, item)).toList(); }
    @Override public String toString() { return "AdvanceDisbursementReturn[id=" + id + ", paymentId=" + receipt.request().command().id() + "]"; }

    /**
     * 借款余额中的只追加原放款退回，和主动还款及还款退回分别记录。
     * @author owlzhangfq@gmail.com
     */
    public record Entry(UUID resolutionId, AdvanceDisbursementReturnPort.ReturnItem proof) {
        /** 每笔实际退回必须归属一个独立财务决定。 */
        public Entry { if (resolutionId == null || proof == null) throw invalid(); }
        public Money amount() { return proof.funding().amount(); }
    }
    private static DomainException invalid() { return new DomainException("INVALID_DISBURSEMENT_RETURN", "A disbursement adjustment requires fresh original evidence and an independent finance decision"); }
}
