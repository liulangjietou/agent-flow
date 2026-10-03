package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 读取原还款当前状态及真实退回调整依据，不发起退款或替 ERP 制证。
 * @author owlzhangfq@gmail.com
 */
public interface AdvanceRepaymentAdjustmentPort {
    int MAX_RETURN_ENTRIES = 100;
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
                   AdvanceRepaymentPort.Receipt current, FundsReturn fundsReturn, ReturnPosting posting, List<ReturnItem> additionalReturns) {
        /** 兼容已保存的单笔全额退回，后续退回通过独立条目追加。 */
        public Receipt(Request request, Status status, long revision, Instant observedAt, Instant validUntil,
                AdvanceRepaymentPort.Receipt current, FundsReturn fundsReturn, ReturnPosting posting) {
            this(request, status, revision, observedAt, validUntil, current, fundsReturn, posting, List.of());
        }
        /** 每笔退回都须有实际资金和独立借方分录，累计不得超过原还款。 */
        public Receipt {
            additionalReturns = additionalReturns == null ? List.of() : List.copyOf(additionalReturns);
            if (request == null || status == null || revision < 1 || observedAt == null || validUntil == null || !validUntil.isAfter(observedAt)
                    || validUntil.isAfter(observedAt.plus(AdvanceRepaymentPort.MAX_EVIDENCE_AGE)) || additionalReturns.size() >= MAX_RETURN_ENTRIES) throw invalid();
            if (current != null && (!current.request().equals(request.original().request()) || !current.matches(current.request(), observedAt)
                    || validUntil.isAfter(current.validUntil()))) throw invalid();
            if (status == Status.UNRESOLVED) {
                if (fundsReturn != null || posting != null || !additionalReturns.isEmpty()) throw invalid();
            } else {
                if (current == null || !current.sameSettlement(request.original()) || current.revision() < request.original().revision()) throw invalid();
                if (status == Status.CONFIRMED) {
                    if (current.status() != AdvanceRepaymentPort.Status.CONFIRMED || fundsReturn != null || posting != null || !additionalReturns.isEmpty()) throw invalid();
                } else {
                    var original = request.original();
                    var entries = entries(fundsReturn, posting, additionalReturns);
                    if (entries.isEmpty()) throw invalid();
                    var total = Money.zero(original.funding().amount().currency());
                    for (var entry : entries) {
                        var funds = entry.fundsReturn(); var debit = entry.posting();
                        if (!funds.amount().currency().equals(total.currency()) || funds.returnedAt().isBefore(original.funding().receivedAt())
                                || debit.postedAt().isBefore(original.posting().postedAt()) || debit.postedAt().isAfter(observedAt)
                                || funds.channel() == original.funding().channel() && funds.transactionReference().equals(original.funding().transactionReference())
                                || debit.voucherReference().equals(original.posting().voucherReference()) && debit.entryReference().equals(original.posting().entryReference())) throw invalid();
                        total = total.plus(entry.fundsReturn().amount());
                    }
                    if (entries.stream().map(ReturnItem::fundsIdentity).distinct().count() != entries.size()
                            || entries.stream().map(ReturnItem::postingIdentity).distinct().count() != entries.size()) throw invalid();
                    int compared = total.compareTo(original.funding().amount());
                    if (status == Status.RETURNED ? compared != 0 || current.status() != AdvanceRepaymentPort.Status.REVERSED
                            : compared >= 0 || current.status() != AdvanceRepaymentPort.Status.CONFIRMED) throw invalid();
                }
            }
        }
        /** 当前证据只绑定原查询，并在最早到期时间前消费。 */
        public boolean matches(Request expected, Instant now) { return request.equals(expected) && now != null && !now.isBefore(observedAt) && now.isBefore(validUntil); }
        /** 完整退款事实不可借新查询替换，观察时间和版本变化不改变已执行的交易。 */
        public boolean sameReturn(Receipt other) {
            return other != null && !returns().isEmpty() && returns().size() == other.returns().size() && preservesReturns(other);
        }
        /** 新的累计证据必须完整包含旧退回，金额、资金引用及已过账分录不可改写。 */
        public boolean preservesReturns(Receipt other) {
            return other != null && request.equals(other.request) && current != null && current.sameSettlement(other.current) && returns().containsAll(other.returns());
        }
        /** 单笔历史与新增条目归并为同一不可变清单，顺序不决定防重身份。 */
        public List<ReturnItem> returns() { return entries(fundsReturn, posting, additionalReturns); }
        /** 已真实退回的累计金额独立于原收款金额。 */
        public Money totalReturned() { return returns().stream().map(value -> value.fundsReturn().amount()).reduce(Money.zero(request.original().funding().amount().currency()), Money::plus); }
        @Override public String toString() { return "RepaymentAdjustmentReceipt[repaymentId=" + request.repaymentId() + ", status=" + status + ", revision=" + revision + "]"; }
    }

    /**
     * 原还款全部或部分退回的已执行资金流水，不能以原收款流水冒充退款。
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
     * 一笔独立退回资金与借方分录；不同流水不能合并改写为累计金额。
     * @author owlzhangfq@gmail.com
     */
    record ReturnItem(FundsReturn fundsReturn, ReturnPosting posting) {
        /** 一对事实必须同额、方向匹配且先退资金再完成记账。 */
        public ReturnItem {
            if (fundsReturn == null || posting == null || !fundsReturn.amount().equals(posting.amount()) || posting.postedAt().isBefore(fundsReturn.returnedAt())) throw invalid();
        }
        /** 以分量比较避免引用包含分隔符时发生拼接碰撞。 */
        public List<String> fundsIdentity() { return List.of(fundsReturn.channel().name(), fundsReturn.transactionReference()); }
        public List<String> postingIdentity() { return List.of(posting.voucherReference(), posting.entryReference()); }
    }
    /**
     * 原还款仍有效时只解除其争议；真实退回另行增加未还款，未核清保持原账。
     * @author owlzhangfq@gmail.com
     */
    enum Status { UNRESOLVED, CONFIRMED, PARTIALLY_RETURNED, RETURNED }

    private static List<ReturnItem> entries(FundsReturn first, ReturnPosting posting, List<ReturnItem> additional) {
        if (first == null && posting == null && additional.isEmpty()) return List.of();
        var entries = new ArrayList<ReturnItem>(); entries.add(new ReturnItem(first, posting)); entries.addAll(additional); return List.copyOf(entries);
    }

    private static boolean reference(String value) { return StringUtils.isNotBlank(value) && value.length() <= 128 && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl); }
    private static DomainException invalid() { return new DomainException("INVALID_REPAYMENT_ADJUSTMENT_RECEIPT", "Repayment adjustment requires matching original receipt, actual returned funds and posted receivable debit"); }
}
