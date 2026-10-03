package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import io.agentflow.finance.PaymentObservation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * 原供应商付款退回公司的独立资金依据；查询不冲销 ERP，也不重新付款。
 * @author owlzhangfq@gmail.com
 */
public interface SupplierPaymentReturnPort {
    int MAX_RETURN_ENTRIES = 100;
    Duration MAX_EVIDENCE_AGE = Duration.ofMinutes(5);

    /** 目标、原交易和公司入款账户全部来自原银行命令，授权到期不妨碍只读核对。 */
    FinanceResult<Receipt> query(Request request);

    /**
     * 固定首次实际成功付款，当前退回状态不能替换历史到账依据。
     * @author owlzhangfq@gmail.com
     */
    record Request(SupplierPaymentCommand command, PaymentObservation original) {
        /** 原始依据只能是精确匹配原供应商、金额和账户的已到账事实。 */
        public Request {
            if (command == null || original == null || original.status() != PaymentObservation.Status.SUCCEEDED
                    || !command.matches(original, true, original.observedAt())) throw invalid();
        }
        @Override public String toString() { return "SupplierPaymentReturnRequest[paymentId=" + command.id() + "]"; }
    }

    /**
     * 累计实际入款与当前原银行状态配对，ERP 是否完成调整由独立链路确认。
     * @author owlzhangfq@gmail.com
     */
    record Receipt(Request request, Status status, long revision, Instant observedAt, Instant validUntil,
                   PaymentObservation current, List<BankReceipt> returns) {
        /** 状态不能代替公司实收资金，也不能把其他账户或其他交易的入款算作本次退回。 */
        public Receipt {
            if (request == null || status == null || revision < 1 || observedAt == null || validUntil == null
                    || observedAt.isBefore(request.original().observedAt()) || !validUntil.isAfter(observedAt)
                    || validUntil.isAfter(observedAt.plus(MAX_EVIDENCE_AGE)) || returns == null
                    || returns.size() > MAX_RETURN_ENTRIES || returns.stream().anyMatch(Objects::isNull)) throw invalid();
            returns = List.copyOf(returns);
            if (current != null && (!request.command().matches(current, true, observedAt)
                    || current.observedAt().isBefore(request.original().observedAt())
                    || validUntil.isAfter(current.observedAt().plus(MAX_EVIDENCE_AGE)))) throw invalid();
            if (status == Status.UNRESOLVED) {
                if (!returns.isEmpty()) throw invalid();
            } else {
                var original = request.original();
                if (current == null || current.revision() < original.revision()
                        || !original.paymentReference().equals(current.paymentReference())
                        || !original.paidAmount().equals(current.paidAmount()) || !original.accountDigest().equals(current.accountDigest())
                        || current.completedAt() == null || current.completedAt().isBefore(original.completedAt())) throw invalid();
                if (status == Status.CONFIRMED) {
                    if (!originalSuccess(current, original) || !returns.isEmpty()) throw invalid();
                } else {
                    if (returns.isEmpty()) throw invalid();
                    var total = Money.zero(request.command().amount().currency());
                    for (var item : returns) {
                        requireFunding(request, item, observedAt);
                        total = total.plus(item.amount());
                    }
                    if (returns.stream().map(BankReceipt::transactionReference).distinct().count() != returns.size()) throw invalid();
                    int compared = total.compareTo(request.command().amount());
                    if (status == Status.RETURNED ? compared != 0 || current.status() != PaymentObservation.Status.REVERSED || current.revision() <= original.revision()
                            : compared >= 0 || !originalSuccess(current, original)) throw invalid();
                }
            }
        }

        /** 证据使用排他到期窗口，收到或登记回执不能延长银行观察时间。 */
        public boolean matches(Request expected, Instant now) {
            return request.equals(expected) && now != null && !now.isBefore(observedAt) && now.isBefore(validUntil);
        }
        /** 连续累计原件必须保留，包括此前仅查询到而尚未登记的入款。 */
        public boolean preservesReturns(Receipt previous) {
            return previous != null && request.equals(previous.request) && returns.containsAll(previous.returns);
        }
        /** 外部换序不会产生新资金，原入款的金额、账户与时刻均不可改变。 */
        public boolean sameReturns(Receipt previous) { return preservesReturns(previous) && returns.size() == previous.returns.size(); }
        /** 新查询不得回退外部修订、回退观察时间或改写同版本的实质事实。 */
        public boolean continues(Receipt previous) {
            return preservesReturns(previous) && revision >= previous.revision && !observedAt.isBefore(previous.observedAt)
                    && (previous.current == null || current != null && current.revision() >= previous.current.revision()
                        && !current.observedAt().isBefore(previous.current.observedAt())
                        && (current.revision() > previous.current.revision() || samePaymentFacts(previous.current)))
                    && (revision > previous.revision || status == previous.status && sameReturns(previous) && samePaymentFacts(previous.current));
        }
        /** 本地原银行对账必须与查询中的实际终态一致，独立退回不能绕过银行争议。 */
        public boolean samePaymentFacts(PaymentObservation other) {
            if (current == null || other == null) return current == other;
            return current.authorizationId().equals(other.authorizationId()) && current.commandDigest().equals(other.commandDigest())
                    && current.status() == other.status() && Objects.equals(current.paymentReference(), other.paymentReference())
                    && Objects.equals(current.paidAmount(), other.paidAmount()) && Objects.equals(current.accountDigest(), other.accountDigest())
                    && Objects.equals(current.completedAt(), other.completedAt()) && Objects.equals(current.receiptReference(), other.receiptReference());
        }
        /** 登记资金与完成记账都须匹配当前无争议银行；相同原付款的旧成功不能覆盖未知或更新的外部修订。 */
        public boolean matchesCurrentBank(SupplierPaymentOperation bank) {
            return bank != null && bank.command().equals(request.command()) && bank.conflictingObservation() == null
                    && (bank.settleable() || bank.status() == SupplierPaymentOperation.Status.REVERSED)
                    && current != null && samePaymentFacts(bank.observation()) && bank.observation().revision() <= current.revision()
                    && !bank.observation().observedAt().isAfter(current.observedAt());
        }
        /** 原付款金额保持，退回单独汇总，不能在这里重开应付或释放预算。 */
        public Money totalReturned() { return returns.stream().map(BankReceipt::amount).reduce(Money.zero(request.command().amount().currency()), Money::plus); }
        @Override public String toString() { return "SupplierPaymentReturnReceipt[paymentId=" + request.command().id() + ", status=" + status + ", revision=" + revision + "]"; }
    }

