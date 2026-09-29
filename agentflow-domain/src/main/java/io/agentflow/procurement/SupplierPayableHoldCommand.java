package io.agentflow.procurement;

import io.agentflow.common.DomainException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 原 ERP 应付的原子预留命令，授权号即幂等号；只预留既有应付，不重新挂账或消耗采购预算。
 * @author owlzhangfq@gmail.com
 */
public record SupplierPayableHoldCommand(SupplierPaymentAuthorization authorization) {
    /** 金额、目标及原应付版本全部取自已保存授权。 */
    public SupplierPayableHoldCommand {
        if (authorization == null) throw new DomainException("INVALID_SUPPLIER_PAYABLE_HOLD_COMMAND", "Payable hold requires an immutable supplier payment authorization");
    }

    public UUID id() { return authorization.id(); }
    public String tenantId() { return authorization.source().reservation().source().tenantId(); }
    public String targetDigest() { return authorization.source().reservation().source().round().targetDigest(); }

    /** 原子预留必须同时复核授权与原应付版本读取窗口，幂等查询不受此期限限制。 */
    public void requireSendAt(Instant now) { authorization.requireReservationAt(now); }

    /** 首次发送窗口取三者最早值；外部已形成的预留本身不得按此时间自动释放。 */
    public Instant sendDeadline() {
        var deadline = authorization.payable().observedAt().plus(ProcurementPayablePort.MAX_EVIDENCE_AGE);
        if (authorization.payable().validUntil().isBefore(deadline)) deadline = authorization.payable().validUntil();
        return authorization.expiresAt().isBefore(deadline) ? authorization.expiresAt() : deadline;
    }

    /** UTF-8 字节长度编码避免分隔符歧义；含完整批准快照和本次复核依据，外部不得只按应付号去重。 */
    public String digest() {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var approved = authorization.source(); var held = approved.reservation(); var source = held.source(); var round = source.round();
            var content = round.content(); var entity = round.legalEntity(); var approval = approved.approval();
            add(digest, "agentflow-supplier-payable-hold-1", id(), source.tenantId(), held.id(), held.version(), held.heldAt(), source.requestId(), source.applicationId(),
                    source.employeeId(), source.requestVersion(), approved.approvedRequestVersion(), round.roundNo(), round.submittedRequestVersion(), round.submittedBy(), round.submittedAt(),
                    content.legalEntityId(), content.title(), content.purpose(), content.supplierReference(), content.payableReference(), content.amount().value().toPlainString(), content.amount().currency(),
                    entity.id(), entity.name(), entity.baseCurrency(), entity.paperReceiptRequired(), entity.sourceVersion(), entity.timeZone(), round.catalogVersion(), round.targetDigest(),
                    approval.roundNo(), approval.applicationVersion(), approval.approvedBy(), approval.approvedAt(), authorization.authorizedBy(), authorization.authorizedAt(), authorization.expiresAt());
            addPayable(digest, round.payable()); addPayable(digest, authorization.payable());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    private static void addPayable(MessageDigest digest, ProcurementPayablePort.Payable payable) {
        var request = payable.request(); var account = payable.account();
        add(digest, request.legalEntityId(), request.employeeId(), request.supplierReference(), request.payableReference(), payable.sourceVersion(), payable.observedAt(), payable.validUntil(),
                payable.supplierName(), account.legalEntityId(), account.supplierReference(), account.accountReference(), account.maskedAccount(), account.accountDigest(), account.sourceVersion(),
                payable.contractReference(), payable.orderReference(), payable.matchingReference(), payable.accrualVoucherReference(), payable.budgetRecognitionReference(), payable.dueOn(),
                payable.gross().value().toPlainString(), payable.gross().currency(), payable.settled().value().toPlainString(), payable.settled().currency(), payable.lines().size());
        for (var line : payable.lines()) {
            add(digest, line.lineNo(), line.orderLineNo(), line.acceptanceReference(), line.invoice().canonical(), line.invoiceLineNo(), line.invoiceDigest(), line.verificationReference(), line.unit(),
                    line.orderedQuantity().toPlainString(), line.acceptedQuantity().toPlainString(), line.invoicedQuantity().toPlainString(),
                    line.orderedGross().value().toPlainString(), line.orderedGross().currency(), line.acceptedGross().value().toPlainString(), line.acceptedGross().currency(),
                    line.invoicedGross().value().toPlainString(), line.invoicedGross().currency(), line.tax().value().toPlainString(), line.tax().currency());
        }
    }

    private static void add(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array()); digest.update(bytes);
        }
    }

    /** 日志不包含原应付、人员、账户及票面信息。 */
    @Override public String toString() { return "SupplierPayableHoldCommand[id=" + id() + "]"; }
}
