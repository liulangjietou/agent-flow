package io.agentflow.finance;

import io.agentflow.common.DomainException;
import org.apache.commons.lang3.StringUtils;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 读取原报销付款退回公司账户及独立应付贷方分录，不执行退款或重付。
 * @author owlzhangfq@gmail.com
 */
public interface ExpensePaymentReturnPort {
    int MAX_RETURN_ENTRIES = 100;
    Duration MAX_EVIDENCE_AGE = Duration.ofMinutes(5);

    /** 按固定原付款和原财务目标查询完整累计退回事实。 */
    FinanceResult<Receipt> query(String tenantId, String targetDigest, Request request);

    /**
     * 原成功付款和原挂账的应付科目由后端持久凭据派生，不追随当前科目映射。
     * @author owlzhangfq@gmail.com
     */
    record Request(PaymentCommand command, PaymentObservation original, String payableAccountCode) {
        /** 仅处理正额报销付款，借款退票不能改列为报销资金退回。 */
        public Request {
            if (command == null || command.purpose() != PaymentCommand.Purpose.EXPENSE_REIMBURSEMENT || original == null
                    || original.status() != PaymentObservation.Status.SUCCEEDED || !original.matches(command, true, original.observedAt())
                    || !reference(payableAccountCode)) throw invalid();
        }
        @Override public String toString() { return "ExpensePaymentReturnRequest[paymentId=" + command.id() + "]"; }
    }

    /**
     * 原银行交易状态与独立入款共同证明退回，撤销状态本身不能替代实际资金。
     * @author owlzhangfq@gmail.com
     */
    record Receipt(Request request, Status status, long revision, Instant observedAt, Instant validUntil,
                   PaymentObservation current, List<ReturnItem> returns) {
        /** 金额、科目、原回单和时间均须一致，重复入款及重复分录在协议入口拒绝。 */
        public Receipt {
            if (request == null || status == null || revision < 1 || observedAt == null || validUntil == null
                    || !validUntil.isAfter(observedAt) || validUntil.isAfter(observedAt.plus(MAX_EVIDENCE_AGE))
                    || returns == null || returns.size() > MAX_RETURN_ENTRIES || returns.stream().anyMatch(Objects::isNull)) throw invalid();
            returns = List.copyOf(returns);
            if (current != null && (!current.matches(request.command(), true, observedAt)
                    || current.observedAt().isBefore(request.original().observedAt())
                    || validUntil.isAfter(current.observedAt().plus(MAX_EVIDENCE_AGE)))) throw invalid();
            if (status == Status.UNRESOLVED) {
                if (!returns.isEmpty()) throw invalid();
            } else {
                var original = request.original(); var command = request.command();
                if (current == null || current.revision() < original.revision()
                        || !original.paymentReference().equals(current.paymentReference()) || !original.paidAmount().equals(current.paidAmount())
                        || !original.accountDigest().equals(current.accountDigest()) || current.completedAt() == null
                        || current.completedAt().isBefore(original.completedAt())) throw invalid();
                if (status == Status.CONFIRMED) {
                    if (!originalSuccess(current, original) || !returns.isEmpty()) throw invalid();
                } else {
                    if (returns.isEmpty()) throw invalid();
                    var total = Money.zero(command.amount().currency());
                    for (var item : returns) {
                        var funds = item.funding(); var posting = item.posting();
                        if (!funds.amount().currency().equals(total.currency()) || funds.receivedAt().isBefore(original.completedAt())
                                || posting.postedAt().isAfter(observedAt) || !request.payableAccountCode().equals(posting.accountCode())
                                || funds.transactionReference().equals(original.paymentReference()) || funds.transactionReference().equals(original.receiptReference())
                                || posting.voucherReference().equals(command.voucherReference())) throw invalid();
                        total = total.plus(funds.amount());
                    }
                    if (returns.stream().map(ReturnItem::fundsIdentity).distinct().count() != returns.size()
                            || returns.stream().map(ReturnItem::postingIdentity).distinct().count() != returns.size()) throw invalid();
                    int compared = total.compareTo(command.amount());
                    if (status == Status.RETURNED ? compared != 0 || current.status() != PaymentObservation.Status.REVERSED || current.revision() <= original.revision()
                            : compared >= 0 || !originalSuccess(current, original)) throw invalid();
                }
            }
        }
        /** 原意图及五分钟证据窗口同时匹配才可采用。 */
        public boolean matches(Request expected, Instant now) { return request.equals(expected) && now != null && !now.isBefore(observedAt) && now.isBefore(validUntil); }
        /** 新累计证据不能遗漏或改写此前已见资金及分录。 */
        public boolean preservesReturns(Receipt previous) { return previous != null && request.equals(previous.request) && returns.containsAll(previous.returns); }
        /** 外部条目换序不构成新的资金退回。 */
        public boolean sameReturns(Receipt previous) { return preservesReturns(previous) && returns.size() == previous.returns.size(); }
        /** 原交易事实必须与本地当前付款一致，观察版本可以增加。 */
        public boolean samePaymentFacts(PaymentObservation other) {
            return current != null && other != null && current.authorizationId().equals(other.authorizationId()) && current.commandDigest().equals(other.commandDigest())
                    && current.status() == other.status() && Objects.equals(current.paymentReference(), other.paymentReference())
                    && Objects.equals(current.paidAmount(), other.paidAmount()) && Objects.equals(current.accountDigest(), other.accountDigest())
                    && Objects.equals(current.completedAt(), other.completedAt()) && Objects.equals(current.receiptReference(), other.receiptReference());
        }
        /** 退回金额单独汇总，不改写原付款金额。 */
        public Money totalReturned() { return returns.stream().map(item -> item.funding().amount()).reduce(Money.zero(request.command().amount().currency()), Money::plus); }
        @Override public String toString() { return "ExpensePaymentReturnReceipt[paymentId=" + request.command().id() + ", status=" + status + ", revision=" + revision + "]"; }
    }

