package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 读取原还款当前状态及真实退回调整依据，不发起退款或替 ERP 制证。
 * @author owlzhangfq@gmail.com
 */
public interface AdvanceRepaymentAdjustmentPort {
    /** 原收款、退款流水与借款借方分录由同一固定财务目标核对。 */
    FinanceResult<Receipt> query(String tenantId, String targetDigest, Request request);

    /**
     * 只能核对已在本地确认的原还款，身份和金额不由操作页面提供。
     * @author owlzhangfq@gmail.com
     */
    record Request(UUID repaymentId, AdvanceRepaymentPort.Receipt original) {
        /** 原始已入账事实不因查询时效过去而被改写或丢弃。 */
        public Request { if (repaymentId == null || original == null || original.status() != AdvanceRepaymentPort.Status.CONFIRMED) throw invalid(); }
        @Override public String toString() { return "RepaymentAdjustmentRequest[repaymentId=" + repaymentId + "]"; }
    }

    /**
     * 汇总版本覆盖原还款与退款、会计调整；来源自己的修订仍独立保留。
     * @author owlzhangfq@gmail.com
     */
    record Receipt(Request request, Status status, long revision, Instant observedAt, Instant validUntil,
                   AdvanceRepaymentPort.Receipt current, FundsReturn fundsReturn, ReturnPosting posting) {
        /** 两种可确认结论均需要近期原收款事实；真实退回还需独立资金与会计依据。 */
        public Receipt {
            if (request == null || status == null || revision < 1 || observedAt == null || validUntil == null || !validUntil.isAfter(observedAt)
                    || validUntil.isAfter(observedAt.plus(AdvanceRepaymentPort.MAX_EVIDENCE_AGE))) throw invalid();
            if (current != null && (!current.request().equals(request.original().request()) || !current.matches(current.request(), observedAt)
                    || validUntil.isAfter(current.validUntil()))) throw invalid();
            if (status == Status.UNRESOLVED) {
                if (fundsReturn != null || posting != null) throw invalid();
            } else {
                if (current == null || !current.sameSettlement(request.original()) || current.revision() < request.original().revision()) throw invalid();
                if (status == Status.CONFIRMED) {
                    if (current.status() != AdvanceRepaymentPort.Status.CONFIRMED || fundsReturn != null || posting != null) throw invalid();
                } else {
                    var original = request.original();
                    if (current.status() != AdvanceRepaymentPort.Status.REVERSED || fundsReturn == null || posting == null
                            || !fundsReturn.amount().equals(original.funding().amount()) || !posting.amount().equals(fundsReturn.amount())
                            || fundsReturn.returnedAt().isBefore(original.funding().receivedAt()) || posting.postedAt().isBefore(fundsReturn.returnedAt())
                            || posting.postedAt().isBefore(original.posting().postedAt()) || posting.postedAt().isAfter(observedAt)
                            || fundsReturn.channel() == original.funding().channel() && fundsReturn.transactionReference().equals(original.funding().transactionReference())
                            || posting.voucherReference().equals(original.posting().voucherReference()) && posting.entryReference().equals(original.posting().entryReference())) throw invalid();
                }
            }
        }
        /** 当前证据只绑定原查询，并在最早到期时间前消费。 */
        public boolean matches(Request expected, Instant now) { return request.equals(expected) && now != null && !now.isBefore(observedAt) && now.isBefore(validUntil); }
        /** 完整退款事实不可借新查询替换，观察时间和版本变化不改变已执行的交易。 */
        public boolean sameReturn(Receipt other) {
            return other != null && status == Status.RETURNED && other.status == Status.RETURNED && request.equals(other.request)
                    && current.sameSettlement(other.current) && fundsReturn.equals(other.fundsReturn) && posting.equals(other.posting);
        }
        @Override public String toString() { return "RepaymentAdjustmentReceipt[repaymentId=" + request.repaymentId() + ", status=" + status + ", revision=" + revision + "]"; }
    }

    /**
     * 全额原还款退回的已执行资金流水，不能以原收款流水冒充退款。
     * @author owlzhangfq@gmail.com
     */
    record FundsReturn(AdvanceRepaymentPort.Channel channel, String transactionReference, Money amount, Instant returnedAt) {
        /** 资金方向由本契约固定为退回原还款，不允许客户端选择收付款方向。 */
        public FundsReturn { if (channel == null || !reference(transactionReference) || amount == null || amount.value().signum() <= 0 || returnedAt == null) throw invalid(); }
    }
    /**
     * ERP 已过账的员工借款借方调整分录，增加同一员工对同一借款的未还金额。
     * @author owlzhangfq@gmail.com
     */
    record ReturnPosting(String voucherReference, String entryReference, Money amount, LocalDate accountingDate, Instant postedAt) {
        /** 明确独立凭证分录及正金额，不以原贷方还款分录代替借方调整。 */
        public ReturnPosting { if (!reference(voucherReference) || !reference(entryReference) || amount == null || amount.value().signum() <= 0 || accountingDate == null || postedAt == null) throw invalid(); }
    }
    /**
     * 原还款仍有效时只解除其争议；真实退回另行增加未还款，未核清保持原账。
     * @author owlzhangfq@gmail.com
     */
    enum Status { UNRESOLVED, CONFIRMED, RETURNED }

    private static boolean reference(String value) { return StringUtils.isNotBlank(value) && value.length() <= 128 && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl); }
    private static DomainException invalid() { return new DomainException("INVALID_REPAYMENT_ADJUSTMENT_RECEIPT", "Repayment adjustment requires matching original receipt, actual returned funds and posted receivable debit"); }
}
