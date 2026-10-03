package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.expense.InvoiceKey;
import io.agentflow.finance.FinanceResult;
import io.agentflow.finance.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * 只读取已完成订单、验收和发票匹配的供应商应付；读取不预留额度，也不代表支付授权。
 * @author owlzhangfq@gmail.com
 */
public interface ProcurementPayablePort {
    int MAX_LINES = 200;
    Duration MAX_EVIDENCE_AGE = Duration.ofMinutes(5);

    /** 始终读取原财务目标，并由外部按申请员工限制可见供应商应付。 */
    FinanceResult<Payable> payable(String tenantId, String targetDigest, Request request);

    /**
     * 稳定应付号与申请人共同限定读取，不允许把其他供应商或法人的款项混入。
     * @author owlzhangfq@gmail.com
     */
    record Request(UUID legalEntityId, String employeeId, String supplierReference, String payableReference) {
        /** 只接收稳定引用，账户与已付金额不由申请人填写。 */
        public Request {
            if (legalEntityId == null || invalidText(employeeId, 128) || invalidText(supplierReference, 128)
                    || invalidText(payableReference, 128)) throw invalid();
        }
    }

    /**
     * 已挂账应付的完整读取快照，订单与预算确认来自原采购，不重复记员工费用。
     * @author owlzhangfq@gmail.com
     */
    record Payable(Request request, String sourceVersion, Instant observedAt, Instant validUntil, String supplierName,
                   SupplierAccountSnapshot account, String contractReference, String orderReference, String matchingReference,
                   String accrualVoucherReference, String budgetRecognitionReference, LocalDate dueOn,
                   Money gross, Money settled, List<MatchedLine> lines) {
        /** 同币种逐项守恒；一个订单行分到多张发票时，合计不能重复使用验收数量和金额。 */
        public Payable {
            if (request == null || invalidText(sourceVersion, 128) || observedAt == null || validUntil == null
                    || !validUntil.isAfter(observedAt) || invalidText(supplierName, 256) || account == null
                    || !account.legalEntityId().equals(request.legalEntityId()) || !account.supplierReference().equals(request.supplierReference())
                    || invalidText(contractReference, 128) || invalidText(orderReference, 128) || invalidText(matchingReference, 128)
                    || invalidText(accrualVoucherReference, 128) || invalidText(budgetRecognitionReference, 128) || dueOn == null
                    || gross == null || gross.value().signum() <= 0 || settled == null || settled.compareTo(gross) > 0
                    || lines == null || lines.isEmpty() || lines.size() > MAX_LINES || lines.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
            lines = List.copyOf(lines);
            var total = Money.zero(gross.currency()); var invoices = new HashSet<String>(); var invoiceFacts = new HashMap<String, MatchedLine>();
            var orders = new HashMap<Integer, MatchedLine>(); var quantities = new HashMap<Integer, BigDecimal>(); var amounts = new HashMap<Integer, Money>();
            for (int index = 0; index < lines.size(); index++) {
                var line = lines.get(index);
                if (line.lineNo() != index + 1 || !invoices.add(line.invoice().canonical() + ":" + line.invoiceLineNo())) throw invalid();
                var previousInvoice = invoiceFacts.putIfAbsent(line.invoice().canonical(), line);
                if (previousInvoice != null && (!previousInvoice.invoiceDigest().equals(line.invoiceDigest())
                        || !previousInvoice.verificationReference().equals(line.verificationReference()))) throw invalid();
                total = total.plus(line.invoicedGross());
                var first = orders.putIfAbsent(line.orderLineNo(), line);
                if (first != null && !first.sameOrderEvidence(line)) throw invalid();
                var quantity = quantities.merge(line.orderLineNo(), line.invoicedQuantity(), BigDecimal::add);
                var amount = amounts.merge(line.orderLineNo(), line.invoicedGross(), Money::plus);
                if (quantity.compareTo(line.acceptedQuantity()) > 0 || amount.compareTo(line.acceptedGross()) > 0) throw invalid();
            }
            if (!total.equals(gross)) throw invalid();
        }

        /** 当前未付余额是外部事实的差值，后续预留与付款仍须复核同一应付版本。 */
        public Money outstanding() { return gross.minus(settled); }

        /** 不接受未来观察、到期快照或超过五分钟的旧匹配依据。 */
        public boolean matches(Request expected, Instant now) {
            return request.equals(expected) && !observedAt.isAfter(now) && validUntil.isAfter(now) && observedAt.plus(MAX_EVIDENCE_AGE).isAfter(now);
        }

        /** 快照含供应商及账户材料，不进入默认对象日志。 */
        @Override public String toString() { return "ProcurementPayable[redacted]"; }
    }

    /**
     * 数量与金额分别约束三单匹配，实际税额来自原发票而非推算税率。
     * @author owlzhangfq@gmail.com
     */
    record MatchedLine(int lineNo, int orderLineNo, String acceptanceReference, InvoiceKey invoice, int invoiceLineNo,
                       String invoiceDigest, String verificationReference, String unit, BigDecimal orderedQuantity,
                       BigDecimal acceptedQuantity, BigDecimal invoicedQuantity, Money orderedGross, Money acceptedGross,
                       Money invoicedGross, Money tax) {
        /** 同一行必须已验收且已查验开票，不支持通过空引用表达待补票或预付款。 */
        public MatchedLine {
            if (lineNo < 1 || orderLineNo < 1 || invalidText(acceptanceReference, 128) || invoice == null || invoiceLineNo < 1
                    || invoiceDigest == null || !invoiceDigest.matches("[a-f0-9]{64}") || invalidText(verificationReference, 128)
                    || invalidText(unit, 32) || invalidQuantity(orderedQuantity) || invalidQuantity(acceptedQuantity) || invalidQuantity(invoicedQuantity)
                    || acceptedQuantity.compareTo(orderedQuantity) > 0 || invoicedQuantity.compareTo(acceptedQuantity) > 0
                    || orderedGross == null || acceptedGross == null || invoicedGross == null || invoicedGross.value().signum() <= 0 || tax == null
                    || acceptedGross.compareTo(orderedGross) > 0 || invoicedGross.compareTo(acceptedGross) > 0 || tax.compareTo(invoicedGross) > 0) throw invalid();
            orderedQuantity = orderedQuantity.stripTrailingZeros(); acceptedQuantity = acceptedQuantity.stripTrailingZeros(); invoicedQuantity = invoicedQuantity.stripTrailingZeros();
        }

        private boolean sameOrderEvidence(MatchedLine other) {
            return acceptanceReference.equals(other.acceptanceReference) && unit.equals(other.unit)
                    && orderedQuantity.equals(other.orderedQuantity) && acceptedQuantity.equals(other.acceptedQuantity)
                    && orderedGross.equals(other.orderedGross) && acceptedGross.equals(other.acceptedGross);
        }

        /** 不展开票号、发票摘要及验收资料。 */
        @Override public String toString() { return "ProcurementMatchedLine[lineNo=" + lineNo + ", orderLineNo=" + orderLineNo + "]"; }
    }

    private static boolean invalidQuantity(BigDecimal value) {
        return value == null || value.signum() <= 0 || value.compareTo(Money.MAX_VALUE) > 0 || value.stripTrailingZeros().scale() > 6;
    }
    private static boolean invalidText(String value, int maximum) {
        return StringUtils.isBlank(value) || value.length() > maximum || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl);
    }
    private static DomainException invalid() {
        return new DomainException("INVALID_PROCUREMENT_PAYABLE", "Procurement payable requires consistent supplier, order, acceptance, verified invoices and posted payable evidence");
    }
}