    /**
     * 已到账公司银行资金与已过账员工应付贷方分录一一对应。
     * @author owlzhangfq@gmail.com
     */
    record ReturnItem(BankReceipt funding, PayableCredit posting) {
        /** 原报销退回只认银行入款，不能复用借款贷方分录或员工现金还款。 */
        public ReturnItem {
            if (funding == null || posting == null || !funding.amount().equals(posting.amount()) || funding.receivedAt().isAfter(posting.postedAt())) throw invalid();
        }
        public String fundsIdentity() { return funding.transactionReference(); }
        public List<String> postingIdentity() { return List.of(posting.voucherReference(), posting.entryReference()); }
    }

    /**
     * 原报销付款退回公司的独立银行流水，不接受付款状态作为入款凭据。
     * @author owlzhangfq@gmail.com
     */
    record BankReceipt(String transactionReference, Money amount, Instant receivedAt) {
        /** 实收资金必须有独立编号、正金额与实际到账时间。 */
        public BankReceipt { if (!reference(transactionReference) || amount == null || amount.value().signum() <= 0 || receivedAt == null) throw invalid(); }
    }

    /**
     * ERP 已过账的员工应付贷方，区别于员工借款应收贷方；科目固定到原挂账快照。
     * @author owlzhangfq@gmail.com
     */
    record PayableCredit(String voucherReference, String entryReference, String accountCode, Money amount, LocalDate accountingDate, Instant postedAt) {
        /** 分录须能逐笔追溯，未过账草稿不构成退回登记依据。 */
        public PayableCredit {
            if (!reference(voucherReference) || !reference(entryReference) || !reference(accountCode) || amount == null
                    || amount.value().signum() <= 0 || accountingDate == null || postedAt == null) throw invalid();
        }
    }

    /**
     * 部分退回保留原交易成功，全额退回同时要求原交易撤销与全额独立入款。
     * @author owlzhangfq@gmail.com
     */
    enum Status { UNRESOLVED, CONFIRMED, PARTIALLY_RETURNED, RETURNED }
    private static boolean originalSuccess(PaymentObservation current, PaymentObservation original) {
        return current.status() == PaymentObservation.Status.SUCCEEDED && original.completedAt().equals(current.completedAt()) && original.receiptReference().equals(current.receiptReference());
    }
    private static boolean reference(String value) { return StringUtils.isNotBlank(value) && value.length() <= 128 && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl); }
    private static DomainException invalid() { return new DomainException("INVALID_EXPENSE_PAYMENT_RETURN_RECEIPT", "Expense payment return requires original payment, received bank funds and a matching payable credit"); }
}
