package io.agentflow.procurement;

import io.agentflow.expense.InvoiceKey;
import io.agentflow.finance.FinanceCatalog;
import io.agentflow.finance.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 授权后可读取的本轮采购依据；不向页面返回支付账户引用、摘要或内部查验凭据。
 * @author owlzhangfq@gmail.com
 */
public record ProcurementPaymentRoundView(int roundNo, long submittedRequestVersion, String submittedBy, Instant submittedAt,
                                         ProcurementPaymentContent content, FinanceCatalog.LegalEntity legalEntity,
                                         String catalogVersion, PayableView payable) {
    /** 预检与实际审批统一使用此投影，内部完整快照不直接序列化。 */
    public static ProcurementPaymentRoundView of(ProcurementPaymentRound round) {
        var payable = round.payable();
        return new ProcurementPaymentRoundView(round.roundNo(), round.submittedRequestVersion(), round.submittedBy(), round.submittedAt(),
                round.content(), round.legalEntity(), round.catalogVersion(), new PayableView(payable.sourceVersion(), payable.observedAt(),
                payable.supplierName(), payable.account().maskedAccount(), payable.contractReference(), payable.orderReference(),
                payable.matchingReference(), payable.accrualVoucherReference(), payable.budgetRecognitionReference(), payable.dueOn(),
                payable.gross(), payable.settled(), payable.outstanding(), payable.lines().stream().map(LineView::of).toList()));
    }

    /**
     * 原未付额仅是冻结时事实；实际付款仍须独立复核和授权。
     * @author owlzhangfq@gmail.com
     */
    public record PayableView(String sourceVersion, Instant observedAt, String supplierName, String maskedAccount,
                              String contractReference, String orderReference, String matchingReference, String accrualVoucherReference,
                              String budgetRecognitionReference, LocalDate dueOn, Money gross, Money settled, Money outstanding,
                              List<LineView> lines) {
        /** 页面材料不能被调用方通过集合原地改动。 */
        public PayableView { lines = List.copyOf(lines); }
    }

    /**
     * 发票与订单行的数量和金额分别展示，便于核对部分付款所依据的原三单匹配。
     * @author owlzhangfq@gmail.com
     */
    public record LineView(int lineNo, int orderLineNo, String acceptanceReference, InvoiceKey invoice, int invoiceLineNo,
                           String unit, String orderedQuantity, String acceptedQuantity, String invoicedQuantity,
                           Money orderedGross, Money acceptedGross, Money invoicedGross, Money tax) {
        private static LineView of(ProcurementPayablePort.MatchedLine line) {
            return new LineView(line.lineNo(), line.orderLineNo(), line.acceptanceReference(), line.invoice(), line.invoiceLineNo(), line.unit(),
                    line.orderedQuantity().toPlainString(), line.acceptedQuantity().toPlainString(), line.invoicedQuantity().toPlainString(),
                    line.orderedGross(), line.acceptedGross(), line.invoicedGross(), line.tax());
        }
    }
}
