package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import io.agentflow.finance.PaymentAccountsPort;
import java.time.Instant;
import org.apache.commons.lang3.StringUtils;

/**
 * 发送前重新读取的原预留、供应商应付和出纳出款账户；只保留选中账户，不保存整份目录。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPaymentEvidence(SupplierPayableHoldObservation hold, ProcurementPayablePort.Payable payable,
        PaymentAccountsPort.Request request, String directoryVersion, PaymentAccountsPort.DebitAccount debitAccount,
        Instant directoryObservedAt, Instant directoryValidUntil, Instant checkedAt) {
    /** 远端观察必须已发生，复查时各项依据均未到期；实际命令绑定由 matches 核验。 */
    public SupplierPaymentEvidence {
        if (hold == null || payable == null || request == null || StringUtils.isBlank(directoryVersion) || directoryVersion.length() > 128
                || debitAccount == null || directoryObservedAt == null || directoryValidUntil == null || checkedAt == null
                || hold.status() != SupplierPayableHoldObservation.Status.HELD || hold.observedAt().isAfter(checkedAt)
                || payable.observedAt().isAfter(checkedAt) || directoryObservedAt.isAfter(checkedAt)
                || !payable.validUntil().isAfter(checkedAt) || !directoryValidUntil.isAfter(checkedAt)
                || !hold.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(checkedAt)
                || !payable.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(checkedAt)
                || !directoryObservedAt.plus(ProcurementPayablePort.MAX_EVIDENCE_AGE).isAfter(checkedAt)) throw changed();
    }

    /** 复查必须还是原出纳所选的同版本账户以及原批准对应的供应商、余额和预留。 */
    public static SupplierPaymentEvidence checked(SupplierPaymentCommand command, PaymentAccountsPort.Directory directory,
            ProcurementPayablePort.Payable payable, SupplierPayableHoldObservation hold, Instant now) {
        if (command == null || directory == null) throw changed();
        var evidence = new SupplierPaymentEvidence(hold, payable, directory.request(), directory.sourceVersion(),
                directory.account(command.debitAccount().reference(), now), directory.observedAt(), directory.validUntil(), now);
        if (!evidence.matches(command, now)) throw changed();
        return evidence;
    }

    /** 发送取所有远端有效期和五分钟观察窗口的交集，收到响应不会延长期限。 */
    public Instant validUntil() {
        var deadline = directoryValidUntil;
        for (var candidate : new Instant[] { payable.validUntil(), directoryObservedAt.plus(ProcurementPayablePort.MAX_EVIDENCE_AGE),
                payable.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE), hold.observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE) }) {
            if (candidate.isBefore(deadline)) deadline = candidate;
        }
        return deadline;
    }

    /** 检查原法人、币种、三方身份及两端账户，不能用新账户或其他应付替换原命令。 */
    public boolean matches(SupplierPaymentCommand command, Instant now) {
        if (command == null || now == null || now.isBefore(checkedAt) || !now.isBefore(validUntil())
                || !debitAccount.equals(command.debitAccount()) || !request.equals(new PaymentAccountsPort.Request(command.payee().legalEntityId(), command.amount().currency(), command.cashier()))
                || !command.matchesHold(hold, now)) return false;
        try { command.holdCommand().authorization().source().requireCurrentPayable(payable, now); return true; }
        catch (DomainException changed) { return false; }
    }

    private static DomainException changed() { return new DomainException("SUPPLIER_PAYMENT_EVIDENCE_CHANGED", "Original payable, hold and selected cashier accounts must remain fresh and unchanged"); }
    /** 证据含原供应商账户，仅在受控持久记录中保存。 */
    @Override public String toString() { return "SupplierPaymentEvidence[checkedAt=" + checkedAt + "]"; }
}
