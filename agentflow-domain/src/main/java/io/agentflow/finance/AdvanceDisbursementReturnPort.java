package io.agentflow.finance;

import io.agentflow.common.DomainException;
import java.time.Instant;
import java.util.List;

/**
 * 查询原放款实际退回公司及借款贷方调整，不发送退款或修改原付款命令。
 * @author owlzhangfq@gmail.com
 */
public interface AdvanceDisbursementReturnPort {
    int MAX_RETURN_ENTRIES = 100;
    /** 固定原财务目标，按已成功的原放款读取完整累计退回。 */
    FinanceResult<Receipt> query(String tenantId, String targetDigest, Request request);

    /**
     * 身份来自本地原成功修订，不能使用当前申请或重新选择的账户替换。
     * @author owlzhangfq@gmail.com
     */
    record Request(PaymentCommand command, PaymentObservation original) {
        /** 只处理已实际到账的员工借款，报销退款由独立用例办理。 */
        public Request {
            if (command == null || command.purpose() != PaymentCommand.Purpose.EMPLOYEE_ADVANCE || original == null
                    || original.status() != PaymentObservation.Status.SUCCEEDED || !original.matches(command, true, original.observedAt())) throw invalid();
        }
        @Override public String toString() { return "DisbursementReturnRequest[paymentId=" + command.id() + "]"; }
    }

    /**
     * 原交易当前事实与每笔独立回款共同构成累计证据，不以撤销状态代替真实入款。
     * @author owlzhangfq@gmail.com
     */
    record Receipt(Request request, Status status, long revision, Instant observedAt, Instant validUntil,
                   PaymentObservation current, List<ReturnItem> returns) {
        /** 资金及分录必须配对、属于原款、未超额，并在五分钟证据期内。 */
        public Receipt {
            if (request == null || status == null || revision < 1 || observedAt == null || validUntil == null
                    || !validUntil.isAfter(observedAt) || validUntil.isAfter(observedAt.plus(AdvanceRepaymentPort.MAX_EVIDENCE_AGE))
                    || returns == null || returns.size() > MAX_RETURN_ENTRIES || returns.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
            returns = List.copyOf(returns);
            if (current != null && (!current.matches(request.command(), true, observedAt)
                    || validUntil.isAfter(current.observedAt().plus(AdvanceRepaymentPort.MAX_EVIDENCE_AGE)))) throw invalid();
            if (status == Status.UNRESOLVED) {
                if (!returns.isEmpty()) throw invalid();
            } else {
                var original = request.original(); var command = request.command();
                if (current == null || current.revision() < original.revision()
                        || !original.paymentReference().equals(current.paymentReference()) || !original.paidAmount().equals(current.paidAmount())
                        || !original.accountDigest().equals(current.accountDigest()) || current.completedAt() == null || current.completedAt().isBefore(original.completedAt())) throw invalid();
                if (status == Status.CONFIRMED) {
                    if (current.status() != PaymentObservation.Status.SUCCEEDED || !original.completedAt().equals(current.completedAt())
                            || !original.receiptReference().equals(current.receiptReference()) || !returns.isEmpty()) throw invalid();
                } else {
                    if (returns.isEmpty()) throw invalid();
                    var total = Money.zero(command.amount().currency());
                    for (var item : returns) {
                        var funds = item.funding(); var posting = item.posting();
                        if (!funds.amount().currency().equals(total.currency()) || funds.receivedAt().isBefore(original.completedAt())
                                || posting.postedAt().isAfter(observedAt) || funds.transactionReference().equals(original.paymentReference())
                                || funds.transactionReference().equals(original.receiptReference()) || posting.voucherReference().equals(command.voucherReference())) throw invalid();
                        total = total.plus(funds.amount());
                    }
                    if (returns.stream().map(ReturnItem::fundsIdentity).distinct().count() != returns.size()
                            || returns.stream().map(ReturnItem::postingIdentity).distinct().count() != returns.size()) throw invalid();
                    int compared = total.compareTo(command.amount());
                    if (status == Status.RETURNED ? compared != 0 || current.status() != PaymentObservation.Status.REVERSED
                            : compared >= 0 || current.status() != PaymentObservation.Status.SUCCEEDED || !original.completedAt().equals(current.completedAt())
                                || !original.receiptReference().equals(current.receiptReference())) throw invalid();
                }
            }
        }
        /** 证据只能供原意图在有效期内采用。 */
        public boolean matches(Request expected, Instant now) { return request.equals(expected) && now != null && !now.isBefore(observedAt) && now.isBefore(validUntil); }
        /** 新证据不得遗漏或改写任何此前已见回款。 */
        public boolean preservesReturns(Receipt previous) { return previous != null && request.equals(previous.request) && returns.containsAll(previous.returns); }
        /** 顺序变化不产生新回款，当前原付款状态仍由各自版本守卫核验。 */
        public boolean sameReturns(Receipt previous) { return preservesReturns(previous) && returns.size() == previous.returns.size(); }
        /** 原付款可增加观察版本，已经确认的身份、状态和回单不能替换。 */
        public boolean samePaymentFacts(PaymentObservation other) {
            return current != null && other != null && current.authorizationId().equals(other.authorizationId()) && current.commandDigest().equals(other.commandDigest())
                    && current.status() == other.status() && java.util.Objects.equals(current.paymentReference(), other.paymentReference())
                    && java.util.Objects.equals(current.paidAmount(), other.paidAmount()) && java.util.Objects.equals(current.accountDigest(), other.accountDigest())
                    && java.util.Objects.equals(current.completedAt(), other.completedAt()) && java.util.Objects.equals(current.receiptReference(), other.receiptReference());
        }
        /** 原放款保持，累计退回独立计算。 */
        public Money totalReturned() { return returns.stream().map(item -> item.funding().amount()).reduce(Money.zero(request.command().amount().currency()), Money::plus); }
        @Override public String toString() { return "DisbursementReturnReceipt[paymentId=" + request.command().id() + ", status=" + status + ", revision=" + revision + "]"; }
    }

    /**
     * 银行已退回公司账户的入款及对应员工借款贷方分录，沿用现有回款原件值对象。
     * @author owlzhangfq@gmail.com
     */
    record ReturnItem(AdvanceRepaymentPort.Funding funding, AdvanceRepaymentPort.Posting posting) {
        /** 放款退票只接受银行入款，员工主动现金或工资还款不能改列为退票。 */
        public ReturnItem {
            if (funding == null || posting == null || funding.channel() != AdvanceRepaymentPort.Channel.BANK_TRANSFER
                    || !funding.amount().equals(posting.amount()) || funding.receivedAt().isAfter(posting.postedAt())) throw invalid();
        }
        public List<String> fundsIdentity() { return List.of(funding.channel().name(), funding.transactionReference()); }
        public List<String> postingIdentity() { return List.of(posting.voucherReference(), posting.entryReference()); }
    }

    /**
     * 部分退回保留原付款成功，全额退回需要原交易撤销与完整独立回款同时成立。
     * @author owlzhangfq@gmail.com
     */
    enum Status { UNRESOLVED, CONFIRMED, PARTIALLY_RETURNED, RETURNED }
    private static DomainException invalid() { return new DomainException("INVALID_DISBURSEMENT_RETURN_RECEIPT", "Disbursement return requires the original successful advance, received bank funds and a matching receivable credit"); }
}
