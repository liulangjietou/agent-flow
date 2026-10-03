package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.Money;
import java.time.Instant;

/**
 * 供应商资金办理的不可变依据：实际批准轮次与仍保留的原应付占用，不能由普通表单金额替代。
 * @author owlzhangfq@gmail.com
 */
public record ApprovedProcurementPayment(ProcurementPayableReservation reservation, ProcurementPaymentRequest.Approval approval,
                                         long approvedRequestVersion) {
    /** 批准紧随该冻结版本，且不得早于原占用；已释放占用不能支持付款授权。 */
    public ApprovedProcurementPayment {
        if (reservation == null || !reservation.held() || approval == null
                || approvedRequestVersion != reservation.source().requestVersion() + 1
                || approval.roundNo() != reservation.source().round().roundNo() || approval.approvedAt().isBefore(reservation.heldAt())) throw changed();
    }

    /** 应用层取得实际采购单和活动占用后，在形成授权前固定两者的精确绑定。 */
    public static ApprovedProcurementPayment from(ProcurementPaymentRequest request, ProcurementPayableReservation reservation) {
        if (request == null || request.approval() == null || reservation == null) throw changed();
        var original = reservation.source();
        if (!original.tenantId().equals(request.tenantId()) || !original.requestId().equals(request.id())
                || !original.applicationId().equals(request.applicationId()) || !original.employeeId().equals(request.employeeId())
                || !original.round().equals(request.currentRound()) || !request.content().equals(original.round().content())) throw changed();
        return new ApprovedProcurementPayment(reservation, request.approval(), request.version());
    }

    /** 余额可随其他已核销付款变化；原应付、匹配、发票、账户及入账依据必须仍是批准时的同一笔。 */
    public void requireCurrentPayable(ProcurementPayablePort.Payable current, Instant now) {
        var round = reservation.source().round(); var original = round.payable();
        if (current == null || now == null || now.isBefore(approval.approvedAt()) || !current.matches(original.request(), now)
                || !original.supplierName().equals(current.supplierName()) || !original.account().equals(current.account())
                || !original.contractReference().equals(current.contractReference()) || !original.orderReference().equals(current.orderReference())
                || !original.matchingReference().equals(current.matchingReference()) || !original.accrualVoucherReference().equals(current.accrualVoucherReference())
                || !original.budgetRecognitionReference().equals(current.budgetRecognitionReference()) || !original.dueOn().equals(current.dueOn())
                || !original.gross().equals(current.gross()) || !original.lines().equals(current.lines())
                || amount().compareTo(current.outstanding()) > 0) {
            throw new DomainException("PROCUREMENT_PAYABLE_CHANGED", "Current payable must retain the approved supplier, matching, account and accounting evidence with sufficient outstanding amount");
        }
    }

    /** 本次金额来自原批准申请，不能用最新未付总额扩大授权。 */
    public Money amount() { return reservation.source().round().content().amount(); }

    /** 日志不展开供应商、票号、金额或账户信息。 */
    @Override public String toString() { return "ApprovedProcurementPayment[requestId=" + reservation.source().requestId() + "]"; }
    private static DomainException changed() { return new DomainException("PROCUREMENT_PAYMENT_SOURCE_CHANGED", "Supplier payment requires the exact approved request and its held original payable"); }
}