    /**
     * 原出款公司账户实际收到的独立资金流水，原付款回单本身不构成入款。
     * @author owlzhangfq@gmail.com
     */
    record BankReceipt(String transactionReference, String creditAccountReference, Money amount, Instant receivedAt) {
        /** 银行须保证原公司账户引用稳定，不接受由当前账户目录替换历史账户。 */
        public BankReceipt {
            if (!reference(transactionReference) || !reference(creditAccountReference) || amount == null
                    || amount.value().signum() <= 0 || receivedAt == null) throw invalid();
        }
        @Override public String toString() { return "SupplierReturnedBankReceipt"; }
    }

    /** 验证单笔入款与原交易的归属，供协议和累计账本恢复共同使用。 */
    static void requireFunding(Request request, BankReceipt item, Instant observedAt) {
        if (!item.amount().currency().equals(request.command().amount().currency())
                || !item.creditAccountReference().equals(request.command().debitAccount().reference())
                || item.receivedAt().isBefore(request.original().completedAt()) || item.receivedAt().isAfter(observedAt)
                || item.transactionReference().equals(request.original().paymentReference())
                || item.transactionReference().equals(request.original().receiptReference())) throw invalid();
    }

    /**
     * 部分退回仍保持原交易成功；全额退回同时要求原交易撤销和足额独立入款。
     * @author owlzhangfq@gmail.com
     */
    enum Status { UNRESOLVED, CONFIRMED, PARTIALLY_RETURNED, RETURNED }

    private static boolean originalSuccess(PaymentObservation current, PaymentObservation original) {
        return current.status() == PaymentObservation.Status.SUCCEEDED && original.completedAt().equals(current.completedAt())
                && original.receiptReference().equals(current.receiptReference());
    }
    private static boolean reference(String value) { return StringUtils.isNotBlank(value) && value.length() <= 128 && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl); }
    private static DomainException invalid() { return new DomainException("INVALID_SUPPLIER_PAYMENT_RETURN_RECEIPT", "Supplier payment return requires the original successful payment and independent funds received by the original company account"); }
}
